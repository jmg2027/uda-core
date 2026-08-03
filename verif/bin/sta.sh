#!/usr/bin/env bash
# Gate-level STA with real IO constraints (OpenSTA + sky130 HD).
#   verif/bin/setup-sta.sh                 # ONCE per container (yosys + liberty + OpenSTA)
#   verif/bin/sta.sh core [config]         # whole CoreTop (once its RTL elaborates)
#   verif/bin/sta.sh unit <unitConfig>     # an implemented functional unit (works today)
# Unit config names: run.sh verif.suites.EmitUnit list  (alu, mul_csa16, div_nrclz, bitalu, ...)
#
# This is a real pre-placement OOC flow, not a rough relative estimate. The map step below runs
# timing-driven abc plus a fanout buffer tree (`buffer`) and gate sizing (`upsize`/`dnsize`), so a
# high-fanout net is driven by a buffer tree instead of one overloaded gate. Combined with the
# liberty wire-load model in the SDC, the reported ns is a defensible pre-layout number. What is
# still absent vs sign-off is real placement / a clock tree; treat the number as a good pre-layout
# estimate (and sweep STA_PERIOD to find the closing frequency), not a taped-out period.
#
# Tunable (env): STA_MAXFO (max fanout before buffering, default 16), STA_PERIOD / STA_IN_DELAY /
# STA_OUT_DELAY (forwarded to the SDC), STA_REPORT (alternate report .tcl).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"; VERIF="$(cd "$HERE/.." && pwd)"; REPO="$(cd "$VERIF/.." && pwd)"
KIND="${1:-unit}"; CFG="${2:-}"
STA=/tmp/verif-sta
LIB="$STA/sky130_fd_sc_hd__tt_025C_1v80.lib"
MAXFO="${STA_MAXFO:-16}"
# abc delay target in ps = clock period (ns) * 1000; steers the mapper toward the delay path.
DTARGET="$(awk "BEGIN{printf \"%d\", ${STA_PERIOD:-10.0}*1000}")"

[ -x "$STA/OpenSTA/build/sta" ] || { echo "OpenSTA missing - run verif/bin/setup-sta.sh first"; exit 1; }

case "$KIND" in
  core)
    CFG="${CFG:-default}"
    WORK="$STA/work_core_$CFG"; mkdir -p "$WORK"
    echo "[sta] emit core ($CFG)"
    bash "$HERE/run.sh" verif.suites.EmitCore "$CFG" "$WORK" >/dev/null 2>&1 \
      || { echo "emit failed - CoreTop is likely still a spec shell (run.sh verif.suites.EmitCore $CFG for the message)"; exit 3; }
    TOP=CoreTop ;;
  unit)
    [ -n "$CFG" ] || { echo "usage: sta.sh unit <unitConfig>   (run.sh verif.suites.EmitUnit list)"; exit 2; }
    WORK="$STA/work_unit_$CFG"; mkdir -p "$WORK"
    echo "[sta] emit unit ($CFG)"
    bash "$HERE/run.sh" verif.suites.EmitUnit "$CFG" "$WORK" >/dev/null 2>&1
    TOP=$(basename "$(ls "$WORK"/*.sv 2>/dev/null | head -1)" .sv) ;;
  *) echo "usage: sta.sh <core|unit> <config>"; exit 2 ;;
esac
SV="$WORK/$TOP.sv"; [ -s "$SV" ] || { echo "emit failed"; exit 1; }

echo "[sta] map to sky130 gates (yosys: timing-driven + fanout buffer N=$MAXFO, top=$TOP)"
cat > "$WORK/map.ys" <<EOF
read_verilog -sv $SV
hierarchy -auto-top
chformal -remove
synth -flatten
dfflibmap -liberty $LIB
# Timing-driven tech map, then bound fanout with a buffer tree and size gates for the delay target,
# so OOC timing reflects a buffered netlist instead of single gates driving huge fanout cones.
abc -liberty $LIB -D $DTARGET -script +strash;dch;map,-B,0.9;buffer,-N,$MAXFO;upsize;dnsize;stime,-p
opt_clean
setundef -zero
tee -o $WORK/stat.txt stat -liberty $LIB
write_verilog -noattr $WORK/gate.v
EOF
yosys "$WORK/map.ys" > "$WORK/yosys.log" 2>&1

# OpenSTA's structural reader rejects the simulation-only cover/assert statements yosys emits.
grep -vE "always @|cover\(|assert\(|assume\(" "$WORK/gate.v" > "$WORK/gate_clean.v"

echo "[sta] OpenSTA (constrained, sky130 HD)"
cd "$REPO"
REPORT="${STA_REPORT:-report.tcl}"
STA_LIB="$LIB" STA_NETLIST="$WORK/gate_clean.v" STA_TOP="$TOP" "$STA/OpenSTA/build/sta" -no_splash -exit "$VERIF/sta/$REPORT" 2>&1 \
  | grep -vE "^Warning 503|^Warning 441|^$"
