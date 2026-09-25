package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs.funcRecoveryKills

/** AluUnit: execution vertex wrapping the external Alu core (ADR-019). */
object AluUnitSpecs {
  val contAluUnit = spec {
    CONTRACT("AluUnit")
      .desc(
        "AluUnit is the single-cycle integer ALU (RV32I arithmetic, logic, shifts, compares, LUI/AUIPC). It accepts IssuedUop tokens from DispatchUnit and emits " +
        "FuResult tokens (value, destination, completion) to PublishMux."
      )
      .has(
        intfAluReqIn,
        intfAluResultOut,
        intfRecoveryEventIn,
        funcIntegrateExternalAlu
      )
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: Combinational compute; the only state is an output token held under backpressure, which is dropped in the event cycle when funcRecoveryKills selects it."
      )
      .build()
  }

  val intfAluReqIn = spec {
    INTERFACE("AluReqIn")
      .desc("Issued uops routed to this unit by DispatchUnit.")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .note("ADR-019C E-2: ready (canAccept) derives only from registered state and the drain of the already-held output token, never from this request's valid or payload.")
      .build()
  }

  val intfAluResultOut = spec {
    INTERFACE("AluResultOut")
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

  val funcIntegrateExternalAlu = spec {
    FUNCTION("IntegrateExternalAlu")
      .desc(
        "Drive udacore.external.alu.design.Alu from the IssuedUop operands and operation, and emit " +
        "one FuResult carrying the uop's robTag and prd when the core produces its result."
      )
      .uses(intfAluReqIn, intfAluResultOut, intfRecoveryEventIn, funcRecoveryKills)
      .build()
  }
}
