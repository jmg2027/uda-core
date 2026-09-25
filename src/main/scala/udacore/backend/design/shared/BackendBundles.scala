package udacore.backend.design.shared

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.common.Causes
import udacore.common.ControlSignal.CSRControl

/** Backend bundle implementations retained ahead of the ADR-019 RTL.
  *
  * Only the CSR-facing bundles exist in the design tree today: they are the
  * interface of the owner-protected csr/CSR.scala (OQ-E) and must keep their
  * shape until that file is rewritten. Every other ADR-019 backend bundle
  * (RobTag, RecoveryEvent, RobEntry, RenameAllocation, LSQ entries, ...) is
  * specified in BackendBundlesSpecs and is authored together with its RTL.
  * The `epoch` meta fields below are legacy of the superseded machine and carry
  * no ADR-019 meaning.
  */

@LocalSpec(bndInterrupt)
class Interrupt() extends Bundle {
  val e = Bool() // External interrupt
  val t = Bool() // Timer interrupt
  val s = Bool() // Software interrupt
}

class CsrReqMeta(val params: BackendParams) extends BackendBundle {
  val rd    = UInt(regIdWidth.W)
  val epoch = UInt(epochWidth.W)
}

@LocalSpec(bndCsrReq)
class CsrReq(val params: BackendParams) extends BackendBundle {
  val csr  = UInt(12.W)
  val op   = CSRControl()
  val data = UInt(xLen.W)
  val meta = new CsrReqMeta(params)
}

class CsrResultTraps extends Bundle {
  val exception = Bool()
  val cause     = UInt(Causes.all.length.W)
}

class CsrResultMeta(val params: BackendParams) extends BackendBundle {
  val rd    = UInt(regIdWidth.W)
  val epoch = UInt(epochWidth.W)
}

@LocalSpec(bndCsrResult)
class CsrResult(val params: BackendParams) extends BackendBundle {
  val csr   = UInt(12.W)
  val data  = UInt(xLen.W)
  val traps = new CsrResultTraps
  val meta  = new CsrResultMeta(params)
}

@LocalSpec(bndCsrTrapRead)
class CsrTrapRead(val params: BackendParams) extends BackendBundle {
  val mtvec   = UInt(xLen.W)
  val mstatus = UInt(xLen.W)
}

@LocalSpec(bndCsrTrapWrite)
class CsrTrapWrite(val params: BackendParams) extends BackendBundle {
  val mepc    = UInt(xLen.W)
  val mcause  = UInt(xLen.W)
  val mtval   = UInt(xLen.W)
  val mstatus = UInt(xLen.W)
}
