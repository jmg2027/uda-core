package udacore.frontend.design.shared

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared.CfiOutcome
import udacore.frontend.spec.shared.FrontendBundlesSpecs._

/** Frontend bundles (FrontendBundlesSpecs). Widths are explicit on the two bundles the backend
  * DecodeUnit consumes, so the backend can build them from its BackendFrontendView mirror; the
  * rest take FrontendParams. Plain UInt codes keep every port pokeable by the L1 SpecTests.
  *
  * ADR-019G: every fetchPc below is the requested (possibly mid-block) fetch PC except
  * PredictorTrain.fetchPc, which is the aligned block base; blockBase and startSlot are derived
  * combinationally (FetchPc).
  */

/** FetchFault codes (bndFetchInst.fault). */
object FetchFault {
  val width           = 2
  val None            = 0.U(width.W)
  val InstPageFault   = 1.U(width.W)
  val InstAccessFault = 2.U(width.W)
}

/** ADR-019G E-1: blockBase = alignDown(fetchPc, FetchBytes), startSlot = (fetchPc - blockBase) / 4. */
object FetchPc {
  def blockBase(pc: UInt, offsetBits: Int): UInt = chisel3.util.Cat(pc(pc.getWidth - 1, offsetBits), 0.U(offsetBits.W))
  def startSlot(pc: UInt, offsetBits: Int): UInt = pc(offsetBits - 1, 2)
  /** cfiPc = blockBase + 4 * slot (ADR-019G E-5). */
  def slotPc(base: UInt, slot: UInt): UInt = base + (slot << 2)
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

@LocalSpec(bndPredictReq)
class PredictReq(vAddrWidth: Int) extends Bundle {
  val fetchPc = UInt(vAddrWidth.W)
}

@LocalSpec(bndPredictorMeta)
class PredictorMeta(val params: FrontendParams) extends FrontendBundle {
  val btbHit      = Bool()
  val btbWay      = UInt(params.btbWayWidth.W)
  val provider    = UInt(params.providerWidth.W)
  val providerCtr = SInt(3.W)
  val altPred     = Bool()
  val useAlt      = Bool()
  val hitMask     = UInt(params.tageTables.W)
}

@LocalSpec(bndHistoryCheckpoint)
class HistoryCheckpoint(val params: FrontendParams) extends FrontendBundle {
  val ghr        = UInt(params.ghrLength.W)
  val rasTop     = UInt(params.rasTopWidth.W)
  val rasEntries = Vec(params.tuning.rasDepth, UInt(params.vAddrWidth.W))
}

@LocalSpec(bndPrediction)
class Prediction(val params: FrontendParams) extends FrontendBundle {
  val fetchPc    = UInt(vAddrWidth.W)
  val cfiValid   = Bool()
  val cfiSlot    = UInt(params.slotWidth.W)
  val cfiType    = UInt(udacore.backend.design.shared.CfiType.width.W)
  val taken      = Bool()
  val target     = UInt(vAddrWidth.W)
  val nextPc     = UInt(vAddrWidth.W)
  val meta       = new PredictorMeta(params)
  val checkpoint = new HistoryCheckpoint(params)
}

@LocalSpec(bndFetchRequest)
class FetchRequest(val params: FrontendParams) extends FrontendBundle {
  val ftqIdx     = UInt(params.ftqIdxWidth.W)
  val fetchPc    = UInt(vAddrWidth.W)
  val lastSlot   = UInt(params.slotWidth.W)
  val exitTaken  = Bool()
  val exitTarget = UInt(vAddrWidth.W)
}

@LocalSpec(bndFetchBlock)
class FetchBlock(val params: FrontendParams) extends FrontendBundle {
  val ftqIdx     = UInt(params.ftqIdxWidth.W)
  val basePc     = UInt(vAddrWidth.W)
  val insts      = Vec(fetchWidth, UInt(32.W))
  val slotValid  = Vec(fetchWidth, Bool())
  val exitTaken  = Bool()
  val exitTarget = UInt(vAddrWidth.W)
  val fault      = UInt(FetchFault.width.W)
}

@LocalSpec(bndHistoryRestore)
class HistoryRestore(val params: FrontendParams) extends FrontendBundle {
  val checkpoint   = new HistoryCheckpoint(params)
  val applyOutcome = Bool()
  val outcome      = new CfiOutcome(params.backend)
  val pc           = UInt(vAddrWidth.W)
}

@LocalSpec(bndPredictorTrain)
class PredictorTrain(val params: FrontendParams) extends FrontendBundle {
  val fetchPc   = UInt(vAddrWidth.W)
  val ghr       = UInt(params.ghrLength.W)
  val meta      = new PredictorMeta(params)
  val predicted = new Prediction(params)
  val committed = new CfiOutcome(params.backend)
}
