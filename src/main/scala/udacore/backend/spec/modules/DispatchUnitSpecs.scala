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
        intfFuAvailabilityOut,
        intfRecoveryEventIn,
        funcFuRoute,
        funcFuAvailability
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

  val intfFuAvailabilityOut = spec {
    INTERFACE("FuAvailabilityOut")
      .desc("Per-class FU availability to the ReservationStation (ADR-019C E-1).")
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

  val funcFuRoute = spec {
    FUNCTION("FuRoute")
      .desc(
        "Offer the IssuedUop on the one request edge selected by fuType; IssuedUopIn.ready " +
        "equals the FuAvailability bit of the presented uop's class. The RS only selects uops " +
        "whose class is available, so routing never reorders, duplicates, or loses uops."
      )
      .note("ADR-019C E-1/E-2.")
      .uses(intfIssuedUopIn, funcRecoveryKills)
      .build()
  }

  val funcFuAvailability = spec {
    FUNCTION("FuAvailability")
      .desc(
        "Drive one availability bit per FU class: set when an IssuedUop of that class " +
        "presented this cycle would be accepted by DispatchUnit and its execution wrapper. " +
        "Each bit derives only from the wrapper's request readiness, which depends on its " +
        "registered state and the drain of its already-held output token and never on the " +
        "current request's valid or payload; a DispatchUnit holding a routed token drives " +
        "that class unavailable and never accepts a hidden second token."
      )
      .uses(intfFuAvailabilityOut)
      .note("ADR-019C E-2: the loop RS selection -> IssuedUop -> FU ready -> FuAvailability -> RS selection is forbidden.")
      .build()
  }
}
