#!/usr/bin/env bash
# Compile the real spec-framework, then specs, then RTL and verification suites.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/env.sh"
python3 "$HERE/build.py"
