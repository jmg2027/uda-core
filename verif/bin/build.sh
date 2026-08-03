#!/usr/bin/env bash
# Compile the verification framework against the real src/main/scala (single source of truth),
# plus the framework sources and suites. Output classes go to verif/out/classes. Run with
# verif/bin/run.sh <MainClass>.
#
# The rebuild tree needs -Ymacro-annotations (the @LocalSpec / spec macros in framework/) and no
# overlay or shim: the tree is self-contained and compiles as-is under the pinned chisel.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
VERIF="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$VERIF/.." && pwd)"
source "$HERE/env.sh"
OUT="$VERIF/out/classes"; mkdir -p "$OUT"

CORE_SRCS=$(find "$REPO/src/main/scala" -name "*.scala")
FRAMEWORK=$(find "$VERIF/src" -name "*.scala")
SUITES=$(find "$VERIF/suites" -name "*.scala")

echo "[build] compiling core + framework + suites -> $OUT"
java -Xss16m -cp "$VERIF_SCALAC:$VERIF_CHISEL_CP" scala.tools.nsc.Main \
  -Xplugin:"$VERIF_PLUGIN" -Ymacro-annotations -d "$OUT" -classpath "$VERIF_CHISEL_CP" \
  $CORE_SRCS $FRAMEWORK $SUITES 2>&1 \
  | grep -viE "useBundlePlugin|feature warning|: warning" || true
echo "[build] done."
