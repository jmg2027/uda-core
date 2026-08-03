package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object MultiplierUnitSpecs {
  val contMultiplierUnit = spec {
    CONTRACT("MultiplierUnit")
      .desc(
        "Multiplier unit processes integer multiply operations for backend execution."
      )
      .has(
        intfMultiplierReqIn,
        intfMultiplierResultOut,
        intfGlobalEpochIn,
        funcIntegrateExternalMultiplier
      )
      .build()
  }

  val intfMultiplierReqIn = spec {
    INTERFACE("MultiplierReqIn")
      .desc("Multiplier request input.")
      .uses(bndMultiplierReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMultiplierResultOut = spec {
    INTERFACE("MultiplierResultOut")
      .desc("Multiplier result output.")
      .uses(bndMultiplierResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfGlobalEpochIn = spec {
    INTERFACE("GlobalEpochIn")
      .desc("Global epoch broadcast input.")
      .is(rawNoDecoupled)
      .build()
  }

  val funcIntegrateExternalMultiplier = spec {
    FUNCTION("integrateExternalMultiplier")
      .desc("Adapt backend requests to external multiplier IP and preserve uop metadata.")
      .uses(intfMultiplierReqIn, intfMultiplierResultOut, intfGlobalEpochIn)
      .note("Instantiate udacore.external.multiplier.design.Multiplier as compute engine")
      .note("Gate response valid instead of buffering; drop response when epoch mismatch")
      .build()
  }
}
