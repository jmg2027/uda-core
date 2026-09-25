package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._

/** RecoveryController: the single producer of the RecoveryEvent (ADR-019
  * D-19.9; supersedes the RedirectUnit/GlobalEpochUnit pair and ADR-013's
  * ladder as amended).
  */
object RecoveryControllerSpecs {
  val contRecoveryController = spec {
    CONTRACT("RecoveryController")
      .desc(
        "Merges the two recovery producers - execute-time branch mispredictions from the " +
        "BranchUnit and commit-head architectural redirects from the TrapController - into at " +
        "most one RecoveryEvent per cycle and broadcasts it to every speculative holder of the " +
        "frontend and backend. It is the only vertex that drives a RecoveryEvent."
      )
      .has(
        intfBranchResolutionIn,
        intfArchRedirectIn,
        intfRecoveryEventOut,
        funcRecoverySelect,
        funcRecoveryPublish,
        propSingleRecoveryPerCycle,
        propArchRedirectWins
      )
      .uses(funcRobOlder, funcRecoveryKills)
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: it holds no entries; a pending BranchResolution token on " +
        "its input is held by the BranchUnit, which applies funcRecoveryKills to it."
      )
      .build()
  }

  val intfBranchResolutionIn = spec {
    INTERFACE("BranchResolutionIn")
      .desc("Mispredicted branch resolutions from the BranchUnit. Ready is always high: a request is either published or discarded in the cycle it is offered.")
      .uses(bndBranchResolution)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfArchRedirectIn = spec {
    INTERFACE("ArchRedirectIn")
      .desc("Commit-head architectural redirects from the TrapController. Ready is always high.")
      .uses(bndArchRedirect)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRecoveryEventOut = spec {
    INTERFACE("RecoveryEventOut")
      .desc("The RecoveryEvent broadcast.")
      .uses(bndRecoveryEvent)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 6: a published fact; there is no ready and no queue.")
      .build()
  }

  val funcRecoverySelect = spec {
    FUNCTION("RecoverySelect")
      .desc(
        "In a cycle with an ArchRedirect, publish it and discard any same-cycle " +
        "BranchResolution (the branch is younger than the commit head and dies with the " +
        "window). Otherwise publish the BranchResolution, if any. v0 has one BranchUnit, so at " +
        "most one branch request exists per cycle; a request whose robTag was killed by an " +
        "earlier event never reaches this vertex (the BranchUnit drops it)."
      )
      .uses(intfBranchResolutionIn, intfArchRedirectIn)
      .note("ADR-019 D-19.9 and the ADR-013 amendment: the older commit-head architectural redirect wins.")
      .build()
  }

  val funcRecoveryPublish = spec {
    FUNCTION("RecoveryPublish")
      .desc(
        "Form the event from the selected request - BranchMispredict: {robTag, checkpointId, " +
        "target = redirectTarget, ftqIdx, cfiOutcome = outcome with its slot, cause}; " +
        "ArchRedirect: {robTag, target, ftqIdx, cause} - and drive it to every consumer in the " +
        "same cycle with one identity."
      )
      .uses(intfRecoveryEventOut)
      .build()
  }

  val propSingleRecoveryPerCycle = spec {
    PROPERTY("SingleRecoveryPerCycle")
      .desc("At most one RecoveryEvent is valid per cycle, and every consumer observes the same field values.")
      .uses(intfRecoveryEventOut)
      .note("Structural (single producer, broadcast wiring) plus a simulation assert.")
      .build()
  }

  val propArchRedirectWins = spec {
    PROPERTY("ArchRedirectWins")
      .desc("Whenever ArchRedirectIn and BranchResolutionIn are both valid in a cycle, the published event has kind ArchRedirect.")
      .uses(funcRecoverySelect)
      .build()
  }
}
