#!/usr/bin/env bash
# Reproducible Apple Silicon verification toolchain provisioner.
#
# This is intentionally separate from setup.sh, which remains the Linux path.
# The macOS tuple below is pinned to the combination validated on Apple Silicon:
#   JDK 17, Scala 2.13.12, Chisel 6.2.0, firtool 1.62.0 (x86_64/Rosetta),
#   Verilator 5.020 (native arm64), MacOSX26.5.sdk.
#
# Persistent artifacts live outside /tmp so a reboot or /tmp cleanup does not
# invalidate verif/.toolchain.env. Re-running this script repairs missing pieces.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
VERIF="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$VERIF/.." && pwd)"
ENVF="$VERIF/.toolchain.env"

SCALA_VER="2.13.12"
CHISEL_VER="6.2.0"
FIRTOOL_VER="1.62.0"
VERILATOR_VER="5.020"
SDK_NAME="MacOSX26.5.sdk"
JDK_FORMULA="openjdk@17"
RISCV_FORMULA="riscv64-elf-gcc"

FIRTOOL_URL="https://github.com/llvm/circt/releases/download/firtool-${FIRTOOL_VER}/firrtl-bin-macos-x64.tar.gz"
FIRTOOL_SHA256="8fe20d2308634d0c564e3fbcaa9d0cbdca0f16df0574e8ea7284e0978f517f91"
VERILATOR_URL="https://github.com/verilator/verilator/archive/refs/tags/v${VERILATOR_VER}.tar.gz"
VERILATOR_SHA256="41ca9abfadf8d2413efbff7f8277379733d0095957fe7769dc38f8fd1bc899a6"

CACHE_ROOT="${VERIF_CACHE_ROOT:-${XDG_CACHE_HOME:-$HOME/Library/Caches}/uda-core-verif}"
DOWNLOADS="$CACHE_ROOT/downloads"
TOOLS="$CACHE_ROOT/tools"
M2="$CACHE_ROOT/m2"
DEPS="$CACHE_ROOT/deps"
CCACHE="$CACHE_ROOT/ccache"
mkdir -p "$DOWNLOADS" "$TOOLS" "$M2" "$DEPS" "$CCACHE"

log(){ echo "[setup-macos] $*"; }
ok(){ echo "[setup-macos]   ok: $*"; }
die(){ echo "[setup-macos] ERROR: $*" >&2; exit 1; }

[ "$(uname -s)" = "Darwin" ] || die "this installer is for macOS; use verif/bin/setup.sh on Linux"
[ "$(uname -m)" = "arm64" ] || die "Apple Silicon arm64 is required (found $(uname -m))"

BREW="$(command -v brew || true)"
[ -n "$BREW" ] || [ ! -x /opt/homebrew/bin/brew ] || BREW=/opt/homebrew/bin/brew
[ -n "$BREW" ] || die "Homebrew is required on Apple Silicon"
BREW_PREFIX="$("$BREW" --prefix)"
export PATH="$BREW_PREFIX/bin:$PATH"

ensure_formula() {
  local formula="$1"
  if "$BREW" list --versions "$formula" >/dev/null 2>&1; then
    ok "Homebrew $formula already installed"
  else
    log "installing Homebrew $formula"
    "$BREW" install "$formula"
  fi
}

sha256_matches() {
  local file="$1" expected="$2"
  [ "$(shasum -a 256 "$file" | awk '{print $1}')" = "$expected" ]
}

fetch_checked() {
  local url="$1" out="$2" expected="$3"
  if [ -f "$out" ]; then
    if sha256_matches "$out" "$expected"; then
      ok "cached $(basename "$out")"
      return
    fi
    log "discarding checksum-mismatched $(basename "$out")"
    rm -f "$out"
  fi
  log "downloading $(basename "$out")"
  curl -fL --retry 3 --retry-delay 2 "$url" -o "$out"
  sha256_matches "$out" "$expected" || die "checksum mismatch for $out"
}

# JDK 17 is keg-only in Homebrew on many hosts, so do not depend on /usr/bin/java.
ensure_formula "$JDK_FORMULA"
JAVA_PREFIX="$("$BREW" --prefix "$JDK_FORMULA")"
JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
[ -x "$JAVA_HOME/bin/java" ] || die "JDK 17 java not found under $JAVA_HOME"
JAVA_MAJOR="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
[ "$JAVA_MAJOR" = "17" ] || die "expected JDK 17, found major ${JAVA_MAJOR:-unknown}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
ok "JDK $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# Maven is used only as a deterministic dependency resolver into the persistent cache.
ensure_formula "maven"
ensure_formula "ccache"

# Resolve Scala/Chisel classpaths without relying on /tmp or a user-global Ivy cache.
POM="$DEPS/pom.xml"
cat > "$POM" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>local.uda</groupId><artifactId>verif-toolchain</artifactId><version>1</version>
  <dependencies>
    <dependency><groupId>org.scala-lang</groupId><artifactId>scala-compiler</artifactId><version>$SCALA_VER</version></dependency>
    <dependency><groupId>org.scala-lang</groupId><artifactId>scala-reflect</artifactId><version>$SCALA_VER</version></dependency>
    <dependency><groupId>org.chipsalliance</groupId><artifactId>chisel_2.13</artifactId><version>$CHISEL_VER</version></dependency>
  </dependencies>
</project>
EOF

MVN=("$BREW_PREFIX/bin/mvn" --batch-mode --no-transfer-progress "-Dmaven.repo.local=$M2" -f "$POM")
log "resolving Scala $SCALA_VER + Chisel $CHISEL_VER"
"${MVN[@]}" -q dependency:build-classpath "-Dmdep.outputFile=$DEPS/chisel_cp.txt" "-Dmdep.pathSeparator=:"
"${MVN[@]}" -q dependency:get "-Dartifact=org.chipsalliance:chisel-plugin_${SCALA_VER}:${CHISEL_VER}"

SCALA_BASE="$M2/org/scala-lang"
VERIF_SCALAC="$SCALA_BASE/scala-compiler/$SCALA_VER/scala-compiler-$SCALA_VER.jar:$SCALA_BASE/scala-library/$SCALA_VER/scala-library-$SCALA_VER.jar:$SCALA_BASE/scala-reflect/$SCALA_VER/scala-reflect-$SCALA_VER.jar"
VERIF_CHISEL_CP="$(cat "$DEPS/chisel_cp.txt")"
VERIF_PLUGIN="$M2/org/chipsalliance/chisel-plugin_${SCALA_VER}/${CHISEL_VER}/chisel-plugin_${SCALA_VER}-${CHISEL_VER}.jar"
IFS=: read -r -a scala_jars <<< "$VERIF_SCALAC"
for jar in "${scala_jars[@]}"; do [ -s "$jar" ] || die "missing Scala jar $jar"; done
[ -s "$VERIF_PLUGIN" ] || die "missing Chisel compiler plugin $VERIF_PLUGIN"
ok "Scala/Chisel dependencies cached under $CACHE_ROOT"

# RISC-V cross tools are not part of the fragile simulator tuple, but the verification
# harness expects the historical riscv64-unknown-elf-* command names.
ensure_formula "$RISCV_FORMULA"
RISCV_BIN="$TOOLS/riscv/bin"
mkdir -p "$RISCV_BIN"
resolve_riscv_tool() {
  local leaf="$1" found=""
  for name in "riscv64-unknown-elf-$leaf" "riscv64-elf-$leaf"; do
    found="$(command -v "$name" 2>/dev/null || true)"
    [ -z "$found" ] || { printf '%s\n' "$found"; return 0; }
  done
  for prefix in "$("$BREW" --prefix "$RISCV_FORMULA" 2>/dev/null || true)" "$("$BREW" --prefix riscv64-elf-binutils 2>/dev/null || true)"; do
    [ -d "$prefix" ] || continue
    found="$(find "$prefix" -type f \( -name "riscv64-unknown-elf-$leaf" -o -name "riscv64-elf-$leaf" \) -perm -111 2>/dev/null | head -1)"
    [ -z "$found" ] || { printf '%s\n' "$found"; return 0; }
  done
  return 1
}
for leaf in gcc objcopy objdump; do
  src="$(resolve_riscv_tool "$leaf" || true)"
  [ -n "$src" ] || die "could not find RISC-V $leaf after installing $RISCV_FORMULA"
  ln -sf "$src" "$RISCV_BIN/riscv64-unknown-elf-$leaf"
done
export PATH="$RISCV_BIN:$PATH"
ok "$("$RISCV_BIN/riscv64-unknown-elf-gcc" --version | head -1)"

# Select the exact SDK that linked successfully with this simulator stack.
SDKROOT="${VERIF_MACOS_SDKROOT:-}"
if [ -z "$SDKROOT" ]; then
  DEVROOT="$(xcode-select -p 2>/dev/null || true)"
  candidates=(
    "$DEVROOT/SDKs/$SDK_NAME"
    "$DEVROOT/Platforms/MacOSX.platform/Developer/SDKs/$SDK_NAME"
    "/Library/Developer/CommandLineTools/SDKs/$SDK_NAME"
    "/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/$SDK_NAME"
  )
  for candidate in "${candidates[@]}"; do
    if [ -d "$candidate" ]; then SDKROOT="$candidate"; break; fi
  done
fi
[ -d "$SDKROOT" ] || die "$SDK_NAME is required; the default MacOSX27.0.sdk is not a validated substitute"
[ "$(basename "$SDKROOT")" = "$SDK_NAME" ] || die "VERIF_MACOS_SDKROOT must point to $SDK_NAME (got $SDKROOT)"
export SDKROOT
ok "SDKROOT=$SDKROOT"

# firtool 1.62.0 only has the validated macOS x64 build here. Keep the real binary
# in persistent storage and expose an explicit Rosetta wrapper to Chisel.
if ! /usr/bin/arch -x86_64 /usr/bin/true >/dev/null 2>&1; then
  die "Rosetta 2 is required for firtool $FIRTOOL_VER (install with: softwareupdate --install-rosetta --agree-to-license)"
fi
FIR_ARCHIVE="$DOWNLOADS/firtool-$FIRTOOL_VER-macos-x64.tar.gz"
FIR_REAL_ROOT="$TOOLS/firtool-$FIRTOOL_VER-x86_64"
FIR_WRAPPER_ROOT="$TOOLS/firtool-$FIRTOOL_VER-rosetta"
if [ ! -x "$FIR_REAL_ROOT/bin/firtool" ]; then
  fetch_checked "$FIRTOOL_URL" "$FIR_ARCHIVE" "$FIRTOOL_SHA256"
  rm -rf "$FIR_REAL_ROOT" "$TOOLS/firtool-$FIRTOOL_VER"
  tar -xzf "$FIR_ARCHIVE" -C "$TOOLS"
  mv "$TOOLS/firtool-$FIRTOOL_VER" "$FIR_REAL_ROOT"
fi
mkdir -p "$FIR_WRAPPER_ROOT/bin"
cat > "$FIR_WRAPPER_ROOT/bin/firtool" <<EOF
#!/usr/bin/env bash
exec /usr/bin/arch -x86_64 "$FIR_REAL_ROOT/bin/firtool" "\$@"
EOF
chmod +x "$FIR_WRAPPER_ROOT/bin/firtool"
FIR_FILE="$(file "$FIR_REAL_ROOT/bin/firtool")"
echo "$FIR_FILE" | grep -q "x86_64" || die "firtool is not the pinned x86_64 build: $FIR_FILE"
FIRTOOL_DIR="$FIR_WRAPPER_ROOT/bin"
CHISEL_FIRTOOL_PATH="$FIRTOOL_DIR"
export FIRTOOL_DIR CHISEL_FIRTOOL_PATH
export PATH="$FIRTOOL_DIR:$PATH"
"$FIRTOOL_DIR/firtool" --version >/dev/null
ok "firtool $FIRTOOL_VER via explicit Rosetta wrapper"

# Homebrew currently ships a newer Verilator that is known to emit duplicate Ready
# protocol messages with Chisel 6.2.0. Build and prefer exactly 5.020, natively.
VERILATOR_PREFIX="$TOOLS/verilator-$VERILATOR_VER-arm64"
if [ ! -x "$VERILATOR_PREFIX/bin/verilator" ] || ! "$VERILATOR_PREFIX/bin/verilator" --version 2>/dev/null | grep -q "Verilator $VERILATOR_VER"; then
  ensure_formula "autoconf"
  ensure_formula "flex"
  ensure_formula "bison"
  ensure_formula "help2man"
  FLEX_PREFIX="$("$BREW" --prefix flex)"
  BISON_PREFIX="$("$BREW" --prefix bison)"
  export PATH="$BISON_PREFIX/bin:$FLEX_PREFIX/bin:$PATH"
  # macOS ships an older FlexLexer.h under the CommandLineTools include tree.
  # Pair Homebrew flex's executable with its own headers/libraries explicitly.
  export CPPFLAGS="-I$FLEX_PREFIX/include ${CPPFLAGS:-}"
  export LDFLAGS="-L$FLEX_PREFIX/lib ${LDFLAGS:-}"
  VER_ARCHIVE="$DOWNLOADS/verilator-v$VERILATOR_VER.tar.gz"
  fetch_checked "$VERILATOR_URL" "$VER_ARCHIVE" "$VERILATOR_SHA256"
  SRC="$CACHE_ROOT/build/verilator-$VERILATOR_VER"
  rm -rf "$SRC" "$VERILATOR_PREFIX"
  mkdir -p "$(dirname "$SRC")"
  tar -xzf "$VER_ARCHIVE" -C "$(dirname "$SRC")"
  cd "$SRC"
  autoconf
  ./configure --prefix="$VERILATOR_PREFIX"
  JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"
  make -j"$JOBS"
  make install
  cd "$REPO"
fi
[ -x "$VERILATOR_PREFIX/bin/verilator_bin" ] || die "missing native verilator_bin in $VERILATOR_PREFIX"
VER_FILE="$(file "$VERILATOR_PREFIX/bin/verilator_bin")"
echo "$VER_FILE" | grep -Eq "arm64|universal" || die "Verilator backend is not native arm64/universal: $VER_FILE"
"$VERILATOR_PREFIX/bin/verilator" --version | grep -q "Verilator $VERILATOR_VER" || die "wrong Verilator version"
VERIF_VERILATOR_PREFIX="$VERILATOR_PREFIX"
export VERIF_VERILATOR_PREFIX
# Do not export VERILATOR_ROOT for an installed Verilator. 5.020's installed
# launcher/binary carries the correct share/verilator root; overriding it with
# the install prefix makes it look for include/verilated_std.sv in the wrong place.
unset VERILATOR_ROOT
export PATH="$VERILATOR_PREFIX/bin:$PATH"
ok "$("$VERILATOR_PREFIX/bin/verilator" --version)"

cat > "$ENVF" <<EOF
# generated by verif/bin/setup-macos.sh; do not commit
# validated Apple Silicon tuple: JDK 17 / Scala $SCALA_VER / Chisel $CHISEL_VER /
# firtool $FIRTOOL_VER (Rosetta) / Verilator $VERILATOR_VER (arm64) / $SDK_NAME
export JAVA_HOME="$JAVA_HOME"
export VERIF_SCALAC="$VERIF_SCALAC"
export VERIF_CHISEL_CP="$VERIF_CHISEL_CP"
export VERIF_PLUGIN="$VERIF_PLUGIN"
export FIRTOOL_DIR="$FIRTOOL_DIR"
export CHISEL_FIRTOOL_PATH="$CHISEL_FIRTOOL_PATH"
export SDKROOT="$SDKROOT"
export VERIF_VERILATOR_PREFIX="$VERIF_VERILATOR_PREFIX"
unset VERILATOR_ROOT
export RISCV_TOOLCHAIN_BIN="$RISCV_BIN"
export CCACHE_DIR="$CCACHE"
export VERIF_SIMCACHE_KEEP=64
export PATH="$VERILATOR_PREFIX/bin:$RISCV_BIN:$FIRTOOL_DIR:$JAVA_HOME/bin:\$PATH"
EOF
ok "wrote $ENVF"

if [ "${VERIF_BUILD:-1}" = "1" ]; then
  log "running verification build"
  bash "$HERE/build.sh"
fi

log "done"
