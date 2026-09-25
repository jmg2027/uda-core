package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.tilelink.TileLinkSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._
import udacore.core.spec.shared.MemoryBundlesSpecs._

/** DataBusAdapter: DataCache <-> dataBus TileLink master (ADR-016 as amended by
  * ADR-019 D-19.13).
  */
object DataBusAdapterSpecs {
  val contDataBusAdapter = spec {
    CONTRACT("DataBusAdapter")
      .desc(
        "Translates DataCache fills, writebacks, and uncached accesses into TileLink " +
        "transactions on the dataBus link and returns channel-D answers by source id. In v0 " +
        "(DataCoherence = false) the link is TL-UH: GetLine -> Get of a line, PutLine -> " +
        "PutFullData burst, GetUncached -> Get, PutUncached -> PutPartialData."
      )
      .has(
        intfDataMemReqIn,
        intfDataMemRespOut,
        intfDataBus,
        funcDataBusTransaction,
        propDataBusCoherenceSeparate
      )
      .uses(contTileLink, paramTLLinkDerivation, paramDataCoherence, propTLChannelPriority)
      .note("Recovery stance: every accepted request is an uncancelable bus transaction and completes (propGenerationTagScope).")
      .build()
  }

  val intfDataMemReqIn = spec {
    INTERFACE("DataMemReqIn")
      .desc("Physical fill/writeback/uncached requests from the DataCache.")
      .uses(bndDataMemReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDataMemRespOut = spec {
    INTERFACE("DataMemRespOut")
      .desc("Answers to the DataCache by source id.")
      .uses(bndDataMemResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDataBus = spec {
    INTERFACE("DataBus")
      .desc("TileLink master link (A and D in v0; B/C/E only when DataCoherence is enabled).")
      .uses(contTileLink)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcDataBusTransaction = spec {
    FUNCTION("DataBusTransaction")
      .desc(
        "Map each DataMemReq op to its TileLink message with a.source = the request's source " +
        "id (MSHR, writeback, or uncached), forward D beats back with the same source, and " +
        "convert denied/corrupt to the denied flag. Responses of different sources may interleave."
      )
      .uses(intfDataMemReqIn, intfDataMemRespOut, intfDataBus)
      .build()
  }

  val propDataBusCoherenceSeparate = spec {
    PROPERTY("DataBusCoherenceSeparate")
      .desc(
        "The data link elaborates B/C/E if and only if DataCoherence is true; a configured " +
        "D-cache alone never does. In v0 no Acquire, Probe, or Release is ever issued."
      )
      .uses(paramDataCoherence)
      .note("Elaboration require on the derived TLLinkParams (ADR-019 D-19.13).")
      .build()
  }
}
