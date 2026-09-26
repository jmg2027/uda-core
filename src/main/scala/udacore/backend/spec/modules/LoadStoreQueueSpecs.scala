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
        intfHeadMemGrantIn,
        intfUncachedLoadReqOut,
        intfUncachedLoadRespIn,
        intfUncachedStoreReqOut,
        intfUncachedStoreRespIn,
        intfStoreBufferEmptyIn,
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
        propNoWrongPathStoreVisible,
        propWrongPathLoadNoResult,
        propLsqRecoveryKeepsOlder,
        propUncachedPerformedOnce,
        propForwardQueryConsumedOnce
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
        "Memory model (owner decision, former OQ-D): single hart, non-coherent v0, no DMA or other agent writes cacheable " +
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
      .desc("ROB head position (LQ release).")
      .uses(bndRobStatus)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4.")
      .build()
  }

  val intfHeadMemGrantIn = spec {
    INTERFACE("HeadMemGrantIn")
      .desc("Execution grant from the CommitUnit for the uncacheable load or store at the ROB head. Always ready.")
      .uses(bndHeadMemGrant)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfUncachedLoadReqOut = spec {
    INTERFACE("UncachedLoadReqOut")
      .desc("The single bus read of a granted uncacheable load, to the DataCache uncached port (physical; no DTLB pairing).")
      .uses(bndUncachedLoadReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfUncachedLoadRespIn = spec {
    INTERFACE("UncachedLoadRespIn")
      .desc("Bus answer of the uncached load (data or accessFault = denied).")
      .uses(bndUncachedLoadResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfUncachedStoreReqOut = spec {
    INTERFACE("UncachedStoreReqOut")
      .desc("The single bus write of a granted uncacheable store, to the DataCache uncached port (physical; bypasses the StoreBuffer and the array).")
      .uses(bndUncachedStoreReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfUncachedStoreRespIn = spec {
    INTERFACE("UncachedStoreRespIn")
      .desc("Bus acknowledgement of the uncached store (accessFault = denied).")
      .uses(bndUncachedStoreResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreBufferEmptyIn = spec {
    INTERFACE("StoreBufferEmptyIn")
      .desc("StoreBuffer empty view: every older committed store has reached the D-cache or bus.")
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4 (committed-state view).")
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
      .note(
        "ADR-019F E-1 (v0 timing/protocol choice, not an ISA requirement): the StoreBuffer " +
        "answers a query in the query cycle, and the LSQ decides and accepts a DCacheLoadResp " +
        "only in a cycle in which its StoreForwardQuery is accepted and the matching " +
        "StoreForwardData is valid (propForwardQueryConsumedOnce). This closes the SQ -> " +
        "StoreBuffer -> D-cache migration window without a store version protocol. A " +
        "pipelined forwarding path must not simply insert a register; it needs an explicit " +
        "store-visibility/version scheme and a later ADR."
      )
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
        "A cacheable store whose paddr and data are both known, or any Faulted store, " +
        "completes on MemResultOut (no register write) so the ROB marks it done; it stays in " +
        "the SQ until commit. An uncacheable store with paddr and data known instead reports " +
        "headExecute (not done) and waits for its HeadMemGrant (funcUncacheableAtHead)."
      )
      .uses(intfMemResultOut)
      .build()
  }

  val funcUncacheableAtHead = spec {
    FUNCTION("UncacheableAtHead")
      .desc(
        "An uncacheable access is never performed speculatively and at most once. A load " +
        "whose cached lookup was answered Uncacheable (recording its paddr from that " +
        "lookup's translation), or a store translated to a non-cacheable page with its data " +
        "known, reports headExecute on MemResultOut and waits. On HeadMemGrant for its robTag, " +
        "and once StoreBufferEmpty holds (older committed stores are ordered before it), a load " +
        "issues UncachedLoadReq{lqIdx, lqGen, paddr, size} and on UncachedLoadResp completes " +
        "with the extended value or a load access fault (tval = its vaddr); a store issues UncachedStoreReq{paddr, data, mask}, keeps its SQ entry, and on " +
        "UncachedStoreResp completes done (marked uncachedPerformed) or with a store access " +
        "fault (tval = its vaddr, held in the SQ entry). The later StoreCommit or ArchRedirect " +
        "then releases the entry."
      )
      .uses(intfHeadMemGrantIn, intfStoreBufferEmptyIn, intfUncachedLoadReqOut,
            intfUncachedLoadRespIn, intfUncachedStoreReqOut, intfUncachedStoreRespIn, intfMemResultOut)
      .build()
  }

  val funcStoreCommitHandoff = spec {
    FUNCTION("StoreCommitHandoff")
      .desc(
        "On StoreCommit for the SQ head: a cacheable store fires CommittedStoreOut{paddr, data, " +
        "mask} in the same cycle and releases the head; an uncachedPerformed store only " +
        "releases the head (its write already happened and must not be repeated). StoreCommit " +
        "is always the projection of the retirement of that very store: it never fires for a " +
        "store that does not retire in the same cycle."
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

  val propNoWrongPathStoreVisible = spec {
    PROPERTY("NoWrongPathStoreVisible")
      .desc(
        "A wrong-path store never becomes visible to the D-cache, the bus, or a later load " +
        "outside forwarding. A cacheable store becomes visible only through CommittedStoreOut in " +
        "its retirement cycle. An uncacheable store becomes visible before retirement only via " +
        "UncachedStoreReq, and only when its robTag is the ROB head, its HeadMemGrant was " +
        "received, no interrupt or debug request can be taken in front of it (grantInFlight), " +
        "and the access is performed at most once (propUncachedPerformedOnce)."
      )
      .uses(funcStoreCommitHandoff, funcUncacheableAtHead)
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

  val propUncachedPerformedOnce = spec {
    PROPERTY("UncachedPerformedOnce")
      .desc(
        "Every uncacheable load or store reaches the bus at most once and only after its " +
        "HeadMemGrant; its SQ/LQ entry is live until its completion is retired or trapped, and " +
        "an uncacheable store never enters the StoreBuffer."
      )
      .uses(funcUncacheableAtHead)
      .note("Simulation assert.")
      .build()
  }

  val propForwardQueryConsumedOnce = spec {
    PROPERTY("ForwardQueryConsumedOnce")
      .desc(
        "A DCacheLoadResp is accepted and decided only in a cycle in which its " +
        "StoreForwardQuery is accepted and the matching StoreForwardData is valid; each query " +
        "is consumed exactly once, in that cycle. A delayed or absent StoreForwardData holds " +
        "the D-cache answer (and the query) and never lets the same answer be decided twice or " +
        "decided without forwarding."
      )
      .uses(funcStoreToLoadForward)
      .note("ADR-019F E-1. Simulation assert plus the delayed-forward-data directed case.")
      .build()
  }

  val propLsqRecoveryKeepsOlder = spec {
    PROPERTY("LsqRecoveryKeepsOlder")
      .desc("A BranchMispredict RecoveryEvent never removes or alters an LQ/SQ entry at or older than the recovering branch.")
      .uses(funcLsqRecovery)
      .build()
  }
}
