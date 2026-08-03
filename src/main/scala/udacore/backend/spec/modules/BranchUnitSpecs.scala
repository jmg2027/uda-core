package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object BranchUnitSpecs {
  val contBranchUnit = spec {
    CONTRACT("BranchUnit")
      .desc(
        "Branch unit evaluates branch conditions and issues redirect commands."
      )
      .has(
        intfBranchUnitReqIn,
        intfBranchUnitResultOut,
        intfMispredictOut
      )
      .build()
  }

  val intfBranchUnitReqIn = spec {
    INTERFACE("BranchUnitReqIn")
      .desc("Branch unit request stream entering the execution unit.")
      .uses(bndBranchUnitReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBranchUnitResultOut = spec {
    INTERFACE("BranchUnitResultOut")
      .desc(
        "Branch resolution result stream emitted toward the publish multiplexer."
      )
      .uses(bndBranchUnitResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMispredictOut = spec {
    INTERFACE("MispredictOut")
      .desc("Mispredict feedback emitted toward the redirect unit.")
      .uses(bndMispredict)
      .is(rawReadyValidIntf)
      .build()
  }
}
