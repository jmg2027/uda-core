package udacore.frontend.design.shared

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.spec.shared.FrontendBundlesSpecs.{bndFetchInst, bndFetchPacket}

/** Frontend bundles consumed by the backend DecodeUnit (FrontendBundlesSpecs). Widths are
  * explicit so the backend can build them from its BackendFrontendView mirror. */

/** FetchFault codes (bndFetchInst.fault). */
object FetchFault {
  val width           = 2
  val None            = 0.U(width.W)
  val InstPageFault   = 1.U(width.W)
  val InstAccessFault = 2.U(width.W)
}

@LocalSpec(bndFetchInst)
class FetchInst(vAddrWidth: Int, ftqIdxWidth: Int, slotWidth: Int) extends Bundle {
  val inst            = UInt(32.W)
  val pc              = UInt(vAddrWidth.W)
  val ftqIdx          = UInt(ftqIdxWidth.W)
  val slot            = UInt(slotWidth.W)
  val blockEnd        = Bool()
  val predictedTaken  = Bool()
  val predictedTarget = UInt(vAddrWidth.W)
  val fault           = UInt(FetchFault.width.W)
}

@LocalSpec(bndFetchPacket)
class FetchPacket(decodeWidth: Int, vAddrWidth: Int, ftqIdxWidth: Int, slotWidth: Int) extends Bundle {
  val insts = Vec(decodeWidth, new FetchInst(vAddrWidth, ftqIdxWidth, slotWidth))
  val valid = Vec(decodeWidth, Bool())
}
