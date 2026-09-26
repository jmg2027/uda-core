package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._
import udacore.core.spec.shared.MemoryBundlesSpecs._
import udacore.core.spec.shared.Sv32Specs._
import udacore.backend.spec.shared.BackendBundlesSpecs.bndTranslationContext

/** InstructionTlb: fetch-side Sv32 translation (ADR-019 D-19.4, D-19.6). */
object InstructionTlbSpecs {
  val contInstructionTlb = spec {
    CONTRACT("InstructionTlb")
      .desc(
        "A fully associative TLB (16 entries, v0) translating fetch addresses. Each FetchUnit " +
        "request is answered, in request order, to the InstructionCache (which uses the " +
        "physical address for its tag compare) with Hit, InstPageFault, or InstAccessFault. " +
        "On a miss it requests a walk from the shared PageTableWalker and answers after the refill."
      )
      .has(
        intfITlbReqIn,
        intfICacheTranslationOut,
        intfItlbWalkReqOut,
        intfItlbWalkRespIn,
        intfItlbFlushIn,
        intfTranslationContextIn,
        funcItlbTranslate,
        funcItlbMissWalk,
        funcItlbRefill,
        funcItlbFlush
      )
      .uses(paramTlbGeometry, funcSv32Decompose, funcTlbMatch, funcTranslationMode,
            funcSv32PermissionCheck, funcPmaCheck, propNoFaultCaching, propSfenceFlushesAll)
      .note(
        "State classes: entries are microarchitectural (never rolled back; a wrong-path fetch " +
        "may refill). A request canceled by a RecoveryEvent is not canceled here: its answer " +
        "is dropped by the FetchUnit's fetch generation (propGenerationTagScope). Addresses: " +
        "requests are virtual, answers physical."
      )
      .build()
  }

  val intfITlbReqIn = spec {
    INTERFACE("ITlbReqIn")
      .desc("Fetch translation requests from the FetchUnit.")
      .uses(bndTranslateReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheTranslationOut = spec {
    INTERFACE("ICacheTranslationOut")
      .desc("One translation per request, in request order, to the InstructionCache.")
      .uses(bndTranslation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfItlbWalkReqOut = spec {
    INTERFACE("ItlbWalkReqOut")
      .desc("Walk requests to the PageTableWalker.")
      .uses(bndWalkReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfItlbWalkRespIn = spec {
    INTERFACE("ItlbWalkRespIn")
      .desc("Walk results from the PageTableWalker.")
      .uses(bndWalkResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfItlbFlushIn = spec {
    INTERFACE("ItlbFlushIn")
      .desc("SFENCE.VMA flush tokens from the PageTableWalker; accepted only when the flush is applied.")
      .uses(bndTlbFlush)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfTranslationContextIn = spec {
    INTERFACE("TranslationContextIn")
      .desc("Committed satp/priv/SUM/MXR view from the backend CsrController.")
      .uses(bndTranslationContext)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4.")
      .build()
  }

  val funcItlbTranslate = spec {
    FUNCTION("ItlbTranslate")
      .desc(
        "Bare mode (funcTranslationMode): answer Hit with pa = va after funcPmaCheck(Fetch). " +
        "Sv32: on a funcTlbMatch hit compose pa (funcSv32Decompose), apply " +
        "funcSv32PermissionCheck(Fetch) then funcPmaCheck(Fetch), and answer Hit, " +
        "InstPageFault, or InstAccessFault."
      )
      .uses(intfITlbReqIn, intfICacheTranslationOut)
      .build()
  }

  val funcItlbMissWalk = spec {
    FUNCTION("ItlbMissWalk")
      .desc(
        "On a miss, send one WalkReq{vpn, context} and hold the request (the single fetch " +
        "stream waits by backpressure). A Leaf result is refilled and the request is answered " +
        "from it; a PageFault or AccessFault result answers the held request with that fault " +
        "and installs nothing; a Retry result (walk overlapped an SFENCE.VMA) re-walks."
      )
      .uses(intfItlbWalkReqOut, intfItlbWalkRespIn)
      .build()
  }

  val funcItlbRefill = spec {
    FUNCTION("ItlbRefill")
      .desc("Install a Leaf walk result, replacing an invalid entry or else a pseudo-LRU victim; never create a second matching entry.")
      .uses(intfItlbWalkRespIn, propNoFaultCaching)
      .build()
  }

  val funcItlbFlush = spec {
    FUNCTION("ItlbFlush")
      .desc("On an accepted flush token, invalidate every entry in that cycle.")
      .uses(intfItlbFlushIn, propSfenceFlushesAll)
      .build()
  }
}
