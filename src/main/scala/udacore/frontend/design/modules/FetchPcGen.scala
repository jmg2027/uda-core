package udacore.frontend.design.modules

import chisel3._
import chisel3.util._
import udacore.backend.design.shared.RecoveryEvent
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.FetchPcGenSpecs._

/** FetchPcGen (spec: FetchPcGenSpecs; ADR-019 WP-3, ADR-019G E-1).
  *
  * The speculative fetch-PC register. It offers the exact requested fetch PC (4-byte aligned, any
  * slot of its block; never rounded to the block base) as one PredictReq and advances to the
  * NextPc of that block. Before the boot token it offers nothing; the boot address seeds the PC
  * once. A RecoveryEvent overrides everything in its cycle: the PC becomes e.target, any pending
  * NextPc or unaccepted PredictReq is dropped, and nothing transfers on PredictReq/NextPc/Boot in
  * the event cycle.
  *
  * NextPcIn.ready depends only on local state (a block awaiting its NextPc, or a request being
  * offered now), never on PredictReqOut.ready, so the BranchPredictor's atomic fork (PredictReq,
  * Prediction and NextPc in one cycle, its request ready waiting on NextPc ready) closes without a
  * combinational cycle and the very first request is accepted. A NextPc token is applied only if it
  * belongs to the block in prediction (awaited, or transferring its request in the same cycle); any
  * other token (a pre-recovery answer) is consumed and discarded.
  *
  * Speculative-holder stance: one PC and at most one outstanding prediction; any RecoveryEvent
  * replaces both.
  */
@LocalSpec(contFetchPcGen)
class FetchPcGen(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBootAddrIn)
    val bootAddrIn = Flipped(Decoupled(UInt(params.vAddrWidth.W)))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params.backend))

    @LocalSpec(intfNextPcIn)
    val nextPcIn = Flipped(Decoupled(new PredictReq(params.vAddrWidth)))

    @LocalSpec(intfPredictReqOut)
    val predictReqOut = Decoupled(new PredictReq(params.vAddrWidth))
  })

  /** The fetch PC to offer next (valid when pcValid). */
  val pc      = RegInit(0.U(vAddrWidth.W))
  val pcValid = RegInit(false.B)
  /** The PredictReq for the last PC transferred and its NextPc has not arrived yet. */
  val waiting = RegInit(false.B)
  /** The boot address has been applied (or a RecoveryEvent superseded it). */
  val booted  = RegInit(false.B)

  private val ev = io.recoveryEventIn

  io.bootAddrIn.ready           := !booted && !ev.valid
  io.predictReqOut.valid        := pcValid && !ev.valid
  io.predictReqOut.bits.fetchPc := pc
  io.nextPcIn.ready             := !ev.valid && (waiting || io.predictReqOut.valid)

  private val ownNext = io.nextPcIn.fire && (waiting || io.predictReqOut.fire)

  @LocalSpec(funcFetchPcSelect)
  val fetchPcSelect: Unit = {
    when(ownNext) {
      pc := io.nextPcIn.bits.fetchPc; pcValid := true.B; waiting := false.B
    }.elsewhen(io.predictReqOut.fire) {
      pcValid := false.B; waiting := true.B // one block in prediction: wait for its NextPc
    }.elsewhen(io.bootAddrIn.fire) {
      pc := io.bootAddrIn.bits; pcValid := true.B; booted := true.B
    }
  }

  @LocalSpec(funcFetchPcRecovery)
  val fetchPcRecovery: Unit = when(ev.valid) {
    pc := ev.target; pcValid := true.B; waiting := false.B; booted := true.B
  }

  @LocalSpec(propFetchPcAligned)
  val fetchPcAligned: Unit =
    assert(!io.predictReqOut.valid || io.predictReqOut.bits.fetchPc(1, 0) === 0.U,
      "FetchPcAligned: a PredictReq carries a fetch PC that is not 4-byte aligned")
}
