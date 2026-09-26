package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._
import udacore.core.spec.shared.MemoryBundlesSpecs._

/** InstructionCache: VIPT L1 I-cache (ADR-019 D-19.4; amends ADR-016 PIPT base). */
object InstructionCacheSpecs {
  val contInstructionCache = spec {
    CONTRACT("InstructionCache")
      .desc(
        "A read-only, virtually indexed, physically tagged L1 instruction cache (16 KiB, 4 " +
        "ways, 64-byte lines, 64 sets, v0). The set is read from the untranslated index bits " +
        "of the fetch address while the InstructionTlb translates it; the physical tag compare " +
        "uses the ITLB answer. A hit returns the aligned 16-byte fetch block; a miss fills the " +
        "whole line through the InstBusAdapter using one miss context."
      )
      .has(
        intfICacheReqIn,
        intfICacheTranslationIn,
        intfICacheRespOut,
        intfICacheInvalidateIn,
        intfInstMemReqOut,
        intfInstMemRespIn,
        funcICacheViptLookup,
        funcICacheMissFill,
        funcICacheUncachedFetch,
        funcICacheInvalidate,
        propICacheReadOnly,
        propICacheResponseOrder,
        propICacheFunctionTransparent
      )
      .uses(paramICacheGeometry, propViptGeometryLegal)
      .note(
        "State classes: lines and replacement state are microarchitectural; a wrong-path fetch " +
        "may fill and the line stays (ADR-019 D-19.12). Requests are never canceled here; stale " +
        "answers are dropped by the FetchUnit generation. Addresses: the request is virtual " +
        "(index only), tags and fills are physical. Faults: translation faults pass through; a " +
        "denied fill is an instruction access fault and installs nothing."
      )
      .build()
  }

  val intfICacheReqIn = spec {
    INTERFACE("ICacheReqIn")
      .desc("Virtually indexed lookups from the FetchUnit, paired in order with ICacheTranslationIn.")
      .uses(bndICacheReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheTranslationIn = spec {
    INTERFACE("ICacheTranslationIn")
      .desc("Per-request translation from the InstructionTlb.")
      .uses(bndTranslation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheRespOut = spec {
    INTERFACE("ICacheRespOut")
      .desc("Fetch blocks or fetch faults to the FetchUnit, in request order.")
      .uses(bndICacheResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheInvalidateIn = spec {
    INTERFACE("ICacheInvalidateIn")
      .desc("FENCE.I invalidate-all tokens from the backend CommitUnit; the transfer completes when every line is invalid.")
      .uses(bndCacheMaintenance)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfInstMemReqOut = spec {
    INTERFACE("InstMemReqOut")
      .desc("Line fills and uncached fetches to the InstBusAdapter (physical).")
      .uses(bndInstMemReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfInstMemRespIn = spec {
    INTERFACE("InstMemRespIn")
      .desc("Fill beats from the InstBusAdapter.")
      .uses(bndInstMemResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcICacheViptLookup = spec {
    FUNCTION("ICacheViptLookup")
      .desc(
        "Read all ways of set va[11:6]; when the paired translation is Hit, compare each valid " +
        "way's tag with pa[33:12]. A hit answers the 16-byte block at va[5:4]. A translation " +
        "fault answers that fault without a memory access."
      )
      .uses(intfICacheReqIn, intfICacheTranslationIn, intfICacheRespOut, propViptGeometryLegal)
      .build()
  }

  val funcICacheMissFill = spec {
    FUNCTION("ICacheMissFill")
      .desc(
        "On a cacheable miss, hold the request (one miss context in v0), request the " +
        "line-aligned physical line, collect its beats, install it in the pseudo-LRU victim " +
        "way, and answer the held request. A fill that completes after its request was " +
        "canceled still installs."
      )
      .uses(intfInstMemReqOut, intfInstMemRespIn)
      .build()
  }

  val funcICacheUncachedFetch = spec {
    FUNCTION("ICacheUncachedFetch")
      .desc("A Hit translation with cacheable = false fetches only the 16-byte block uncached and installs nothing.")
      .uses(intfInstMemReqOut)
      .build()
  }

  val funcICacheInvalidate = spec {
    FUNCTION("ICacheInvalidate")
      .desc("On an accepted invalidate token clear every valid bit; a fill in progress completes but does not install.")
      .uses(intfICacheInvalidateIn)
      .build()
  }

  val propICacheReadOnly = spec {
    PROPERTY("ICacheReadOnly")
      .desc("The instruction cache never holds modified data and never issues a Put: its TileLink footprint is Get/AccessAckData only.")
      .build()
  }

  val propICacheResponseOrder = spec {
    PROPERTY("ICacheResponseOrder")
      .desc("Answers leave in the order requests were accepted, one answer per request.")
      .uses(intfICacheRespOut)
      .build()
  }

  val propICacheFunctionTransparent = spec {
    PROPERTY("ICacheFunctionTransparent")
      .desc("For any legal I-cache geometry the retire stream of any program is identical; the cache changes only timing.")
      .uses(propIsaRetireEquivalence)
      .build()
  }
}
