package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs.funcRecoveryKills

/** BitAluUnit: execution vertex wrapping the external BitAlu core (ADR-019). */
object BitAluUnitSpecs {
  val contBitAluUnit = spec {
    CONTRACT("BitAluUnit")
      .desc(
        "BitAluUnit is the optional bit-manipulation unit (Zba/Zbb/Zbs/Zbc contribution, ADR-017). It accepts IssuedUop tokens from DispatchUnit and emits " +
        "FuResult tokens (value, destination, completion) to PublishMux."
      )
      .has(
        intfBitAluReqIn,
        intfBitAluResultOut,
        intfRecoveryEventIn,
        funcIntegrateExternalBitAlu
      )
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: Absent in the v0 reference configuration (RV32IM): its decode rows are not contributed, so its instructions trap illegal (propDisabledExtensionTraps). When enabled it is a single-cycle unit with the AluUnit recovery stance."
      )
      .build()
  }

  val intfBitAluReqIn = spec {
    INTERFACE("BitAluReqIn")
      .desc("Issued uops routed to this unit by DispatchUnit.")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBitAluResultOut = spec {
    INTERFACE("BitAluResultOut")
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

  val funcIntegrateExternalBitAlu = spec {
    FUNCTION("IntegrateExternalBitAlu")
      .desc(
        "Drive udacore.external.bitalu.design.BitAlu from the IssuedUop operands and operation, and emit " +
        "one FuResult carrying the uop's robTag and prd when the core produces its result."
      )
      .uses(intfBitAluReqIn, intfBitAluResultOut, intfRecoveryEventIn, funcRecoveryKills)
      .build()
  }
}
