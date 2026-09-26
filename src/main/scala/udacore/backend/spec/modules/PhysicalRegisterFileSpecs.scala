package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._

/** PhysicalRegisterFile: the unified integer PRF (ADR-019 D-19.7/D-19.8). */
object PhysicalRegisterFileSpecs {
  val contPhysicalRegisterFile = spec {
    CONTRACT("PhysicalRegisterFile")
      .desc(
        "IntegerPrfEntries registers holding every speculative and committed integer value. " +
        "The ROB is data-less, so the PRF is the only value store: commit changes the rRAT " +
        "mapping, never copies data. p0 always reads zero and is never written."
      )
      .has(
        intfPhysicalRegWriteIn,
        intfRegisterFileReadReqIn,
        intfRegisterFileReadRespOut,
        intfCommitPrfReadReqIn,
        intfCommitPrfReadRespOut,
        funcReadAtSelect,
        propPrfPortsFixed
      )
      .uses(paramIntegerPrfEntries, paramPublishWidth, paramIssueWidth)
      .note(
        "Recovery stance: recovery-exempt. Values of killed producers become unreachable when " +
        "rename restores its map, and their prds return through the free list; the PRF itself " +
        "holds no program-order state and observes no RecoveryEvent."
      )
      .build()
  }

  val intfPhysicalRegWriteIn = spec {
    INTERFACE("PhysicalRegWriteIn")
      .desc("One write per cycle from PublishMux (PublishWidth = 1).")
      .uses(bndPhysicalRegWrite)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRegisterFileReadReqIn = spec {
    INTERFACE("RegisterFileReadReqIn")
      .desc("Operand read requests from the ReservationStation.")
      .uses(bndRegisterFileReadReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRegisterFileReadRespOut = spec {
    INTERFACE("RegisterFileReadRespOut")
      .desc("Operand values to the ReservationStation.")
      .uses(bndRegisterFileReadResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCommitPrfReadReqIn = spec {
    INTERFACE("CommitPrfReadReqIn")
      .desc("Verification-only commit-time read request from the CommitUnit (ADR-019B E-3); always ready; elaborated only when usingRvvi.")
      .uses(bndCommitPrfReadReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCommitPrfReadRespOut = spec {
    INTERFACE("CommitPrfReadRespOut")
      .desc("Same-cycle answer to CommitPrfReadReqIn (ADR-019B E-3); elaborated only when usingRvvi.")
      .uses(bndCommitPrfReadResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcReadAtSelect = spec {
    FUNCTION("ReadAtSelect")
      .desc(
        "Operands are read when the RS selects a uop: 2 x IssueWidth read ports, plus " +
        "CommitWidth commit-time read ports (CommitPrfReadReqIn/RespOut, always ready, answered " +
        "in the same cycle) that exist only when usingRvvi elaborates the retire stream " +
        "(ADR-010 D-10.3, ADR-019B E-3). A read in the same cycle as a write to the same prd " +
        "returns the new value."
      )
      .uses(intfRegisterFileReadReqIn, intfRegisterFileReadRespOut, intfCommitPrfReadReqIn, intfCommitPrfReadRespOut)
      .build()
  }

  val propPrfPortsFixed = spec {
    PROPERTY("PrfPortsFixed")
      .desc("PRF depth is IntegerPrfEntries; read/write port counts depend only on IssueWidth, PublishWidth, and CommitWidth, never on ROB or PRF depth.")
      .uses(paramIntegerPrfEntries)
      .note("Elaboration require.")
      .build()
  }
}
