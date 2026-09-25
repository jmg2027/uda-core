package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs.bndRecoveryEvent
import udacore.core.spec.shared.CoreBundlesSpecs.bndBootAddr

/** FetchPcGen: the speculative fetch-PC register of the frontend (ADR-019 WP-3). */
object FetchPcGenSpecs {
  val contFetchPcGen = spec {
    CONTRACT("FetchPcGen")
      .desc(
        "FetchPcGen owns the speculative fetch PC. It offers one PredictReq per fetch block to " +
        "the BranchPredictor and advances to the predictor's nextPc. It is the single frontend " +
        "consumer of redirect targets: boot seeds it, and every RecoveryEvent overrides it."
      )
      .has(
        intfBootAddrIn,
        intfRecoveryEventIn,
        intfNextPcIn,
        intfPredictReqOut,
        funcFetchPcSelect,
        funcFetchPcRecovery,
        propFetchPcAligned
      )
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: holds one PC and at most one pending NextPc token. " +
        "Ordering: a single register. Live: from boot or redirect until replaced. Recovery: any " +
        "RecoveryEvent replaces it (nothing it holds can be older than a backend uop). Reclaim: n/a."
      )
      .build()
  }

  val intfBootAddrIn = spec {
    INTERFACE("BootAddrIn")
      .desc("Single boot pulse from BootSequencer carrying the first fetch PC (M-mode, translation off).")
      .uses(bndBootAddr)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRecoveryEventIn = spec {
    INTERFACE("RecoveryEventIn")
      .desc("The common RecoveryEvent broadcast; target is the new fetch PC.")
      .uses(bndRecoveryEvent, bndFetchRedirect)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 6: a published recovery fact, never backpressured.")
      .build()
  }

  val intfNextPcIn = spec {
    INTERFACE("NextPcIn")
      .desc("Predicted next fetch PC from the BranchPredictor for the block just predicted.")
      .uses(bndPredictReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPredictReqOut = spec {
    INTERFACE("PredictReqOut")
      .desc("Current fetch PC toward the BranchPredictor. Backpressured by the predictor (restore in progress, FTQ full).")
      .uses(bndPredictReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcFetchPcSelect = spec {
    FUNCTION("FetchPcSelect")
      .desc(
        "Priority for the next fetch PC: (1) RecoveryEvent.target in the event cycle; (2) the " +
        "boot address before the first fetch; (3) the NextPc token of the block last " +
        "predicted. After offering PredictReq for PC p, FetchPcGen offers nothing until the " +
        "NextPc for p arrives (one block in prediction at a time in v0)."
      )
      .uses(intfBootAddrIn, intfNextPcIn, intfPredictReqOut)
      .build()
  }

  val funcFetchPcRecovery = spec {
    FUNCTION("FetchPcRecovery")
      .desc(
        "On a RecoveryEvent of either kind: drop any pending NextPc token and any unaccepted " +
        "PredictReq, set the fetch PC to the event target, and offer it as the next PredictReq. " +
        "The BranchPredictor holds that request (ready low) until its history restore is applied."
      )
      .uses(intfRecoveryEventIn)
      .build()
  }

  val propFetchPcAligned = spec {
    PROPERTY("FetchPcAligned")
      .desc(
        "Every PredictReq.fetchPc is 4-byte aligned: boot, trap-vector, xEPC, and branch " +
        "targets reaching FetchPcGen are aligned by construction (a misaligned branch target " +
        "raises instruction-address-misaligned on the branch instead of redirecting)."
      )
      .uses(intfPredictReqOut)
      .note("Simulation assert (ADR-015 D-15.3).")
      .build()
  }
}
