package udacore.core.design.shared

import chisel3._
import framework.macros.LocalSpec
import udacore.core.spec.shared.CoreBundlesSpecs.bndDebugReq
import udacore.core.spec.shared.MemoryBundlesSpecs._

/** Core-shared memory, maintenance, and debug bundles consumed by the backend. */

/** bndCacheMaintenance.op codes. */
object MaintOp {
  val width             = 1
  val ICacheInvalidateAll = 0.U(width.W)
  val DCacheCleanAll      = 1.U(width.W)
}

@LocalSpec(bndCacheMaintenance)
class CacheMaintenance extends Bundle {
  val op = UInt(MaintOp.width.W)
}

@LocalSpec(bndTlbFlush)
class TlbFlush(vAddrWidth: Int) extends Bundle {
  val vaddr      = UInt(vAddrWidth.W)
  val vaddrValid = Bool()
  val asid       = UInt(9.W)
  val asidValid  = Bool()
}

@LocalSpec(bndDebugReq)
class DebugReq extends Bundle {
  val debugReq = Bool()
}

// ---- Translation, D-cache load, and uncached-port bundles (MemoryBundlesSpecs) --------------------
//
// Plain UInt codes (not ChiselEnum) keep every port pokeable by the L1 SpecTests. Data words
// travel lane-aligned: byte i of a word sits in bits [8i+7:8i] of the naturally aligned word
// that contains the access.

/** bndTranslateReq.access. */
object AccessType {
  val width = 2
  val Fetch = 0.U(width.W)
  val Load  = 1.U(width.W)
  val Store = 2.U(width.W)
}

/** bndTranslation.status. */
object TranslationStatus {
  val width       = 2
  val Hit         = 0.U(width.W)
  val Miss        = 1.U(width.W)
  val PageFault   = 2.U(width.W)
  val AccessFault = 3.U(width.W)
}

/** bndDCacheLoadResp.status. */
object LoadStatus {
  val width       = 3
  val Data        = 0.U(width.W)
  val TlbMiss     = 1.U(width.W)
  val Replay      = 2.U(width.W)
  val PageFault   = 3.U(width.W)
  val AccessFault = 4.U(width.W)
  val Uncacheable = 5.U(width.W)
}

/** bndWalkResp.status. */
object WalkStatus {
  val width       = 2
  val Leaf        = 0.U(width.W)
  val PageFault   = 1.U(width.W)
  val AccessFault = 2.U(width.W)
  val Retry       = 3.U(width.W)
}

@LocalSpec(bndTranslateReq)
class TranslateReq(reqIdWidth: Int, vAddrWidth: Int) extends Bundle {
  val reqId  = UInt(reqIdWidth.W)
  val vaddr  = UInt(vAddrWidth.W)
  val access = UInt(AccessType.width.W)
}

@LocalSpec(bndTranslation)
class Translation(reqIdWidth: Int, pAddrWidth: Int) extends Bundle {
  val reqId     = UInt(reqIdWidth.W)
  val status    = UInt(TranslationStatus.width.W)
  val paddr     = UInt(pAddrWidth.W)
  val cacheable = Bool()
}

/** PmaAttr of paramPmaMap. */
class PmaAttr extends Bundle {
  val cacheable  = Bool()
  val executable = Bool()
  val readable   = Bool()
  val writable   = Bool()
}

@LocalSpec(bndTlbEntry)
class TlbEntry extends Bundle {
  val valid     = Bool()
  val vpn       = UInt(20.W)
  val superpage = Bool()
  val ppn       = UInt(22.W)
  val asid      = UInt(9.W)
  val global    = Bool()
  val r, w, x, u, a, d = Bool()
  val pma       = new PmaAttr
}

/** TLB -> PTW miss handoff: the page to walk and the committed TranslationContext captured at the
  * miss (satp root and ASID; the PTW walks with this snapshot, not the live CSRs). */
@LocalSpec(bndWalkReq)
class WalkReq extends Bundle {
  val vpn     = UInt(20.W)
  val context = new udacore.backend.design.shared.TranslationContext
}

/** Sv32 page-table entry in memory order (field order = bits 31..0, so asUInt is the raw PTE). */
@LocalSpec(bndSv32Pte)
class Sv32Pte extends Bundle {
  val ppn1 = UInt(12.W)
  val ppn0 = UInt(10.W)
  val rsw  = UInt(2.W)
  val d, a, g, u, x, w, r, v = Bool()
  def ppn: UInt = chisel3.util.Cat(ppn1, ppn0)
}

/** A physical PTE read from the PageTableWalker to the DataCache: no virtual address, no translation metadata. */
@LocalSpec(bndPtwMemReq)
class PtwMemReq(pAddrWidth: Int) extends Bundle {
  val paddr = UInt(pAddrWidth.W)
}

@LocalSpec(bndPtwMemResp)
class PtwMemResp extends Bundle {
  val pte         = new Sv32Pte
  val accessFault = Bool()
}

@LocalSpec(bndWalkResp)
class WalkResp extends Bundle {
  val vpn    = UInt(20.W)
  val status = UInt(WalkStatus.width.W)
  val entry  = new TlbEntry
}

@LocalSpec(bndDCacheLoadReq)
class DCacheLoadReq(lqIdxWidth: Int, lqGenWidth: Int, vAddrWidth: Int) extends Bundle {
  val lqIdx = UInt(lqIdxWidth.W)
  val lqGen = UInt(lqGenWidth.W)
  val vaddr = UInt(vAddrWidth.W)
  val size  = UInt(2.W)
}

@LocalSpec(bndDCacheLoadResp)
class DCacheLoadResp(lqIdxWidth: Int, lqGenWidth: Int, pAddrWidth: Int, dataWidth: Int) extends Bundle {
  val lqIdx  = UInt(lqIdxWidth.W)
  val lqGen  = UInt(lqGenWidth.W)
  val status = UInt(LoadStatus.width.W)
  val paddr  = UInt(pAddrWidth.W)
  val data   = UInt(dataWidth.W)
}

@LocalSpec(bndUncachedLoadReq)
class UncachedLoadReq(lqIdxWidth: Int, lqGenWidth: Int, pAddrWidth: Int) extends Bundle {
  val lqIdx = UInt(lqIdxWidth.W)
  val lqGen = UInt(lqGenWidth.W)
  val paddr = UInt(pAddrWidth.W)
  val size  = UInt(2.W)
}

@LocalSpec(bndUncachedLoadResp)
class UncachedLoadResp(lqIdxWidth: Int, lqGenWidth: Int, dataWidth: Int) extends Bundle {
  val lqIdx       = UInt(lqIdxWidth.W)
  val lqGen       = UInt(lqGenWidth.W)
  val data        = UInt(dataWidth.W)
  val accessFault = Bool()
}

@LocalSpec(bndUncachedStoreReq)
class UncachedStoreReq(sqIdxWidth: Int, pAddrWidth: Int, dataWidth: Int) extends Bundle {
  val sqIdx = UInt(sqIdxWidth.W)
  val paddr = UInt(pAddrWidth.W)
  val data  = UInt(dataWidth.W)
  val mask  = UInt((dataWidth / 8).W)
}

@LocalSpec(bndUncachedStoreResp)
class UncachedStoreResp(sqIdxWidth: Int) extends Bundle {
  val sqIdx       = UInt(sqIdxWidth.W)
  val accessFault = Bool()
}

@LocalSpec(bndStoreDrainReq)
class StoreDrainReq(pAddrWidth: Int, dataWidth: Int) extends Bundle {
  val paddr = UInt(pAddrWidth.W)
  val data  = UInt(dataWidth.W)
  val mask  = UInt((dataWidth / 8).W)
}

/** bndStoreDrainResp carries no fields: the handshake itself is the completion. */
@LocalSpec(bndStoreDrainResp)
class StoreDrainResp extends Bundle

// ---- Instruction side (MemoryBundlesSpecs) ---------------------------------------------------------

@LocalSpec(bndICacheReq)
class ICacheReq(reqIdWidth: Int, vAddrWidth: Int) extends Bundle {
  val vaddr = UInt(vAddrWidth.W)
  val reqId = UInt(reqIdWidth.W)
}

/** I-cache to InstBusAdapter: one Get of 2^size bytes at paddr (a 64-byte line fill or a 16-byte
  * uncached block, v0). sizeBits is the instruction link's TileLink size width. */
@LocalSpec(bndInstMemReq)
class InstMemReq(pAddrWidth: Int, sizeBits: Int) extends Bundle {
  val paddr = UInt(pAddrWidth.W)
  val size  = UInt(sizeBits.W)
}

/** InstBusAdapter to I-cache: one AccessAckData beat; denied = TileLink denied or corrupt. */
@LocalSpec(bndInstMemResp)
class InstMemResp(dataWidth: Int) extends Bundle {
  val data   = UInt(dataWidth.W)
  val last   = Bool()
  val denied = Bool()
}

/** fault uses the FetchFault codes (None 0, InstPageFault 1, InstAccessFault 2). */
@LocalSpec(bndICacheResp)
class ICacheResp(reqIdWidth: Int, fetchWidth: Int) extends Bundle {
  val reqId = UInt(reqIdWidth.W)
  val data  = Vec(fetchWidth, UInt(32.W))
  val fault = UInt(2.W)
}
