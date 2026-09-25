package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.PublishMuxSpecs._

/** PublishMux (spec: PublishMuxSpecs; ADR-014 single lane, ADR-019C E-7).
  *
  * Grants the oldest valid candidate (funcRobOlder) each cycle; the granted result writes the
  * PRF (when wen), broadcasts its wakeup (when wen), and completes its ROB entry in one
  * transfer, withheld while a needed output is not ready. Losers stay valid on their edges.
  *
  * Recovery stance: PublishMux holds no tokens and observes no RecoveryEvent; every producer
  * drops a result funcRecoveryKills selects in the event cycle, so only live candidates
  * reach this vertex. CSR results use the legacy bndCsrResult (no robTag) and cannot be
  * published until the CSR request/result shape is amended (HANDOFF C-3).
  */
@LocalSpec(contPublishMux)
class PublishMux(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfAluResultIn)
    val aluResultIn = Flipped(Decoupled(new FuResult(params)))

    @LocalSpec(intfBitAluResultIn)
    val bitAluResultIn = if (params.enableBitAlu) Some(Flipped(Decoupled(new FuResult(params)))) else None

    @LocalSpec(intfMultiplierResultIn)
    val multiplierResultIn = Flipped(Decoupled(new FuResult(params)))

    @LocalSpec(intfDividerResultIn)
    val dividerResultIn = Flipped(Decoupled(new FuResult(params)))

    @LocalSpec(intfBranchResultIn)
    val branchResultIn = Flipped(Decoupled(new FuResult(params)))

    @LocalSpec(intfCsrResultIn)
    val csrResultIn = Flipped(Decoupled(new CsrResult(params)))

    @LocalSpec(intfMemResultIn)
    val memResultIn = Flipped(Decoupled(new MemResult(params)))

    @LocalSpec(intfPhysicalRegWriteOut)
    val physicalRegWriteOut = Decoupled(new PhysicalRegWrite(params))

    @LocalSpec(intfWakeupBroadcastOut)
    val wakeupBroadcastOut = Output(new WakeupBroadcast(params))

    @LocalSpec(intfRobCompletionOut)
    val robCompletionOut = Decoupled(new RobCompletion(params))
  })

  private class Cand(val valid: Bool, val robTag: RobTag, val prd: UInt, val wen: Bool, val data: UInt,
      val exception: ExceptionInfo, val cfi: CfiOutcome, val headExecute: Bool, val ready: Bool)

  private def fu(d: DecoupledIO[FuResult]) = new Cand(d.valid, d.bits.robTag, d.bits.prd, d.bits.wen, d.bits.data,
    d.bits.exception, d.bits.cfiOutcome, false.B, d.ready)
  private val noCfi = 0.U.asTypeOf(new CfiOutcome(params))
  private val m = io.memResultIn
  private val cands: Seq[Cand] =
    Seq(fu(io.aluResultIn), fu(io.multiplierResultIn), fu(io.dividerResultIn), fu(io.branchResultIn)) ++
    io.bitAluResultIn.map(fu).toSeq :+
    new Cand(m.valid, m.bits.robTag, m.bits.prd, m.bits.wen, m.bits.data, m.bits.exception, noCfi, m.bits.headExecute, m.ready)

  // ---- funcPublishArbitrate ----------------------------------------------------------------------

  @LocalSpec(funcPublishArbitrate)
  val publishArbitrate: (Bool, UInt) = {
    val tags = VecInit(cands.map(_.robTag))
    val idx  = cands.indices.map(_.U(log2Ceil(cands.size).max(1).W))
    cands.zip(idx).map { case (c, i) => (c.valid, i) }.reduce { (x, y) =>
      val takeY = y._1 && (!x._1 || RobOrder.robOlder(tags(y._2), tags(x._2)))
      (x._1 || y._1, Mux(takeY, y._2, x._2))
    }
  }
  private val (any, win) = publishArbitrate
  private def sel[T <: Data](f: Cand => T): T = VecInit(cands.map(f))(win)

  // ---- funcPublishFanout --------------------------------------------------------------------------

  private val pw  = io.physicalRegWriteOut
  private val rc  = io.robCompletionOut
  private val wen = sel(_.wen)

  @LocalSpec(funcPublishFanout)
  val publishFanout: Bool = {
    val allOk = rc.ready && (!wen || pw.ready)
    pw.valid       := any && wen && rc.ready
    pw.bits.prd    := sel(_.prd)
    pw.bits.data   := sel(_.data)
    rc.valid       := any && (!wen || pw.ready)
    rc.bits.robTag      := sel(_.robTag)
    rc.bits.exception   := sel(_.exception)
    rc.bits.cfiOutcome  := sel(_.cfi)
    rc.bits.headExecute := sel(_.headExecute)
    val fire = any && allOk
    io.wakeupBroadcastOut.valid := fire && wen
    io.wakeupBroadcastOut.prd   := sel(_.prd)
    cands.zipWithIndex.foreach { case (c, i) => c.ready := fire && win === i.U }
    fire
  }

  // CSR results: legacy bndCsrResult carries no robTag (HANDOFF C-3); never accepted here.
  io.csrResultIn.ready := false.B
  assert(!io.csrResultIn.valid, "PublishArbitrate: a CSR result cannot be published before the C-3 amendment")

  // ---- propSingleDrain (simulation assertion) -------------------------------------------------------

  @LocalSpec(propSingleDrain)
  val singleDrain: Unit = {
    val fires = cands.map(c => c.valid && c.ready)
    assert(PopCount(fires) <= 1.U, "SingleDrain: more than one result drained in a cycle")
    assert(pw.fire === (publishFanout && wen) && rc.fire === publishFanout,
      "SingleDrain: PRF write, wakeup, and completion are not one transfer")
    when(io.wakeupBroadcastOut.valid) {
      assert(pw.fire && pw.bits.prd === io.wakeupBroadcastOut.prd, "SingleDrain: wakeup without its PRF write")
    }
  }
}
