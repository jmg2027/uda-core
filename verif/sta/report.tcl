# OpenSTA driver: link the buffered gate netlist + SDC, report timing constraints,
# design-rule violations, the worst paths, and the external-memory input path.
#   sta -no_splash -exit verif/sta/report.tcl   (driven by verif/bin/sta.sh)
# Expects env STA_LIB / STA_NETLIST / STA_TOP (linked top module: CoreTop or a unit name).
read_liberty $env(STA_LIB)
if {[info exists env(STA_SRAM_LIB)] && $env(STA_SRAM_LIB) ne ""} {
  read_liberty $env(STA_SRAM_LIB)
}
read_verilog $env(STA_NETLIST)
link_design $env(STA_TOP)
read_sdc verif/sta/core.sdc

puts "################ SUMMARY ################"
report_clock_properties [all_clocks]
report_worst_slack -max
report_worst_slack -min
report_tns -max
report_tns -min
report_wns -max
report_wns -min

# A single P - slack conversion is not a frequency estimate when IO delays are fractions of
# P. Record the measured constraint point; use a new STA run to test any different period.
set period [expr {[info exists ::env(STA_PERIOD)] ? $::env(STA_PERIOD) : 10.0}]
puts [format "Measured clock constraint: %.3f ns (%.3f MHz)" $period [expr {1000.0/$period}]]
puts "Timing model: sky130 HD TT 25C 1.80V, statistical wire load, ideal clock/reset"
if {[info exists env(STA_SRAM_LIB)] && $env(STA_SRAM_LIB) ne ""} {
  puts "SRAM model: pinned OpenRAM macro TT 25C 1.80V, analytical Elmore timing (not PEX)"
} else {
  puts "No SRAM macro library included in this target"
}
puts "No placement, extracted parasitics or clock tree; no owner PPA acceptance bar"

puts "################ CONSTRAINT COVERAGE ################"
check_setup -verbose

puts "################ DESIGN-RULE VIOLATIONS (max fanout / transition / capacitance) ################"
puts "## empty = the buffer pass brought every net in budget; entries = nets to look at, not OOC noise"
report_check_types -max_slew -max_fanout -max_capacitance -violators

puts "################ WORST 8 PATHS (endpoint + slack) ################"
report_checks -path_delay max -group_path_count 8 -sort_by_slack -fields {fanout slew capacitance} -digits 3

puts "################ REGISTER-TO-REGISTER PATHS ################"
report_checks -from [all_registers -clock_pins] -to [all_registers -data_pins] \
  -path_delay max -group_path_count 4 -fields {fanout slew capacitance} -digits 3

puts "################ WORST 4 HOLD PATHS (ideal clocks) ################"
report_checks -path_delay min -group_path_count 4 -sort_by_slack \
  -fields {fanout slew capacitance} -digits 3

set mem_glob [expr {[info exists ::env(STA_MEM_GLOB)] ? $::env(STA_MEM_GLOB) : "io_*Bus_*"}]
set mem_ports [get_ports -quiet $mem_glob]
if {[llength $mem_ports] > 0} {
  puts "################ MEMORY-BOUNDARY INPUT PATHS (TileLink io_*Bus_*) ################"
  report_checks -from $mem_ports -path_delay max -group_path_count 4 -fields {fanout slew} -digits 3
}
puts "STA_REPORT_COMPLETE"
