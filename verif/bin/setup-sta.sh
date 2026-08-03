#!/usr/bin/env bash
# ONE-TIME per container: provision the gate-level STA toolchain (yosys + sky130 HD liberty +
# CUDD + OpenSTA). Heavy (OpenSTA is a source build, needs network) - NOT in the SessionStart
# hook on purpose; run it only when you want real placement-free timing. See verif/bin/sta.sh.
set -u
STA=/tmp/verif-sta
mkdir -p "$STA"
log(){ echo "[setup-sta] $*"; }

# 1. yosys (synthesis) - apt has 0.33
# apt update may fail on an unrelated third-party repo (exit != 0 with usable lists), so do not
# chain install behind it; stale lists still install fine.
command -v yosys >/dev/null 2>&1 || { log "apt yosys"; apt-get update --allow-releaseinfo-change -qq >/dev/null 2>&1 || true; DEBIAN_FRONTEND=noninteractive apt-get install -y -qq yosys >/dev/null 2>&1 || log "WARN: yosys apt failed"; }

# 2. sky130 HD liberty (tt corner)
LIB="$STA/sky130_fd_sc_hd__tt_025C_1v80.lib"
[ -s "$LIB" ] || { log "download sky130 HD liberty"; curl -fsSL "https://raw.githubusercontent.com/efabless/skywater-pdk-libs-sky130_fd_sc_hd/master/timing/sky130_fd_sc_hd__tt_025C_1v80.lib" -o "$LIB" 2>/dev/null || log "WARN: liberty download failed"; }

# 3. build deps + CUDD + OpenSTA
if [ ! -x "$STA/OpenSTA/build/sta" ]; then
  log "apt build deps"; apt-get install -y -qq cmake g++ swig bison flex tcl-dev libeigen3-dev zlib1g-dev >/dev/null 2>&1 || log "WARN: deps apt failed"
  # Sources come via git clone: this environment's proxy 403s github tarball downloads
  # (codeload/archive) but relays git itself, so clone is the reliable fetch path.
  if [ ! -f "$STA/cudd/cudd/libcudd.a" ] && [ ! -f "$STA/cudd/cudd/.libs/libcudd.a" ]; then
    log "build CUDD"
    [ -d "$STA/cudd" ] || git clone --depth 1 --branch cudd-3.0.0 https://github.com/ivmai/cudd "$STA/cudd" >/dev/null 2>&1
    # git checkout timestamps make configure.ac look newer than the shipped autotools outputs,
    # so make tries to regenerate them (needs automake/m4); order the timestamps instead.
    ( cd "$STA/cudd" \
      && touch aclocal.m4 && sleep 1 && touch configure && find . -name Makefile.in -exec touch {} + \
      && ./configure --enable-shared >/dev/null 2>&1 && make -j"$(nproc)" >/dev/null 2>&1 ) || log "WARN: CUDD build failed"
  fi
  log "build OpenSTA (source, ~5-10 min)"
  [ -d "$STA/OpenSTA" ] || git clone --depth 1 https://github.com/parallaxsw/OpenSTA "$STA/OpenSTA" >/dev/null 2>&1
  ( cd "$STA/OpenSTA" && mkdir -p build && cd build && cmake .. -DCMAKE_BUILD_TYPE=Release -DCUDD_DIR="$STA/cudd" >/dev/null 2>&1 && make -j"$(nproc)" >/dev/null 2>&1 ) \
    || log "WARN: OpenSTA build failed"
fi

[ -x "$STA/OpenSTA/build/sta" ] && [ -s "$LIB" ] && echo "[setup-sta] ready: OpenSTA + liberty in $STA" || echo "[setup-sta] INCOMPLETE - check network/root (see messages above)"
