package udacore.core.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.core.spec.shared.CoreParamsSpecs._

/** MMU and L1 cache edge bundles (ADR-019 WP-6/WP-7).
  *
  * Every field states whether an address is virtual or physical. Physical
  * addresses are authoritative for memory ordering and forwarding; virtual
  * index bits are used only where the VIPT legality (propViptGeometryLegal)
  * makes them equal to physical bits.
  */
object MemoryBundlesSpecs {

  // ---- Sv32 ------------------------------------------------------------------

  val bndSv32Pte = spec {
    BUNDLE("Sv32Pte")
      .desc("Sv32 page-table entry as read by the PTW (RISC-V privileged spec).")
      .markdownTable(
        List("Bits", "Field", "Meaning"),
        List(
          List("31:20", "PPN[1]", "12 bits"),
          List("19:10", "PPN[0]", "10 bits"),
          List("9:8", "RSW", "ignored"),
          List("7", "D", "dirty"),
          List("6", "A", "accessed"),
          List("5", "G", "global mapping"),
          List("4", "U", "user-accessible"),
          List("3:1", "X W R", "permissions; all zero = pointer to next level"),
          List("0", "V", "valid")
        )
      )
      .build()
  }

  val bndTlbEntry = spec {
    BUNDLE("TlbEntry")
      .desc("One fully-associative TLB entry (ITLB or DTLB).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("valid", "Bool", "Entry holds a translation."),
          List("vpn", "UInt(20)", "Virtual page number; for a superpage only VPN[1] is compared."),
          List("superpage", "Bool", "4 MiB leaf found at level 1."),
          List("ppn", "UInt(22)", "Physical page number; for a superpage PPN[0] is replaced by VPN[0]."),
          List("asid", "UInt(9)", "Address-space id of the walk that filled it."),
          List("global", "Bool", "G of any PTE on the walk (non-leaf or leaf): matches every ASID."),
          List("r, w, x, u, a, d", "Bool", "Leaf PTE permission/status bits."),
          List("pma", "PmaAttr", "Physical attributes of the page from paramPmaMap.")
        )
      )
      .uses(paramTlbGeometry, paramPmaMap)
      .build()
  }

  val bndTranslateReq = spec {
    BUNDLE("TranslateReq")
      .desc("A translation request into a TLB.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("reqId", "UInt", "Requester-local id (FetchUnit fetch generation, or LSQ entry id and generation)."),
          List("vaddr", "UInt(vAddrWidth)", "Virtual address."),
          List("access", "AccessType", "Fetch | Load | Store (selects permission check and fault cause).")
        )
      )
      .uses(paramVAddrWidth)
      .build()
  }

  val bndTranslation = spec {
    BUNDLE("Translation")
      .desc("A TLB answer for one request.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("reqId", "UInt", "Echo of the request id."),
          List("status", "TranslationStatus", "Hit | Miss (walk started or joined) | PageFault | AccessFault."),
          List("paddr", "UInt(pAddrWidth)", "Physical address (Hit)."),
          List("cacheable", "Bool", "PMA attribute of paddr (Hit).")
        )
      )
      .uses(paramPAddrWidth)
      .build()
  }

  val bndWalkReq = spec {
    BUNDLE("WalkReq")
      .desc("Miss handoff from a TLB to the shared PTW.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("vpn", "UInt(20)", "Virtual page number to walk."),
          List("context", "TranslationContext", "satp root and ASID captured at the miss.")
        )
      )
      .build()
  }

  val bndWalkResp = spec {
    BUNDLE("WalkResp")
      .desc("Walk result from the PTW to the requesting TLB.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("vpn", "UInt(20)", "Walked VPN."),
          List("status", "WalkStatus", "Leaf | PageFault (invalid, reserved W-without-R, misaligned superpage, no leaf at level 0) | AccessFault (PMA-illegal PTE address or denied PTE read) | Retry (the walk overlapped an SFENCE.VMA; nothing may be installed)."),
          List("entry", "TlbEntry", "Refill contents (Leaf).")
        )
      )
      .uses(bndTlbEntry)
      .build()
  }

  val bndPtwMemReq = spec {
    BUNDLE("PtwMemReq")
      .desc("A PTE read issued by the PTW. Physical address; never translated.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("paddr", "UInt(pAddrWidth)", "Physical address of the 4-byte PTE."))
      )
      .uses(paramPAddrWidth)
      .build()
  }

  val bndPtwMemResp = spec {
    BUNDLE("PtwMemResp")
      .desc("PTE read data from the D-cache.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("pte", "Sv32Pte", "Entry read."),
          List("accessFault", "Bool", "The read was denied on the bus.")
        )
      )
      .uses(bndSv32Pte)
      .build()
  }

  val bndTlbFlush = spec {
    BUNDLE("TlbFlush")
      .desc("SFENCE.VMA service token. v0 carries the rs1/rs2 operands but every encoding invalidates all entries.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("vaddr, vaddrValid", "UInt(vAddrWidth), Bool", "rs1 operand (ignored in v0)."),
          List("asid, asidValid", "UInt(9), Bool", "rs2 operand (ignored in v0).")
        )
      )
      .build()
  }

  // ---- Instruction side ------------------------------------------------------

  val bndICacheReq = spec {
    BUNDLE("ICacheReq")
      .desc("Fetch lookup into the VIPT I-cache, issued in the same cycle as the matching ITLB request.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("vaddr", "UInt(vAddrWidth)", "Fetch-block virtual address; set index from untranslated bits."),
          List("reqId", "UInt", "FetchUnit fetch generation (stale-response filter).")
        )
      )
      .build()
  }

  val bndICacheResp = spec {
    BUNDLE("ICacheResp")
      .desc("Fetch-block answer from the I-cache to the FetchUnit.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("reqId", "UInt", "Echo."),
          List("data", "Vec(4, UInt(32))", "The aligned 16-byte fetch block."),
          List("fault", "FetchFault", "None | InstPageFault | InstAccessFault (translation or fill denied).")
        )
      )
      .build()
  }

  // ---- Data side ---------------------------------------------------------------

  val bndDCacheLoadReq = spec {
    BUNDLE("DCacheLoadReq")
      .desc("Speculative cached load lookup into the VIPT D-cache, issued in the same cycle as the matching DTLB request. Uncacheable loads never use this path after their first (Uncacheable-answered) lookup.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("lqIdx, lqGen", "UInt", "LQ entry and its allocation generation."),
          List("vaddr", "UInt(vAddrWidth)", "Load virtual address; set index from untranslated bits."),
          List("size", "UInt(2)", "Access size.")
        )
      )
      .build()
  }

  val bndDCacheLoadResp = spec {
    BUNDLE("DCacheLoadResp")
      .desc("Load answer; may return out of order (hit-under-miss) and is reassociated by lqIdx/lqGen.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("lqIdx, lqGen", "UInt", "Echo."),
          List("status", "LoadStatus", "Data | TlbMiss | Replay (no MSHR/target free) | PageFault | AccessFault | Uncacheable (must retry at head)."),
          List("paddr", "UInt(pAddrWidth)", "Translated physical address (every status after a TLB hit)."),
          List("data", "UInt(XLen)", "Loaded bytes (Data).")
        )
      )
      .uses(paramPAddrWidth)
      .build()
  }

  val bndStoreDrainReq = spec {
    BUNDLE("StoreDrainReq")
      .desc("One committed store from the StoreBuffer head into the D-cache. Physical address.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("paddr", "UInt(pAddrWidth)", "Physical address."),
          List("data, mask", "UInt(XLen), UInt(XLen/8)", "Bytes.")
        )
      )
      .build()
  }

  val bndStoreDrainResp = spec {
    BUNDLE("StoreDrainResp")
      .desc("Completion of one committed cacheable store drain (the bytes are in the array). Never faults: cacheable regions are fill/writeback-fault-free by the paramPmaMap contract.")
      .build()
  }

  val bndUncachedLoadReq = spec {
    BUNDLE("UncachedLoadReq")
      .desc("LSQ to D-cache uncached port: the single bus read of a granted uncacheable load. Physical address; no TLB pairing.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("lqIdx, lqGen", "UInt", "LQ entry and allocation generation (response reassociation)."),
          List("paddr", "UInt(pAddrWidth)", "Physical address recorded from the first lookup's translation."),
          List("size", "UInt(2)", "Access size (Get of that size).")
        )
      )
      .uses(paramPAddrWidth)
      .build()
  }

  val bndUncachedLoadResp = spec {
    BUNDLE("UncachedLoadResp")
      .desc("Bus answer of an uncached load, returned only after the TileLink AccessAckData.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("lqIdx, lqGen", "UInt", "Echo."),
          List("data", "UInt(XLen)", "Raw loaded bytes (the LSQ sign/zero-extends)."),
          List("accessFault", "Bool", "TileLink denied/corrupt: precise load access fault, the load is still at the ROB head.")
        )
      )
      .build()
  }

  val bndUncachedStoreReq = spec {
    BUNDLE("UncachedStoreReq")
      .desc("LSQ to D-cache uncached port: the single bus write of a granted uncacheable store. Physical address.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("sqIdx", "UInt", "SQ entry (response reassociation)."),
          List("paddr", "UInt(pAddrWidth)", "Physical address."),
          List("data, mask", "UInt(XLen), UInt(XLen/8)", "Bytes (PutPartialData).")
        )
      )
      .uses(paramPAddrWidth)
      .build()
  }

  val bndUncachedStoreResp = spec {
    BUNDLE("UncachedStoreResp")
      .desc("Bus acknowledgement of an uncached store, returned only after the TileLink AccessAck.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("sqIdx", "UInt", "Echo."),
          List("accessFault", "Bool", "TileLink denied/corrupt: precise store access fault, the store is still at the ROB head.")
        )
      )
      .build()
  }

  val bndCacheMaintenance = spec {
    BUNDLE("CacheMaintenance")
      .desc("fence.i maintenance token: I-cache invalidate-all, or D-cache clean-all (write back every dirty line, lines stay valid).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("op", "MaintOp", "ICacheInvalidateAll | DCacheCleanAll"))
      )
      .build()
  }

  val bndInstMemReq = spec {
    BUNDLE("InstMemReq")
      .desc("I-cache to InstBusAdapter: line fill (Get of lineBytes) or uncached fetch Get. Physical address.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("paddr", "UInt(pAddrWidth)", "Line-aligned (fill) or block-aligned (uncached)."),
          List("size", "UInt", "log2 bytes: lineBytes for a fill, 16 for an uncached block.")
        )
      )
      .build()
  }

  val bndInstMemResp = spec {
    BUNDLE("InstMemResp")
      .desc("InstBusAdapter to I-cache: one AccessAckData beat.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("data", "UInt(XLen)", "Beat."),
          List("last", "Bool", "Final beat."),
          List("denied", "Bool", "TileLink denied/corrupt: instruction access fault.")
        )
      )
      .build()
  }

  val bndDataMemReq = spec {
    BUNDLE("DataMemReq")
      .desc("D-cache to DataBusAdapter: line fill, dirty-line writeback, or uncached access. Physical address.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("op", "DataMemOp", "GetLine | PutLine | GetUncached | PutUncached"),
          List("source", "UInt", "MSHR id, writeback id, or uncached id."),
          List("paddr", "UInt(pAddrWidth)", "Address."),
          List("data, mask, last", "", "Put beats.")
        )
      )
      .build()
  }

  val bndDataMemResp = spec {
    BUNDLE("DataMemResp")
      .desc("DataBusAdapter to D-cache: AccessAck/AccessAckData beats reassociated by source.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("source", "UInt", "Echo."),
          List("data, last", "UInt(XLen), Bool", "Beat."),
          List("denied", "Bool", "Access fault.")
        )
      )
      .build()
  }
}
