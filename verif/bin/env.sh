#!/usr/bin/env bash
# Resolve the toolchain for the verification framework. All locations are env-overridable;
# defaults try the local coursier cache (fast path) and fall back to `cs fetch`. No machine-
# specific paths are baked in beyond the standard coursier cache layout.
#
#   VERIF_CHISEL_CP  : classpath jars for chisel 6.2.0 + scala-library (':'-separated)
#   VERIF_PLUGIN     : chisel-plugin jar (-Xplugin)
#   VERIF_SCALAC     : scala-compiler:scala-library:scala-reflect jars
#   FIRTOOL_DIR      : dir containing the `firtool` binary (added to PATH)
set -e
SCALA_VER="${SCALA_VER:-2.13.12}"
CHISEL_VER="${CHISEL_VER:-6.2.0}"

# prefer the resolved toolchain written by setup.sh
_THIS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$_THIS/.toolchain.env" ] && source "$_THIS/.toolchain.env"

# scala compiler jars
if [ -z "${VERIF_SCALAC:-}" ]; then
  if [ -f /tmp/scalac/scala-compiler.jar ]; then
    VERIF_SCALAC=/tmp/scalac/scala-compiler.jar:/tmp/scalac/scala-library.jar:/tmp/scalac/scala-reflect.jar
  elif command -v cs >/dev/null 2>&1; then
    VERIF_SCALAC=$(cs fetch -p org.scala-lang:scala-compiler:$SCALA_VER org.scala-lang:scala-reflect:$SCALA_VER)
  else
    echo "env.sh: need VERIF_SCALAC or coursier (cs) on PATH" >&2; exit 1
  fi
fi

# chisel classpath + plugin
if [ -z "${VERIF_CHISEL_CP:-}" ]; then
  if [ -f /tmp/chisel_cp.txt ]; then VERIF_CHISEL_CP=$(cat /tmp/chisel_cp.txt)
  elif command -v cs >/dev/null 2>&1; then
    VERIF_CHISEL_CP=$(cs fetch -p org.chipsalliance:chisel_2.13:$CHISEL_VER)
  else echo "env.sh: need VERIF_CHISEL_CP or coursier" >&2; exit 1; fi
fi
if [ -z "${VERIF_PLUGIN:-}" ]; then
  if [ -f /tmp/chisel_plugin.txt ]; then VERIF_PLUGIN=$(cat /tmp/chisel_plugin.txt)
  elif command -v cs >/dev/null 2>&1; then
    VERIF_PLUGIN=$(cs fetch --intransitive org.chipsalliance:chisel-plugin_${SCALA_VER}:$CHISEL_VER)
  else echo "env.sh: need VERIF_PLUGIN or coursier" >&2; exit 1; fi
fi

# firtool on PATH
FIRTOOL_DIR="${FIRTOOL_DIR:-/tmp/firtool-1.62.0/bin}"
[ -d "$FIRTOOL_DIR" ] && export PATH="$FIRTOOL_DIR:$PATH"

export VERIF_SCALAC VERIF_CHISEL_CP VERIF_PLUGIN

# Simulator build speedup
# EphemeralSimulator recompiles the Verilated C++ on every `simulate{}` call, and the DUT is
# identical across scenarios -> the generated C++ is byte-identical. Route Verilator's C++
# compiles through ccache (Verilator honors $OBJCACHE) so only the first compile pays the cost;
# the rest are cache hits (~20x on a sweep). Persist the cache outside /tmp (which is reclaimed).
if command -v ccache >/dev/null 2>&1; then
  export OBJCACHE="ccache"
  export CCACHE_DIR="${CCACHE_DIR:-/root/.cache/verif-ccache}"
  export CCACHE_MAXSIZE="${CCACHE_MAXSIZE:-3G}"
  # broaden hits: the temp build dir path varies per run, don't let it bust the hash
  export CCACHE_BASEDIR="${CCACHE_BASEDIR:-/tmp}"
  export CCACHE_SLOPPINESS="${CCACHE_SLOPPINESS:-time_macros,locale,include_file_mtime,include_file_ctime,pch_defines}"
  # EphemeralSimulator builds each sim under a uniquely-named /tmp dir, which gets baked into the
  # generated C++ paths -> don't hash the cwd/dir so identical models hit across runs.
  export CCACHE_NOHASHDIR="${CCACHE_NOHASHDIR:-1}"
fi
# Parallelize the (cold) Verilated C++ build across all cores, and compile it at -O0: the sims
# here run hundreds-to-thousands of cycles (milliseconds), so trading sim speed for a 2-4x faster
# cold g++ on the huge generated C++ is a clear win; ccache-hot runs skip g++ either way.
# verilated.mk assigns OPT_FAST/OPT_GLOBAL with plain '=', which a MAKEFLAGS variable assignment
# overrides (env alone would not).
export MAKEFLAGS="${MAKEFLAGS:--j$(nproc 2>/dev/null || echo 4) OPT_FAST=-O0 OPT_GLOBAL=-O0}"
