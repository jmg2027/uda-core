#!/usr/bin/env bash
# Run a compiled suite/main. Build first with verif/bin/build.sh.
#   usage: run.sh <fully.qualified.MainClass> [args...]
#   e.g.:  run.sh verif.suites.BitAluSuite
set -e -o pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
VERIF="$(cd "$HERE/.." && pwd)"
source "$HERE/env.sh"
OUT="$VERIF/out/classes"
MAIN="$1"; shift || true
java -cp "$VERIF_CHISEL_CP:$OUT" "$MAIN" "$@" 2>&1 \
  | grep -vE "Verilator|%Warning|verilator|Wcaller|^-|espresso|\[warn\]|^Picked up JAVA_TOOL_OPTIONS"
