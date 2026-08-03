package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object AluUnitSpecs {
  val contAluUnit = spec {
    CONTRACT("AluUnit")
      .desc("Integer ALU vertex that wraps the external ALU compute engine.")
      .has(
        intfAluReqIn,
        intfAluResultOut,
        funcIntegrateExternalAlu
      )
      .build()
  }

  val intfAluReqIn = spec {
    INTERFACE("AluReqIn")
      .desc("Integer ALU request stream entering the execution unit.")
      .uses(bndAluReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfAluResultOut = spec {
    INTERFACE("AluResultOut")
      .desc("Integer ALU result stream leaving the execution unit.")
      .uses(bndAluResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcIntegrateExternalAlu = spec {
    FUNCTION("integrateExternalAlu")
      .desc(
        "Connect udacore.external.alu.design.Alu to ready/valid request and response channels."
      )
      .uses(intfAluReqIn, intfAluResultOut)
      .build()
  }
}
