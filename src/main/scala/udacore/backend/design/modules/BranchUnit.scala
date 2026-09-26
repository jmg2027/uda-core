package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import udacore.common.ControlSignal.BranchControl
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.BranchUnitSpecs._

/** BranchUnit (spec: BranchUnitSpecs; ADR-019 D-19.9, ADR-019C E-4..E-6).
  *
  * Resolves one branch per accept and keeps two independent output tokens:
  *  - control: BranchResolution (mispredicted, non-faulting) XOR CheckpointRelease
  *    (correctly predicted or faulting), and
  *  - result: BranchResult (link value, cfiOutcome, misaligned-target exception) in a
  *    one-entry holder toward PublishMux.
  * Neither waits for the other. BranchResolutionOut.valid is registered state only: it never
  * depends on BranchResultOut.ready, the PublishMux grant, or the RecoveryEvent it causes, which
  * removes the ADR-019C C-2 loop.
  *
  * Recovery stance (rawSpeculativeHolder): funcRecoveryKills drops the unfired release and the
  * unpublished result of a killed branch in the event cycle; a resolution presented in the cycle
  * of an older ArchRedirect is discarded by the RecoveryController (ArchRedirect wins). The
  * branch survives its own recovery.
  */
@LocalSpec(contBranchUnit)
class BranchUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBranchUnitReqIn)
    val branchUnitReqIn = Flipped(Decoupled(new IssuedUop(params)))

    @LocalSpec(intfBranchResultOut)
    val branchResultOut = Decoupled(new FuResult(params))

    @LocalSpec(intfBranchResolutionOut)
    val branchResolutionOut = Decoupled(new BranchResolution(params))

    @LocalSpec(intfCheckpointReleaseOut)
    val checkpointReleaseOut = Decoupled(new CheckpointRelease(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))
  })

  private val req = io.branchUnitReqIn
  private val ev  = io.recoveryEventIn
  private val holder = new ResultHolder(new FuResult(params), (r: FuResult) => r.robTag, ev, io.branchResultOut)
  private def bc(e: BranchControl.Type): UInt = e.litValue.U(4.W)

  // ---- funcBranchResolve --------------------------------------------------------------------

  private val op    = req.bits.op
  private val ctrl  = op(3, 0)
  private val a     = req.bits.src1
  private val b     = req.bits.src2
  private val pc    = req.bits.pc
  private val isJal  = ctrl === bc(BranchControl.JAL)
  private val isJalr = ctrl === bc(BranchControl.JALR)

  @LocalSpec(funcBranchResolve)
  val branchResolve: (Bool, UInt) = {
    val cond = MuxLookup(ctrl, false.B)(Seq(
      bc(BranchControl.BEQ)  -> (a === b),
      bc(BranchControl.BNE)  -> (a =/= b),
      bc(BranchControl.BLT)  -> (a.asSInt < b.asSInt),
      bc(BranchControl.BGE)  -> (a.asSInt >= b.asSInt),
      bc(BranchControl.BLTU) -> (a < b),
      bc(BranchControl.BGEU) -> (a >= b)))
    val taken  = isJal || isJalr || cond
    val target = Mux(isJalr, (a + req.bits.imm) & ~1.U(xLen.W), pc + req.bits.imm)(vAddrWidth - 1, 0)
    (taken, target)
  }
  private val (taken, target) = branchResolve
  private val faulting = taken && target(1, 0) =/= 0.U

  // ---- funcMispredictDetect -------------------------------------------------------------------

  private val pred = req.bits.prediction

  @LocalSpec(funcMispredictDetect)
  val mispredictDetect: (Bool, UInt) = {
    val mis = Mux(pred.predictedTaken, !taken || target =/= pred.predictedTarget, taken)
    val cause = Mux(pred.predictedTaken,
      Mux(!taken, RecoveryCause.DirectionMispredict, RecoveryCause.TargetMispredict),
      Mux(isJal || isJalr, RecoveryCause.UnpredictedCfi, RecoveryCause.DirectionMispredict))
    (mis, cause)
  }
  private val (mis, cause) = mispredictDetect

  // ---- funcBranchRecoveryRequest (ADR-019C E-4/E-5) ----------------------------------------------

  val ctlValid  = RegInit(false.B)
  val ctlIsRes  = Reg(Bool())
  val ctlRes    = Reg(new BranchResolution(params))
  val ctlRel    = Reg(new CheckpointRelease(params))
  val ctlPredT  = Reg(Bool())
  val ctlPredTg = Reg(UInt(vAddrWidth.W))

  private val rsv = io.branchResolutionOut
  private val rel = io.checkpointReleaseOut
  private val ctlTag       = Mux(ctlIsRes, ctlRes.robTag, ctlRel.robTag)
  private val ctlKilledNow = ctlValid && RobOrder.recoveryKills(ev, ctlTag)
  private val ctlCanAccept = !ctlValid || Mux(ctlIsRes, rsv.ready, rel.ready)

  @LocalSpec(funcBranchRecoveryRequest)
  val branchRecoveryRequest: Unit = {
    rsv.valid := ctlValid && ctlIsRes // registered state only (no RecoveryEvent, no result ready)
    rsv.bits  := ctlRes
    rel.valid := ctlValid && !ctlIsRes && !ctlKilledNow
    rel.bits  := ctlRel
    when(rsv.fire || rel.fire || ctlKilledNow) { ctlValid := false.B }
    req.ready := holder.canAccept && ctlCanAccept

    val outcome = Wire(new CfiOutcome(params))
    outcome.cfiType := op(6, 4)
    outcome.slot    := pred.slot
    outcome.taken   := taken
    outcome.target  := target
    val r = Wire(new FuResult(params))
    r.robTag          := req.bits.robTag
    r.prd             := req.bits.prd
    r.wen             := req.bits.hasDest
    r.data            := pc + 4.U
    r.exception.valid := faulting
    r.exception.cause := 0.U // instruction address misaligned
    r.exception.tval  := target
    r.cfiOutcome      := outcome
    val load = req.fire && !RobOrder.recoveryKills(ev, req.bits.robTag)
    holder.load(load, r)
    when(load) {
      ctlValid              := true.B
      ctlIsRes              := mis && !faulting
      ctlRes.robTag         := req.bits.robTag
      ctlRes.checkpointId   := req.bits.checkpointId
      ctlRes.ftqIdx         := pred.ftqIdx
      ctlRes.pc             := pc
      ctlRes.outcome        := outcome
      ctlRes.redirectTarget := Mux(taken, target, pc + 4.U)
      ctlRes.cause          := cause
      ctlRel.checkpointId   := req.bits.checkpointId
      ctlRel.robTag         := req.bits.robTag
      ctlPredT              := pred.predictedTaken
      ctlPredTg             := pred.predictedTarget
    }
  }

  // ---- Properties (simulation assertions) ---------------------------------------------------------

  @LocalSpec(propRecoveryOnlyOnMispredict)
  val recoveryOnlyOnMispredict: Unit =
    when(rsv.fire) {
      val o = ctlRes.outcome
      assert(o.taken =/= ctlPredT || (o.taken && o.target =/= ctlPredTg),
        "RecoveryOnlyOnMispredict: resolution for a correctly predicted branch")
      assert(!(o.taken && o.target(1, 0) =/= 0.U), "RecoveryOnlyOnMispredict: resolution for a faulting branch")
    }

  @LocalSpec(propBranchCompletesOnce)
  val branchCompletesOnce: Unit =
    when(req.fire) {
      assert(!holder.valid || io.branchResultOut.fire || RobOrder.recoveryKills(ev, holder.bits.robTag),
        "BranchCompletesOnce: a new branch overwrote an unpublished result")
    }

  @LocalSpec(propBranchControlOnce)
  val branchControlOnce: Unit = {
    assert(!(rsv.fire && rel.fire), "BranchControlOnce: resolution and release in one cycle")
    when(req.fire) {
      assert(!ctlValid || rsv.fire || rel.fire || ctlKilledNow, "BranchControlOnce: a control token was lost")
    }
  }
}
