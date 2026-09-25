package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._
import udacore.core.spec.shared.MemoryBundlesSpecs._

/** LoadStoreQueue: owner of speculative memory ordering (ADR-019 D-19.5,
  * D-19.12; amends ADR-003).
  */
object LoadStoreQueueSpecs {
  val contLoadStoreQueue = spec {
    CONTRACT("LoadStoreQueue")
      .desc(
        "An LQ of LoadQueueDepth and an SQ of StoreQueueDepth entries, allocated in program " +
        "order at rename. It receives virtual addresses from the AGU, translates them through " +
        "the DTLB (loads in parallel with the VIPT D-cache lookup, stores translation-only), " +
        "keeps each entry's physical address as the authoritative ordering key, forwards " +
        "older store data to younger loads, blocks loads behind older stores with unresolved " +
        "addresses, completes loads and stores into the PRF/ROB, and hands each committed " +
        "store to the StoreBuffer. Speculative stores never leave the SQ before commit."
      )
      .has(
        intfLsqAllocIn,
        intfMemAddressIn,
        intfDtlbReqOut,
        intfDtlbStoreRespIn,
        intfDtlbRefillIn,
        intfDCacheLoadReqOut,
        intfDCacheLoadRespIn,
        intfStoreForwardQueryOut,
        intfStoreForwardDataIn,
        intfMemResultOut,
        intfStoreCommitIn,
        intfCommittedStoreOut,
        intfRobStatusIn,
        intfRecoveryEventIn,
        funcLsqAllocate,
        funcAddressCapture,
        funcTranslationWait,
        funcConservativeDisambig,
        funcLoadIssue,
        funcStoreToLoadForward,
        funcLoadComplete,
        funcStoreComplete,
        funcUncacheableAtHead,
        funcStoreCommitHandoff,
        funcLsqRecovery,
        propPhysicalOrderingAuthority,
        propNoPassUnresolvedStore,
        propNoSpeculativeStoreVisible,
        propWrongPathLoadNoResult,
        propLsqRecoveryKeepsOlder
      )
      .uses(paramLoadQueueDepth, paramStoreQueueDepth, funcRobOlder, bndLoadQueueEntry, bndStoreQueueEntry)
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance. Ordering: LQ and SQ are circular FIFOs in program order; " +
        "cross-queue age is decided by robTag (funcRobOlder). Live: from allocation until " +
        "completion-and-commit (SQ: handoff to the StoreBuffer; LQ: retirement of the load, " +
        "observed through RobStatus.headTag). Recovery: funcRecoveryKills per entry; killed " +
        "entries are removed and each queue tail rewinds to its oldest killed entry. In-flight " +
        "D-cache responses of killed or reallocated LQ entries are dropped by the per-entry " +
        "allocation generation (propGenerationTagScope). Survives: every older entry with its " +
        "address, data, and state."
      )
      .note(
        "Memory model (OQ-D): single hart, non-coherent v0, no other agent writes cacheable " +
        "memory, so load-load reordering between different or equal addresses is unobservable " +
        "and no load-load ordering check exists. There is no memory-order-violation replay: " +
        "the conservative disambiguation below makes it unnecessary (ADR-019 D-19.5)."
      )
      .build()
  }

  val intfLsqAllocIn = spec {
    INTERFACE("LsqAllocIn")
      .desc("Program-order allocations from RenameUnit; ready is low when the target queue is full.")
      .uses(bndLsqAllocation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMemAddressIn = spec {
    INTERFACE("MemAddressIn")
      .desc("Effective virtual addresses and store data from the AGU, matched to entries by robTag.")
      .uses(bndMemAddress)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbReqOut = spec {
    INTERFACE("DtlbReqOut")
      .desc("Translation requests (access = Load or Store, reqId = queue, index, generation) to the CoreTop-level DataTlb.")
      .uses(bndTranslateReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbStoreRespIn = spec {
    INTERFACE("DtlbStoreRespIn")
      .desc("Translation answers for store-address requests (loads receive theirs through the D-cache response).")
      .uses(bndTranslation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbRefillIn = spec {
    INTERFACE("DtlbRefillIn")
      .desc("Refill notices {vpn} from the DataTlb when a walk it started completes; wakes translation-pending entries. Always ready.")
      .uses(bndWalkResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheLoadReqOut = spec {
    INTERFACE("DCacheLoadReqOut")
      .desc("Load lookups to the VIPT DataCache, fired in the same cycle as the load's DtlbReqOut.")
      .uses(bndDCacheLoadReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheLoadRespIn = spec {
    INTERFACE("DCacheLoadRespIn")
      .desc("Load answers (possibly out of order), reassociated by lqIdx and lqGen.")
      .uses(bndDCacheLoadResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreForwardQueryOut = spec {
    INTERFACE("StoreForwardQueryOut")
      .desc("Physical-address forwarding probe into the StoreBuffer for a load whose D-cache answer arrived.")
      .uses(bndStoreForwardQuery)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreForwardDataIn = spec {
    INTERFACE("StoreForwardDataIn")
      .desc("Committed-store forwarding answer from the StoreBuffer.")
      .uses(bndStoreForwardData)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMemResultOut = spec {
    INTERFACE("MemResultOut")
      .desc("Load values and memory completions/exceptions toward PublishMux.")
      .uses(bndMemResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreCommitIn = spec {
    INTERFACE("StoreCommitIn")
      .desc("StoreCommit view from the CommitUnit for the retiring store, which is always the SQ head.")
      .uses(bndCommitBroadcast)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCommittedStoreOut = spec {
    INTERFACE("CommittedStoreOut")
      .desc("The committed SQ head handed to the StoreBuffer; fires in the same cycle as StoreCommitIn.")
      .uses(bndCommittedStore)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRobStatusIn = spec {
    INTERFACE("RobStatusIn")
      .desc("ROB head position (uncacheable-load gate and LQ release).")
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

  val funcLsqAllocate = spec {
    FUNCTION("LsqAllocate")
      .desc(
        "Append a load at the LQ tail (incrementing that entry's generation) or a store at the " +
        "SQ tail, in state AddrPending, with robTag, size, signedness, and destination."
      )
      .uses(intfLsqAllocIn)
      .build()
  }

  val funcAddressCapture = spec {
    FUNCTION("AddressCapture")
      .desc(
        "On MemAddress for robTag: record vaddr (and store data and byte mask). A misaligned " +
        "access moves to Faulted with the address-misaligned cause. A store then issues a " +
        "translation-only DtlbReq; a load becomes Ready for funcLoadIssue."
      )
      .uses(intfMemAddressIn, intfDtlbReqOut)
      .build()
  }

  val funcTranslationWait = spec {
    FUNCTION("TranslationWait")
      .desc(
        "A Miss answer (from DtlbStoreRespIn for a store, or status TlbMiss in the D-cache " +
        "answer for a load) moves only that entry to TranslationPending with its VPN; every " +
        "other entry continues. A DtlbRefill whose vpn matches returns the entry to Ready " +
        "(load) or re-issues the translation (store). A PageFault or AccessFault answer moves " +
        "the entry to Faulted with the page-fault or access-fault cause of its access type."
      )
      .uses(intfDtlbStoreRespIn, intfDtlbRefillIn, intfDCacheLoadRespIn)
      .note("ADR-019 D-19.5: a DTLB miss blocks only the affected memory uop.")
      .build()
  }

  val funcConservativeDisambig = spec {
    FUNCTION("ConservativeDisambig")
      .desc(
        "A load may issue only when every older SQ entry (funcRobOlder on robTag) has a " +
        "resolved physical address or is Faulted. Virtual-address comparison may be used only " +
        "as an early hint and never creates a forwarding or ordering effect by itself."
      )
      .uses(funcRobOlder)
      .note("ADR-019 D-19.5 base policy; speculative disambiguation needs a later ADR.")
      .build()
  }

  val funcLoadIssue = spec {
    FUNCTION("LoadIssue")
      .desc(
        "Select the oldest Ready load permitted by funcConservativeDisambig and fire " +
        "DtlbReqOut{Load} and DCacheLoadReqOut in the same cycle (VIPT parallel lookup), " +
        "moving it to Issued."
      )
      .uses(intfDtlbReqOut, intfDCacheLoadReqOut, funcConservativeDisambig)
      .build()
  }

  val funcStoreToLoadForward = spec {
    FUNCTION("StoreToLoadForward")
      .desc(
        "When a load's D-cache answer carries its paddr, compare it with every older SQ entry " +
        "and query the StoreBuffer. Each load byte takes the youngest older SQ store covering " +
        "it, else the youngest committed StoreBuffer store covering it, else the cache data. " +
        "If an older overlapping SQ store has no data yet, the load waits (WaitStoreData) and " +
        "re-issues when the data arrives; if the StoreBuffer reports partial, the load waits " +
        "(WaitStoreDrain) and re-issues after the overlapping store drains."
      )
      .uses(intfStoreForwardQueryOut, intfStoreForwardDataIn, funcRobOlder)
      .build()
  }

  val funcLoadComplete = spec {
    FUNCTION("LoadComplete")
      .desc(
        "A load with merged data (Data status plus forwarding) completes with a sign- or " +
        "zero-extended value on MemResultOut; a Faulted load completes with its exception and " +
        "no register write; a Replay status returns the load to Ready."
      )
      .uses(intfMemResultOut)
      .build()
  }

  val funcStoreComplete = spec {
    FUNCTION("StoreComplete")
      .desc(
        "A store whose paddr and data are both known, or that is Faulted, completes on " +
        "MemResultOut (no register write) so the ROB marks it done; it stays in the SQ until commit."
      )
      .uses(intfMemResultOut)
      .build()
  }

  val funcUncacheableAtHead = spec {
    FUNCTION("UncacheableAtHead")
      .desc(
        "A load whose translation reports a non-cacheable page (status Uncacheable) is not " +
        "performed speculatively: it waits until RobStatus.headTag equals its robTag and then " +
        "re-issues with uncached set. Stores to non-cacheable pages need no gate: they only " +
        "leave the SQ after commit."
      )
      .uses(intfRobStatusIn, intfDCacheLoadReqOut)
      .build()
  }

  val funcStoreCommitHandoff = spec {
    FUNCTION("StoreCommitHandoff")
      .desc(
        "On StoreCommit for the SQ head, fire CommittedStoreOut{paddr, data, mask, uncacheable} " +
        "in the same cycle and release the head. The handoff never occurs for a store that is " +
        "not the ROB head's store."
      )
      .uses(intfStoreCommitIn, intfCommittedStoreOut)
      .build()
  }

  val funcLsqRecovery = spec {
    FUNCTION("LsqRecovery")
      .desc(
        "On a RecoveryEvent, remove every LQ and SQ entry that funcRecoveryKills selects and " +
        "drop every unaccepted output token belonging to one; rewind each tail to its oldest " +
        "removed entry. An in-flight D-cache answer for a removed entry is dropped on arrival " +
        "because its lqGen no longer matches."
      )
      .uses(intfRecoveryEventIn, funcRecoveryKills)
      .build()
  }

  val propPhysicalOrderingAuthority = spec {
    PROPERTY("PhysicalOrderingAuthority")
      .desc("Every forwarding decision and every load-behind-store ordering decision is made on resolved physical addresses; no load value is taken from a store matched only by virtual address.")
      .uses(funcStoreToLoadForward, funcConservativeDisambig)
      .build()
  }

  val propNoPassUnresolvedStore = spec {
    PROPERTY("NoPassUnresolvedStore")
      .desc("No load is issued to the D-cache while an older, non-faulted store in the SQ lacks a physical address.")
      .uses(funcConservativeDisambig)
      .note("Simulation assert.")
      .build()
  }

  val propNoSpeculativeStoreVisible = spec {
    PROPERTY("NoSpeculativeStoreVisible")
      .desc(
        "No store leaves the SQ except through CommittedStoreOut in its commit cycle, so a " +
        "wrong-path store can never reach the StoreBuffer, the D-cache, or the bus."
      )
      .uses(funcStoreCommitHandoff)
      .note("ADR-019 D-19.12. Simulation assert plus the wrong-path-store directed case.")
      .build()
  }

  val propWrongPathLoadNoResult = spec {
    PROPERTY("WrongPathLoadNoResult")
      .desc(
        "A load killed by a RecoveryEvent never writes the PRF, wakes a consumer, or completes " +
        "a ROB entry, even when its D-cache miss later fills a line (the fill itself may stay)."
      )
      .uses(funcLsqRecovery)
      .note("ADR-019 D-19.12 and verification obligation 'wrong-path load may fill cache but leaves no architectural result'.")
      .build()
  }

  val propLsqRecoveryKeepsOlder = spec {
    PROPERTY("LsqRecoveryKeepsOlder")
      .desc("A BranchMispredict RecoveryEvent never removes or alters an LQ/SQ entry at or older than the recovering branch.")
      .uses(funcLsqRecovery)
      .build()
  }
}
