package udacore.frontend.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.frontend.spec.shared.FrontendParamsSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs.{bndCfiOutcome, bndRecoveryEvent}
import udacore.core.spec.shared.CoreParamsSpecs.paramVAddrWidth

/** Frontend bundles (ADR-019 WP-2).
  *
  * A fetch block is a vector of aligned 32-bit instructions with per-slot PC
  * and exception metadata (D-19.3). Prediction is made from the fetch PC
  * before instruction bytes exist (D-19.2); the FTQ owns prediction metadata
  * and history checkpoints (D-19.11). No bundle carries an epoch.
  */
object FrontendBundlesSpecs {

  val bndPredictReq = spec {
    BUNDLE("PredictReq")
      .desc("Fetch PC offered by FetchPcGen to the BranchPredictor.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("fetchPc", "UInt(vAddrWidth)", "4-byte aligned fetch PC; its block is fetchPc aligned down to FetchBytes."))
      )
      .uses(paramVAddrWidth, paramFetchBytes)
      .build()
  }

  val bndPrediction = spec {
    BUNDLE("Prediction")
      .desc(
        "One fetch-block prediction produced from the fetch PC alone. The FTQ index is " +
        "attached by the FetchTargetQueue when the prediction enters it."
      )
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("fetchPc", "UInt(vAddrWidth)", "Block start PC."),
          List("cfiValid", "Bool", "A tracked control-flow instruction exists at or after fetchPc in this block (BTB hit)."),
          List("cfiSlot", "UInt(log2(FetchWidth))", "Its slot."),
          List("cfiType", "CfiType", "Branch | Jal | Jalr | Call | Ret."),
          List("taken", "Bool", "Predicted taken (jumps always; branches per TAGE)."),
          List("target", "UInt(vAddrWidth)", "RAS top for Ret, BTB target otherwise."),
          List("nextPc", "UInt(vAddrWidth)", "taken ? target : next aligned block start."),
          List("meta", "PredictorMeta", "Training metadata."),
          List("checkpoint", "HistoryCheckpoint", "GHR/RAS state BEFORE this block's speculative update.")
        )
      )
      .uses(paramFetchWidth)
      .build()
  }

  val bndPredictorMeta = spec {
    BUNDLE("PredictorMeta")
      .desc("TAGE/BTB metadata kept in the FTQ until training.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("btbHit, btbWay", "Bool, UInt", "BTB lookup result."),
          List("provider", "UInt(log2(tables+1))", "Longest-history matching TAGE table (0 = bimodal base)."),
          List("providerCtr", "SInt(3)", "Provider counter value at prediction."),
          List("altPred", "Bool", "Alternate prediction (next-longest match or base)."),
          List("useAlt", "Bool", "The final direction came from altPred (weak newly allocated provider)."),
          List("hitMask", "UInt(tables)", "Which tagged tables matched.")
        )
      )
      .uses(paramTageGeometry)
      .build()
  }

  val bndHistoryCheckpoint = spec {
    BUNDLE("HistoryCheckpoint")
      .desc("Speculative predictor history state captured before a block's update (D-19.11).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("ghr", "UInt(GhrLength)", "Global history."),
          List("rasTop", "UInt(log2(RasDepth))", "RAS top pointer."),
          List("rasEntries", "Vec(RasDepth, UInt(vAddrWidth))", "Full RAS contents (v0 full snapshot): wrong-path pops followed by pushes, or pushes that wrap the circular stack, may overwrite any entry, so only a full snapshot makes restore exact.")
        )
      )
      .uses(paramGhrLength, paramRasDepth)
      .build()
  }

  val bndFtqEntry = spec {
    BUNDLE("FtqEntry")
      .desc("Fetch target queue entry: one fetch block from prediction to commit.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("valid", "Bool", "Live."),
          List("fetchPc", "UInt(vAddrWidth)", "Block start PC."),
          List("prediction", "Prediction", "Predicted exit (slot/type/taken/target/nextPc)."),
          List("meta", "PredictorMeta", "Training metadata."),
          List("checkpoint", "HistoryCheckpoint", "History before this block."),
          List("fetched", "Bool", "The block has been sent to the FetchUnit."),
          List("resolved", "CfiOutcome", "Actual exit written by a BranchMispredict RecoveryEvent that names this entry.")
        )
      )
      .uses(bndPrediction, bndPredictorMeta, bndHistoryCheckpoint, bndCfiOutcome)
      .build()
  }

  val bndFetchRequest = spec {
    BUNDLE("FetchRequest")
      .desc("FTQ to FetchUnit: fetch one block.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("ftqIdx", "FtqIdx", "Owning FTQ entry."),
          List("fetchPc", "UInt(vAddrWidth)", "Start PC."),
          List("lastSlot", "UInt(log2(FetchWidth))", "Predicted exit slot (taken CFI) or the last slot of the block."),
          List("exitTaken, exitTarget", "Bool, UInt(vAddrWidth)", "Whether lastSlot is a predicted-taken exit, and its predicted target.")
        )
      )
      .uses(paramFtqIdxWidth)
      .build()
  }

  val bndFetchBlock = spec {
    BUNDLE("FetchBlock")
      .desc("FetchUnit output: the instructions of one fetch block with per-slot metadata.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("ftqIdx", "FtqIdx", "Owning FTQ entry."),
          List("basePc", "UInt(vAddrWidth)", "Aligned block address."),
          List("insts", "Vec(FetchWidth, UInt(32))", "Instruction words."),
          List("slotValid", "Vec(FetchWidth, Bool)", "Slots from fetchPc through the predicted exit slot."),
          List("exitTaken, exitTarget", "Bool, UInt(vAddrWidth)", "Echo of the FetchRequest prediction for the last valid slot."),
          List("fault", "FetchFault", "None | InstPageFault | InstAccessFault; a faulting block delivers one valid slot (the first) carrying the fault.")
        )
      )
      .uses(paramFetchWidth, paramFtqIdxWidth)
      .build()
  }

  val bndFetchInst = spec {
    BUNDLE("FetchInst")
      .desc("One instruction entering DecodeUnit.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("inst", "UInt(32)", "Instruction word."),
          List("pc", "UInt(vAddrWidth)", "basePc + 4 * slot."),
          List("ftqIdx", "FtqIdx", "Owning FTQ entry."),
          List("slot", "UInt(log2(FetchWidth))", "Slot within the block."),
          List("blockEnd", "Bool", "Last delivered slot of its block."),
          List("predictedTaken", "Bool", "This slot is the block's predicted-taken exit."),
          List("predictedTarget", "UInt(vAddrWidth)", "Predicted target when predictedTaken."),
          List("fault", "FetchFault", "Fetch fault carried to the ROB.")
        )
      )
      .uses(paramFtqIdxWidth)
      .build()
  }

  val bndFetchPacket = spec {
    BUNDLE("FetchPacket")
      .desc("Up to DecodeWidth consecutive instructions in program order from the fetch buffer to DecodeUnit.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("insts", "Vec(DecodeWidth, FetchInst)", "Instructions, oldest first."),
          List("valid", "Vec(DecodeWidth, Bool)", "Contiguous from lane 0.")
        )
      )
      .uses(bndFetchInst, paramDecodeWidth)
      .build()
  }

  val bndFetchRedirect = spec {
    BUNDLE("FetchRedirect")
      .desc("The frontend's field projection of bndRecoveryEvent: {valid, kind, target, ftqIdx, cfiOutcome}.")
      .uses(bndRecoveryEvent)
      .note("Not a separate edge or producer: every frontend vertex reads the same RecoveryEvent broadcast.")
      .build()
  }

  val bndHistoryRestore = spec {
    BUNDLE("HistoryRestore")
      .desc("FTQ to BranchPredictor after a RecoveryEvent: the checkpoint to restore and the resolved outcome to apply.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("checkpoint", "HistoryCheckpoint", "State before the recovering block."),
          List("applyOutcome", "Bool", "BranchMispredict: apply cfiOutcome after restore; ArchRedirect: restore only."),
          List("outcome", "CfiOutcome", "Resolved exit of the recovering block."),
          List("pc", "UInt(vAddrWidth)", "PC of the recovering instruction (return address = pc + 4 for a call).")
        )
      )
      .uses(bndHistoryCheckpoint, bndCfiOutcome)
      .build()
  }

  val bndPredictorTrain = spec {
    BUNDLE("PredictorTrain")
      .desc("FTQ to BranchPredictor at block commit: the deterministic training point (D-19.11).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("fetchPc", "UInt(vAddrWidth)", "Block start PC (BTB/TAGE index)."),
          List("ghr", "UInt(GhrLength)", "History the block was predicted with (checkpoint.ghr)."),
          List("meta", "PredictorMeta", "Prediction-time metadata."),
          List("predicted", "Prediction", "Predicted exit."),
          List("committed", "CfiOutcome", "Committed exit.")
        )
      )
      .uses(bndPredictorMeta, bndPrediction, bndCfiOutcome)
      .build()
  }
}
