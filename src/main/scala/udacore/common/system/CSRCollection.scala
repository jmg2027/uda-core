package udacore.common.system

import chisel3._
import udacore.common._

class CSRCollection extends Bundle {
  import CSR._

  val mstatus    = new MStatus().named("mstatus")
  val mstatush   = new MStatush().named("mstatush")
  val misa       = new MISA().named("misa")
  val medeleg    = new CSRReg().named("medeleg")
  val mideleg    = new CSRReg().named("mideleg")
  val mie        = new MIE().named("mie")
  val mtvec      = new TVec().named("mtvec")
  val mvendorid  = new CSRReg().named("mvendorid")
  val marchid    = new CSRReg().named("marchid")
  val mimpid     = new CSRReg().named("mimpid")
  val mhartid    = new CSRReg().named("mhartid")
  val mscratch   = new CSRReg().named("mscratch")
  val mepc       = new CSRReg().named("mepc")
  val mcause     = new CSRReg().named("mcause")
  val mtval      = new CSRReg().named("mtval")
  val mip        = new MIP().named("mip")
  val mconfigptr = new CSRReg().named("mconfigptr")
  val menvcfg    = new Envcfg().named("menvcfg")
  val menvcfgh   = new Envcfg().named("menvcfgh")
  val mseccfg    = new CSRReg().named("mseccfg")

  val mcounterinhibit = new CSRReg().named("mcounterinhibit")
  val mcycle          = new CSRCounter().named("mcycle")
  val minstret        = new CSRCounter().named("minstret")
  val mcycleh         = new CSRCounter().named("mcycleh")
  val minstreth       = new CSRCounter().named("minstreth")

  val mhpmcounter  = {
    for (i <- 3 until 32) yield new CSRCounter().named(s"mhpmcounter$i")
  }
  val mhpmevent    = {
    for (i <- 3 until 32) yield new CSRCounter().named(s"mhpmevent$i")
  }
  val mhpmcounterh = {
    for (i <- 3 until 32) yield new CSRCounter().named(s"mhpmcounter${i}h")
  }
  val mhpmeventh   = {
    for (i <- 3 until 32) yield new CSRCounter().named(s"mhpmevent${i}h")
  }

  // for debugger
  val dcsr      = new Dcsr().named("dcsr")
  val dpc       = new CSRReg().named("dpc")
  val dscratch0 = new CSRReg().named("dscratch0")
  val dscratch1 = new CSRReg().named("dscratch1")
  val tselect   = new CSRReg().named("tselect")
  val tdata1    = new TData1().named("tdata1")
  val tdata2    = new CSRReg().named("tdata2")
  val tdata3    = new CSRReg().named("tdata3")
  val tinfo     = new TInfo().named("tinfo")
}
