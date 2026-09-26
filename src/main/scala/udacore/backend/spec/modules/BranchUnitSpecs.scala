package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs.funcRecoveryKills

/** BranchUnit: execute-time control-flow resolution and misprediction
  * detection (ADR-019 D-19.9; supersedes ADR-011 commit-head branch redirect).
  */
object BranchUnitSpecs {
  val contBranchUnit = spec {
    CONTRACT("BranchUnit")
      .desc(
        "Resolves conditional branches, JAL, and JALR at execute, writes the link value, " +
        "records the resolved outcome in the ROB through PublishMux, and - only when the " +
        "frontend prediction was wrong - requests an execute-time selective recovery from the " +
        "RecoveryController. It is the single branch-recovery producer in v0."
      )
      .has(
        intfBranchUnitReqIn,
        intfBranchResultOut,
        intfBranchResolutionOut,
        intfCheckpointReleaseOut,
        intfRecoveryEventIn,
        funcBranchResolve,
        funcMispredictDetect,
        funcBranchRecoveryRequest,
        propRecoveryOnlyOnMispredict,
        propBranchCompletesOnce,
        propBranchControlOnce
      )
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: holds at most one branch and its two independent output " +
        "tokens (control: BranchResolution or CheckpointRelease; result: BranchResult). A " +
        "RecoveryEvent that kills the held robTag (funcRecoveryKills) drops every unfired " +
        "token, so a killed branch never completes or requests recovery; a token that already " +
        "fired is not undone (ADR-019C E-6)."
      )
      .build()
  }

  val intfBranchUnitReqIn = spec {
    INTERFACE("BranchUnitReqIn")
      .desc("Issued control-flow uops (operands, pc, imm, prediction, checkpointId, ftqIdx).")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .note("ADR-019C E-2: ready (canAccept) derives only from registered state and the drain of the already-held output token, never from this request's valid or payload.")
      .build()
  }

  val intfBranchResultOut = spec {
    INTERFACE("BranchResultOut")
      .desc("Completion with link value (JAL/JALR rd = pc + 4) and cfiOutcome toward PublishMux, for every branch.")
      .uses(bndFuResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBranchResolutionOut = spec {
    INTERFACE("BranchResolutionOut")
      .desc("Recovery request for a mispredicted control-flow uop, to the RecoveryController.")
      .uses(bndBranchResolution)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCheckpointReleaseOut = spec {
    INTERFACE("CheckpointReleaseOut")
      .desc("Checkpoint release for a branch that completes without recovery (correct prediction or faulting target), to RenameUnit.")
      .uses(bndCheckpointRelease)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRecoveryEventIn = spec {
    INTERFACE("RecoveryEventIn")
      .desc("The common RecoveryEvent broadcast.")
      .uses(bndRecoveryEvent)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 6.")
      .build()
  }

  val funcBranchResolve = spec {
    FUNCTION("BranchResolve")
      .desc(
        "Compute taken (BEQ/BNE/BLT/BGE/BLTU/BGEU on src1/src2; always for JAL/JALR) and target " +
        "(pc + imm; JALR: (src1 + imm) with bit 0 cleared). A taken target that is not 4-byte " +
        "aligned produces an instruction-address-misaligned exception on the branch (tval = " +
        "target) instead of a recovery; the trap is taken precisely at commit."
      )
      .uses(intfBranchUnitReqIn, intfBranchResultOut)
      .build()
  }

  val funcMispredictDetect = spec {
    FUNCTION("MispredictDetect")
      .desc(
        "Compare the resolution with the slot's prediction: DirectionMispredict when taken != " +
        "predictedTaken for a slot the frontend predicted; TargetMispredict when both are " +
        "taken but target != predictedTarget; UnpredictedCfi when the slot was not the " +
        "block's predicted exit and the CFI is taken. A not-taken branch that was not " +
        "predicted is correct. The redirect target is taken ? target : pc + 4."
      )
      .uses(intfBranchUnitReqIn)
      .build()
  }

  val funcBranchRecoveryRequest = spec {
    FUNCTION("BranchRecoveryRequest")
      .desc(
        "Control resolution and result publication are independent channels (ADR-019C E-4): " +
        "for a mispredicted, non-faulting branch offer BranchResolution{robTag, checkpointId, " +
        "ftqIdx, pc, outcome, redirectTarget, cause}; for a correctly predicted or faulting " +
        "branch offer CheckpointRelease{checkpointId, robTag}; every branch offers its " +
        "BranchResult. Neither channel waits for the other: they fire in the same cycle or in " +
        "different cycles. BranchResolutionOut.valid never depends on BranchResultOut.ready, " +
        "the PublishMux grant, or the RecoveryEvent its own resolution produces. RenameUnit " +
        "frees a mispredicted branch's checkpoint when it applies the RecoveryEvent. The " +
        "branch itself survives its own recovery; all older uops remain live."
      )
      .note("ADR-019C E-4/E-5/E-6: an older same-cycle ArchRedirect kills the branch and discards its unfired tokens.")
      .uses(intfBranchResolutionOut, intfCheckpointReleaseOut, funcRecoveryKills)
      .build()
  }

  val propRecoveryOnlyOnMispredict = spec {
    PROPERTY("RecoveryOnlyOnMispredict")
      .desc("BranchResolutionOut fires only for a live branch whose resolved outcome differs from its prediction, and never for a faulting branch.")
      .uses(intfBranchResolutionOut)
      .build()
  }

  val propBranchControlOnce = spec {
    PROPERTY("BranchControlOnce")
      .desc(
        "Every live branch produces exactly one control side effect - CheckpointRelease XOR " +
        "BranchResolution, never both and never neither - unless a RecoveryEvent kills it " +
        "before its control token fires (ADR-019C E-5)."
      )
      .uses(intfBranchResolutionOut, intfCheckpointReleaseOut)
      .note("Simulation assert.")
      .build()
  }

  val propBranchCompletesOnce = spec {
    PROPERTY("BranchCompletesOnce")
      .desc("Every live branch completes exactly once on BranchResultOut; a killed branch never completes.")
      .uses(intfBranchResultOut)
      .build()
  }
}
