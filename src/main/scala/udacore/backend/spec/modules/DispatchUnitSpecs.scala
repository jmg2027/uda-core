package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs.funcRecoveryKills

/** DispatchUnit: routes issued uops to their execution unit (ADR-019). */
object DispatchUnitSpecs {
  val contDispatchUnit = spec {
    CONTRACT("DispatchUnit")
      .desc("Routes each IssuedUop from the ReservationStation to exactly one execution unit selected by fuType.")
      .has(
        intfIssuedUopIn,
        intfAluReqOut,
        intfBitAluReqOut,
        intfMultiplierReqOut,
        intfDividerReqOut,
        intfBranchUnitReqOut,
        intfAddressGenerationReqOut,
        intfCsrReqOut,
        intfRecoveryEventIn,
        funcFuRoute
      )
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: holds at most one routed token under backpressure; it is " +
        "dropped in the event cycle if funcRecoveryKills selects it. Serialization is no longer " +
        "a dispatch gate: it is enforced at rename (funcSerializeGate)."
      )
      .build()
  }

  val intfIssuedUopIn = spec {
    INTERFACE("IssuedUopIn")
      .desc("Issued uops from the ReservationStation.")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfAluReqOut = spec {
    INTERFACE("AluReqOut")
      .desc("Integer ALU operations (including LUI/AUIPC).")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBitAluReqOut = spec {
    INTERFACE("BitAluReqOut")
      .desc("Bit-manipulation operations; elaborated only when the optional BitAluUnit extension is enabled (absent in v0).")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMultiplierReqOut = spec {
    INTERFACE("MultiplierReqOut")
      .desc("MUL/MULH/MULHSU/MULHU.")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDividerReqOut = spec {
    INTERFACE("DividerReqOut")
      .desc("DIV/DIVU/REM/REMU.")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBranchUnitReqOut = spec {
    INTERFACE("BranchUnitReqOut")
      .desc("Conditional branches, JAL, JALR.")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfAddressGenerationReqOut = spec {
    INTERFACE("AddressGenerationReqOut")
      .desc("Loads and stores (address computation).")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrReqOut = spec {
    INTERFACE("CsrReqOut")
      .desc("CSR instructions (always the only live uop, by the rename serialization gate).")
      .uses(bndCsrReq)
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

  val funcFuRoute = spec {
    FUNCTION("FuRoute")
      .desc(
        "Offer the IssuedUop on the one request edge selected by fuType; IssuedUopIn is ready " +
        "exactly when that edge is ready. The RS only selects uops whose unit can accept, so " +
        "routing never reorders or duplicates uops."
      )
      .uses(intfIssuedUopIn, funcRecoveryKills)
      .build()
  }
}
