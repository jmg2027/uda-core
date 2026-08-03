package udacore.backend.design.shared

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared.BackendParams
import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.common.Causes
import udacore.common.ControlSignal.{CSRControl, DividerControl, MultiplierControl}

/** Backend bundle implementations.
  *
  * Implements the bundle specifications defined in BackendBundlesSpecs. Field
  * details to be filled in later based on specific requirements.
  */

@LocalSpec(bndInstructionIssue)
class InstructionIssue() extends Bundle {
  // Fields: instBytes, pc, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndInterrupt)
class Interrupt() extends Bundle {
  val e = Bool() // External interrupt
  val t = Bool() // Timer interrupt
  val s = Bool() // Software interrupt
}

@LocalSpec(bndMemoryOpResp)
class MemoryOpResp() extends Bundle {
  // Fields: data, meta, fault
  // TODO: Define specific field implementations
}

@LocalSpec(bndDecodedUop)
class DecodedUop() extends Bundle {
  // Fields: uopId, opcode, operands, immediates, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndRenamedUop)
class RenamedUop() extends Bundle {
  // Fields: uopId, physicals, dependencies, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndRegisterFileReadReq)
class RegisterFileReadReq() extends Bundle {
  // Fields: prs, meta, epochTag
  // TODO: Define specific field implementations
}

@LocalSpec(bndRegisterFileReadResp)
class RegisterFileReadResp() extends Bundle {
  // Fields: values, bypassMask, epochTag
  // TODO: Define specific field implementations
}

@LocalSpec(bndDispatchedUop)
class DispatchedUop() extends Bundle {
  // Fields: uopId, operands, fuMask, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndDecodedUopAlloc)
class DecodedUopAlloc() extends Bundle {
  // Fields: uopId, physicals, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndAluReq)
class AluReq() extends Bundle {
  // Fields: uopId, op, lhs, rhs, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndBitAluReq)
class BitAluReq() extends Bundle {
  // Fields: uopId, op, lhs, rhs, mask
  // TODO: Define specific field implementations
}

@LocalSpec(bndMultiplierReq)
class MultiplierReq(val params: BackendParams) extends BackendBundle {
  val uopId = UInt(32.W)
  val lhs   = UInt(xLen.W)
  val rhs   = UInt(xLen.W)
  val op    = MultiplierControl()
  val rd    = UInt(regIdWidth.W)
  val seq   = UInt(32.W)
  val epoch = UInt(epochWidth.W)
}

@LocalSpec(bndDividerReq)
class DividerReq(val params: BackendParams) extends BackendBundle {
  val uopId   = UInt(32.W)
  val dividend = UInt(xLen.W)
  val divisor  = UInt(xLen.W)
  val op      = DividerControl()
  val rd      = UInt(regIdWidth.W)
  val seq     = UInt(32.W)
  val epoch   = UInt(epochWidth.W)
}

@LocalSpec(bndBranchUnitReq)
class BranchUnitReq() extends Bundle {
  // Fields: uopId, lhs, rhs, pc, target, prediction
  // TODO: Define specific field implementations
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

@LocalSpec(bndAddressGenerationReq)
class AddressGenerationReq() extends Bundle {
  // Fields: uopId, base, offset, attrs
  // TODO: Define specific field implementations
}

@LocalSpec(bndAluResult)
class AluResult() extends Bundle {
  // Fields: uopId, result, flags
  // TODO: Define specific field implementations
}

@LocalSpec(bndBitAluResult)
class BitAluResult() extends Bundle {
  // Fields: uopId, result, flags
  // TODO: Define specific field implementations
}

@LocalSpec(bndMultiplierResult)
class MultiplierResult(val params: BackendParams) extends BackendBundle {
  val uopId = UInt(32.W)
  val data  = UInt(xLen.W)
  val rd    = UInt(regIdWidth.W)
  val seq   = UInt(32.W)
  val epoch = UInt(epochWidth.W)
  val flags = UInt(4.W)
}

@LocalSpec(bndDividerResult)
class DividerResult(val params: BackendParams) extends BackendBundle {
  val uopId    = UInt(32.W)
  val quotient = UInt(xLen.W)
  val remainder = UInt(xLen.W)
  val rd       = UInt(regIdWidth.W)
  val seq      = UInt(32.W)
  val epoch    = UInt(epochWidth.W)
  val flags    = UInt(4.W)
}

@LocalSpec(bndBranchUnitResult)
class BranchUnitResult() extends Bundle {
  // Fields: uopId, pc, target, epochTag
  // TODO: Define specific field implementations
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

@LocalSpec(bndPublishResult)
class PublishResult() extends Bundle {
  // Fields: uopId, data, flags
  // TODO: Define specific field implementations
}

@LocalSpec(bndCommitResult)
class CommitResult() extends Bundle {
  // Fields: uopId, data, flags, epochTag
  // TODO: Define specific field implementations
}

@LocalSpec(bndWriteBack)
class WriteBack() extends Bundle {
  // Fields: prs, data, flags
  // TODO: Define specific field implementations
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

@LocalSpec(bndException)
class Exception() extends Bundle {
  // Fields: uopId, cause, epochTag
  // TODO: Define specific field implementations
}

@LocalSpec(bndRedirect)
class Redirect() extends Bundle {
  // Fields: pc, target, reason, epochTag
  // TODO: Define specific field implementations
}

@LocalSpec(bndMispredict)
class Mispredict() extends Bundle {
  // Fields: pc, target, prediction, epochTag
  // TODO: Define specific field implementations
}

@LocalSpec(bndMemoryOpReq)
class MemoryOpReq() extends Bundle {
  // Fields: addr, size, write, data, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndRedirectOut)
class RedirectOut() extends Bundle {
  // Fields: pc, target, reason, epochTag
  // TODO: Define specific field implementations
}
