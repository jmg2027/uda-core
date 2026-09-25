package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._
import udacore.core.spec.shared.MemoryBundlesSpecs._

/** DataCache: VIPT, non-blocking, write-back L1 D-cache (ADR-019 D-19.5,
  * D-19.12, D-19.13; amends ADR-016 PIPT base and dcache => TL-C).
  */
object DataCacheSpecs {
  val contDataCache = spec {
    CONTRACT("DataCache")
      .desc(
        "A virtually indexed, physically tagged, write-back, write-allocate L1 data cache " +
        "(16 KiB, 4 ways, 64-byte lines, 64 sets, 2 MSHRs with 2 load targets each, v0). It " +
        "serves three request streams: speculative loads from the LSQ (paired with DTLB " +
        "answers), committed store drains from the StoreBuffer (physical), and PTE reads from " +
        "the PageTableWalker (physical). Misses are non-blocking: hits are served under " +
        "outstanding misses and load answers return out of order, tagged for reassociation. " +
        "The v0 data link is non-coherent TL-UH."
      )
      .has(
        intfDCacheLoadReqIn,
        intfDCacheTranslationIn,
        intfDCacheLoadRespOut,
        intfStoreDrainReqIn,
        intfStoreDrainRespOut,
        intfPtwMemReqIn,
        intfPtwMemRespOut,
        intfDCacheCleanReqIn,
        intfDCacheCleanRespOut,
        intfDataMemReqOut,
        intfDataMemRespIn,
        funcDCacheViptLookup,
        funcDCacheMshr,
        funcDCacheStoreWrite,
        funcDCacheWriteback,
        funcDCachePhysicalRead,
        funcDCacheUncached,
        funcDCacheCleanAll,
        funcDCachePortArbitrate,
        propDCacheCommittedStoresOnly,
        propDCacheLoadAnswerExactlyOnce,
        propDCacheFunctionTransparent
      )
      .uses(paramDCacheGeometry, paramDataCoherence, propViptGeometryLegal)
      .note(
        "State classes: lines, dirty bits, replacement state, and MSHRs are microarchitectural " +
        "or committed state - only committed stores ever write the array, and a wrong-path " +
        "load may allocate an MSHR and install its line (ADR-019 D-19.12). Load requests are " +
        "never canceled here; answers for killed or reallocated LQ entries are dropped by the " +
        "LSQ generation. Addresses: load requests are virtual (index only) plus a physical " +
        "translation; store drains and PTE reads are physical. Faults: translation faults pass " +
        "through; a denied fill answers an access fault and installs nothing."
      )
      .note(
        "Coherence (D-19.13): DataCoherence = false in v0, so the link is TL-UH (Get and " +
        "PutFullData bursts). A later TL-C option adds Acquire/Probe/Release service behind the " +
        "same core-facing interfaces; cache presence alone never enables it."
      )
      .build()
  }

  val intfDCacheLoadReqIn = spec {
    INTERFACE("DCacheLoadReqIn")
      .desc("Load lookups from the LoadStoreQueue, paired in order with DCacheTranslationIn.")
      .uses(bndDCacheLoadReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheTranslationIn = spec {
    INTERFACE("DCacheTranslationIn")
      .desc("Per-load translation from the DataTlb.")
      .uses(bndTranslation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheLoadRespOut = spec {
    INTERFACE("DCacheLoadRespOut")
      .desc("Load answers to the LoadStoreQueue, possibly out of order.")
      .uses(bndDCacheLoadResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreDrainReqIn = spec {
    INTERFACE("StoreDrainReqIn")
      .desc("Committed stores from the StoreBuffer head.")
      .uses(bndStoreDrainReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreDrainRespOut = spec {
    INTERFACE("StoreDrainRespOut")
      .desc("Store completion to the StoreBuffer.")
      .uses(bndStoreDrainResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPtwMemReqIn = spec {
    INTERFACE("PtwMemReqIn")
      .desc("Physical PTE reads from the PageTableWalker.")
      .uses(bndPtwMemReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPtwMemRespOut = spec {
    INTERFACE("PtwMemRespOut")
      .desc("PTE data to the PageTableWalker.")
      .uses(bndPtwMemResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheCleanReqIn = spec {
    INTERFACE("DCacheCleanReqIn")
      .desc("FENCE.I clean-all requests from the backend CommitUnit.")
      .uses(bndCacheMaintenance)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheCleanRespOut = spec {
    INTERFACE("DCacheCleanRespOut")
      .desc("Clean-all completion to the backend CommitUnit.")
      .uses(bndCacheMaintenance)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDataMemReqOut = spec {
    INTERFACE("DataMemReqOut")
      .desc("Fills, writebacks, and uncached accesses to the DataBusAdapter (physical).")
      .uses(bndDataMemReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDataMemRespIn = spec {
    INTERFACE("DataMemRespIn")
      .desc("Bus answers from the DataBusAdapter, by source id.")
      .uses(bndDataMemResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcDCacheViptLookup = spec {
    FUNCTION("DCacheViptLookup")
      .desc(
        "Read all ways of set va[11:6] for a load while the DTLB translates it; when the " +
        "paired translation is Hit and cacheable, compare tags with pa[33:12]. Hit: answer " +
        "Data with paddr. Translation Miss: answer TlbMiss. Translation fault: answer the " +
        "fault. Hit but not cacheable: answer Uncacheable (the LSQ retries at the ROB head)."
      )
      .uses(intfDCacheLoadReqIn, intfDCacheTranslationIn, intfDCacheLoadRespOut, propViptGeometryLegal)
      .build()
  }

  val funcDCacheMshr = spec {
    FUNCTION("DCacheMshr")
      .desc(
        "A cacheable load miss allocates a free MSHR (or joins the MSHR already fetching its " +
        "line) as one of at most two load targets and issues a line fill; hits and other " +
        "misses continue meanwhile (hit-under-miss, miss-under-miss up to two lines). When " +
        "the line arrives it is installed and every target is answered Data, each tagged with " +
        "its lqIdx/lqGen, in any order relative to other answers. A load that finds no free " +
        "MSHR or target slot is answered Replay."
      )
      .uses(intfDataMemReqOut, intfDataMemRespIn, paramDCacheGeometry)
      .build()
  }

  val funcDCacheStoreWrite = spec {
    FUNCTION("DCacheStoreWrite")
      .desc(
        "A cacheable committed store that hits writes its bytes and sets the line dirty, then " +
        "answers the StoreBuffer. A miss allocates an MSHR, fills the line (write-allocate), " +
        "then writes and answers. Store drains are served in arrival order."
      )
      .uses(intfStoreDrainReqIn, intfStoreDrainRespOut)
      .build()
  }

  val funcDCacheWriteback = spec {
    FUNCTION("DCacheWriteback")
      .desc("Replacing a dirty victim writes the whole line back as a PutLine burst before the fill that replaces it may install.")
      .uses(intfDataMemReqOut)
      .build()
  }

  val funcDCachePhysicalRead = spec {
    FUNCTION("DCachePhysicalRead")
      .desc(
        "A PTE read is looked up with its physical address (index pa[11:6], tag pa[33:12]); a " +
        "hit answers at once, a miss fills through an MSHR like a load. It never consults a TLB."
      )
      .uses(intfPtwMemReqIn, intfPtwMemRespOut)
      .build()
  }

  val funcDCacheUncached = spec {
    FUNCTION("DCacheUncached")
      .desc(
        "An uncached load (issued only at the ROB head) or a store drain to a non-cacheable " +
        "address bypasses the array as a single GetUncached/PutUncached bus access; nothing is " +
        "installed. An uncacheable store drain is answered only after the bus acknowledgement, " +
        "with accessFault = denied, so the head store can trap precisely."
      )
      .uses(intfDataMemReqOut)
      .build()
  }

  val funcDCacheCleanAll = spec {
    FUNCTION("DCacheCleanAll")
      .desc("On a clean request, write back every dirty line (lines stay valid and become clean), then answer the completion.")
      .uses(intfDCacheCleanReqIn, intfDCacheCleanRespOut)
      .note("FENCE.I support: instruction fetch is not coherent with the D-cache in v0.")
      .build()
  }

  val funcDCachePortArbitrate = spec {
    FUNCTION("DCachePortArbitrate")
      .desc(
        "When requests compete for the array: PTE reads first, then store drains, then loads. " +
        "A lower-priority request waits by backpressure; each stream is served in its own order."
      )
      .uses(intfPtwMemReqIn, intfStoreDrainReqIn, intfDCacheLoadReqIn)
      .build()
  }

  val propDCacheCommittedStoresOnly = spec {
    PROPERTY("DCacheCommittedStoresOnly")
      .desc(
        "Array data and dirty bits change only through StoreDrainReqIn (committed stores) and " +
        "line fills; no load, PTE read, or wrong-path access ever modifies a byte of cached data."
      )
      .uses(intfStoreDrainReqIn)
      .note("ADR-019 D-19.12: speculative stores never reach the cache (propNoSpeculativeStoreVisible upstream).")
      .build()
  }

  val propDCacheLoadAnswerExactlyOnce = spec {
    PROPERTY("DCacheLoadAnswerExactlyOnce")
      .desc("Every accepted load request is answered exactly once with its own lqIdx/lqGen, regardless of hits, misses, or replays in between.")
      .uses(intfDCacheLoadRespOut)
      .build()
  }

  val propDCacheFunctionTransparent = spec {
    PROPERTY("DCacheFunctionTransparent")
      .desc("For any legal D-cache geometry and MSHR count the retire stream of any program is identical; the cache changes only timing.")
      .uses(propIsaRetireEquivalence)
      .build()
  }
}
