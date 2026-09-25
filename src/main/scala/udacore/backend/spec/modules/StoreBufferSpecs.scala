package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs.paramStoreBufferDepth
import udacore.core.spec.shared.MemoryBundlesSpecs.{bndStoreDrainReq, bndStoreDrainResp}

/** StoreBuffer: committed store-drain buffer below the SQ (ADR-003 as amended
  * by ADR-019 D-19.12).
  *
  * It never holds a speculative store: the LoadStoreQueue owns speculative
  * store ordering, and a store enters here only in its commit cycle.
  */
object StoreBufferSpecs {
  val contStoreBuffer = spec {
    CONTRACT("StoreBuffer")
      .desc(
        "A FIFO of StoreBufferDepth committed stores. Each entry is irrevocable from the cycle " +
        "it is accepted; the head drains to the D-cache (or, for a non-cacheable address, " +
        "around the array to the bus) in commit order, one at a time. It answers " +
        "physical-address forwarding queries from the LSQ and completes drain requests from " +
        "the CommitUnit when empty."
      )
      .has(
        intfCommittedStoreIn,
        intfStoreDrainReqOut,
        intfStoreDrainRespIn,
        intfStoreForwardQueryIn,
        intfStoreForwardDataOut,
        intfStoreBufferDrainReqIn,
        intfStoreBufferDrainRespOut,
        funcCommitOrderDrain,
        funcCommittedForward,
        funcDrainFence,
        propInOrderDrain,
        propCommittedSurvivesRecovery,
        propStoreBufferLiveness
      )
      .uses(paramStoreBufferDepth)
      .note(
        "Recovery stance: recovery-exempt. Every entry is committed architectural state; no " +
        "RecoveryEvent of either kind changes it, and the vertex has no RecoveryEvent input."
      )
      .build()
  }

  val intfCommittedStoreIn = spec {
    INTERFACE("CommittedStoreIn")
      .desc("Committed stores from the LSQ SQ head; ready is low when full (backpressuring store commit).")
      .uses(bndCommittedStore)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreDrainReqOut = spec {
    INTERFACE("StoreDrainReqOut")
      .desc("Head store write into the CoreTop-level DataCache (physical address).")
      .uses(bndStoreDrainReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreDrainRespIn = spec {
    INTERFACE("StoreDrainRespIn")
      .desc("Completion of the head store's write; frees the head.")
      .uses(bndStoreDrainResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreForwardQueryIn = spec {
    INTERFACE("StoreForwardQueryIn")
      .desc("Physical-address forwarding probes from the LSQ.")
      .uses(bndStoreForwardQuery)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreForwardDataOut = spec {
    INTERFACE("StoreForwardDataOut")
      .desc("Forwarding answers to the LSQ, one per query, in query order.")
      .uses(bndStoreForwardData)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreBufferDrainReqIn = spec {
    INTERFACE("StoreBufferDrainReqIn")
      .desc("Drain request from the CommitUnit (FENCE, FENCE.I, SFENCE.VMA).")
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreBufferDrainRespOut = spec {
    INTERFACE("StoreBufferDrainRespOut")
      .desc("Drain completion to the CommitUnit, with the accessFault of an uncacheable head store.")
      .uses(bndStoreDrainResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcCommitOrderDrain = spec {
    FUNCTION("CommitOrderDrain")
      .desc(
        "Offer the head entry as StoreDrainReq; free it only on its StoreDrainResp. Entries " +
        "leave strictly in acceptance (commit) order, one outstanding drain at a time in v0."
      )
      .uses(intfStoreDrainReqOut, intfStoreDrainRespIn)
      .build()
  }

  val funcCommittedForward = spec {
    FUNCTION("CommittedForward")
      .desc(
        "For a query {paddr, mask}, return per byte the data of the youngest entry that writes " +
        "that byte (hitMask), and partial = true when some queried byte is covered by an " +
        "entry whose other bytes make an exact merge impossible for the v0 forwarding network " +
        "(e.g. a different aligned word overlapping the access)."
      )
      .uses(intfStoreForwardQueryIn, intfStoreForwardDataOut)
      .note("Every StoreBuffer entry is older than every live load, so no age compare is needed; only buffer order matters.")
      .build()
  }

  val funcDrainFence = spec {
    FUNCTION("DrainFence")
      .desc(
        "Accept a StoreBufferDrainReq and answer StoreBufferDrainResp once every entry present " +
        "at acceptance has drained (no new store can arrive meanwhile: commit is waiting). The " +
        "response carries accessFault = 1 iff the last drained entry was an uncacheable store " +
        "whose bus write was denied; that is how the head uncacheable store's fault becomes " +
        "precise (CommitUnit funcUncacheableStoreAtHead)."
      )
      .uses(intfStoreBufferDrainReqIn, intfStoreBufferDrainRespOut)
      .build()
  }

  val propInOrderDrain = spec {
    PROPERTY("InOrderDrain")
      .desc("Committed stores reach the D-cache or bus in exactly the order they were committed, with no duplication or omission.")
      .uses(funcCommitOrderDrain)
      .note("Simulation assert.")
      .build()
  }

  val propCommittedSurvivesRecovery = spec {
    PROPERTY("CommittedSurvivesRecovery")
      .desc("No RecoveryEvent (BranchMispredict or ArchRedirect) removes or alters a StoreBuffer entry.")
      .note("Structural: the vertex has no RecoveryEvent input; simulation assert on occupancy across events.")
      .build()
  }

  val propStoreBufferLiveness = spec {
    PROPERTY("StoreBufferLiveness")
      .desc("If the D-cache eventually accepts every drain, a full buffer always drains, so store commit always progresses.")
      .uses(funcCommitOrderDrain)
      .note("Directed backpressure test: full buffer plus slow bus.")
      .build()
  }
}
