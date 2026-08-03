# OpenSTA driver: link the buffered gate netlist + SDC, report the achievable frequency, any
# design-rule violations, the worst paths, and the external-memory input path.
#   sta -no_splash -exit verif/sta/report.tcl   (driven by verif/bin/sta.sh)
# Expects env STA_LIB / STA_NETLIST / STA_TOP (linked top module: CoreTop or a unit name).
read_liberty $env(STA_LIB)
read_verilog $env(STA_NETLIST)
link_design $env(STA_TOP)
read_sdc verif/sta/core.sdc

puts "################ SUMMARY ################"
report_worst_slack -max
report_tns
report_wns

# Turn the worst setup slack into a closing frequency: at clock period P and worst slack S, the
# smallest period that still meets timing is P - S, i.e. fmax = 1000 / (P - S) MHz.
set period [expr {[info exists ::env(STA_PERIOD)] ? $::env(STA_PERIOD) : 10.0}]
if {[catch {worst_slack -max} ws]} { set ws [sta::worst_slack_corner [sta::cmd_corner] max] }
set achievable [expr {$period - $ws}]
if {$achievable > 0} {
  puts [format "Achievable period (OOC est): %.3f ns  ->  %.1f MHz   (worst slack %.3f ns at %.1f ns clock)" \
    $achievable [expr {1000.0/$achievable}] $ws $period]
} else {
  puts [format "Achievable period (OOC est): met with margin at %.1f ns clock (worst slack %.3f ns)" $period $ws]
}

puts "################ DESIGN-RULE VIOLATIONS (max fanout / transition / capacitance) ################"
puts "## empty = the buffer pass brought every net in budget; entries = nets to look at, not OOC noise"
report_check_types -max_slew -max_fanout -max_capacitance -violators

puts "################ WORST 8 PATHS (endpoint + slack) ################"
report_checks -path_delay max -group_path_count 8 -sort_by_slack -fields {fanout slew capacitance} -digits 3

set mem_ports [get_ports -quiet io_*Bus_*]
if {[llength $mem_ports] > 0} {
  puts "################ MEMORY-BOUNDARY INPUT PATHS (TileLink io_*Bus_*) ################"
  report_checks -from $mem_ports -path_delay max -group_path_count 4 -fields {fanout slew} -digits 3
}
