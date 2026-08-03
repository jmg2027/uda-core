package udacore.memorysubsystem.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.memorysubsystem.spec.shared.MemorySubsystemBundlesSpecs._
import udacore.memorysubsystem.spec.shared.MemorySubsystemParamsSpecs.paramStoreBufferDepth
import udacore.backend.spec.shared.BackendBundlesSpecs.bndCommitBroadcast
import udacore.backend.spec.shared.BackendParamsSpecs.funcSeqOlder
import udacore.core.spec.shared.CoreBundlesSpecs.bndGlobalEpoch

/** StoreBuffer (ADR-003 M1/M2, D-3.1..D-3.10).
  *
  * The correctness boundary between UDA epoch speculation and the irreversible
  * side effect of a memory write. It converts speculative, epoch-tagged store
  * tokens into a strictly in-order, commit-gated, irrevocable write stream.
  */
object StoreBufferSpecs {

  // CONTRACT ---------------------------------------------------------------
  val contStoreBuffer = spec {
    CONTRACT("StoreBuffer")
      .desc(
        "Age-ordered (seqTag-ordered) commit-gated holding buffer that converts speculative, epoch-tagged store tokens into an in-order irrevocable write stream; the correctness boundary between UDA speculation and memory side effects."
      )
      .has(
        intfStoreDispatchIn,
        intfStoreCommitIn,
        intfLoadFwdQuery,
        intfLoadFwdData,
        intfControllerWriteOut,
        intfControllerWriteAckIn,
        intfStoreCompleteOut,
        intfGlobalEpochIn
      )
      .uses(paramStoreBufferDepth)
      .note("ADR-003 D-3.2: a speculative (committed=false) entry NEVER issues a controller write.")
      .note("ADR-003 D-3.4: only the HEAD, when committed=true, drains to the controller, in seqTag order.")
      .note(
        "ADR-003 D-3.5: epoch mismatch invalidates uncommitted entries only; committed entries are irrevocable and drain regardless of the current global epoch."
      )
      .build()
  }

  // INTERFACES -------------------------------------------------------------
  val intfStoreDispatchIn = spec {
    INTERFACE("StoreDispatchIn")
      .desc("Shaped speculative store token entering the buffer from the StoreUnit.")
      .uses(bndStoreMemoryReq)
      .is(rawReadyValidIntf)
      .build()
  }

  // ADR-003 D-3.3 / ADR-012 D-12.4: consumes the unified commit broadcast StoreCommit view.
  val intfStoreCommitIn = spec {
    INTERFACE("StoreCommitIn")
      .desc("Commit token that marks a buffered store irrevocable, keyed by canonical seqTag.")
      .uses(bndCommitBroadcast)
      .is(rawReadyValidIntf)
      .note(
        "ADR-003 D-3.3: consumes WP-A bndCommitBroadcast StoreCommit projected view {seqTag, epoch}; commit is in-order so tokens arrive in seqTag order."
      )
      .build()
  }

  val intfLoadFwdQuery = spec {
    INTERFACE("LoadFwdQuery")
      .desc("Load address/size/age probe from the LoadUnit for store-to-load forwarding.")
      .uses(bndLoadFwdQuery)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfLoadFwdData = spec {
    INTERFACE("LoadFwdData")
      .desc("Per-byte forwarded store bytes plus coverage mask and full-hit flag.")
      .uses(bndLoadFwdData)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfControllerWriteOut = spec {
    INTERFACE("ControllerWriteOut")
      .desc("Committed HEAD write request to the MemoryController.")
      .uses(bndControllerReq)
      .is(rawReadyValidIntf)
      .build()
  }

  // ADR-003 D-3.4/D-3.6: the MemoryController write acknowledgement that frees the HEAD.
  val intfControllerWriteAckIn = spec {
    INTERFACE("ControllerWriteAckIn")
      .desc("Write acknowledgement from the MemoryController for the drained committed HEAD store.")
      .uses(bndWriteAck)
      .is(rawReadyValidIntf)
      .note(
        "The HEAD entry frees and StoreComplete{seqTag, fault} is emitted only on this ack; the StoreComplete fault field is sourced from the ack (ADR-003 D-3.4/D-3.6)."
      )
      .build()
  }

  val intfStoreCompleteOut = spec {
    INTERFACE("StoreCompleteOut")
      .desc("Post-ack store retirement/fault token to the ResponseArbiter (no data).")
      .uses(bndStoreComplete)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfGlobalEpochIn = spec {
    INTERFACE("GlobalEpochIn")
      .desc("Global epoch broadcast used to kill uncommitted entries.")
      .uses(bndGlobalEpoch)
      .is(rawNoDecoupled)
      .build()
  }

  // FUNCTIONS --------------------------------------------------------------
  val funcCommitDrain = spec {
    FUNCTION("CommitDrain")
      .desc(
        "Only the buffer HEAD, and only when committed=true, may issue a controller write; on the MemoryController write-ack the head frees, advances, and emits StoreComplete{seqTag, fault} with the fault sourced from the ack (ADR-003 D-3.4/D-3.6)."
      )
      .uses(intfStoreCommitIn, intfControllerWriteOut, intfControllerWriteAckIn, intfStoreCompleteOut)
      .note("Writes reach memory strictly in program (seqTag) order, one at a time, after retirement.")
      .build()
  }

  val funcEpochKill = spec {
    FUNCTION("EpochKill")
      .desc(
        "On entry.epoch =/= globalEpoch every speculative (uncommitted) entry is invalidated and squeezed out of the age order (ADR-003 D-3.5)."
      )
      .uses(intfGlobalEpochIn)
      .note(
        "Committed entries are irrevocable and exempt: they never change state on epoch kill and drain regardless of the current global epoch."
      )
      .build()
  }

  val funcStoreForward = spec {
    FUNCTION("StoreForward")
      .desc(
        "Answers a LoadFwdQuery with per-byte forwarded data computed from all buffered stores with entry.seqTag < load.seqTag (older only, youngest-per-byte wins, same word), returning {data, mask, hit} (ADR-003 D-3.9)."
      )
      .uses(intfLoadFwdQuery, intfLoadFwdData, funcSeqOlder)
      .note("Age comparison uses the canonical wrap-aware seqOlder compare over the bounded live seqTag range (ADR-012 D-12.3).")
      .build()
  }

  // PROPERTIES -------------------------------------------------------------
  val propNoSpeculativeWrite = spec {
    PROPERTY("NoSpeculativeWrite")
      .desc(
        "A controller write request never fires for a source entry whose committed bit is false; the single load-bearing invariant of the subsystem (ADR-003 D-3.2)."
      )
      .uses(intfControllerWriteOut)
      .note("Paired with an @LocalSpec design assert !(ctrlWrite.fire && !entry.committed) (ADR-015 D-15.3).")
      .build()
  }

  val propInOrderDrain = spec {
    PROPERTY("InOrderDrain")
      .desc(
        "Successive committed writes leave the buffer in monotonically non-decreasing seqTag order (wrap-aware); the head drains one at a time (ADR-003 D-3.4)."
      )
      .uses(intfControllerWriteOut)
      .note("Paired with an @LocalSpec design assert on write-order monotonicity.")
      .build()
  }

  val propCommittedSurvivesKill = spec {
    PROPERTY("CommittedSurvivesKill")
      .desc(
        "An epoch kill never clears or changes the state of a committed entry; committed stores are irrevocable and epoch-independent (ADR-003 D-3.5)."
      )
      .uses(intfGlobalEpochIn)
      .note("Paired with an @LocalSpec design assert that no committed entry changes state on epoch mismatch.")
      .build()
  }

  val propLoadForwardExact = spec {
    PROPERTY("LoadForwardExact")
      .desc(
        "A load forwards a byte only from an OLDER store (wrap-aware entry.seqTag < load.seqTag) at the same word; the youngest older store wins per byte, and full byte-mask coverage suppresses the controller read (ADR-003 D-3.9, ADR-012 D-12.3)."
      )
      .uses(intfLoadFwdQuery, intfLoadFwdData, funcSeqOlder)
      .note("Paired with an @LocalSpec design assert that every forwarded byte source satisfies the wrap-aware seqOlder(entry.seqTag, load.seqTag) age compare (ADR-012 D-12.3).")
      .build()
  }

  val propStoreBufferLiveness = spec {
    PROPERTY("StoreBufferLiveness")
      .desc(
        "The external bus eventually accepts a committed write, so a full buffer always drains, so commit always advances; no deadlock under a full buffer with a slow bus (ADR-003 verification obligations, critique m3/V-MI-2)."
      )
      .uses(intfControllerWriteOut, intfStoreCompleteOut)
      .note("Paired with a directed backpressure test (full buffer + slow bus) and a rank function on committed-head occupancy.")
      .build()
  }

  val propCommittedEntryEpochExempt = spec {
    PROPERTY("CommittedEntryEpochExempt")
      .desc(
        "A committed store-buffer entry is the enumerated epoch-exempt vertex of ADR-005 D-5.2: it holds an epoch but never uses it for a correctness compare and contributes 0 to maxSurvivableGenerations."
      )
      .uses(propEpochVertexEnumeration, intfGlobalEpochIn)
      .note("Machine check 5 (propRegQueueTagged) tags the committed-entry Reg as epoch-exempt against the enumeration table.")
      .build()
  }
}
