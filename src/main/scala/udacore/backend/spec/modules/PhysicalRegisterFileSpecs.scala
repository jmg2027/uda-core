package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._

/** Physical Register File specifications for Unified PRF architecture.
  *
  * The PhysicalRegisterFile serves as a unified storage for both architectural
  * and speculative register state. It eliminates the need for separate
  * architectural and virtual register files by using a map table approach
  * where commit updates mappings rather than copying data.
  */
object PhysicalRegisterFileSpecs {
  val contPhysicalRegisterFile = spec {
    CONTRACT("PhysicalRegisterFile")
      .desc(
        "Unified physical register file for speculative and committed register state."
      )
      .note(
        "Total entries: 33 (architectural baseline) + N (speculative), parametric via SpeculativeRegNum"
      )
      .note("Each entry: valid bit and data, keyed by physical tag")
      .note(
        "Epoch stance: epoch-FREE by construction (not an epoch-holding vertex, not in " +
        "propEpochVertexEnumeration). PRF data carries no epoch tag: wrong-path values " +
        "become unreachable when RenameUnit restores the architectural map on redirect " +
        "(ADR-001) and their physical registers return via free-list reconstruction; the " +
        "in-flight uops referencing them die by epoch in RS/PublishMux. A per-entry epoch " +
        "tag would be redundant state on the ADR-008 N=1 fold-away path."
      )
      .note(
        "No separate architectural/virtual distinction - mapping managed by RenameUnit"
      )
      .has(
        intfPhysicalRegWriteIn,
        intfRegisterFileReadReqIn,
        intfRegisterFileReadRespOut,
        funcReadAtSelect,
        propPrfPortsVsN
      )
      .build()
  }

  // ADR-002 / ADR-010: read ports are a function of issue width plus verif commit ports.
  val funcReadAtSelect = spec {
    FUNCTION("ReadAtSelect")
      .desc("Operands are read at RS select time; read ports = issueWidth*2 plus commitWidth commit-time ports when usingRvvi.")
      .note("The extra commit-time wdata read ports exist only in the verif build (usingRvvi=true, WP-D CoreParams); base folds them away (ADR-010 D-10.3).")
      .uses(paramSpeculativeRegNum)
      .build()
  }

  // ADR-012 / ADR-010 / P01: PRF depth scales with N; read ports do not.
  val propPrfPortsVsN = spec {
    PROPERTY("PrfPortsVsN")
      .desc("PRF depth = 33 + SpeculativeRegNum; read ports are a function of issue width plus the verif commit ports, NOT of N.")
      .note("Closes critique m1: widening the window grows depth, not read-port count (ADR-010 consequences). Pair with an elaboration require.")
      .uses(paramSpeculativeRegNum, paramPhysicalRegNum)
      .build()
  }

  val intfPhysicalRegWriteIn = spec {
    INTERFACE("PhysicalRegWriteIn")
      .desc("Physical register write input.")
      .uses(bndPhysicalRegWrite)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRegisterFileReadReqIn = spec {
    INTERFACE("RegisterFileReadReqIn")
      .desc("Physical register read request input.")
      .uses(bndRegisterFileReadReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRegisterFileReadRespOut = spec {
    INTERFACE("RegisterFileReadRespOut")
      .desc("Physical register read response output.")
      .uses(bndRegisterFileReadResp)
      .is(rawReadyValidIntf)
      .build()
  }
}
