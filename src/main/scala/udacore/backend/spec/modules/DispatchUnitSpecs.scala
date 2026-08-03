package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object DispatchUnitSpecs {
  val contDispatchUnit = spec {
    CONTRACT("DispatchUnit")
      .desc(
        "Dispatch unit routes ready operations toward backend execution lanes."
      )
      .has(
        intfDispatchedUopIn,
        intfAluReqOut,
        intfBitAluReqOut,
        intfMultiplierReqOut,
        intfDividerReqOut,
        intfBranchUnitReqOut,
        intfCsrReqOut,
        intfAddressGenerationReqOut,
        funcSerializingDispatchGate
      )
      .build()
  }

  // ADR-004 D-4.2: single-in-flight serialize as ready backpressure.
  val funcSerializingDispatchGate = spec {
    FUNCTION("SerializingDispatchGate")
      .desc("Grant the CSR/system edge ready only when no serializing uop is in flight; a 1-bit scoreboard is set at dispatch of a serializing uop and cleared at its commitGrant.")
      .note("Ready backpressure (FCL edge property), never a node-internal stall counter; at most one un-committed CSR/system uop exists, so a CSR read never observes a not-yet-committed CSR write (ADR-004 D-4.2).")
      .uses(intfDispatchedUopIn, intfCsrReqOut)
      .build()
  }

  val intfDispatchedUopIn = spec {
    INTERFACE("DispatchedUopIn")
      .desc("Dispatch-ready uop input.")
      .uses(bndDispatchedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfAluReqOut = spec {
    INTERFACE("AluReqOut")
      .desc("Integer ALU request output.")
      .uses(bndAluReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBitAluReqOut = spec {
    INTERFACE("BitAluReqOut")
      .desc("Bit manipulation request output.")
      .uses(bndBitAluReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMultiplierReqOut = spec {
    INTERFACE("MultiplierReqOut")
      .desc("Multiplier request output.")
      .uses(bndMultiplierReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDividerReqOut = spec {
    INTERFACE("DividerReqOut")
      .desc("Divider request output.")
      .uses(bndDividerReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBranchUnitReqOut = spec {
    INTERFACE("BranchUnitReqOut")
      .desc("Branch unit request output.")
      .uses(bndBranchUnitReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrReqOut = spec {
    INTERFACE("CsrReqOut")
      .desc("CSR request output.")
      .uses(bndCsrReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfAddressGenerationReqOut = spec {
    INTERFACE("AddressGenerationReqOut")
      .desc("Address generation request output.")
      .uses(bndAddressGenerationReq)
      .is(rawReadyValidIntf)
      .build()
  }
}
