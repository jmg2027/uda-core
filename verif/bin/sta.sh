#!/usr/bin/env bash
# Gate-level STA with real IO constraints (OpenSTA + sky130 HD).
#   verif/bin/setup-sta.sh                 # ONCE per container (yosys + liberty + OpenSTA)
#   verif/bin/sta.sh core [config]         # whole product CoreTop (retire stream disabled)
#   verif/bin/sta.sh unit <unitConfig>     # an implemented functional unit (works today)
# Unit config names: run.sh verif.suites.EmitUnit list  (alu, mul_csa16, div_nrclz, bitalu, ...)
#
# This is a real pre-placement OOC flow, not a rough relative estimate. The map step below runs
# timing-driven abc plus a fanout buffer tree (`buffer`) and gate sizing (`upsize`/`dnsize`), so a
# high-fanout net can be driven by a buffer tree instead of one overloaded gate. The SDC uses
# a statistical wire-load model. Reports are pre-placement estimates: no extracted parasitics,
# clock tree or sign-off claim. Core cache data banks use pinned OpenRAM SRAM macro timing
# views; their public library uses an analytical Elmore model, not PEX characterization.
#
# Tunable (env): STA_MAXFO (max fanout before buffering, default 16), STA_PERIOD,
# STA_MEM_MARGIN / STA_IO_MARGIN (fractions of clock period), STA_REPORT and STA_WORK_ROOT.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; VERIF="$(cd "$HERE/.." && pwd)"; REPO="$(cd "$VERIF/.." && pwd)"
source "$HERE/env.sh"
KIND="${1:-unit}"; CFG="${2:-}"
STA="${VERIF_STA_HOME:-$REPO/../.uda-toolchain/sta}"
LIB="$STA/sky130_fd_sc_hd__tt_025C_1v80.lib"
MAXFO="${STA_MAXFO:-16}"
WORK_ROOT="${STA_WORK_ROOT:-$VERIF/out/sta}"
SRAM_NAME=sky130_sram_1kbyte_1rw1r_32x256_8
SRAM_LIB=""
SRAM_READ=""
SRAM_CHECK=""
SRAM_STAT=""
# abc delay target in ps = clock period (ns) * 1000; steers the mapper toward the delay path.
DTARGET="$(awk "BEGIN{printf \"%d\", ${STA_PERIOD:-10.0}*1000}")"

[ -x "$STA/OpenSTA/build/sta" ] || { echo "OpenSTA missing - run verif/bin/setup-sta.sh first"; exit 1; }
[ -s "$LIB" ] || { echo "sky130 liberty missing - run verif/bin/setup-sta.sh first"; exit 1; }
command -v yosys >/dev/null || { echo "yosys missing - run verif/bin/setup-sta.sh first"; exit 1; }

case "$KIND" in
  core)
    CFG="${CFG:-default}"
    WORK="$WORK_ROOT/core_${CFG}_sram"; mkdir -p "$WORK"
    bash "$HERE/setup-sram.sh" --verify-only
    SRAM_DIR="${VERIF_SRAM_HOME:-$STA/sram}/$SRAM_NAME"
    SRAM_LIB="$SRAM_DIR/${SRAM_NAME}_TT_1p8V_25C.lib"
    SRAM_READ="read_liberty -lib $SRAM_LIB"
    SRAM_CHECK="select -assert-count 32 t:$SRAM_NAME"
    SRAM_STAT="-liberty $SRAM_LIB"
    echo "[sta] emit core ($CFG)"
    bash "$HERE/run.sh" verif.suites.EmitCore "$CFG" "$WORK" > "$WORK/emit.log" 2>&1 \
      || { cat "$WORK/emit.log" >&2; exit 1; }
    TOP=CoreTop ;;
  unit)
    [ -n "$CFG" ] || { echo "usage: sta.sh unit <unitConfig>   (run.sh verif.suites.EmitUnit list)"; exit 2; }
    WORK="$WORK_ROOT/unit_$CFG"; mkdir -p "$WORK"
    echo "[sta] emit unit ($CFG)"
    bash "$HERE/run.sh" verif.suites.EmitUnit "$CFG" "$WORK" > "$WORK/emit.log" 2>&1
    TOP=$(basename "$(ls "$WORK"/*.sv 2>/dev/null | head -1)" .sv) ;;
  *) echo "usage: sta.sh <core|unit> <config>"; exit 2 ;;
esac
SV="$WORK/$TOP.sv"; [ -s "$SV" ] || { echo "emit failed"; exit 1; }
if [ "$KIND" = "core" ]; then
  # Chisel emits split HDL plus a non-HDL blackbox resource list. Preserve every HDL
  # source while excluding only the .f list from the SystemVerilog parser input.
  python3 - "$WORK" "$TOP" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
aggregate = root / (sys.argv[2] + ".all.sv")
sources = sorted(p for p in root.glob("*.sv") if p != aggregate)
aggregate.write_text("\n".join("// Source: " + p.name + "\n" + p.read_text() for p in sources))
PY
  SV="$WORK/$TOP.all.sv"
fi
SYNTH_SOURCE="$SV"
if [ "$KIND" = "core" ]; then
  command -v sv2v >/dev/null || {
    echo "sv2v missing: CoreTop uses packed SystemVerilog arrays unsupported by the native Yosys reader" >&2
    exit 1
  }
  echo "[sta] convert SystemVerilog arrays (sv2v)"
  SYNTH_SOURCE="$WORK/$TOP.v"
  sv2v -DSYNTHESIS --top="$TOP" "$SV" > "$SYNTH_SOURCE" 2> "$WORK/sv2v.log"
fi

echo "[sta] map to sky130 gates (yosys: timing-driven + fanout buffer N=$MAXFO, top=$TOP)"
cat > "$WORK/map.ys" <<EOF
$SRAM_READ
read_verilog -sv $SYNTH_SOURCE
hierarchy -check -top $TOP
chformal -remove
synth -flatten -top $TOP -noabc
dfflibmap -liberty $LIB
write_rtlil $WORK/premap.il
# Timing-driven tech map, then bound fanout with a buffer tree and size gates for the delay target,
# so OOC timing reflects a buffered netlist instead of single gates driving huge fanout cones.
abc -liberty $LIB -D $DTARGET -script +strash;dch;map,-B,0.9;write_blif,$WORK/abc_mapped.blif;cleanup;buffer,-p,-N,$MAXFO;write_blif,$WORK/abc_buffered.blif;upsize;dnsize;stime,-p
opt_clean
setundef -zero
$SRAM_CHECK
tee -o $WORK/stat.txt stat -liberty $LIB $SRAM_STAT
tee -o $WORK/stat.json stat -json -liberty $LIB $SRAM_STAT
write_verilog -noattr $WORK/gate.v
EOF
yosys "$WORK/map.ys" > "$WORK/yosys.log" 2>&1
if [ -n "$SRAM_LIB" ]; then
  python3 "$HERE/sram-area.py" "$WORK/stat.json" "$SRAM_LIB" --output "$WORK/area.json"
fi

# OpenSTA must receive the complete structural netlist. Never strip residual processes to
# make it parse: a remaining process means technology mapping did not finish correctly.
if grep -qE 'always[[:space:]]*@|cover\(|assert\(|assume\(' "$WORK/gate.v"; then
  echo "[sta] mapped netlist still contains behavioral/formal statements" >&2
  exit 1
fi
cp "$WORK/gate.v" "$WORK/gate_clean.v"

echo "[sta] OpenSTA (constrained, sky130 HD)"
cd "$REPO"
REPORT="${STA_REPORT:-report.tcl}"
cp "$VERIF/sta/core.sdc" "$WORK/core.sdc"
cp "$VERIF/sta/$REPORT" "$WORK/report.tcl"
STA_LIB="$LIB" STA_SRAM_LIB="$SRAM_LIB" STA_NETLIST="$WORK/gate_clean.v" STA_TOP="$TOP" "$STA/OpenSTA/build/sta" -no_splash -exit "$VERIF/sta/$REPORT" > "$WORK/timing.txt" 2>&1
cat "$WORK/timing.txt"
if grep -qE '^Error|^critical [0-9]+|invalid command name' "$WORK/timing.txt"; then
  echo "[sta] OpenSTA reported an error; inspect $WORK/timing.txt" >&2
  exit 1
fi
if [ "$REPORT" = "report.tcl" ]; then
  grep -q 'STA_REPORT_COMPLETE' "$WORK/timing.txt" || {
    echo "[sta] timing report did not complete; inspect $WORK/timing.txt" >&2
    exit 1
  }
fi
{
  echo "kind=$KIND"
  echo "config=$CFG"
  echo "top=$TOP"
  echo "measured_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "period_ns=${STA_PERIOD:-10.0}"
  echo "memory_io_fraction=${STA_MEM_MARGIN:-0.6}"
  echo "other_io_fraction=${STA_IO_MARGIN:-0.3}"
  echo "max_fanout=$MAXFO"
  if [ -n "$SRAM_LIB" ]; then
    echo "sram_macro=$SRAM_NAME"
    echo "sram_macro_count=32"
    echo "sram_source_commit=965df150c754fe2b3f93a0bd1f9883eb114279b2"
    echo "sram_liberty=$SRAM_LIB"
    echo "sram_liberty_sha256=$(shasum -a 256 "$SRAM_LIB" | awk '{print $1}')"
    echo "sram_timing_model=OpenRAM analytical Elmore; no PEX characterization"
  fi
  echo "liberty=$LIB"
  echo "liberty_sha256=$(shasum -a 256 "$LIB" | awk '{print $1}')"
  echo "rtl_sha256=$(shasum -a 256 "$SV" | awk '{print $1}')"
  echo "rtl_source=$SV"
  echo "gate_sha256=$(shasum -a 256 "$WORK/gate_clean.v" | awk '{print $1}')"
  yosys -V
  if [ "$KIND" = "core" ]; then sv2v --version; fi
  "$STA/OpenSTA/build/sta" -version
} > "$WORK/run.txt"
echo "[sta] artifacts: $WORK"
