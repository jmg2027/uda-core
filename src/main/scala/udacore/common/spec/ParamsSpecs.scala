package udacore.common.spec

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

/** Parameter specification framework for UDACore.
  *
  * Defines the three-tier parameter architecture: Contract, Tuning, Private.
  * Each domain implements this pattern in their respective spec/shared/
  * directories.
  */
object ParamsSpecs {

  val rawParameterTiering = spec {
    RAW("ParameterTiering", prefix = "ParameterArch")
      .desc("""
        |Parameters are organized into three tiers:
        |- Contract: Interface contracts that parent integrators must set (bus widths, cache block sizes, etc.)
        |- Tuning: Performance optimization settings used by parents and subsystem leaders (queue depths, predictor sizes, etc.)  
        |- Private: Internal settings accessible only to subsystem owners (experimental toggles, internal guardbands, etc.)
        """.stripMargin)
      .note("Three-tier parameter architecture: Contract -> Tuning -> Private")
      .code("""
        |// Example domain parameter organization:
        |object CoreParamsSpecs {
        |  // Contract Tier - Must be specified by integrators
        |  val paramDataWidth = spec {
        |    PARAMETER("DataWidth")
        |      .desc("Core data width in bits")
        |      .note("Contract tier - affects all interfaces")
        |      .build()
        |  }
        |  
        |  // Tuning Tier - Performance optimization
        |  val paramRobDepth = spec {
        |    PARAMETER("RobDepth")
        |      .desc("Reorder buffer entries")
        |      .note("Tuning tier - affects performance")
        |      .build()
        |  }
        |  
        |  // Private Tier - Internal implementation
        |  val paramBootCycles = spec {
        |    PARAMETER("BootCycles")
        |      .desc("Boot sequence cycles")
        |      .note("Private tier - implementation detail")
        |      .build()
        |  }
        |}
        """.stripMargin)
      .build()
  }

  val rawDomainBoundaries = spec {
    RAW("DomainBoundaries", prefix = "DomainArch")
      .desc("""
        |Each domain (core, frontend, backend) maintains an independent parameter namespace.
        |Local parameter bundles are defined in design/shared/, and only necessary contract fields are
        |exposed to parent domains through design/api/. Cross-domain access is permitted only through api/ packages.
        """.stripMargin)
      .note("Domain isolation with controlled API exposure")
      .code("""
        |// Domain parameter isolation example:
        |// frontend/spec/shared/FrontendParamsSpecs.scala
        |// backend/spec/shared/BackendParamsSpecs.scala
        |// core/spec/shared/CoreParamsSpecs.scala
        |
        |// Cross-domain access only through api/:
        |// frontend/design/api/FrontendApi.scala exports needed contract params
        |// backend uses: import udacore.frontend.design.api.FrontendApi
        """.stripMargin)
      .build()
  }

  val rawContractParams = spec {
    RAW("ContractParams", prefix = "Contract")
      .desc("Parameters that must be specified by integrators")
      .build()
  }

  val rawTuningParams = spec {
    RAW("TuningParams", prefix = "Tuning")
      .desc("Parameters that can be tuned for performance optimization")
      .build()
  }

  val rawPrivateParams = spec {
    RAW("PrivateParams", prefix = "Private")
      .desc("Internal parameters not exposed to integrators")
      .build()
  }
}
