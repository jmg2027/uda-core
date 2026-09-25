package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._

/** ReservationStation: unified integer RS with out-of-order wakeup/select (ADR-019). */
object ReservationStationSpecs {
  val contReservationStation = spec {
    CONTRACT("ReservationStation")
      .desc(
        "Holds renamed uops until their source operands are ready, then selects IssueWidth " +
        "ready uops per cycle independent of program order, reads their operands from the " +
        "PRF, and sends them to DispatchUnit."
      )
      .has(
        intfRsAllocIn,
        intfWakeupBroadcastIn,
        intfRegisterFileReadReqOut,
        intfRegisterFileReadRespIn,
        intfIssuedUopOut,
        intfFuAvailabilityIn,
        intfRecoveryEventIn,
        funcRsWakeup,
        funcSelectOldestReady,
        funcRsRecovery,
        propRsIssueOnlyReady,
        propRsRecoveryKeepsOlder,
        propRsIssueStable
      )
      .uses(paramIntegerRsEntries, paramIssueWidth, funcRobOlder)
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance. Ordering: entries are unordered storage, each holding its " +
        "robTag; age for arbitration comes from funcRobOlder. Live: from allocation until " +
        "issue. Recovery: funcRecoveryKills per entry and per held output token. Survives: " +
        "older entries and their ready bits. Reclaim: killed entries become free in the event cycle."
      )
      .build()
  }

  val intfRsAllocIn = spec {
    INTERFACE("RsAllocIn")
      .desc("Allocation tokens from RenameUnit; ready is low when no entry is free.")
      .uses(bndRenameAllocation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfWakeupBroadcastIn = spec {
    INTERFACE("WakeupBroadcastIn")
      .desc("Result-publication fact from PublishMux.")
      .uses(bndWakeupBroadcast)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 5: consumers match, never backpressure.")
      .build()
  }

  val intfRegisterFileReadReqOut = spec {
    INTERFACE("RegisterFileReadReqOut")
      .desc("Operand read for the selected uop.")
      .uses(bndRegisterFileReadReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRegisterFileReadRespIn = spec {
    INTERFACE("RegisterFileReadRespIn")
      .desc("Operand values for the selected uop.")
      .uses(bndRegisterFileReadResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfIssuedUopOut = spec {
    INTERFACE("IssuedUopOut")
      .desc("Selected uop with operands to DispatchUnit.")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfFuAvailabilityIn = spec {
    INTERFACE("FuAvailabilityIn")
      .desc("Per-class FU availability from DispatchUnit (ADR-019C E-1).")
      .uses(bndFuAvailability)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 7.")
      .build()
  }

  val intfRecoveryEventIn = spec {
    INTERFACE("RecoveryEventIn")
      .desc("The common RecoveryEvent broadcast.")
      .uses(bndRecoveryEvent)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 6.")
      .build()
  }

  val funcRsWakeup = spec {
    FUNCTION("RsWakeup")
      .desc("Each cycle, set the ready bit of every entry source whose prs equals WakeupBroadcast.prd; an allocation in the same cycle as the wakeup of its source is also woken.")
      .uses(intfWakeupBroadcastIn)
      .build()
  }

  val funcSelectOldestReady = spec {
    FUNCTION("SelectOldestReady")
      .desc(
        "Among entries with both sources ready whose FuAvailability bit is set, select the " +
        "oldest by funcRobOlder (the v0 arbitration policy), read its operands, and issue it. " +
        "A ready entry of an unavailable class never blocks a younger ready entry of an " +
        "available class; within a class the choice is oldest-first, so the ROB head is never " +
        "starved. The PRF read and the entry release coincide with the issue transfer; a " +
        "selected entry killed by a same-cycle RecoveryEvent is not issued and is freed."
      )
      .note("ADR-019C E-1/E-3.")
      .uses(intfIssuedUopOut, intfFuAvailabilityIn, funcRobOlder)
      .note("This is edgeWakeupSelect (EdgeBudgetSpecs), budget 0 stages.")
      .build()
  }

  val funcRsRecovery = spec {
    FUNCTION("RsRecovery")
      .desc("On a RecoveryEvent, free every entry and drop every unaccepted output token whose robTag funcRecoveryKills selects; all other entries are untouched.")
      .uses(intfRecoveryEventIn, funcRecoveryKills)
      .build()
  }

  val propRsIssueOnlyReady = spec {
    PROPERTY("RsIssueOnlyReady")
      .desc("A uop is issued only when both source prds hold their final values (ready bit set by a wakeup or at rename).")
      .uses(intfIssuedUopOut)
      .build()
  }

  val propRsIssueStable = spec {
    PROPERTY("RsIssueStable")
      .desc(
        "While IssuedUopOut is valid and not ready, its bits and selected entry stay unchanged " +
        "until the transfer or a RecoveryEvent kill of that entry; an entry is freed only by " +
        "its issue transfer or a kill, never for a uop that was not accepted (ADR-019C E-3)."
      )
      .uses(intfIssuedUopOut)
      .note("Simulation assert.")
      .build()
  }

  val propRsRecoveryKeepsOlder = spec {
    PROPERTY("RsRecoveryKeepsOlder")
      .desc("A BranchMispredict RecoveryEvent never frees or alters an entry at or older than the recovering branch.")
      .uses(funcRsRecovery)
      .build()
  }
}
