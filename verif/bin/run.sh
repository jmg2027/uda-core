#!/usr/bin/env bash
# Run a compiled suite/main. Build first with verif/bin/build.sh.
#   usage: run.sh <fully.qualified.MainClass> [args...]
#   e.g.:  run.sh verif.suites.BitAluSuite
set -e -o pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
VERIF="$(cd "$HERE/.." && pwd)"
source "$HERE/env.sh"
OUT="$VERIF/out/classes"
if [ ! -f "$VERIF/out/.ready" ]; then
  echo "run.sh: run verif/bin/build.sh successfully before running a suite" >&2
  exit 1
fi
MAIN="$1"; shift || true
exec java -cp "$VERIF_CHISEL_CP:$OUT" "$MAIN" "$@"
