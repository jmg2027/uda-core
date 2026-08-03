package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.tilelink.TileLinkSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._

/** Instruction cache vertex specifications (ADR-016, capCacheHierarchy).
  *
  * Spec-only for now: the vertex elaborates when CoreContractParams.icache is
  * Some(CacheParams). It splices into the ProgMemReq/ProgMemResp edges between
  * FrontendTop and the InstBusAdapter at CoreTop level - both neighbours keep
  * their bundles and handshakes, which is what makes the cache a pure
  * performance element in the UDA sense (function is unchanged with or
  * without it).
  */
object InstructionCacheSpecs {

  val contInstructionCache = spec {
    CONTRACT("InstructionCache")
      .desc("""Instruction cache vertex.
              | An optional read-only cache on the instruction fetch path.
              | Consumes fetch requests from FrontendTop's ProgMemReq edge,
              | answers hits from its array, and fills misses through the
              | InstBusAdapter as TileLink burst reads (TL-UH). Never owns
              | lines coherently - the instruction link stays hasBCE=false.
              """)
      .has(
        intfICacheFetchReqIn,
        intfICacheFetchRespOut,
        intfICacheFillReqOut,
        intfICacheFillRespIn,
        intfICacheInvalidateIn,
        funcICacheLookup,
        funcICacheFill,
        funcICacheInvalidate,
        propICacheReadOnly,
        propICacheEpochBlind,
        propICacheFunctionTransparent
      )
      .uses(paramCacheGeometry, contTileLink)
      .draw(
        "mermaid",
        """
      | graph LR
      |     fetchreq@{shape: text, label: ICacheFetchReqIn}
      |     fetchresp@{shape: text, label: ICacheFetchRespOut}
      |     fillreq@{shape: text, label: ICacheFillReqOut}
      |     fillresp@{shape: text, label: ICacheFillRespIn}
      |     inval@{shape: text, label: ICacheInvalidateIn}
      |
      |     subgraph InstructionCache
      |         direction LR
      |         lookup[TagDataLookup]
      |         mshr[FillEngine]
      |         lookup -- MissFill --> mshr
      |         mshr -- Refill --> lookup
      |     end
      |
      |     fetchreq --> lookup
      |     lookup --> fetchresp
      |     mshr --> fillreq
      |     fillresp --> mshr
      |     inval --> lookup
      """
      )
      .note(
        "Placement: a CoreTop-level vertex between FrontendTop and InstBusAdapter " +
        "(funcExternalMemoryBridge). With an ITLB configured, the TLB vertex sits " +
        "upstream of the cache, so the cache indexes/tags physical addresses at the base " +
        "point (PIPT); a VIPT option is a later parameter, constrained to " +
        "sets*blockBytes <= pageBytes."
      )
      .build()
  }

  val intfICacheFetchReqIn = spec {
    INTERFACE("ICacheFetchReqIn")
      .desc("Fetch request edge in (the same ExternalProgramMemoryReq bundle FrontendTop emits).")
      .is(rawReadyValidIntf)
      .note("Backpressure on this edge is the only stall mechanism: no side-band stall wires.")
      .build()
  }

  val intfICacheFetchRespOut = spec {
    INTERFACE("ICacheFetchRespOut")
      .desc("Fetch response edge out (the same ExternalProgramMemoryResp bundle FrontendTop consumes).")
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheFillReqOut = spec {
    INTERFACE("ICacheFillReqOut")
      .desc(
        "Block fill request edge toward the InstBusAdapter; the adapter forms a TileLink " +
        "channel-A Get of blockBytes (TL-UH burst)."
      )
      .is(rawReadyValidIntf)
      .uses(contTileLink)
      .build()
  }

  val intfICacheFillRespIn = spec {
    INTERFACE("ICacheFillRespIn")
      .desc("Fill beats back from the adapter (channel-D AccessAckData payload, one edge token per beat).")
      .is(rawReadyValidIntf)
      .uses(contTileLink)
      .build()
  }

  val intfICacheInvalidateIn = spec {
    INTERFACE("ICacheInvalidateIn")
      .desc(
        "Whole-array invalidate command edge (fence.i). Producer is the commit-side " +
        "system-op path; the token completes (ready/valid) when the invalidate is durable."
      )
      .is(rawReadyValidIntf)
      .note(
        "Carried as a dataflow token, not a broadcast wire: fence.i is a serializing " +
        "instruction (ADR-004), so a single in-flight token is exact."
      )
      .build()
  }

  val funcICacheLookup = spec {
    FUNCTION("ICacheLookup")
      .desc(
        "Tag/data lookup: a fetch request hits if its line is valid; the hit response " +
        "flows to ICacheFetchRespOut. A miss allocates the fill engine and the request " +
        "waits by backpressure (base point: blocking, one outstanding miss)."
      )
      .build()
  }

  val funcICacheFill = spec {
    FUNCTION("ICacheFill")
      .desc(
        "Miss fill: read the whole block via ICacheFillReqOut (TL-UH burst Get through the " +
        "adapter), collect beats, install the line, replay the missing request. Replacement " +
        "is per-set (way selection policy is a private parameter)."
      )
      .build()
  }

  val funcICacheInvalidate = spec {
    FUNCTION("ICacheInvalidate")
      .desc(
        "fence.i service: clear all valid bits. No writeback exists (read-only array), so " +
        "invalidation is single-cycle over the valid array; the command token then completes."
      )
      .build()
  }

  val propICacheReadOnly = spec {
    PROPERTY("ICacheReadOnly")
      .desc(
        "The instruction cache never holds modified data and never issues Put/Release: its " +
        "TileLink footprint is Get/AccessAckData only, so the instruction link never " +
        "elaborates B/C/E (hasBCE=false is an invariant, not a default)."
      )
      .build()
  }

  val propICacheEpochBlind = spec {
    PROPERTY("ICacheEpochBlind")
      .desc(
        "The cache carries no epoch state and never filters tokens: a fill in flight when a " +
        "redirect fires completes and installs (it is architectural data, address-keyed, " +
        "never wrong-path-poisoned). Wrong-path fetch responses are discarded upstream by " +
        "FrontendTop's epoch filtering, exactly as with the TCM-direct attachment."
      )
      .note("Same epoch stance as the bus adapters (ADR-016 D-16.5).")
      .build()
  }

  val propICacheFunctionTransparent = spec {
    PROPERTY("ICacheFunctionTransparent")
      .desc(
        "With icache=None and icache=Some the core is function-equivalent (same retire " +
        "stream for the same program); the cache may only change timing. This is the UDA " +
        "separation of function and performance applied to the vertex, and the acceptance " +
        "test is retire-stream equivalence across the two configs (ADR-015 D-15.4 style)."
      )
      .build()
  }
}
