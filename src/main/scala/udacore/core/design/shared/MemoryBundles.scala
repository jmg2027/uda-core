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
