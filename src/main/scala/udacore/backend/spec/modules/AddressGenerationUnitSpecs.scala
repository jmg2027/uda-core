package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object AddressGenerationUnitSpecs {
  val contAddressGenerationUnit = spec {
    CONTRACT("AddressGenerationUnit")
      .desc(
        "Address generation unit translates dispatch requests into memory accesses."
      )
      .has(
        intfAddressGenerationReqIn,
        intfMemoryOpReqOut
      )
      .build()
  }

  val intfAddressGenerationReqIn = spec {
    INTERFACE("AddressGenerationReqIn")
      .desc("Address generation request stream entering the AGU.")
      .uses(bndAddressGenerationReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMemoryOpReqOut = spec {
    INTERFACE("MemoryOpReqOut")
      .desc("Memory command stream emitted by the AGU.")
      .uses(bndMemoryOpReq)
      .is(rawReadyValidIntf)
      .build()
  }
}
