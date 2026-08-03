package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object ReservationStationSpecs {
  val contReservationStation = spec {
    CONTRACT("ReservationStation")
      .desc(
        "Reservation station buffers renamed uops, wakes up on broadcast, schedules dispatch."
      )
      .has(
        intfRenamedUopIn,
        intfWakeupBroadcastIn,
        intfRegisterFileReadReqOut,
        intfRegisterFileReadRespIn,
        intfDispatchedUopOut,
        funcSelectOldestReady,
        propWakeupEpochQualified,
        propRsEagerFilter
      )
      .note("Stores uop with physical register dependencies (prs1, prs2, prd)")
      .note("Wakeup: matches broadcasted prd with pending dependencies")
      .note("Dispatch: when all operands ready, reads PRF and dispatches to FU")
      .build()
  }

  // ADR-007: name the IPC-critical wakeup->select loop (edgeWakeupSelect, B=0).
  val funcSelectOldestReady = spec {
    FUNCTION("SelectOldestReady")
      .desc("Each cycle, wake entries whose prs match the wakeup broadcast, then select the oldest ready entry for dispatch.")
      .note("This is the edgeWakeupSelect IPC-critical loop (PublishMux wakeup(prd) -> RS match -> select/grant), budget 0 stages at every N (ADR-007 D-7.2).")
      .uses(intfWakeupBroadcastIn, intfDispatchedUopOut)
      .build()
  }

  // ADR-005 D-5.1: wakeup match must be qualified by epoch.
  val propWakeupEpochQualified = spec {
    PROPERTY("WakeupEpochQualified")
      .desc("A wakeup only matches an RS entry whose epoch equals globalEpoch; a stale broadcast never wakes a live entry.")
      .note("Pair with a design assert (ADR-005, ADR-015 D-15.3).")
      .uses(intfWakeupBroadcastIn)
      .build()
  }

  // ADR-005 D-5.2: RS entries are eager-filter epoch-holding vertices.
  val propRsEagerFilter = spec {
    PROPERTY("RsEagerFilter")
      .desc("Every held RS entry compares entry.epoch === globalEpoch combinationally EVERY cycle it holds a token and self-invalidates on mismatch.")
      .note("Enumerated eager-filter vertex, contributes 1 to maxSurvivableGenerations (ADR-005 D-5.2); it MUST NOT defer the compare to dispatch. Pair with a design assert.")
      .build()
  }

  val intfRenamedUopIn = spec {
    INTERFACE("RenamedUopIn")
      .desc("Renamed uop input.")
      .uses(bndRenamedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfWakeupBroadcastIn = spec {
    INTERFACE("WakeupBroadcastIn")
      .desc("Wakeup broadcast input for dependency resolution.")
      .uses(bndWakeupBroadcast)
      .note("Matches prd in broadcast with pending prs1/prs2 dependencies")
      .is(rawNoDecoupled)
      .note(
        "Sanctioned broadcast class (rawNoDecoupled note 5, ADR-014): a wakeup is a "+
        "non-negotiable published fact riding the result publish; consumers epoch-qualify, "+
        "never backpressure."
      )
      .build()
  }

  val intfRegisterFileReadReqOut = spec {
    INTERFACE("RegisterFileReadReqOut")
      .desc("Register read request output.")
      .uses(bndRegisterFileReadReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRegisterFileReadRespIn = spec {
    INTERFACE("RegisterFileReadRespIn")
      .desc("Register read response input.")
      .uses(bndRegisterFileReadResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDispatchedUopOut = spec {
    INTERFACE("DispatchedUopOut")
      .desc("Dispatch-ready uop output.")
      .uses(bndDispatchedUop)
      .is(rawReadyValidIntf)
      .build()
  }
}
