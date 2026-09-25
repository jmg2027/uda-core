package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.frontend.spec.shared.FrontendParamsSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs.{bndFtqCommit, bndRecoveryEvent}
import udacore.backend.spec.shared.BackendParamsSpecs.{funcRobOlder, funcRecoveryKills}

/** FetchTargetQueue: prediction metadata and history checkpoints per fetch
  * block, from prediction to commit (ADR-019 D-19.2, D-19.11).
  */
object FetchTargetQueueSpecs {
  val contFetchTargetQueue = spec {
    CONTRACT("FetchTargetQueue")
      .desc(
        "A circular queue with one entry per predicted fetch block. It decouples prediction " +
        "from instruction fetch, issues fetch requests in order, keeps each block's " +
        "prediction, TAGE/BTB metadata, and GHR/RAS checkpoint until the block's last " +
        "instruction commits, repairs predictor history after a RecoveryEvent, and emits the " +
        "commit-time training record."
      )
      .has(
        intfPredictionIn,
        intfFetchRequestOut,
        intfFtqCommitIn,
        intfHistoryRestoreOut,
        intfPredictorTrainOut,
        intfRecoveryEventIn,
        funcFtqAllocate,
        funcFtqFetchIssue,
        funcFtqRecovery,
        funcFtqCommitTrain,
        propFtqInOrderRelease,
        propFtqRecoveryKeepsOlder
      )
      .uses(paramFtqDepth, paramFtqIdxWidth, funcRobOlder)
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: entries are ordered by ftqIdx ({wrap, idx}, compared with " +
        "the funcRobOlder rule). An entry is live from enqueue until FtqCommit names it or a " +
        "RecoveryEvent discards it. BranchMispredict discards entries younger than e.ftqIdx " +
        "and keeps e.ftqIdx and all older; ArchRedirect discards all. Reclaim: the tail pointer " +
        "rewinds to e.ftqIdx + 1 (or to the head for ArchRedirect)."
      )
      .build()
  }

  val intfPredictionIn = spec {
    INTERFACE("PredictionIn")
      .desc("Predictions from the BranchPredictor; ready is low when the FTQ is full.")
      .uses(bndPrediction)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfFetchRequestOut = spec {
    INTERFACE("FetchRequestOut")
      .desc("In-order fetch requests (ftqIdx, fetchPc, lastSlot) to the FetchUnit.")
      .uses(bndFetchRequest)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfFtqCommitIn = spec {
    INTERFACE("FtqCommitIn")
      .desc("Block-commit notices from the backend CommitUnit (FtqCommit view of the commit broadcast), in program order.")
      .uses(bndFtqCommit)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfHistoryRestoreOut = spec {
    INTERFACE("HistoryRestoreOut")
      .desc("One restore token per RecoveryEvent to the BranchPredictor.")
      .uses(bndHistoryRestore)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPredictorTrainOut = spec {
    INTERFACE("PredictorTrainOut")
      .desc("One training record per committed block; backpressure delays the FTQ release (and hence commit), never drops training.")
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

  val funcFtqAllocate = spec {
    FUNCTION("FtqAllocate")
      .desc("Enqueue each accepted Prediction at the tail in prediction order and assign it ftqIdx = tail.")
      .uses(intfPredictionIn)
      .build()
  }

  val funcFtqFetchIssue = spec {
    FUNCTION("FtqFetchIssue")
      .desc(
        "A fetch pointer walks live entries in order and issues one FetchRequest per entry, " +
        "with lastSlot = the predicted taken exit slot or FetchWidth-1. After a RecoveryEvent " +
        "the fetch pointer restarts at the first entry allocated after the event."
      )
      .uses(intfFetchRequestOut)
      .build()
  }

  val funcFtqRecovery = spec {
    FUNCTION("FtqRecovery")
      .desc(
        "BranchMispredict: discard every entry younger than e.ftqIdx (funcRecoveryKills in " +
        "the fetch-block order domain), record e.cfiOutcome as the resolved exit of entry " +
        "e.ftqIdx, and send HistoryRestore{checkpoint of e.ftqIdx, applyOutcome, outcome, pc}. " +
        "ArchRedirect: send HistoryRestore{checkpoint of e.ftqIdx if that entry is live, else " +
        "the current tail checkpoint, applyOutcome = false} and then discard all entries."
      )
      .uses(intfRecoveryEventIn, intfHistoryRestoreOut, funcRecoveryKills)
      .note(
        "The ArchRedirect history choice is microarchitectural; any deterministic choice is " +
        "legal (ADR-019 D-19.11), and this one is fixed for reproducibility."
      )
      .build()
  }

  val funcFtqCommitTrain = spec {
    FUNCTION("FtqCommitTrain")
      .desc(
        "On FtqCommit{ftqIdx, exit}: the named entry is the head; emit PredictorTrain{fetchPc, " +
        "checkpoint.ghr, meta, prediction, committed exit} and release the head. The FtqCommit " +
        "transfer completes only together with the PredictorTrain transfer."
      )
      .uses(intfFtqCommitIn, intfPredictorTrainOut)
      .build()
  }

  val propFtqInOrderRelease = spec {
    PROPERTY("FtqInOrderRelease")
      .desc("Every FtqCommit names the current head entry; entries are released only from the head, in ftqIdx order.")
      .uses(intfFtqCommitIn)
      .note("Simulation assert.")
      .build()
  }

  val propFtqRecoveryKeepsOlder = spec {
    PROPERTY("FtqRecoveryKeepsOlder")
      .desc(
        "A BranchMispredict RecoveryEvent never discards or modifies (other than recording the " +
        "resolved exit of e.ftqIdx) any entry at or older than e.ftqIdx."
      )
      .uses(intfRecoveryEventIn)
      .note("Simulation assert.")
      .build()
  }
}
