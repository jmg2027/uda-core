package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.frontend.spec.shared.FrontendParamsSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs.bndRecoveryEvent

/** BranchPredictor: PC-indexed BTB + TAGE + RAS (ADR-019 D-19.2, D-19.11).
  *
  * Decomposition choice (WP-3): the BTB, TAGE, and RAS are internal RAW
  * subcores of this one vertex, not separate rawTop vertices. The vertex owns
  * the speculative GHR and RAS; the FTQ owns their checkpoints.
  */
object BranchPredictorSpecs {
  val contBranchPredictor = spec {
    CONTRACT("BranchPredictor")
      .desc(
        "Predicts, from the fetch PC alone, whether the fetch block contains a taken " +
        "control-flow instruction, where it is, and where it goes. The BTB owns control-flow " +
        "presence, type, and target; TAGE owns conditional-branch direction only; the RAS " +
        "owns return targets. Each prediction is emitted twice in one transfer: the full " +
        "Prediction (with metadata and the pre-update history checkpoint) to the " +
        "FetchTargetQueue, and the nextPc to FetchPcGen."
      )
      .has(
        intfPredictReqIn,
        intfPredictionOut,
        intfNextPcOut,
        intfHistoryRestoreIn,
        intfPredictorTrainIn,
        intfRecoveryEventIn,
        funcBtbLookup,
        funcTageDirection,
        funcRasPredict,
        funcBlockExitSelect,
        funcSpeculativeHistoryUpdate,
        funcHistoryRestore,
        funcPredictorTraining,
        propPredictFromPcOnly,
        propOneTakenCfiPerBlock,
        propHistoryRestoreExact,
        propPredictorStateMicroarchitectural,
        rawBtbSubcore,
        rawTageSubcore,
        rawRasSubcore
      )
      .uses(paramBtbGeometry, paramTageGeometry, paramGhrLength, paramRasDepth, paramFetchWidth)
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: the speculative GHR and RAS are the only speculative state. " +
        "They are not ordered per entry; they are repaired only by HistoryRestore from the FTQ " +
        "after a RecoveryEvent. BTB/TAGE tables are microarchitectural and are never rolled back."
      )
      .build()
  }

  val intfPredictReqIn = spec {
    INTERFACE("PredictReqIn")
      .desc("Fetch PC from FetchPcGen. Ready is low while a history restore is outstanding.")
      .uses(bndPredictReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPredictionOut = spec {
    INTERFACE("PredictionOut")
      .desc("Prediction with metadata and checkpoint into the FetchTargetQueue. FTQ-full backpressures prediction.")
      .uses(bndPrediction)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfNextPcOut = spec {
    INTERFACE("NextPcOut")
      .desc("nextPc of the same prediction to FetchPcGen; fires in the same cycle as PredictionOut (atomic fork).")
      .uses(bndPredictReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfHistoryRestoreIn = spec {
    INTERFACE("HistoryRestoreIn")
      .desc("Checkpoint plus resolved outcome from the FTQ after a RecoveryEvent.")
      .uses(bndHistoryRestore)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPredictorTrainIn = spec {
    INTERFACE("PredictorTrainIn")
      .desc("Commit-time training record from the FTQ.")
      .uses(bndPredictorTrain)
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

  val funcBtbLookup = spec {
    FUNCTION("BtbLookup")
      .desc(
        "Index and tag the BTB with blockBase = alignDown(fetchPc, FetchBytes). Each entry " +
        "keeps an absolute block slot; among hitting ways, consider only entries whose slot is " +
        ">= startSlot = (fetchPc - blockBase) / 4 (an entry for an earlier slot is ignored, " +
        "ADR-019G E-3); the lowest such slot is the " +
        "block's tracked control-flow instruction, with its type (Branch/Jal/Jalr/Call/Ret) " +
        "and last-seen target. A JALR that is not a return is predicted to its BTB target; " +
        "v0 has no indirect-target predictor (ITTAGE is a later extension)."
      )
      .uses(intfPredictReqIn, paramBtbGeometry)
      .build()
  }

  val funcTageDirection = spec {
    FUNCTION("TageDirection")
      .desc(
        "For a tracked Branch, compute each tagged table's index and tag from blockBase " +
        "(ADR-019G E-3) and a folded prefix of the speculative GHR of that table's history length. " +
        "The provider is the longest-history matching table; the alternate is the next-longest " +
        "matching table or the bimodal base. The direction is the provider's counter sign, " +
        "except that a weak provider whose useful counter is zero uses the alternate. TAGE " +
        "never decides presence, type, or target."
      )
      .uses(paramTageGeometry, paramGhrLength)
      .build()
  }

  val funcRasPredict = spec {
    FUNCTION("RasPredict")
      .desc(
        "A predicted-taken Call pushes cfiPc + 4 onto the speculative RAS, where cfiPc = " +
        "blockBase + 4 * cfiSlot (ADR-019G E-5, never requestedFetchPc + 4 * slot); a " +
        "predicted Ret pops and uses the popped value as the target; a Ret that is also a " +
        "Call (coroutine hint) pops then pushes. Call/Ret classification follows the RISC-V " +
        "link-register hints (rd or rs1 in {x1, x5})."
      )
      .uses(paramRasDepth)
      .build()
  }

  val funcBlockExitSelect = spec {
    FUNCTION("BlockExitSelect")
      .desc(
        "The block exit is the tracked CFI if it is predicted taken (Jal/Jalr/Call/Ret always, " +
        "Branch per TAGE); nextPc = its target. Otherwise nextPc = blockBase + FetchBytes " +
        "(the next block boundary, not fetchPc + FetchBytes; ADR-019G E-4) and the block is " +
        "predicted to fall through all remaining slots."
      )
      .uses(intfPredictionOut, intfNextPcOut)
      .build()
  }

  val funcSpeculativeHistoryUpdate = spec {
    FUNCTION("SpeculativeHistoryUpdate")
      .desc(
        "After capturing the checkpoint {ghr, rasTop, rasEntries} into the Prediction, update " +
        "speculatively: the GHR shifts in exactly one bit, the predicted direction, iff the " +
        "block's tracked CFI is a Branch; otherwise it is unchanged. The RAS updates per " +
        "funcRasPredict."
      )
      .uses(paramGhrLength)
      .build()
  }

  val funcHistoryRestore = spec {
    FUNCTION("HistoryRestore")
      .desc(
        "On a RecoveryEvent, discard any in-flight lookup and hold PredictReqIn until a " +
        "HistoryRestore arrives. Restore GHR, rasTop, and every RAS entry from the checkpoint; then, if applyOutcome, apply the recovering instruction's resolved " +
        "outcome with the same rules as the speculative update: shift in the resolved " +
        "direction iff it is a Branch; push pc+4 for a Call; pop for a Ret. pc is " +
        "HistoryRestore.pc = blockBase + 4 * outcome.slot (ADR-019G E-5)."
      )
      .uses(intfHistoryRestoreIn, intfRecoveryEventIn)
      .build()
  }

  val funcPredictorTraining = spec {
    FUNCTION("PredictorTraining")
      .desc(
        "The single deterministic training point is block commit (a PredictorTrain token, in " +
        "program order), indexed and tagged by PredictorTrain.fetchPc = blockBase (ADR-019G " +
        "E-7). BTB: allocate or update the entry for the committed taken exit " +
        "(slot, type, target); a committed fall-through leaves the BTB unchanged. TAGE " +
        "(a committed taken Branch exit, or a tracked Branch that committed not-taken): " +
        "update the provider counter toward the outcome, " +
        "update the useful counter when provider and alternate disagree, and on a " +
        "misprediction allocate one entry in a longer-history table whose useful counter is " +
        "zero (decaying useful counters otherwise). Training never touches speculative GHR/RAS."
      )
      .uses(intfPredictorTrainIn)
      .note("ADR-019 D-19.11: resolution-time training is not used in v0.")
      .build()
  }

  val propPredictFromPcOnly = spec {
    PROPERTY("PredictFromPcOnly")
      .desc(
        "A Prediction depends only on the fetch PC, the BTB/TAGE tables, and the speculative " +
        "GHR/RAS; no instruction bits of the same fetch block reach the prediction logic."
      )
      .note("Structural (no instruction-data input exists on this vertex) plus a simulation assert.")
      .build()
  }

  val propOneTakenCfiPerBlock = spec {
    PROPERTY("OneTakenCfiPerBlock")
      .desc(
        "v0 limitation made explicit: each Prediction names at most one control-flow " +
        "instruction, and every slot after a predicted-taken exit is invalid in the fetch block."
      )
      .uses(intfPredictionOut)
      .build()
  }

  val propHistoryRestoreExact = spec {
    PROPERTY("HistoryRestoreExact")
      .desc(
        "After a HistoryRestore, the GHR, the RAS top pointer, and every RAS entry equal the " +
        "recovering block's checkpoint updated by exactly the recovering instruction's " +
        "resolved outcome, independent of any wrong-path push, pop, or wrap made in between."
      )
      .uses(intfHistoryRestoreIn)
      .build()
  }

  val propPredictorStateMicroarchitectural = spec {
    PROPERTY("PredictorStateMicroarchitectural")
      .desc(
        "No predictor state (BTB, TAGE, GHR, RAS) can change an architectural result: for any " +
        "program the retire stream is identical for every predictor configuration and " +
        "training history; only timing differs."
      )
      .uses(propIsaRetireEquivalence)
      .note("L3 acceptance: retire-stream equivalence with the predictor forced to always-not-taken.")
      .build()
  }

  val rawBtbSubcore = spec {
    RAW("Btb", "subcore")
      .desc("Set-associative, fetch-block-indexed branch target buffer with per-entry {tag, slot, cfiType, target}; true-LRU replacement per set.")
      .build()
  }

  val rawTageSubcore = spec {
    RAW("Tage", "subcore")
      .desc("Bimodal base plus tagged tables with geometric history lengths; entries {tag, ctr, useful}; folded-history index/tag hashing.")
      .build()
  }

  val rawRasSubcore = spec {
    RAW("Ras", "subcore")
      .desc("Circular return address stack with a top pointer; overflow overwrites the oldest entry, underflow returns the stale entry (a misprediction, never an error).")
      .note(
        "v0 recovery is by full-contents snapshot per FTQ entry (bndHistoryCheckpoint; " +
        "FtqDepth x RasDepth x vAddrWidth bits). A speculative write log or linked RAS with a " +
        "persistent stack pointer is the later area optimization and must keep " +
        "propHistoryRestoreExact."
      )
      .build()
  }
}
