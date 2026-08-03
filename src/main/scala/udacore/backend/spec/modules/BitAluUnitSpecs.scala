package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object BitAluUnitSpecs {
  val contBitAluUnit = spec {
    CONTRACT("BitAluUnit")
      .desc("Bit-manipulation vertex integrating the external bit-ALU core.")
      .has(
        intfBitAluReqIn,
        intfBitAluResultOut,
        funcIntegrateExternalBitAlu
      )
      .build()
  }

  val intfBitAluReqIn = spec {
    INTERFACE("BitAluReqIn")
      .desc(
        "BitALU request input interface receives bit manipulation operation requests."
      )
      .uses(bndBitAluReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBitAluResultOut = spec {
    INTERFACE("BitAluResultOut")
      .desc("Bit ALU result stream leaving the execution unit.")
      .uses(bndBitAluResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcIntegrateExternalBitAlu = spec {
    FUNCTION("integrateExternalBitAlu")
      .desc("Bridge udacore.external.bitalu.design.BitAlu into the backend ready/valid graph.")
      .uses(intfBitAluReqIn, intfBitAluResultOut)
      .build()
  }
}
