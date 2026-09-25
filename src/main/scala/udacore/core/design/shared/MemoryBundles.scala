package udacore.core.design.shared

import chisel3._
import framework.macros.LocalSpec
import udacore.core.spec.shared.CoreBundlesSpecs.bndDebugReq
import udacore.core.spec.shared.MemoryBundlesSpecs.{bndCacheMaintenance, bndTlbFlush}

/** Core-shared maintenance and debug bundles consumed by the backend CommitUnit. */

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
