package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.tilelink.TileLinkSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._
import udacore.core.spec.shared.MemoryBundlesSpecs._

/** InstBusAdapter: InstructionCache <-> instBus TileLink master (ADR-016 as
  * amended by ADR-019).
  */
object InstBusAdapterSpecs {
  val contInstBusAdapter = spec {
    CONTRACT("InstBusAdapter")
      .desc(
        "Translates InstructionCache line fills and uncached fetches into TileLink channel-A " +
        "Get requests on the instBus link and returns channel-D AccessAckData beats. It is the " +
        "only agent on the instruction link."
      )
      .has(
        intfInstMemReqIn,
        intfInstMemRespOut,
        intfInstBus,
        funcInstBusGet,
        propInstBusGetOnly
      )
      .uses(contTileLink, paramTLLinkDerivation)
      .note("Recovery stance: every accepted request is an uncancelable bus transaction and completes (propGenerationTagScope).")
      .build()
  }

  val intfInstMemReqIn = spec {
    INTERFACE("InstMemReqIn")
      .desc("Physical fill/uncached requests from the InstructionCache.")
      .uses(bndInstMemReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfInstMemRespOut = spec {
    INTERFACE("InstMemRespOut")
      .desc("Response beats to the InstructionCache.")
      .uses(bndInstMemResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfInstBus = spec {
    INTERFACE("InstBus")
      .desc("TileLink master link (channels A and D; TL-UH bursts for line fills).")
      .uses(contTileLink)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcInstBusGet = spec {
    FUNCTION("InstBusGet")
      .desc(
        "Each request becomes one Get{address = paddr, size} with a single source id; the " +
        "AccessAckData beats return in order with last on the final beat; denied or corrupt " +
        "sets the response's denied flag (instruction access fault)."
      )
      .uses(intfInstMemReqIn, intfInstMemRespOut, intfInstBus)
      .build()
  }

  val propInstBusGetOnly = spec {
    PROPERTY("InstBusGetOnly")
      .desc("Channel A carries only Get; the instruction link never elaborates B/C/E (hasBCE = false).")
      .uses(intfInstBus)
      .build()
  }
}
