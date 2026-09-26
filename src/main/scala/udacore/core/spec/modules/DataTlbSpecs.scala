package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._
import udacore.core.spec.shared.MemoryBundlesSpecs._
import udacore.core.spec.shared.Sv32Specs._
import udacore.backend.spec.shared.BackendBundlesSpecs.bndTranslationContext

/** DataTlb: load/store Sv32 translation with a non-blocking miss path
  * (ADR-019 D-19.5, D-19.6).
  */
object DataTlbSpecs {
  val contDataTlb = spec {
    CONTRACT("DataTlb")
      .desc(
        "A fully associative TLB (16 entries, v0) translating LSQ load and store addresses " +
        "with the effective data privilege. Load requests are answered to the DataCache in " +
        "lockstep with the load's cache lookup (VIPT); store requests are answered to the LSQ. " +
        "A miss is answered immediately with status Miss so only the affected memory uop " +
        "waits; the walk result is announced to the LSQ as a refill notice."
      )
      .has(
        intfDtlbReqIn,
        intfDCacheTranslationOut,
        intfDtlbStoreRespOut,
        intfDtlbRefillOut,
        intfDtlbWalkReqOut,
        intfDtlbWalkRespIn,
        intfDtlbFlushIn,
        intfTranslationContextIn,
        funcDtlbTranslate,
        funcDtlbMissNonBlocking,
        funcDtlbRefill,
        funcDtlbFlush,
        propDtlbMissLocal
      )
      .uses(paramTlbGeometry, funcSv32Decompose, funcTlbMatch, funcTranslationMode,
            funcSv32PermissionCheck, funcPmaCheck, propNoFaultCaching, propSfenceFlushesAll)
      .note(
        "State classes: entries and the walk-fault record are microarchitectural; a wrong-path " +
        "load or store may start a walk and refill (ADR-019 D-19.12). Canceled LSQ requests are " +
        "not canceled here; their answers are dropped by the LSQ entry generation. Addresses: " +
        "requests are virtual, answers physical; ordering decisions are made only on the " +
        "physical answers (propPhysicalOrderingAuthority)."
      )
      .build()
  }

  val intfDtlbReqIn = spec {
    INTERFACE("DtlbReqIn")
      .desc("Load and store translation requests from the LoadStoreQueue.")
      .uses(bndTranslateReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheTranslationOut = spec {
    INTERFACE("DCacheTranslationOut")
      .desc("Answers to load requests, in request order, to the DataCache (physical tag compare).")
      .uses(bndTranslation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbStoreRespOut = spec {
    INTERFACE("DtlbStoreRespOut")
      .desc("Answers to store requests, to the LoadStoreQueue.")
      .uses(bndTranslation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbRefillOut = spec {
    INTERFACE("DtlbRefillOut")
      .desc("One notice per completed walk (any status) to the LoadStoreQueue, which retries every translation-pending entry.")
      .uses(bndWalkResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbWalkReqOut = spec {
    INTERFACE("DtlbWalkReqOut")
      .desc("Walk requests to the PageTableWalker.")
      .uses(bndWalkReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbWalkRespIn = spec {
    INTERFACE("DtlbWalkRespIn")
      .desc("Walk results from the PageTableWalker.")
      .uses(bndWalkResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbFlushIn = spec {
    INTERFACE("DtlbFlushIn")
      .desc("SFENCE.VMA flush tokens from the PageTableWalker.")
      .uses(bndTlbFlush)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfTranslationContextIn = spec {
    INTERFACE("TranslationContextIn")
      .desc("Committed satp/dataPriv/SUM/MXR view from the backend CsrController.")
      .uses(bndTranslationContext)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4.")
      .build()
  }

  val funcDtlbTranslate = spec {
    FUNCTION("DtlbTranslate")
      .desc(
        "Bare mode: Hit with pa = va after funcPmaCheck. Sv32 hit: compose pa, apply " +
        "funcSv32PermissionCheck(Load or Store) with dataPriv, then funcPmaCheck, and answer " +
        "Hit (with the cacheable attribute), LoadPageFault/StorePageFault, or " +
        "LoadAccessFault/StoreAccessFault. Load answers go to the DataCache, store answers to the LSQ."
      )
      .uses(intfDtlbReqIn, intfDCacheTranslationOut, intfDtlbStoreRespOut)
      .build()
  }

  val funcDtlbMissNonBlocking = spec {
    FUNCTION("DtlbMissNonBlocking")
      .desc(
        "On a miss answer Miss at once. If no walk is outstanding, send WalkReq for this VPN; " +
        "otherwise do nothing (the retry after the next refill notice starts the next walk). " +
        "A walk that ends in PageFault or AccessFault is kept in a one-entry fault record " +
        "{vpn, asid, status}; a later miss on the same VPN and ASID answers that fault instead " +
        "of walking again. The record is cleared by a flush or replaced by the next walk result."
      )
      .uses(intfDtlbWalkReqOut, intfDtlbWalkRespIn)
      .note("ADR-019 D-19.5: independent memory uops keep executing while one waits for the PTW.")
      .build()
  }

  val funcDtlbRefill = spec {
    FUNCTION("DtlbRefill")
      .desc(
        "Install a Leaf walk result (no duplicate, pseudo-LRU victim), then send the refill " +
        "notice; for a fault or Retry result send the notice without installing."
      )
      .uses(intfDtlbWalkRespIn, intfDtlbRefillOut, propNoFaultCaching)
      .build()
  }

  val funcDtlbFlush = spec {
    FUNCTION("DtlbFlush")
      .desc("On an accepted flush token, invalidate every entry and the fault record in that cycle.")
      .uses(intfDtlbFlushIn, propSfenceFlushesAll)
      .build()
  }

  val propDtlbMissLocal = spec {
    PROPERTY("DtlbMissLocal")
      .desc("A DTLB miss never deasserts DtlbReqIn.ready: every request, hit or miss, is answered, so a miss stalls only the requesting LSQ entry.")
      .uses(funcDtlbMissNonBlocking)
      .note("Simulation assert plus the ADR-019 directed case (independent work continues during a DTLB walk).")
      .build()
  }
}
