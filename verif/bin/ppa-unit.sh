#!/usr/bin/env bash
# Out-of-context PPA of the implemented external functional units across their config knobs.
# For each config:
#   emit standalone unit -> yosys map to sky130 HD -> area + DFF (yosys stat)
#   -> OpenSTA constrained worst-slack + report_power (uniform default activity).
#
# Reuses the toolchain installed by verif/bin/setup-sta.sh (yosys + sky130 liberty + OpenSTA).
# OOC caveat (same as the main line): a plain abc map and no placement/wireload here, so
# ABSOLUTE ns/power are inflated - use for RELATIVE comparison only. Area and DFF counts are
# solid; power is a default-activity estimate (relative).
#
#   verif/bin/ppa-unit.sh                        # every config in EmitUnit's catalog
#   verif/bin/ppa-unit.sh mul_csa16 mul_csa32    # a subset
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"; VERIF="$(cd "$HERE/.." && pwd)"; REPO="$(cd "$VERIF/.." && pwd)"
STA=/tmp/verif-sta
LIB="$STA/sky130_fd_sc_hd__tt_025C_1v80.lib"
OPENSTA="$STA/OpenSTA/build/sta"
[ -x "$OPENSTA" ] || { echo "OpenSTA missing - run verif/bin/setup-sta.sh first"; exit 1; }

CFGS=("$@")
if [ ${#CFGS[@]} -eq 0 ]; then
  mapfile -t CFGS < <(bash "$HERE/run.sh" verif.suites.EmitUnit list | awk '{print $1}')
fi
CLK_NS=10.0
ACTIVITY=0.2   # uniform toggle probability for the default-activity power estimate

printf '%-14s | %10s | %6s | %9s | %12s | %12s\n' config "area(um2)" DFFs "wns(ns)" "power(W)" "leak(W)"
printf -- '---------------+------------+--------+-----------+--------------+--------------\n'

for CFG in "${CFGS[@]}"; do
  W="$STA/work_ppa_$CFG"; mkdir -p "$W"

  bash "$HERE/run.sh" verif.suites.EmitUnit "$CFG" "$W" >/dev/null 2>&1
  SV=$(ls "$W"/*.sv 2>/dev/null | head -1); [ -s "$SV" ] || { echo "$CFG: emit failed"; continue; }
  TOP=$(basename "$SV" .sv)

  cat > "$W/map.ys" <<EOF
read_verilog -sv $SV
hierarchy -auto-top
chformal -remove
synth -flatten
dfflibmap -liberty $LIB
abc -liberty $LIB
opt_clean
setundef -zero
tee -o $W/stat.txt stat -liberty $LIB
write_verilog -noattr $W/gate.v
EOF
  yosys "$W/map.ys" > "$W/yosys.log" 2>&1

  AREA=$(grep -iE "Chip area for (top )?module" "$W/stat.txt" | grep -oE '[0-9]+\.[0-9]+' | tail -1)
  DFF=$(grep -iE '^\s+sky130_fd_sc_hd__df' "$W/stat.txt" | awk '{s+=$2} END{print s+0}')

  grep -vE "always @|cover\(|assert\(|assume\(" "$W/gate.v" > "$W/gate_clean.v"

  cat > "$W/sdc.tcl" <<EOF
create_clock -name clk -period $CLK_NS [get_ports clock]
set_input_delay  1.0 -clock clk [all_inputs]
set_output_delay 1.0 -clock clk [all_outputs]
set_driving_cell -lib_cell sky130_fd_sc_hd__buf_2 -pin X [all_inputs]
set_load 0.05 [all_outputs]
EOF
  cat > "$W/pwr.tcl" <<EOF
read_liberty $LIB
read_verilog $W/gate_clean.v
link_design $TOP
source $W/sdc.tcl
set_power_activity -global -activity $ACTIVITY -duty 0.5
report_worst_slack -max
report_power -digits 6
EOF
  "$OPENSTA" -no_splash -exit "$W/pwr.tcl" > "$W/power.txt" 2>&1 || true

  WNS=$(grep -iE 'worst slack max' "$W/power.txt" | awk '{print $NF}')
  # report_power "Total" row: internal switching leakage total (Watts)
  PTOT=$(grep -iE '^Total' "$W/power.txt" | awk '{print $5}')
  PLEAK=$(grep -iE '^Total' "$W/power.txt" | awk '{print $4}')

  printf '%-14s | %10s | %6s | %9s | %12s | %12s\n' "$CFG" "${AREA:-?}" "${DFF:-?}" "${WNS:-?}" "${PTOT:-?}" "${PLEAK:-?}"
done
