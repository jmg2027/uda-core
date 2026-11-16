#!/usr/bin/env bash
# Compare the current inline SRAM model against the pinned OpenRAM source.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# Machine defaults must not replace explicit caller overrides.
sram_model_sta_set="${VERIF_STA_HOME+x}"
sram_model_sta_home="${VERIF_STA_HOME-}"
sram_model_sram_set="${VERIF_SRAM_HOME+x}"
sram_model_sram_home="${VERIF_SRAM_HOME-}"
if [ -f "$HERE/../.toolchain.env" ]; then
  source "$HERE/../.toolchain.env"
fi
if [ -n "$sram_model_sta_set" ]; then
  export VERIF_STA_HOME="$sram_model_sta_home"
fi
if [ -n "$sram_model_sram_set" ]; then
  export VERIF_SRAM_HOME="$sram_model_sram_home"
fi
exec python3 "$HERE/test-sram-model.py" "$@"
