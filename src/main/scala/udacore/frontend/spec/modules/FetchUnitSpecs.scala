package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
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
        "Speculative-holder stance: in-flight fetch requests are ordered FIFO. They cannot be " +
        "canceled in the ITLB/I-cache, so every request carries the local fetch generation " +
        "(propGenerationTagScope class 1); a RecoveryEvent advances the generation and every " +
        "older-generation response is dropped. Nothing is reclaimed from the caches: wrong-path " +
        "fills and ITLB refills are allowed to complete and install (ADR-019 D-19.12)."
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
    INTERFACE("ITlbReqOut")
      .desc("Translation request (access = Fetch) to the CoreTop-level InstructionTlb.")
      .uses(bndTranslateReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheReqOut = spec {
    INTERFACE("ICacheReqOut")
      .desc("Virtually indexed lookup to the CoreTop-level InstructionCache; fires in the same cycle as ITlbReqOut.")
      .uses(bndICacheReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheRespIn = spec {
    INTERFACE("ICacheRespIn")
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
    INTERFACE("RecoveryEventIn")
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
        "ICacheReq{vaddr = fetchPc aligned to FetchBytes} and fire both in the same cycle " +
        "(atomic fork); either not ready holds the request. The I-cache set index comes from " +
        "untranslated address bits; the physical tag compare waits for the ITLB answer inside " +
        "the I-cache."
      )
      .uses(intfFetchRequestIn, intfITlbReqOut, intfICacheReqOut)
      .build()
  }

  val funcFetchGeneration = spec {
    FUNCTION("FetchGeneration")
      .desc(
        "A local fetch generation counter increments on every RecoveryEvent; each request " +
        "carries the current generation as reqId, and an ICacheResp whose reqId differs from " +
        "the current generation is consumed and dropped without producing a FetchBlock. " +
        "Requests not yet sent are discarded on the event."
      )
      .uses(intfRecoveryEventIn, intfICacheRespIn)
      .note("Local transaction bookkeeping only (ADR-019 D-19.10); it never orders program uops.")
      .build()
  }

  val funcFetchBlockAssembly = spec {
    FUNCTION("FetchBlockAssembly")
      .desc(
        "Split the 16-byte response into four 32-bit words; mark slots valid from the fetchPc " +
        "slot through lastSlot. A response with a fetch fault produces a block whose only " +
        "valid slot is the fetchPc slot, carrying the fault (instruction page fault or " +
        "instruction access fault) to be raised precisely at commit."
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
