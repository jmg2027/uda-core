package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.frontend.spec.shared.FrontendParamsSpecs._

/** BranchPredecoder: first-taken prune and epoch-tagged prediction (ADR-009 D-9.5). */
object BranchPredecoderSpecs {
  val contBranchPredecoder = spec {
    CONTRACT("BranchPredecoder")
      .desc(
        "Branch predecoder classifies each slot in a group (direct-jump / conditional-branch / " +
          "indirect-jump), applies the first-taken prune, and drives one epoch-tagged prediction " +
          "per group back to NextPcGen."
      )
      .has(
        intfPredictorIn,
        intfPredecodedOut,
        intfPredictorOut,
        funcFirstTakenPrune,
        funcPredictionFeedback
      )
      .build()
  }

  val intfPredictorIn = spec {
    INTERFACE("PredictorInputIn")
      .desc("Predecode metadata entering the branch predecoder.")
      .is(rawReadyValidIntf)
      .uses(bndPredictorInput)
      .build()
  }

  val intfPredecodedOut = spec {
    INTERFACE("PredecodedOut")
      .desc("Predecoded, first-taken-pruned slot group forwarded to the issue queue.")
      .is(rawReadyValidIntf)
      .uses(bndIssueFrontend)
      .note("Only slots 0..t survive the prune (funcFirstTakenPrune); this is the IssueQueue enqueue payload.")
      .build()
  }

  val intfPredictorOut = spec {
    INTERFACE("PredictorOutputOut")
      .desc("Epoch-tagged first-taken prediction emitted by the branch predecoder to NextPcGen.")
      .is(rawReadyValidIntf)
      .uses(bndPredictorOutput)
      .build()
  }

  val funcFirstTakenPrune = spec {
    FUNCTION("FirstTakenPrune")
      .desc(
        "Within a slot group in PC order, marks slots 0..t valid and t+1..K-1 invalid, where t " +
          "is the first taken control transfer (direct-jump always; conditional-branch if " +
          "predicted taken; indirect always with unknown target). If no slot is taken, all are " +
          "valid and the group falls through sequentially."
      )
      .markdownTable(
        List("Class", "Target known in FE?", "Taken?", "Effect at slot t"),
        List(
          List("JAL/C.J/C.JAL", "yes", "always", "predict target, prune successors"),
          List("BR/C.BEQZ/C.BNEZ", "yes", "predictor", "predict direction; if taken prune successors"),
          List("JALR/C.JR/C.JALR", "no", "always", "indirectStall, prune successors, await redirect")
        )
      )
      .note("Direct/branch targets are computed here from the immediate (RvcExpander J/B imm).")
      .build()
  }

  val funcPredictionFeedback = spec {
    FUNCTION("PredictionFeedback")
      .desc(
        "Emits one epoch-tagged PredictorOutput per group to NextPcGen: " +
          "{valid, taken, target, srcPc, srcEpoch, indirectStall}."
      )
      .note("NextPcGen consumes it only if srcEpoch === GlobalEpoch (guardrail G3); a wrong-path prediction self-cancels.")
      .uses(bndPredictorOutput, paramBranchPredictorEntries)
      .build()
  }
}
