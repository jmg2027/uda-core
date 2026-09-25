package udacore.backend.design.shared

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs.{funcRecoveryKills, funcRobOlder}
import udacore.core.spec.shared.CoreBundlesSpecs.bndExceptionInfo
import udacore.common.Causes
import udacore.common.ControlSignal.CSRControl

/** Backend bundle implementations.
  *
  * Two groups live here. The ADR-019 bundles (ordering identity, recovery,
  * rename allocation) are authored together with the RTL that first drives
  * them, each against its BackendBundlesSpecs field table; the remaining
  * ADR-019 bundles (RobEntry, LSQ entries, ...) are added with their vertices.
  * The CSR-facing bundles at the end are the interface of the legacy
  * csr/CSR.scala (OQ-E waived; rewritten with the ADR-019 RTL) and keep their
  * shape until that file is rewritten; their `epoch` meta fields are legacy of
  * the superseded machine and carry no ADR-019 meaning.
  */

// ---- Enumerated payload codes ----------------------------------------------
//
// The spec names the enumerations (RecoveryKind, RecoveryCause, CfiType, FuType)
// by their members only; these objects fix the v0 encodings. Plain UInt codes
// (not ChiselEnum) keep every port pokeable by the L1 SpecTests.

object RecoveryKind {
  val width            = 1
  val BranchMispredict = 0.U(width.W)
  val ArchRedirect     = 1.U(width.W)
}

object RecoveryCause {
  val width               = 3
  val DirectionMispredict = 0.U(width.W)
  val TargetMispredict    = 1.U(width.W)
  val UnpredictedCfi      = 2.U(width.W)
  val Trap                = 3.U(width.W)
  val Interrupt           = 4.U(width.W)
  val XRet                = 5.U(width.W)
  val Refetch             = 6.U(width.W)
}

object CfiType {
  val width  = 3
  val None   = 0.U(width.W)
  val Branch = 1.U(width.W)
  val Jal    = 2.U(width.W)
  val Jalr   = 3.U(width.W)
  val Call   = 4.U(width.W)
  val Ret    = 5.U(width.W)
}

object FuType {
  val width  = 3
  val Alu    = 0.U(width.W)
  val Mul    = 1.U(width.W)
  val Div    = 2.U(width.W)
  val Branch = 3.U(width.W)
  val Mem    = 4.U(width.W)
  val Csr    = 5.U(width.W)
  val System = 6.U(width.W)
}

/** Unit-local operation code carried opaquely from DecodeUnit to the unit named
  * by fuType; RenameUnit never interprets it. For fuType System the "unit" is the
  * commit head, and op carries the SysOp code (see SysOp). */
object UopOp {
  val width = 5
}

/** Commit-head system behavior recorded in the ROB entry (bndRobEntry.sysOp). DecodeUnit
  * sets it (funcSerializingTag); it travels in DecodedUop.op for fuType System uops, and
  * every fuType Csr uop is Csr (the commit-side refetch rule for CSR writes applies). */
object SysOp {
  val width     = 3
  val None      = 0.U(width.W)
  val XRet      = 1.U(width.W)
  val Fence     = 2.U(width.W)
  val FenceI    = 3.U(width.W)
  val SfenceVma = 4.U(width.W)
  val Wfi       = 5.U(width.W)
  val Csr       = 6.U(width.W)
}

// ---- Ordering and recovery identity ----------------------------------------

@LocalSpec(bndRobTag)
class RobTag(val params: BackendParams) extends BackendBundle {
  val wrap = Bool()
  val idx  = UInt((robTagWidth - 1).W)
}

@LocalSpec(bndBranchCheckpointId)
class BranchCheckpointId(val params: BackendParams) extends BackendBundle {
  val id = UInt(checkpointIdWidth.W)
}

@LocalSpec(bndCheckpointRelease)
class CheckpointRelease(val params: BackendParams) extends BackendBundle {
  val checkpointId = new BranchCheckpointId(params)
  val robTag       = new RobTag(params)
}

@LocalSpec(bndCfiOutcome)
class CfiOutcome(val params: BackendParams) extends BackendBundle {
  val cfiType = UInt(CfiType.width.W)
  val slot    = UInt(fetchSlotWidth.W)
  val taken   = Bool()
  val target  = UInt(vAddrWidth.W)
}

@LocalSpec(bndRecoveryEvent)
class RecoveryEvent(val params: BackendParams) extends BackendBundle {
  val valid        = Bool()
  val kind         = UInt(RecoveryKind.width.W)
  val robTag       = new RobTag(params)
  val checkpointId = new BranchCheckpointId(params)
  val target       = UInt(vAddrWidth.W)
  val ftqIdx       = UInt(ftqIdxWidth.W)
  val cfiOutcome   = new CfiOutcome(params)
  val cause        = UInt(RecoveryCause.width.W)
}

/** The single program-order function and the common kill predicate (ADR-019
  * D-19.9). Every speculative holder evaluates recovery only through these. */
object RobOrder {
  @LocalSpec(funcRobOlder)
  def robOlder(a: RobTag, b: RobTag): Bool =
    Mux(a.wrap === b.wrap, a.idx < b.idx, a.idx > b.idx)

  @LocalSpec(funcRecoveryKills)
  def recoveryKills(e: RecoveryEvent, t: RobTag): Bool =
    e.valid && (e.kind === RecoveryKind.ArchRedirect ||
      (e.kind === RecoveryKind.BranchMispredict && robOlder(e.robTag, t)))

  /** The tag allocated after `t`: idx wraps at robDepth and toggles wrap. */
  def next(t: RobTag): RobTag = {
    val n    = Wire(chiselTypeOf(t))
    val last = t.idx === (t.params.robDepth - 1).U
    n.idx  := Mux(last, 0.U, t.idx + 1.U)
    n.wrap := t.wrap ^ last
    n
  }
}

// ---- Rename -----------------------------------------------------------------

@LocalSpec(bndExceptionInfo)
class ExceptionInfo(val params: BackendParams) extends BackendBundle {
  val valid = Bool()
  val cause = UInt(5.W)
  val tval  = UInt(xLen.W)
}

/** The DecodedUop prediction field (PredictionView): the frontend's per-slot prediction. */
class PredictionView(val params: BackendParams) extends BackendBundle {
  val predictedTaken  = Bool()
  val predictedTarget = UInt(vAddrWidth.W)
  val ftqIdx          = UInt(ftqIdxWidth.W)
  val slot            = UInt(fetchSlotWidth.W)
  val blockEnd        = Bool()
}

@LocalSpec(bndDecodedUop)
class DecodedUop(val params: BackendParams) extends BackendBundle {
  val pc              = UInt(vAddrWidth.W)
  val insn            = UInt(iLen.W)
  val fuType          = UInt(FuType.width.W)
  val op              = UInt(UopOp.width.W)
  val rd              = UInt(regIdWidth.W)
  val rs1             = UInt(regIdWidth.W)
  val rs2             = UInt(regIdWidth.W)
  val imm             = UInt(xLen.W)
  val isCfi           = Bool()
  val isLoad          = Bool()
  val isStore         = Bool()
  val serialize       = Bool()
  val prediction      = new PredictionView(params)
  val predictionFault = Bool()
  val exception       = new ExceptionInfo(params)
}

/** A decode packet: up to DecodeWidth DecodedUops, oldest first; the valid lanes
  * form a prefix (intfDecodedPacketOut). */
class DecodedPacket(val params: BackendParams) extends BackendBundle {
  val lanes = Vec(params.decodeWidth, new DecodedLane(params))
}

/** One DecodedPacket lane. */
class DecodedLane(val params: BackendParams) extends BackendBundle {
  val valid = Bool()
  val uop   = new DecodedUop(params)
}

@LocalSpec(bndRenameAllocation)
class RenameAllocation(val params: BackendParams) extends BackendBundle {
  val robTag       = new RobTag(params)
  val uop          = new DecodedUop(params)
  val prs1         = UInt(physRegIdWidth.W)
  val prs2         = UInt(physRegIdWidth.W)
  val prs1Ready    = Bool()
  val prs2Ready    = Bool()
  val hasDest      = Bool()
  val newPrd       = UInt(physRegIdWidth.W)
  val oldPrd       = UInt(physRegIdWidth.W)
  val checkpointId = new BranchCheckpointId(params)
}

@LocalSpec(bndLsqAllocation)
class LsqAllocation(val params: BackendParams) extends BackendBundle {
  val robTag  = new RobTag(params)
  val isLoad  = Bool()
  val isStore = Bool()
  val size    = UInt(2.W)
  val signed  = Bool()
  val prd     = UInt(physRegIdWidth.W)
}

/** RenameCommit: the RenameUnit-projected view of the commit broadcast. */
@LocalSpec(bndCommitBroadcast)
class RenameCommit(val params: BackendParams) extends BackendBundle {
  val archRd  = UInt(regIdWidth.W)
  val newPrd  = UInt(physRegIdWidth.W)
  val oldPrd  = UInt(physRegIdWidth.W)
  val hasDest = Bool()
}

@LocalSpec(bndWakeupBroadcast)
class WakeupBroadcast(val params: BackendParams) extends BackendBundle {
  val valid = Bool()
  val prd   = UInt(physRegIdWidth.W)
}

@LocalSpec(bndRobEntry)
class RobEntry(val params: BackendParams) extends BackendBundle {
  val valid           = Bool()
  val done            = Bool()
  val pc              = UInt(vAddrWidth.W)
  val insn            = UInt(iLen.W)
  val archRd          = UInt(regIdWidth.W)
  val hasDest         = Bool()
  val newPrd          = UInt(physRegIdWidth.W)
  val oldPrd          = UInt(physRegIdWidth.W)
  val exception       = new ExceptionInfo(params)
  val isCfi           = Bool()
  val checkpointId    = new BranchCheckpointId(params)
  val cfiOutcome      = new CfiOutcome(params)
  val ftqIdx          = UInt(ftqIdxWidth.W)
  val blockEnd        = Bool()
  val isLoad          = Bool()
  val isStore         = Bool()
  val headExecute     = Bool()
  val serialize       = Bool()
  val sysOp           = UInt(SysOp.width.W)
  val predictionFault = Bool()
}

@LocalSpec(bndRobCompletion)
class RobCompletion(val params: BackendParams) extends BackendBundle {
  val robTag      = new RobTag(params)
  val exception   = new ExceptionInfo(params)
  val cfiOutcome  = new CfiOutcome(params)
  val headExecute = Bool()
}

@LocalSpec(bndRobHead)
class RobHead(val params: BackendParams) extends BackendBundle {
  val robTag = new RobTag(params)
  val entry  = new RobEntry(params)
}

@LocalSpec(bndRobStatus)
class RobStatus(val params: BackendParams) extends BackendBundle {
  val empty   = Bool()
  val headTag = new RobTag(params)
}

// ---- Legacy CSR-facing bundles ----------------------------------------------

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
