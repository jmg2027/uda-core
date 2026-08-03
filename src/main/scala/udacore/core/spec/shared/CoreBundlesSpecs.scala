package udacore.core.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.core.spec.shared.CoreParamsSpecs._
import scala.annotation.meta.param

object CoreBundlesSpecs {
  val bndBootAddr = spec {
    BUNDLE("BootAddr")
      .desc("Boot address for the core domain.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("bootAddr", "UInt(programMemoryAddrWidth)", "Boot address for the core domain."))
      )
      .uses(paramProgramMemoryAddrWidth)
      .build()
  }

  val bndGlobalEpoch = spec {
    BUNDLE("GlobalEpoch")
      .desc("Global epoch broadcast shared across the core domain.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("globalEpoch", "UInt(epochWidth)", "Global epoch value."))
      )
      .uses(paramEpochWidth)
      .build()
  }

  val bndHartEnable = spec {
    BUNDLE("HartEnable")
      .desc("Core enable command from outer domains.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("hartEnable", "Bool", "Core enable command from outer domains."))
      )
      .build()
  }

  val bndCoreEnable = spec {
    BUNDLE("CoreEnable")
      .desc("Core enable command with boot address to fetch unit.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("bootAddr", "UInt(programMemoryAddrWidth)", "Core enable command from outer domains."))
      )
      .build()
  }

  // Placeholder pending architecture-team contract - restored for name resolution
  val bndEnable = spec {
    BUNDLE("Enable")
      .desc("Placeholder pending architecture-team contract")
      .build()
  }

  val bndInterrupt = spec {
    BUNDLE("Interrupt")
      .desc("Interrupt sources from outer domains.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("e", "Bool", "External interrupt"),
          List("t", "Bool", "Timer interrupt"),
          List("s", "Bool", "Software interrupt")
        )
      )
      .note("Need configurable support for CLIC")
      .note("Interrupt types are defined in Types.scala")
      .note(
        "ADR-004 C3: the mie/mip enable+pending computation stays in the CSR and is exported as the bndInterruptCtrl view (owned by WP-A BackendBundlesSpecs); the decision to take moves to CommitUnit, which samples only at a retire boundary. This external Interrupt bundle is the raw source-line input, sampled at the commit head."
      )
      .build()
  }

  val bndDebugReq = spec {
    BUNDLE("DebugReq")
      .desc("Debug request from external system.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("debugReq", "Bool", "Debug request signal from debug module."))
      )
      .build()
  }

  val bndExternalProgramMemoryReq = spec {
    BUNDLE("ExternalProgramMemoryReq")
      .desc(
        "Program memory request on the internal edge from FrontendTop to the InstBusAdapter, " +
        "which translates it to a TileLink channel-A Get (ADR-016)."
      )
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("addr", "UInt(programMemoryAddrWidth)", "Program memory request address."),
        )
      )
      .uses(paramProgramMemoryAddrWidth)
      .note(
        "Fetch-side txnId/attribute fields arrive with the ICache/ITLB accommodations; the " +
        "uncached base point needs only the address (the InstBusAdapter forms size/mask/source)."
      )
      .build()
  }

  val bndExternalProgramMemoryResp = spec {
    BUNDLE("ExternalProgramMemoryResp")
      .desc(
        "Program memory response on the internal edge from the InstBusAdapter back to " +
        "FrontendTop, carrying a TileLink channel-D AccessAckData payload (ADR-016)."
      )
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("data", "UInt(programMemoryDataWidth)", "Program memory response data."),
              List("exception", "Exception", "Program memory response exception signal."))
      )
      .has(bndException)
      .uses(paramProgramMemoryDataWidth)
      .build()
  }

  val bndExternalDataMemoryReq = spec {
    BUNDLE("ExternalDataMemoryReq")
      .desc(
        "Data memory request on the internal edge from MemorySubsystemTop to the " +
        "DataBusAdapter, which translates it to TileLink Get/Put on channel A (ADR-016)."
      )
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("addr", "UInt(dataMemoryAddrWidth)", "Request address."),
          List("command", "UInt(dataMemoryCommandWidth)", "Request command."),
          List("txnId", "UInt(dataMemoryTxnIdWidth)", "Request transaction ID."),
          List("mask", "UInt(dataMemoryMaskWidth)", "Request mask per byte."),
          List("size", "UInt(dataMemorySizeWidth)", "Request size. Single byte: 0, Half word: 1, Word: 2"),
          List("data", "UInt(dataMemoryDataWidth)", "Request data field.")
        )
      )
      .entry("command", "Read, Write")
      .entry("txnId", "Index of request queue entry")
      .entry("size", "Size of the request. Single byte = 0, Half word = 1, Word = 2")
      .entry("data", "Request channel data field used for write")
      .note("Burst not supported")
      .note(
        "ADR-003 D-3.13: the request meta carries txnId (load transaction id) and the seqTag of the originating committed store, so the memory subsystem can reunite load responses by txnId and StoreComplete by seqTag (ADR-003 M4). txnId width derives from LoadOutstanding (0 when single-outstanding)."
      )
      .uses(paramDataMemoryAddrWidth, paramDataMemoryDataWidth, paramDataMemoryTxnIdWidth,
      paramDataMemorySizeWidth, paramDataMemoryMaskWidth, paramDataMemoryCommandWidth)
      .build()
  }

  val bndExternalDataMemoryResp = spec {
    BUNDLE("ExternalDataMemoryResp")
      .desc(
        "Data memory response on the internal edge from the DataBusAdapter back to " +
        "MemorySubsystemTop, carrying a TileLink channel-D payload reunited by txnId (ADR-016)."
      )
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("data", "UInt(dataMemoryDataWidth)", "Data memory response data."),
          List("command", "UInt(dataMemoryCommandWidth)", "Data memory response command."),
          List("txnId", "UInt(dataMemoryTxnIdWidth)", "Data memory response transaction ID."),
          List("exception", "Exception", "Data memory response exception signal.")
        )
      )
      .has(bndException)
      .uses(paramDataMemoryDataWidth,
      paramDataMemoryCommandWidth,
      paramDataMemoryTxnIdWidth)
      .note("There is no permission check... not yet")
      .note("There is no address translation... not yet")
      .note(
        "ADR-003 D-3.13: the response meta carries txnId/seqTag so the ResponseArbiter can reassociate unordered responses back to the backend (loads by txnId, StoreComplete by seqTag)."
      )
      .build()
  }

  val bndException = spec {
    BUNDLE("Exception")
      .desc("Exceptions from outer memory interfaces.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("ae", "Bool", "Access exception while page table walking."),
          List("ma", "Bool", "Misaligned exception."),
          List("pf", "Bool", "Page fault exception.")
        )
      )
      .note(
        "ADR-016: cache and TLB are in-core accommodations (capCacheHierarchy / " +
        "capAddressTranslation). Translation faults (pf) are raised by the in-core TLB " +
        "vertices; bus access faults (ae) originate as TileLink channel-D denied/corrupt, " +
        "converted by the bus adapters. Nothing outside the TileLink boundary raises exceptions."
      )
      .build()
  }
}