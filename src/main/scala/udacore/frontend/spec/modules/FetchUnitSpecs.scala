package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.spec.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.frontend.spec.shared.FrontendParamsSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs.bndRecoveryEvent
import udacore.core.spec.shared.MemoryBundlesSpecs.{bndICacheReq, bndICacheResp, bndTranslateReq}

/** FetchUnit: parallel ITLB + VIPT I-cache access for one fetch block
  * (ADR-019 D-19.3, D-19.4).
  */
object FetchUnitSpecs {
  val contFetchUnit = spec {
    CONTRACT("FetchUnit")
      .desc(
        "FetchUnit turns an FTQ FetchRequest into one FetchBlock. It issues the ITLB " +
        "translation request and the I-cache lookup for the same virtual fetch address in " +
        "one transfer (the ITLB answers the I-cache directly for the physical tag compare), " +
        "then assembles the returned 16-byte block into four aligned 32-bit slots. It performs " +
        "no prediction and no decoding."
      )
      .has(
        intfFetchRequestIn,
        intfITlbReqOut,
        intfICacheReqOut,
        intfICacheRespIn,
        intfFetchBlockOut,
        intfRecoveryEventIn,
        funcParallelFetchLookup,
        funcFetchGeneration,
        funcFetchBlockAssembly,
        propFetchInOrder,
        propNoStaleFetchBlock
      )
      .uses(paramFetchBytes, paramFetchWidth)
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: v0 permits one outstanding fetch lookup. It cannot be " +
        "canceled in the ITLB/I-cache, so a RecoveryEvent marks it stale and its answer is " +
        "drained before another lookup is accepted. Each request also carries the local fetch " +
        "generation (propGenerationTagScope class 1). The drain rule prevents a generation " +
        "wrap from mistaking an old answer for a new one. Nothing is reclaimed from the " +
        "caches: wrong-path fills and ITLB refills may complete and install (ADR-019 D-19.12)."
      )
      .build()
  }

  val intfFetchRequestIn = spec {
    INTERFACE("FetchRequestIn")
      .desc("In-order fetch requests from the FetchTargetQueue.")
      .uses(bndFetchRequest)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfITlbReqOut = spec {
    INTERFACE("FetchUnitSpecs.intfITlbReqOut")
      .desc("Translation request (access = Fetch) to the CoreTop-level InstructionTlb.")
      .uses(bndTranslateReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheReqOut = spec {
    INTERFACE("FetchUnitSpecs.intfICacheReqOut")
      .desc("Virtually indexed lookup to the CoreTop-level InstructionCache; fires in the same cycle as ITlbReqOut.")
      .uses(bndICacheReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheRespIn = spec {
    INTERFACE("FetchUnitSpecs.intfICacheRespIn")
      .desc("Fetch-block data or fetch fault from the InstructionCache, in request order.")
      .uses(bndICacheResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfFetchBlockOut = spec {
    INTERFACE("FetchBlockOut")
      .desc("Assembled fetch blocks to the FetchBuffer, in request order.")
      .uses(bndFetchBlock)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRecoveryEventIn = spec {
    INTERFACE("FetchUnitSpecs.intfRecoveryEventIn")
      .desc("The common RecoveryEvent broadcast.")
      .uses(bndRecoveryEvent)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 6.")
      .build()
  }

  val funcParallelFetchLookup = spec {
    FUNCTION("ParallelFetchLookup")
      .desc(
        "For each accepted FetchRequest, offer ITlbReq{vaddr = fetchPc, access = Fetch} and " +
        "ICacheReq{vaddr = blockBase = fetchPc aligned down to FetchBytes} (ADR-019G E-6) and " +
        "fire both in the same cycle " +
        "(atomic fork); either not ready holds the request. The I-cache set index comes from " +
        "untranslated address bits; the physical tag compare waits for the ITLB answer inside " +
        "the I-cache. v0 accepts the next FetchRequest only after the prior response has been " +
        "delivered or discarded."
      )
      .uses(intfFetchRequestIn, intfITlbReqOut, intfICacheReqOut)
      .build()
  }

  val funcFetchGeneration = spec {
    FUNCTION("FetchGeneration")
      .desc(
        "A local fetch generation counter increments on every RecoveryEvent; each request " +
        "carries the current generation as reqId. A RecoveryEvent marks any outstanding " +
        "request stale, suppresses a held FetchBlock in the event cycle, and discards a " +
        "not-yet-sent request. The stale ICacheResp is consumed and dropped even if its reqId " +
        "equals the counter after wrap. No new lookup is accepted until that response drains. " +
        "A live response must echo the issued reqId."
      )
      .uses(intfRecoveryEventIn, intfICacheRespIn)
      .note("Local transaction bookkeeping only (ADR-019 D-19.10); it never orders program uops.")
      .build()
  }

  val funcFetchBlockAssembly = spec {
    FUNCTION("FetchBlockAssembly")
      .desc(
        "Split the 16-byte response into four 32-bit words; basePc = blockBase; mark slots " +
        "valid from startSlot = fetchPc[log2(FetchBytes)-1:2] through lastSlot. A response " +
        "with a fetch fault produces a block whose only valid slot is startSlot (not " +
        "necessarily slot 0), carrying the fault (instruction page fault or instruction access " +
        "fault) to be raised precisely at commit (ADR-019G E-6). A fault suppresses the " +
        "predicted exit because the only delivered slot is the faulting slot. Hold the " +
        "assembled block stable under FetchBlockOut backpressure."
      )
      .uses(intfFetchBlockOut)
      .build()
  }

  val propFetchInOrder = spec {
    PROPERTY("FetchInOrder")
      .desc("FetchBlocks leave in the order their FetchRequests were accepted; ftqIdx increases (wrap-aware) along FetchBlockOut between RecoveryEvents.")
      .uses(intfFetchBlockOut)
      .build()
  }

  val propNoStaleFetchBlock = spec {
    PROPERTY("NoStaleFetchBlock")
      .desc("No FetchBlock is produced, in or after a RecoveryEvent cycle, for a FetchRequest accepted before that event.")
      .uses(intfFetchBlockOut, intfRecoveryEventIn)
      .build()
  }
}
