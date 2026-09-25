package udacore.frontend.spec.top

import framework.macros.SpecEmit.spec
import framework.specs.Spec._

import udacore.common.spec.DesignRuleSpecs._
import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs.{bndFtqCommit, bndRecoveryEvent}
import udacore.core.spec.shared.CoreBundlesSpecs.bndBootAddr
import udacore.core.spec.shared.MemoryBundlesSpecs.{bndICacheReq, bndICacheResp, bndTranslateReq}

/** FrontendTop: conventional PC-indexed speculative frontend (ADR-019 D-19.2).
  *
  * FetchPcGen -> BranchPredictor (BTB+TAGE+RAS) -> FetchTargetQueue ->
  * FetchUnit (ITLB + VIPT I-cache, both CoreTop-level vertices) -> FetchBuffer
  * -> backend DecodeUnit. There is no predecoder, slot slicer, compressed
  * expander, or indirect-jump stall: prediction precedes fetch, and every
  * misprediction is corrected by the backend's execute-time RecoveryEvent.
  */
object FrontendTopSpecs {
  val contFrontendTop = spec {
    CONTRACT("FrontendTop")
      .desc("""
      | Frontend top level contract (rawTop: vertex instantiation and :<>= wiring only).
      | FetchPcGen holds the speculative fetch PC; the BranchPredictor predicts each 16-byte
      | fetch block from its PC alone; the FetchTargetQueue records the prediction, its
      | metadata, and the GHR/RAS checkpoint, and issues fetch requests in order; the
      | FetchUnit looks up the ITLB and the VIPT I-cache in parallel; the FetchBuffer
      | delivers two instructions per cycle to the backend DecodeUnit.
      | The RecoveryEvent broadcast from the backend is the only redirect input; the
      | FtqCommit edge from the backend is the only training input.
      """)
      .is(rawTop)
      .draw(
        "mermaid",
        """
    graph LR
    %% Legend
    %% -.-> : rawNoDecoupled broadcast (RecoveryEvent, class 6)
    %% --> : Decoupled edge (ready/valid)
    %% [] : vertex   [[]] : rawTop

    subgraph inputs_group[Inputs]
        in_anchor:::hidden
        boot@{shape: text, label: BootAddr}
        rec@{shape: text, label: RecoveryEvent}
        ftqc@{shape: text, label: FtqCommit}
        icresp@{shape: text, label: ICacheResp}
    end

    pcg[FetchPcGen]
    bp[BranchPredictor]
    ftq[FetchTargetQueue]
    fu[FetchUnit]
    fb[FetchBuffer]

    boot --> pcg
    ftqc --> ftq
    icresp --> fu

    rec -.-> pcg
    rec -.-> bp
    rec -.-> ftq
    rec -.-> fu
    rec -.-> fb

    subgraph FrontendTop
        direction LR
        pcg -- PredictReq --> bp
        bp -- NextPc --> pcg
        bp -- Prediction --> ftq
        ftq -- HistoryRestore --> bp
        ftq -- PredictorTrain --> bp
        ftq -- FetchRequest --> fu
        fu -- FetchBlock --> fb
    end

    fu --> itlbreq
    fu --> icreq
    fb --> packet

    subgraph outputs_group[Outputs]
        packet@{shape: text, label: FetchPacket}
        itlbreq@{shape: text, label: ITlbReq}
        icreq@{shape: text, label: ICacheReq}
    end

    style inputs_group fill:transparent,stroke:transparent
    style outputs_group fill:transparent,stroke:transparent
      """.stripMargin
      )
      .note(
        "Edge reconciliation (propGraphConsistency): boot -> FetchPcGen.BootAddrIn; " +
        "FetchPcGen.PredictReqOut -> BranchPredictor.PredictReqIn; BranchPredictor.NextPcOut -> " +
        "FetchPcGen.NextPcIn; BranchPredictor.PredictionOut -> FetchTargetQueue.PredictionIn; " +
        "FetchTargetQueue.HistoryRestoreOut -> BranchPredictor.HistoryRestoreIn; " +
        "FetchTargetQueue.PredictorTrainOut -> BranchPredictor.PredictorTrainIn; " +
        "FetchTargetQueue.FetchRequestOut -> FetchUnit.FetchRequestIn; FetchUnit.FetchBlockOut " +
        "-> FetchBuffer.FetchBlockIn; FtqCommitIn -> FetchTargetQueue.FtqCommitIn; " +
        "ICacheRespIn -> FetchUnit.ICacheRespIn; FetchUnit.ITlbReqOut -> ITlbReqOut; " +
        "FetchUnit.ICacheReqOut -> ICacheReqOut; FetchBuffer.FetchPacketOut -> FetchPacketOut; " +
        "RecoveryEventIn broadcast to all five vertices."
      )
      .note(
        "The ITLB -> I-cache translation edge and the ITLB/PTW edges are CoreTop-level; they do " +
        "not cross this boundary."
      )
      .uses(propGraphConsistency)
      .has(
        intfBootAddrIn,
        intfRecoveryEventIn,
        intfFtqCommitIn,
        intfICacheRespIn,
        intfFetchPacketOut,
        intfITlbReqOut,
        intfICacheReqOut
      )
      .build()
  }

  val intfBootAddrIn = spec {
    INTERFACE("BootAddrIn")
      .desc("Boot pulse from the CoreTop BootSequencer, wired to FetchPcGen.")
      .uses(bndBootAddr)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRecoveryEventIn = spec {
    INTERFACE("RecoveryEventIn")
      .desc("The backend RecoveryEvent broadcast, fanned to every frontend speculative holder.")
      .uses(bndRecoveryEvent)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 6.")
      .build()
  }

  val intfFtqCommitIn = spec {
    INTERFACE("FtqCommitIn")
      .desc("Block-commit notices from the backend CommitUnit, wired to the FetchTargetQueue.")
      .uses(bndFtqCommit)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheRespIn = spec {
    INTERFACE("ICacheRespIn")
      .desc("Fetch-block responses from the CoreTop-level InstructionCache, wired to the FetchUnit.")
      .uses(bndICacheResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfFetchPacketOut = spec {
    INTERFACE("FetchPacketOut")
      .desc("Instruction packets (up to DecodeWidth) to the backend DecodeUnit.")
      .uses(bndFetchPacket)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfITlbReqOut = spec {
    INTERFACE("ITlbReqOut")
      .desc("Fetch translation requests to the CoreTop-level InstructionTlb.")
      .uses(bndTranslateReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheReqOut = spec {
    INTERFACE("ICacheReqOut")
      .desc("Virtually indexed fetch lookups to the CoreTop-level InstructionCache.")
      .uses(bndICacheReq)
      .is(rawReadyValidIntf)
      .build()
  }
}
