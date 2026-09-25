package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs.funcRecoveryKills

/** DividerUnit: execution vertex wrapping the external Divider core (ADR-019). */
object DividerUnitSpecs {
  val contDividerUnit = spec {
    CONTRACT("DividerUnit")
      .desc(
        "DividerUnit is the M-extension divider (DIV/DIVU/REM/REMU). It accepts IssuedUop tokens from DispatchUnit and emits " +
        "FuResult tokens (value, destination, completion) to PublishMux."
      )
      .has(
        intfDividerReqIn,
        intfDividerResultOut,
        intfRecoveryEventIn,
        funcIntegrateExternalDivider
      )
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: The wrapper holds at most one operation. On a RecoveryEvent that kills the held robTag (funcRecoveryKills) it marks the operation killed, lets the external core finish, and discards the result instead of publishing it; the core's kill port stays tied inactive (ADR-017: external IP kill ports are not a UDACore recovery path). An operation older than the recovery point always completes and publishes."
      )
      .build()
  }

  val intfDividerReqIn = spec {
    INTERFACE("DividerReqIn")
      .desc("Issued uops routed to this unit by DispatchUnit.")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDividerResultOut = spec {
    INTERFACE("DividerResultOut")
      .desc("Results toward PublishMux; backpressured by the single publish lane.")
      .uses(bndFuResult)
      .is(rawReadyValidIntf)
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

  val funcIntegrateExternalDivider = spec {
    FUNCTION("IntegrateExternalDivider")
      .desc(
        "Drive udacore.external.divider.design.Divider from the IssuedUop operands and operation, and emit " +
        "one FuResult carrying the uop's robTag and prd when the core produces its result."
      )
      .uses(intfDividerReqIn, intfDividerResultOut, intfRecoveryEventIn, funcRecoveryKills)
      .build()
  }
}
