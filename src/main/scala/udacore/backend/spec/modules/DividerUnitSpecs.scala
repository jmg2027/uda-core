package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object DividerUnitSpecs {
  val contDividerUnit = spec {
    CONTRACT("DividerUnit")
      .desc("Divider unit processes integer divide and remainder operations.")
      .has(
        intfDividerReqIn,
        intfDividerResultOut,
        intfGlobalEpochIn,
        funcIntegrateExternalDivider
      )
      .build()
  }

  val intfDividerReqIn = spec {
    INTERFACE("DividerReqIn")
      .desc("Divider request input.")
      .uses(bndDividerReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDividerResultOut = spec {
    INTERFACE("DividerResultOut")
      .desc("Divider result output.")
      .uses(bndDividerResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfGlobalEpochIn = spec {
    INTERFACE("GlobalEpochIn")
      .desc("Global epoch broadcast input.")
      .is(rawNoDecoupled)
      .build()
  }

  val funcIntegrateExternalDivider = spec {
    FUNCTION("integrateExternalDivider")
      .desc("Translate backend requests to external divider IP and track uop context.")
      .uses(intfDividerReqIn, intfDividerResultOut, intfGlobalEpochIn)
      .note("Instantiate udacore.external.divider.design.Divider as compute engine")
      .note("Gate response valid instead of buffering; drop response when epoch mismatch")
      .build()
  }
}
