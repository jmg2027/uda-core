# Out-of-context STA constraints for the rebuild line (sky130 HD, OpenSTA), shared by the
# CoreTop and functional-unit targets (STA_TOP selects the linked design in report.tcl).
#
# Ported from the main line's klase32.sdc. Three things make the reported ns a defensible
# pre-layout number instead of an unbuffered-net artifact:
#   1. fanout buffering happens in synthesis - verif/bin/sta.sh runs abc `buffer`/`upsize`/`dnsize`,
#      so a high-fanout net is driven by a real buffer tree, not one overloaded gate.
#   2. a statistical wire-load model (liberty "Small": fanout -> length -> R/C) charges every net a
#      realistic parasitic cap/res from its fanout, instead of an ideal zero-cap wire.
#   3. every data IO gets a driving cell + load and an off-chip input/output delay, so input cones
#      are timed from a realistic source, not an ideal zero-arrival one.
# What is still missing vs a signed-off flow is real placement (actual wire lengths) and a clock
# tree; the wire-load model estimates the former statistically and reset/clock are handled below.
#
# Overridable from the environment: STA_PERIOD (clock), STA_MEM_MARGIN (external-memory IO
# fraction, default 0.6), STA_IO_MARGIN (all other IO fraction, default 0.3), STA_MEM_GLOB
# (port glob for the memory boundary, default io_*Bus_* = the TileLink links). Sweep STA_PERIOD to find
# the frequency the design actually closes at.
#
# reset is a global net that in silicon gets its own buffer tree (like the clock). In OOC there is
# no such tree, so it is modelled the way it will be built rather than as one off-chip pin driving
# the whole design: false path (arrival), ideal network, and (in the IO section below) no off-chip
# driving cell - so its transition is ideal instead of the huge slew a single driver into a
# several-thousand-fanout wire-load net would give, which otherwise bleeds into every data gate.

set period [expr {[info exists ::env(STA_PERIOD)] ? $::env(STA_PERIOD) : 10.0}]
# IO budget as a fraction of the clock period, applied per port. The external memory boundary
# ports (STA_MEM_GLOB, CoreTop's TileLink links io_instBus_* / io_dataBus_*) get a large 60%
# off-chip budget - they face an off-chip SRAM/fabric with real setup/launch time, so their
# IO-bounded cones (load response -> next request) must be timed tightly. Every other data IO
# gets 30% (the on-chip-neighbour default). Kept as fractions so they track a STA_PERIOD sweep.
set mem_glob [expr {[info exists ::env(STA_MEM_GLOB)] ? $::env(STA_MEM_GLOB) : "io_*Bus_*"}]
set mem_pct  [expr {[info exists ::env(STA_MEM_MARGIN)] ? $::env(STA_MEM_MARGIN) : 0.6}]
set oth_pct  [expr {[info exists ::env(STA_IO_MARGIN)] ? $::env(STA_IO_MARGIN) : 0.3}]
set mem_delay [expr {$mem_pct * $period}]
set oth_delay [expr {$oth_pct * $period}]

create_clock -name clk -period $period [get_ports clock]

# Statistical wire-load: charge each net RC from its fanout. "top" mode = one model for the whole
# flattened design (there is no submodule hierarchy after synth -flatten).
set_wire_load_mode top
set_wire_load_model -name Small

# Off-chip IO budget, per port. clock and reset are excluded (ideal distribution networks; see the
# header). Port objects are passed to the delay/driver commands directly (OpenSTA has no
# remove_from_collection, and re-globbing bit-blasted names like foo[7] is unsafe).
foreach in_port [all_inputs] {
  set nm [get_name $in_port]
  if {$nm eq "clock" || $nm eq "reset"} continue
  set isMem [string match $mem_glob $nm]
  set d [expr {$isMem ? $mem_delay : $oth_delay}]
  set_input_delay   $d -clock clk $in_port
  set_driving_cell -lib_cell sky130_fd_sc_hd__buf_2 -pin X $in_port
}
foreach out_port [all_outputs] {
  set nm [get_name $out_port]
  set isMem [string match $mem_glob $nm]
  set d [expr {$isMem ? $mem_delay : $oth_delay}]
  set_output_delay $d -clock clk $out_port
}
set_load 0.05 [all_outputs]

# Design-rule limits: report_check_types flags any net the buffer pass could not bring in budget,
# so a large reported delay can be traced to a real DRV instead of dismissed as "OOC noise".
set_max_fanout      16.0 [current_design]
set_max_transition  0.75 [current_design]
set_max_capacitance 0.5  [current_design]

# Asynchronous inputs: debugReq is the only async pin by spec on the main line; the rebuild's
# intfDebugReqIn is speced rawNoDecoupled and lands on the same treatment when CoreTop's io
# exists. string match keeps this a no-op for unit targets without such a port.
foreach in_port [all_inputs] {
  if {[string match "io_debugReq*" [get_name $in_port]]} { set_false_path -from $in_port }
}
if {[llength [get_ports -quiet reset]] > 0} {
  set_false_path -from [get_ports reset]
  set_ideal_network [get_ports reset]
}
