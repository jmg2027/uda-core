package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.RecoveryControllerSpecs._

/** RecoveryController (spec: RecoveryControllerSpecs, ADR-019 D-19.9, ADR-019A E-5).
  *
  * The single RecoveryEvent producer. Both request inputs are always ready and the event
  * is formed combinationally in the cycle a request is offered: a request is published or
  * discarded in that cycle, never queued. RecoveryEvent is a rawNoDecoupled class 6 fact,
  * so the one output wire is the broadcast every consumer observes (one identity).
  *
  * Recovery stance (rawSpeculativeHolder): it holds no entries and no state, so it has
  * nothing to recover; a pending BranchResolution on its input is held by the BranchUnit,
  * which applies funcRecoveryKills to it.
  */
@LocalSpec(contRecoveryController)
class RecoveryController(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBranchResolutionIn)
    val branchResolutionIn = Flipped(Decoupled(new BranchResolution(params)))

    @LocalSpec(intfArchRedirectIn)
    val archRedirectIn = Flipped(Decoupled(new ArchRedirect(params)))

    @LocalSpec(intfRecoveryEventOut)
    val recoveryEventOut = Output(new RecoveryEvent(params))
  })

  private val br = io.branchResolutionIn
  private val ar = io.archRedirectIn

  // ---- funcRecoverySelect ------------------------------------------------------------

  /** The ArchRedirect is selected whenever present; a same-cycle BranchResolution is
    * accepted (ready is always high) and discarded - that branch is younger than the
    * commit head and dies with the window. */
  @LocalSpec(funcRecoverySelect)
  val recoverySelect: Bool = {
    br.ready := true.B
    ar.ready := true.B
    ar.valid
  }
  private val takeArch   = recoverySelect
  private val takeBranch = br.valid && !ar.valid

  // ---- funcRecoveryPublish -------------------------------------------------------------

  @LocalSpec(funcRecoveryPublish)
  val recoveryPublish: RecoveryEvent = {
    val e = Wire(new RecoveryEvent(params))
    e.valid := takeArch || takeBranch
    e.kind  := Mux(takeArch, RecoveryKind.ArchRedirect, RecoveryKind.BranchMispredict)
    when(takeArch) {
      e.robTag       := ar.bits.robTag
      e.checkpointId := 0.U.asTypeOf(e.checkpointId) // BranchMispredict only
      e.target       := ar.bits.target
      e.ftqIdx       := ar.bits.ftqIdx
      e.cfiOutcome   := 0.U.asTypeOf(e.cfiOutcome)   // BranchMispredict only
      e.cause        := ar.bits.cause
    }.otherwise {
      e.robTag       := br.bits.robTag
      e.checkpointId := br.bits.checkpointId
      e.target       := br.bits.redirectTarget
      e.ftqIdx       := br.bits.ftqIdx
      e.cfiOutcome   := br.bits.outcome
      e.cause        := br.bits.cause
    }
    io.recoveryEventOut := e
    e
  }

  // Request legality: each producer names only its own causes.
  private val brCause = VecInit(RecoveryCause.DirectionMispredict, RecoveryCause.TargetMispredict,
    RecoveryCause.UnpredictedCfi).contains(br.bits.cause)
  // ADR-019B E-1: Debug is its own ArchRedirect cause (never aliased to Trap).
  private val arCause = VecInit(RecoveryCause.Trap, RecoveryCause.Interrupt, RecoveryCause.Debug,
    RecoveryCause.XRet, RecoveryCause.Refetch).contains(ar.bits.cause)
  when(br.valid) { assert(brCause, "RecoveryPublish: request cause does not match its kind") }
  when(ar.valid) { assert(arCause, "RecoveryPublish: request cause does not match its kind") }

  // ---- Properties (simulation assertions) -------------------------------------------------

  @LocalSpec(propSingleRecoveryPerCycle)
  val singleRecoveryPerCycle: Unit = {
    val ev = io.recoveryEventOut // what every consumer observes
    // One producer and one broadcast wire: the event is valid exactly when a request is
    // offered, and it is exactly the selected request (so every consumer sees one identity).
    assert(ev.valid === (ar.valid || br.valid), "SingleRecoveryPerCycle: event valid differs from the request set")
    when(ev.valid && ev.kind === RecoveryKind.ArchRedirect) {
      assert(ev.robTag.asUInt === ar.bits.robTag.asUInt && ev.target === ar.bits.target &&
        ev.ftqIdx === ar.bits.ftqIdx && ev.cause === ar.bits.cause,
        "SingleRecoveryPerCycle: ArchRedirect event is not the selected request")
    }
    when(ev.valid && ev.kind === RecoveryKind.BranchMispredict) {
      assert(ev.robTag.asUInt === br.bits.robTag.asUInt && ev.target === br.bits.redirectTarget &&
        ev.checkpointId.asUInt === br.bits.checkpointId.asUInt && ev.ftqIdx === br.bits.ftqIdx &&
        ev.cfiOutcome.asUInt === br.bits.outcome.asUInt && ev.cause === br.bits.cause,
        "SingleRecoveryPerCycle: BranchMispredict event is not the selected request")
    }
  }

  @LocalSpec(propArchRedirectWins)
  val archRedirectWins: Unit =
    when(ar.valid && br.valid) {
      assert(io.recoveryEventOut.valid && io.recoveryEventOut.kind === RecoveryKind.ArchRedirect,
        "ArchRedirectWins: a same-cycle BranchResolution was published over an ArchRedirect")
    }
}
