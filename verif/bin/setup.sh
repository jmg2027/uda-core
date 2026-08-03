#!/usr/bin/env bash
# Idempotent toolchain provisioner for the verif framework. Reuse-first: if a piece is already
# present (cached container or a prior run) it is skipped; otherwise it is fetched at pinned
# versions. Writes resolved paths to verif/.toolchain.env. Safe to run many times.
#
# Stages: java truststore (TLS proxy) -> coursier -> scala jars -> chisel cp -> firtool ->
#         apt (verilator, riscv gcc) -> .toolchain.env -> (optional) framework build.
#
# Network: needs outbound to maven central, github releases, and the apt mirror (governed by the
# environment's network policy). Each stage logs clearly so a failure is diagnosable.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
VERIF="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$VERIF/.." && pwd)"
ENVF="$VERIF/.toolchain.env"
SCALA_VER=2.13.12
CHISEL_VER=6.2.0
FIRTOOL_VER=1.62.0
CACHE=/tmp/verif-tc
mkdir -p "$CACHE"
log(){ echo "[setup] $*"; }
ok(){ echo "[setup]   ok: $*"; }

# 1. JVM truststore for the TLS-intercepting proxy (coursier needs to trust the proxy CA)
TRUST=/tmp/truststore.jks
if [ ! -f "$TRUST" ]; then
  if [ -f /etc/ssl/certs/java/cacerts ]; then cp /etc/ssl/certs/java/cacerts "$TRUST" 2>/dev/null && ok "truststore from system cacerts"
  elif command -v keytool >/dev/null 2>&1 && [ -f /etc/ssl/certs/ca-certificates.crt ]; then
    # split the PEM bundle and import each cert (best-effort)
    csplit -z -f "$CACHE/ca-" -b "%03d.pem" /etc/ssl/certs/ca-certificates.crt '/-----BEGIN CERTIFICATE-----/' '{*}' >/dev/null 2>&1
    rm -f "$TRUST"; i=0
    for c in "$CACHE"/ca-*.pem; do keytool -importcert -noprompt -keystore "$TRUST" -storepass changeit -alias "ca$i" -file "$c" >/dev/null 2>&1; i=$((i+1)); done
    [ -f "$TRUST" ] && ok "truststore built from ca-certificates.crt ($i certs)" || log "WARN: truststore build failed; coursier may still work via system trust"
  fi
else ok "truststore present"; fi
JAVA_TLS=""; [ -f "$TRUST" ] && JAVA_TLS="-J-Djavax.net.ssl.trustStore=$TRUST -J-Djavax.net.ssl.trustStorePassword=changeit"

# 2. coursier launcher
CS=/tmp/cs
if [ ! -x "$CS" ]; then
  log "fetching coursier launcher"
  curl -fsSL https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz 2>/dev/null | gunzip > "$CS" && chmod +x "$CS" && ok "coursier" \
    || log "WARN: coursier download failed (set CS to an existing launcher)"
else ok "coursier present"; fi

# 3. scala compiler jars
SCALAC_DIR=/tmp/scalac; mkdir -p "$SCALAC_DIR"
if [ ! -f "$SCALAC_DIR/scala-compiler.jar" ]; then
  log "resolving scala $SCALA_VER compiler"
  CP=$("$CS" fetch $JAVA_TLS org.scala-lang:scala-compiler:$SCALA_VER org.scala-lang:scala-reflect:$SCALA_VER 2>/dev/null)
  for j in $(echo "$CP" | tr ':' ' '); do case "$j" in
    *scala-compiler*) cp "$j" "$SCALAC_DIR/scala-compiler.jar";;
    *scala-reflect*)  cp "$j" "$SCALAC_DIR/scala-reflect.jar";;
    *scala-library*)  cp "$j" "$SCALAC_DIR/scala-library.jar";; esac; done
  [ -f "$SCALAC_DIR/scala-compiler.jar" ] && ok "scala jars" || log "WARN: scala jar resolve failed"
else ok "scala jars present"; fi
VERIF_SCALAC="$SCALAC_DIR/scala-compiler.jar:$SCALAC_DIR/scala-library.jar:$SCALAC_DIR/scala-reflect.jar"

# 4. chisel classpath + plugin
if [ -s /tmp/chisel_cp.txt ]; then VERIF_CHISEL_CP=$(cat /tmp/chisel_cp.txt); ok "chisel cp cached"
else
  log "resolving chisel $CHISEL_VER"
  VERIF_CHISEL_CP=$("$CS" fetch $JAVA_TLS -p org.chipsalliance:chisel_2.13:$CHISEL_VER 2>/dev/null)
  [ -n "$VERIF_CHISEL_CP" ] && echo "$VERIF_CHISEL_CP" > /tmp/chisel_cp.txt && ok "chisel cp" || log "WARN: chisel resolve failed"
fi
if [ -s /tmp/chisel_plugin.txt ]; then VERIF_PLUGIN=$(cat /tmp/chisel_plugin.txt); ok "chisel plugin cached"
else
  VERIF_PLUGIN=$("$CS" fetch $JAVA_TLS --intransitive org.chipsalliance:chisel-plugin_${SCALA_VER}:$CHISEL_VER 2>/dev/null)
  [ -n "$VERIF_PLUGIN" ] && echo "$VERIF_PLUGIN" > /tmp/chisel_plugin.txt && ok "chisel plugin" || log "WARN: plugin resolve failed"
fi

# 5. firtool (CIRCT)
FIRTOOL_DIR=/tmp/firtool-$FIRTOOL_VER/bin
if [ ! -x "$FIRTOOL_DIR/firtool" ]; then
  log "fetching firtool $FIRTOOL_VER"
  curl -fsSL "https://github.com/llvm/circt/releases/download/firtool-$FIRTOOL_VER/firrtl-bin-linux-x64.tar.gz" -o "$CACHE/firtool.tgz" 2>/dev/null \
    && tar -xzf "$CACHE/firtool.tgz" -C /tmp 2>/dev/null && ok "firtool" || log "WARN: firtool download failed"
else ok "firtool present"; fi

# 6. system packages (verilator, riscv gcc/binutils, ccache). ccache activates the env.sh
# OBJCACHE path: the Verilated C++ of an unchanged DUT is byte-identical across runs, so with it
# installed a recompile collapses to cache hits (the g++ step is the wall-clock bulk).
need_apt=""
command -v verilator >/dev/null 2>&1 || need_apt="$need_apt verilator"
command -v ccache >/dev/null 2>&1 || need_apt="$need_apt ccache"
command -v riscv64-unknown-elf-gcc >/dev/null 2>&1 || need_apt="$need_apt gcc-riscv64-unknown-elf binutils-riscv64-unknown-elf"
if [ -n "$need_apt" ]; then
  log "apt-get install$need_apt"
  # update may fail on an unrelated third-party repo; stale lists still install fine
  apt-get update --allow-releaseinfo-change -qq >/dev/null 2>&1 || true
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $need_apt >/dev/null 2>&1 \
    && ok "apt packages" || log "WARN: apt install failed (need root/network)"
else ok "verilator + riscv gcc present"; fi

# 6.5 espresso (chisel decode-table minimizer). Without it on PATH, hardware elaboration falls
# back to a much slower minimizer and prints "espresso failed to run" each elaboration, so every
# simulate() pays a big penalty. Small C build, cached.
if ! command -v espresso >/dev/null 2>&1; then
  ESPDIR="$CACHE/espresso-logic"
  if [ ! -x "$ESPDIR/bin/espresso" ]; then
    log "building espresso (decode minimizer; speeds elaboration)..."
    rm -rf "$ESPDIR"
    if curl -fsSL "https://codeload.github.com/classabbyamp/espresso-logic/tar.gz/refs/heads/master" 2>/dev/null \
         | tar xz -C "$CACHE" 2>/dev/null && mv "$CACHE/espresso-logic-master" "$ESPDIR" 2>/dev/null; then
      (cd "$ESPDIR/espresso-src" && make >/dev/null 2>&1) || log "WARN: espresso build failed"
    else log "WARN: espresso download failed (network) - elaboration will use the slow fallback"; fi
  fi
  if [ -x "$ESPDIR/bin/espresso" ]; then
    cp "$ESPDIR/bin/espresso" /usr/local/bin/espresso 2>/dev/null && ok "espresso -> /usr/local/bin" \
      || { export PATH="$ESPDIR/bin:$PATH"; ok "espresso on PATH ($ESPDIR/bin)"; }
  fi
else ok "espresso present"; fi

# 7. write resolved env
cat > "$ENVF" <<EOF
# generated by verif/bin/setup.sh - resolved toolchain locations
export VERIF_SCALAC="$VERIF_SCALAC"
export VERIF_CHISEL_CP="$VERIF_CHISEL_CP"
export VERIF_PLUGIN="$VERIF_PLUGIN"
export FIRTOOL_DIR="$FIRTOOL_DIR"
export JAVA_TLS_OPTS="$JAVA_TLS"
EOF
ok "wrote $ENVF"

# 8. optional framework build (best-effort)
if [ "${VERIF_BUILD:-1}" = "1" ] && [ -n "${VERIF_CHISEL_CP:-}" ]; then
  log "building framework (this is the ~3-5 min compile)..."
  if bash "$HERE/build.sh" >/tmp/verif-build.log 2>&1; then touch "$VERIF/out/.ready"; ok "framework built (out/.ready)"
  else log "WARN: framework build failed - see /tmp/verif-build.log"; fi
fi
log "done."
