package udacore.common.system

import chisel3._
import chisel3.util._

object CSR {
  import CSRTemplateBase._
  import CSRFields._

  class CSRReg(val defaultValue: UInt = 0.U)
      extends CoreCSRReg[CSRFields.CSRReg] {
    override def field = new CSRFields.CSRReg
    def default        = defaultValue
  }

  class CSRCounter extends CSRReg {
    def count(inc: UInt, inhibit: Bool) = {
      when(inhibit) {
        reg := reg
      }.otherwise {
        reg.asUInt := reg.asUInt + inc
      }
    }
  }

  class Dcsr extends CoreCSRReg[CSRFields.Dcsr] {
    override def field = new CSRFields.Dcsr
    def default: UInt  = 0x4000_0403.U
  }

  class TInfo extends CoreCSRReg[CSRFields.TInfo] {
    override def field = new CSRFields.TInfo
    def default: UInt  = 0x1000_0040.U
    // version = 1
    // mcontrol6 = 1
  }

  class TData1 extends CoreCSRReg[CSRFields.MControl6] {
    override def field = new CSRFields.MControl6
    def default: UInt  = 0x68001000.U // for mcontrol6 status
  }

  class MStatus extends CoreCSRReg[CSRFields.MStatus] {
    override def field = new CSRFields.MStatus
    def default        = 40.U
  }

  class MStatush extends CoreCSRReg[CSRFields.MStatush] {
    def field   = new CSRFields.MStatush
    def default = 0.U
  }

  class MIP extends CoreCSRReg[CSRFields.MIP] {
    override def field = new CSRFields.MIP
    def default        = 0.U
  }

  class MIE extends MIP

  class Envcfg extends CoreCSRReg[CSRFields.Envcfg] {
    def field   = new CSRFields.Envcfg
    def default = 0.U
  }

  class TVec extends CoreCSRReg[CSRFields.TVec] {
    def field   = new CSRFields.TVec
    def default = 0.U
  }

  class MISA extends CSRReg {
    override def default: UInt = {
      // Fixed RISC-V 32-bit base ISA with standard extensions
      val isaString =
        "IMAFDCSU" // Base integer + multiply/divide + atomics + float + double + compressed + supervisor + user
      val isaMax = (BigInt(log2Ceil(32) - 4) << (32 - 2)) |
        isaString
          .map(x => 1 << (x - 'A'))
          .foldLeft((0))(_ | _)
      isaMax.U
    }
  }
}
