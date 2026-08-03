package udacore.common.system

import chisel3._
import chisel3.util._
import udacore.common._

object CSRTemplateBase {
  abstract class CSRRegTemplate[T <: Bundle] {
    def field: T
    def default: UInt

    val reg: T = RegInit(field, default.asTypeOf(field))

    /** Give this CSR register a descriptive name in the emitted RTL. */
    def named(name: String): this.type = {
      reg.suggestName(name)
      this
    }

    def write[D <: Data](wdata: D): Unit = {
      val newData = Wire(field)
      newData := wdata.asTypeOf(field)
      reg     := newData
    }

    def read[D <: Data](rdata: D): Unit = {
      rdata := reg.asTypeOf(rdata)
    }
  }

  abstract class CoreCSRReg[T <: Bundle]
      extends CSRRegTemplate[T]
}
