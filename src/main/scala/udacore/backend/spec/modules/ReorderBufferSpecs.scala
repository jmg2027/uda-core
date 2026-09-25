package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._

/** ReorderBuffer: explicit, data-less ROB (ADR-019 D-19.7; supersedes ADR-002). */
object ReorderBufferSpecs {
  val contReorderBuffer = spec {
    CONTRACT("ReorderBuffer")
      .desc(
        "A circular buffer of RobDepth entries indexed by robTag.idx. Entries are allocated in " +
        "program order by RenameUnit, completed out of order by PublishMux, and offered to " +
        "CommitUnit strictly from the head. The ROB stores ordering and precise-state metadata " +
        "(bndRobEntry), never register values: results live in the unified PRF."
      )
      .has(
        intfRobAllocIn,
        intfRobCompletionIn,
        intfRobHeadOut,
        intfRobStatusOut,
        intfRecoveryEventIn,
        funcRobAllocate,
        funcRobComplete,
        funcRobHeadOffer,
        funcRobRecovery,
        propRobRetireInOrder,
        propOlderSurvivesRecovery,
        propRobCompletionTargetsLive
      )
      .uses(paramRobDepth, paramRobTagWidth, funcRobOlder, propRobTagUniqueness)
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance. Ordering: robTag (funcRobOlder). Live: from allocation " +
        "until retirement through RobHeadOut or a RecoveryEvent kill. Recovery: " +
        "funcRecoveryKills per entry; BranchMispredict keeps the recovering branch and every " +
        "older entry. Survives: all older entries with their done/exception/outcome state. " +
        "Reclaim: the tail rewinds to e.robTag + 1 (ArchRedirect also moves the head there)."
      )
      .build()
  }

  val intfRobAllocIn = spec {
    INTERFACE("RobAllocIn")
      .desc("Program-order allocation tokens from RenameUnit; ready is low when all RobDepth entries are live.")
      .uses(bndRenameAllocation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRobCompletionIn = spec {
    INTERFACE("RobCompletionIn")
      .desc("Out-of-order completions from PublishMux (one per cycle, v0), keyed by robTag.")
      .uses(bndRobCompletion)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRobHeadOut = spec {
    INTERFACE("RobHeadOut")
      .desc("The head entry, offered to CommitUnit; the transfer is the retirement (or the trap hand-off) of that entry.")
      .uses(bndRobHead)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRobStatusOut = spec {
    INTERFACE("RobStatusOut")
      .desc("Every-cycle {empty, headTag} view to RenameUnit and the LoadStoreQueue.")
      .uses(bndRobStatus)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4.")
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

  val funcRobAllocate = spec {
    FUNCTION("RobAllocate")
      .desc(
        "Write the entry at robTag.idx from the RenameAllocation: valid, done = false (true " +
        "for uops that need no execution, e.g. a decode-time exception or FENCE), pc, insn, " +
        "archRd, newPrd, oldPrd, exception from decode, isCfi, checkpointId, ftqIdx, blockEnd, " +
        "isLoad, isStore, serialize, sysOp, predictionFault. The allocation robTag must equal the tail."
      )
      .uses(intfRobAllocIn)
      .build()
  }

  val funcRobComplete = spec {
    FUNCTION("RobComplete")
      .desc(
        "Set done for the named entry and record its exception, for a control-flow uop its " +
        "resolved cfiOutcome. A completion with headExecute = 1 does not set done: it marks the " +
        "entry headExecute so CommitUnit can grant it at the head; the later completion " +
        "(headExecute = 0) sets done. Completion order is unconstrained (OoO completion)."
      )
      .uses(intfRobCompletionIn)
      .build()
  }

  val funcRobHeadOffer = spec {
    FUNCTION("RobHeadOffer")
      .desc(
        "RobHeadOut.valid = head.valid && (head.done || head.headExecute): valid presents the " +
        "head, only the transfer (valid && ready) dequeues it. A head with headExecute && !done " +
        "is presented so the CommitUnit can observe it and grant its execution, but the " +
        "CommitUnit holds ready low until the LSQ completion sets done (and clears headExecute). " +
        "The transfer of a done, exception-free entry is its retirement: the head advances by one. " +
        "The transfer of an entry carrying an exception is a trap hand-off, not a retirement: " +
        "the head does NOT advance and the ROB offers nothing further (headLocked) until the " +
        "ArchRedirect RecoveryEvent naming that robTag empties the window (funcRobRecovery)."
      )
      .uses(intfRobHeadOut)
      .build()
  }

  val funcRobRecovery = spec {
    FUNCTION("RobRecovery")
      .desc(
        "BranchMispredict: invalidate every entry killed by funcRecoveryKills, set the tail to " +
        "e.robTag + 1, and set blockEnd on entry e.robTag (its fetch block now ends there). " +
        "ArchRedirect: invalidate every entry and set head = tail = e.robTag + 1."
      )
      .uses(intfRecoveryEventIn, funcRecoveryKills)
      .build()
  }

  val propRobRetireInOrder = spec {
    PROPERTY("RobRetireInOrder")
      .desc(
        "Every entry leaves the ROB exactly once, either by retirement through RobHeadOut " +
        "while it is the head, or by a RecoveryEvent kill. Retired entries leave in robTag " +
        "order; a live robTag is skipped only when an ArchRedirect kills it (a trapping head, " +
        "or the head in front of which an interrupt is taken). No RobHeadOut transfer occurs " +
        "while headLocked."
      )
      .uses(intfRobHeadOut)
      .note("Simulation assert.")
      .build()
  }

  val propOlderSurvivesRecovery = spec {
    PROPERTY("OlderSurvivesRecovery")
      .desc(
        "A BranchMispredict RecoveryEvent never invalidates, and never changes the done, " +
        "exception, or outcome state of, the recovering branch or any entry older than it. " +
        "In particular a long-latency uop (e.g. DIV) older than a mispredicted branch still " +
        "completes and retires."
      )
      .uses(funcRobRecovery, funcRobOlder)
      .note("Simulation assert plus the ADR-019 directed case (older DIV, younger mispredicted branch).")
      .build()
  }

  val propRobCompletionTargetsLive = spec {
    PROPERTY("RobCompletionTargetsLive")
      .desc(
        "Every RobCompletion names a live, not-yet-done entry: killed uops never complete, so " +
        "a tag reallocated after a recovery can never receive a stale completion."
      )
      .uses(intfRobCompletionIn, funcRecoveryKills)
      .note("Simulation assert; its violation means some speculative holder skipped funcRecoveryKills.")
      .build()
  }
}
