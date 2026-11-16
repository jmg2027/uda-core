#!/usr/bin/env bash
# Provision the pinned OpenRAM SRAM behavioral, timing and physical views.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$HERE/../.toolchain.env" ]; then
  source "$HERE/../.toolchain.env"
fi
exec python3 "$HERE/setup-sram.py" "$@"
