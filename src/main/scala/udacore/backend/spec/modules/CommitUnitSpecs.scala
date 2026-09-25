package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._
import udacore.core.spec.shared.MemoryBundlesSpecs.{bndCacheMaintenance, bndTlbFlush}

/** CommitUnit: in-order retirement from the ROB head, precise traps, and the
  * commit-head system-operation sequencer (ADR-019 D-19.7/D-19.8; ADR-004;
  * ADR-010; ADR-012 commit broadcast concept).
  */
object CommitUnitSpecs {
  val contCommitUnit = spec {
    CONTRACT("CommitUnit")
      .desc(
        "Consumes the ROB head in program order. A done, exception-free head retires by firing " +
        "every commit-broadcast view it needs in one cycle: rRAT update and oldPrd free, SQ " +
        "head handoff for stores, FTQ block commit, CSR commit grant, and the retire token. A " +
        "head with an exception, a pending interrupt at a retire boundary, or a serializing " +
        "system operation is handed to the TrapController, which produces the architectural " +
        "redirect. CommitUnit also sequences fence, fence.i, and sfence.vma maintenance."
      )
      .has(
        intfRobHeadIn,
        intfRenameCommitOut,
        intfStoreCommitOut,
        intfFtqCommitOut,
        intfCommitGrantOut,
        intfExceptionOut,
        intfInterruptCtrlIn,
        intfRetireStreamOut,
        intfStoreBufferDrainReqOut,
        intfStoreBufferDrainRespIn,
        intfICacheInvalidateOut,
        intfDCacheCleanReqOut,
        intfDCacheCleanRespIn,
        intfSfenceVmaOut,
        intfRecoveryEventIn,
        intfHeadMemGrantOut,
        intfDebugReqIn,
        funcCommitHead,
        funcTrapHold,
        funcHeadMemGrant,
        funcPreciseTrapHandoff,
        funcInterruptSampling,
        funcBlockEndCommit,
        funcSystemOpSequencing,
        propCommitInOrder,
        propNoCommitPastException,
        propTrapHoldUntilRedirect,
        propRetireNonBlocking
      )
      .uses(paramCommitWidth, bndCommitBroadcast)
      .note(
        "Recovery stance: CommitUnit only ever handles the oldest live uop, which no " +
        "BranchMispredict can kill; an ArchRedirect it caused ends its current sequence."
      )
      .build()
  }

  val intfRobHeadIn = spec {
    INTERFACE("RobHeadIn")
      .desc("The ROB head entry; accepting it retires the entry or hands its trap to the TrapController.")
      .uses(bndRobHead)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRenameCommitOut = spec {
    INTERFACE("RenameCommitOut")
      .desc("RenameCommit view to RenameUnit (rRAT update, oldPrd free, checkpoint release).")
      .uses(bndCommitBroadcast)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreCommitOut = spec {
    INTERFACE("StoreCommitOut")
      .desc("StoreCommit view to the LoadStoreQueue for a retiring store; its transfer completes only with the SQ-to-StoreBuffer handoff.")
      .uses(bndCommitBroadcast)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfFtqCommitOut = spec {
    INTERFACE("FtqCommitOut")
      .desc("FtqCommit to the frontend FetchTargetQueue when a blockEnd uop retires.")
      .uses(bndFtqCommit)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCommitGrantOut = spec {
    INTERFACE("CommitGrantOut")
      .desc("Commit strobe {robTag, valid} qualifying the CSR write of the retiring CSR uop.")
      .uses(bndCommitBroadcast)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4.")
      .build()
  }

  val intfExceptionOut = spec {
    INTERFACE("ExceptionOut")
      .desc("Trap, interrupt, xRET, or Refetch hand-off to the TrapController.")
      .uses(bndException)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfInterruptCtrlIn = spec {
    INTERFACE("InterruptCtrlIn")
      .desc("Pending-and-enabled interrupt view from the CsrController.")
      .uses(bndInterruptCtrl)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 2 (derived from asynchronous lines) sampled only at a retire boundary.")
      .build()
  }

  val intfRetireStreamOut = spec {
    INTERFACE("RetireStreamOut")
      .desc("One verification retire token per retirement or trap entry (ADR-010), elaborated only when usingRvvi.")
      .uses(bndRetireToken)
      .is(rawReadyValidIntf)
      .note("Observation-only: ready is tied high by the harness; commit never waits on it (propRetireNonBlocking).")
      .build()
  }

  val intfStoreBufferDrainReqOut = spec {
    INTERFACE("StoreBufferDrainReqOut")
      .desc("Drain request to the StoreBuffer for FENCE, FENCE.I, and SFENCE.VMA.")
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreBufferDrainRespIn = spec {
    INTERFACE("StoreBufferDrainRespIn")
      .desc("Completes when every committed store older than the request has been written to the D-cache or bus.")
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheInvalidateOut = spec {
    INTERFACE("ICacheInvalidateOut")
      .desc("FENCE.I I-cache invalidate-all token; fires when the invalidate is durable.")
      .uses(bndCacheMaintenance)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheCleanReqOut = spec {
    INTERFACE("DCacheCleanReqOut")
      .desc("FENCE.I D-cache clean-all request (the I-side is not coherent with the D-cache).")
      .uses(bndCacheMaintenance)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheCleanRespIn = spec {
    INTERFACE("DCacheCleanRespIn")
      .desc("D-cache clean-all completion: every dirty line has been written back.")
      .uses(bndCacheMaintenance)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfSfenceVmaOut = spec {
    INTERFACE("SfenceVmaOut")
      .desc("SFENCE.VMA token to the PageTableWalker, which flushes both TLBs; fires when the flush is durable.")
      .uses(bndTlbFlush)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRecoveryEventIn = spec {
    INTERFACE("RecoveryEventIn")
      .desc("The common RecoveryEvent broadcast; CommitUnit uses it only to release the trap hold.")
      .uses(bndRecoveryEvent)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 6.")
      .build()
  }

  val intfHeadMemGrantOut = spec {
    INTERFACE("HeadMemGrantOut")
      .desc("Execution grant for an uncacheable memory uop at the ROB head, to the LoadStoreQueue.")
      .uses(bndHeadMemGrant)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDebugReqIn = spec {
    INTERFACE("DebugReqIn")
      .desc("Asynchronous debug request line, sampled only at a precise retire boundary like an interrupt.")
      .uses(udacore.core.spec.shared.CoreBundlesSpecs.bndDebugReq)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 2.")
      .build()
  }

  val funcCommitHead = spec {
    FUNCTION("CommitHead")
      .desc(
        "A done head with no exception, no pending interrupt, no sysOp, and no " +
        "predictionFault retires when all of its views are ready in the same cycle: " +
        "RenameCommit always; StoreCommit if isStore; FtqCommit if blockEnd; CommitGrant if a " +
        "CSR uop; the retire token when usingRvvi. Otherwise the head waits."
      )
      .uses(intfRobHeadIn, intfRenameCommitOut, intfStoreCommitOut, intfFtqCommitOut, intfCommitGrantOut)
      .build()
  }

  val funcTrapHold = spec {
    FUNCTION("TrapHold")
      .desc(
        "Sending any ExceptionOut token (Sync, Interrupt, or SysOp XRet/Refetch) sets " +
        "trapPending with the hand-off robTag. While trapPending, RobHeadIn.ready is low, no " +
        "commit-broadcast view fires, no interrupt is sampled, and no further ExceptionOut is " +
        "sent. trapPending clears only in the cycle a RecoveryEvent of kind ArchRedirect with " +
        "that robTag is observed. The TrapController may take any number of cycles."
      )
      .uses(intfExceptionOut, intfRobHeadIn, intfRecoveryEventIn)
      .build()
  }

  val funcHeadMemGrant = spec {
    FUNCTION("HeadMemGrant")
      .desc(
        "When the head entry is not done and is marked headExecute (an uncacheable load or " +
        "store), and no trap hold is active, send HeadMemGrant{robTag} once and set " +
        "grantInFlight. While grantInFlight, no interrupt or debug request is sampled in front " +
        "of that head, so the single bus access it performs is never killed and replayed. The " +
        "head then completes normally (done, possibly with an exception) and retires through " +
        "the ordinary atomic commit, or traps precisely; grantInFlight clears at that retirement " +
        "or trap hand-off."
      )
      .uses(intfHeadMemGrantOut, intfRobHeadIn)
      .note(
        "The commit broadcast is untouched: a StoreCommit only ever fires for a store that " +
        "retires in that cycle. For an uncacheable store it releases the SQ entry, which was " +
        "already performed, without a StoreBuffer handoff (LSQ funcStoreCommitHandoff)."
      )
      .build()
  }

  val funcPreciseTrapHandoff = spec {
    FUNCTION("PreciseTrapHandoff")
      .desc(
        "A head whose entry carries an exception is not retired: CommitUnit accepts it as a " +
        "hand-off (the ROB locks its head), sends Exception{Sync, cause, tval, pc, robTag, " +
        "ftqIdx}, emits a trap retire token, and holds (funcTrapHold). No younger uop has " +
        "updated architectural state, so the trap is precise; the TrapController's " +
        "ArchRedirect then discards the whole window."
      )
      .uses(intfExceptionOut, intfRetireStreamOut)
      .build()
  }

  val funcInterruptSampling = spec {
    FUNCTION("InterruptSampling")
      .desc(
        "At a retire boundary (before offering the next head for retirement), if a debug " +
        "request is pending (debug mode not active), or an enabled interrupt is pending for " +
        "the current privilege (mip & mie, mideleg, MIE/SIE, priv) and not in debug mode, send " +
        "Exception{Debug | Interrupt, cause, pc = head pc, robTag = head} without accepting the " +
        "head from the ROB, then hold (funcTrapHold); the head is killed by the ArchRedirect and " +
        "re-executes after the handler returns. Debug has priority over interrupts. Never " +
        "sampled during a trap hold, a serialization sequence, or while a HeadMemGrant is in " +
        "flight (funcHeadMemGrant)."
      )
      .uses(intfInterruptCtrlIn, intfDebugReqIn, intfExceptionOut)
      .build()
  }

  val funcBlockEndCommit = spec {
    FUNCTION("BlockEndCommit")
      .desc(
        "When a blockEnd uop retires, send FtqCommit{ftqIdx, exit}, where exit is the uop's " +
        "cfiOutcome if it is a taken control-flow uop and cfiType None otherwise."
      )
      .uses(intfFtqCommitOut)
      .build()
  }

  val funcSystemOpSequencing = spec {
    FUNCTION("SystemOpSequencing")
      .desc(
        "For a head with sysOp: FENCE drains the StoreBuffer, then retires. FENCE.I drains the " +
        "StoreBuffer, cleans the D-cache, invalidates the I-cache, retires, and sends " +
        "Exception{SysOp, Refetch}. SFENCE.VMA drains the StoreBuffer, sends the TLB flush, " +
        "retires, and sends Refetch. A retiring CSR write, and a predictionFault uop, retire " +
        "and send Refetch. MRET/SRET retire and send XRet. WFI retires and waits for a pending " +
        "interrupt (or debug request) before the next head is offered."
      )
      .uses(intfStoreBufferDrainReqOut, intfStoreBufferDrainRespIn, intfICacheInvalidateOut,
            intfDCacheCleanReqOut, intfDCacheCleanRespIn, intfSfenceVmaOut, intfExceptionOut)
      .note("SFENCE.VMA drains committed stores first so a page-table store is visible to the next walk; v0 flushes every TLB entry for every encoding (ADR-019 D-19.6).")
      .build()
  }

  val propCommitInOrder = spec {
    PROPERTY("CommitInOrder")
      .desc(
        "rRAT updates, prd frees, CSR writes, store handoffs to the StoreBuffer, and FTQ " +
        "releases happen only for the ROB head, in robTag order, one uop per cycle (v0)."
      )
      .uses(intfRobHeadIn)
      .note("Simulation assert.")
      .build()
  }

  val propNoCommitPastException = spec {
    PROPERTY("NoCommitPastException")
      .desc(
        "No uop younger than a head with an exception ever fires a commit-broadcast view; the " +
        "next event after the exception hand-off is the ArchRedirect RecoveryEvent that names it."
      )
      .uses(funcPreciseTrapHandoff)
      .build()
  }

  val propTrapHoldUntilRedirect = spec {
    PROPERTY("TrapHoldUntilRedirect")
      .desc(
        "Between an ExceptionOut transfer and the ArchRedirect RecoveryEvent that names its " +
        "robTag, RobHeadIn never transfers and no commit-broadcast view (RenameCommit, " +
        "StoreCommit, FtqCommit, CommitGrant) fires, regardless of TrapController latency."
      )
      .uses(funcTrapHold)
      .note("Simulation assert.")
      .build()
  }

  val propRetireNonBlocking = spec {
    PROPERTY("RetireNonBlocking")
      .desc("Whenever the retire token is valid its sink is ready, so commit progress never depends on the retire port.")
      .uses(intfRetireStreamOut)
      .note("ADR-010 D-10.2; simulation assert.")
      .build()
  }
}
