package udacore.external.multiplier.design

import chisel3._
import chisel3.util._
import udacore.common.ControlSignal._
import udacore.common.util.Util.ceilDiv
import udacore.common.util.seg.{SegIdx, SegVec}
import framework.macros.LocalSpec
import udacore.external.multiplier.spec.MultiplierSpecs._

// MultiplierPacket definition for CoreIO
class MultiplierPacket(dataWidth: Int) extends Bundle {
  val src1 = UInt(dataWidth.W)
  val src2 = UInt(dataWidth.W)
  val op   = MultiplierControl()
}

@LocalSpec(paramMultiplier)
case class MultiplierParams(
    dataWidth: Int = 32,
    sliceWidth: Int = 16,
    slicesPerCycle: Int = 1,
    accumulationMethod: AccumMethod.Value = AccumMethod.IterativeAdder,
    fusion: Boolean = true,
    earlyOutZero: Boolean = true,
    earlyOutOne: Boolean = true,
    earlyOutUpper16IsZero: Boolean = true,
    vectorMode: Boolean = false,
    useDSP: Boolean = false
) {
  require(
    dataWidth % sliceWidth == 0,
    s"sliceWidth ($sliceWidth) must divide dataWidth ($dataWidth)"
  )
}

/** Iterative multiplier with carry-save reduction
  * @param p
  *   multiplier configuration
  */
@LocalSpec(intfMultiplierIO)
class MultiplierIO(dataWidth: Int) extends Bundle {
  val start  = Input(Bool())
  val kill   = Input(Bool())
  val op     = Input(MultiplierControl())
  val src1   = Input(UInt(dataWidth.W))
  val src2   = Input(UInt(dataWidth.W))
  val busy   = Output(Bool())
  val done   = Output(Bool())
  val result = Output(UInt(dataWidth.W))
}



@LocalSpec(contMultiplier)
class Multiplier(params: MultiplierParams) extends Module {
  val io = IO(new MultiplierIO(params.dataWidth))

  val core = Module(new IterativeAdderCore(params))

  val dataWidth = params.dataWidth

  val startAccepted = io.start && !core.io.busy && !io.kill

  @LocalSpec(funcDriveMultiplierCore)
  val driveCore = {
    core.io.packet.src1 := io.src1
    core.io.packet.src2 := io.src2
    core.io.packet.op   := io.op

    core.io.start := startAccepted
    core.io.kill  := io.kill
  }

  @LocalSpec(funcExposeMultiplierStatus)
  val exposeStatus = {
    io.busy   := core.io.busy && !io.kill
    io.done   := core.io.done && !io.kill
    io.result := core.io.result
  }
}


// AGENT: DO NOT TOUCH CORE LOGICS
// Verified multiplier core ported from the main line's klase32_rvv_coprocessor
// (klase32.functionalunit.multiplier: MulCore / IterativeAdderCore / SliceMultiplier).
// This is the CORRECTED lineage: the earlier functionalunit/ copy truncated the
// slice product to 2*width (losing the sign of a large unsigned slice product) and
// gated the upper-half fast path with `||` (either half zero) instead of `&&` (both
// halves zero), so MULH/MULHSU at multi-slice configs (e.g. 16,1) produced wrong high
// words. The rvv_coprocessor core fixes both: SliceMultiplier exposes the full,
// untruncated signed product (pFull, 2*width+2 bits) and the accumulator sign-extends
// each partial product before the carry-propagate fold; earlyOutBothHalfZero requires
// BOTH upper halves zero and is gated to segmentCount <= 2. Adapted to the rebuild top
// IO (packet.src1/src2) and MultiplierParams (ADR-018 owner directive).
//
// Parameter-driven multi-cycle multiplier.
//   dataWidth (multiple of sliceWidth) / sliceWidth (power of two, divides dataWidth) /
//   slicesPerCycle (partial products issued per cycle). MUL / MULH / MULHSU / MULHU.
// Completion: MUL finishes once the low partial products are done; MULH* needs all of
// them; a fast-hit on a zero upper half of BOTH operands gives MULH* = 0 (0 busy
// cycles) and MUL only the low PPs.

/** Enumeration of accumulation methods. */
object AccumMethod extends Enumeration {
  type AccumMethod = Value
  val IterativeAdder, CarrySaveTree = Value
}

/** One signed/unsigned slice product with per-operand MSB sign extension. */
class SliceMultiplier(width: Int) extends Module {
  val io = IO(new Bundle {
    val a, b = Input(UInt(width.W))
    val aIsMsb, bIsMsb = Input(Bool())
    val aIsSigned, bIsSigned = Input(Bool())
    // Full, untruncated signed product. Truncating to 2*width loses the true sign of a
    // large unsigned slice product (e.g. 0xFFFF*0xFFFF reads negative in 2*width bits);
    // the full width lets accumulators sign-extend the slice result correctly to the
    // whole product width.
    val pFull = Output(SInt((2 * width + 2).W))
    val en = Input(Bool())
  })

  val aExtended = Wire(SInt((width + 1).W))
  val bExtended = Wire(SInt((width + 1).W))

  val productResult = Wire(SInt((2 * width + 2).W))

  val aSignBit = io.aIsMsb && io.aIsSigned && io.a(width - 1)
  val bSignBit = io.bIsMsb && io.bIsSigned && io.b(width - 1)

  aExtended := Cat(aSignBit, io.a).asSInt
  bExtended := Cat(bSignBit, io.b).asSInt

  when(io.en) {
    productResult := (aExtended * bExtended).asSInt
  }.otherwise {
    productResult := 0.S
  }
  io.pFull := productResult
}

class CoreIO(dataWidth: Int) extends Bundle {
  val packet = Input(new MultiplierPacket(dataWidth))
  val start  = Input(Bool())
  val kill   = Input(Bool())
  val busy   = Output(Bool())
  val done   = Output(Bool())
  val result = Output(UInt(dataWidth.W))
}

/** Shared control skeleton for the multi-cycle multiplier core.
  *
  * Owns everything that is independent of how partial products are summed: the phase
  * schedule, early-out detection, the slice multipliers and their per-phase operand
  * routing, and the busy/done FSM. Subclasses implement only the accumulation datapath
  * and drive io.result.
  */
abstract class MulCore(val parameters: MultiplierParams) extends Module {
  val io = IO(new CoreIO(parameters.dataWidth))

  protected val dataWidth  = parameters.dataWidth
  protected val sliceWidth = parameters.sliceWidth
  protected val nSlice     = parameters.slicesPerCycle

  protected val segmentCount  = dataWidth / sliceWidth
  protected val ppTotal       = segmentCount * segmentCount
  protected val ppLow         = segmentCount * (segmentCount + 1) / 2
  protected val ppLowEarlyOut = (segmentCount / 2) * (segmentCount / 2) max 1
  protected val maxPhase      = ceilDiv(ppTotal, nSlice)
  protected val phaseWidth    = log2Ceil(maxPhase) max 1
  protected val msbIdx        = segmentCount - 1

  require(isPow2(sliceWidth), s"sliceWidth ($sliceWidth) must be a power of two")
  require(dataWidth % sliceWidth == 0, s"sliceWidth ($sliceWidth) must divide dataWidth ($dataWidth)")
  require(nSlice >= 1 && nSlice <= ppTotal, s"slicesPerCycle ($nSlice) must be in 1..ppTotal ($ppTotal)")

  // Partial products in column-ascending issue order: by column (i + j), then by i. This
  // puts the MUL low-result products (columns 0..segmentCount-1) first, so the early-out
  // phase targets cover exactly them, and it lets the accumulator finalize low columns
  // before high ones.
  protected val sliceIndexTable = {
    val ordered = (for (i <- 0 until segmentCount; j <- 0 until segmentCount) yield (i, j))
      .sortBy { case (i, j) => (i + j, i) }
    ordered.map { case (i, j) => ((i + j) * sliceWidth, (i, j)) }.toVector
  }

  // Micro-opcodes
  protected val isLow     = io.packet.op === MultiplierControl.MUL
  protected val aIsSigned = (io.packet.op === MultiplierControl.MULH ||
    io.packet.op === MultiplierControl.MULHSU)
  protected val bIsSigned = (io.packet.op === MultiplierControl.MULH)
  // x*1 == x only collapses to "phase 0" when a single phase already covers the whole
  // product (segmentCount == 1); for multi-segment schedules phase 0 is just one partial
  // product, so the shortcut is gated off there.
  protected val earlyOutOne = (io.packet.src1 === (1.U) || io.packet.src2 === (1.U)) &&
    parameters.earlyOutOne.B && (segmentCount == 1).B
  protected val earlyOutZero = (io.packet.src1 === (0.U) || io.packet.src2 === (0.U)) &&
    parameters.earlyOutZero.B
  // The single-partial-product upper-half shortcut (ppLowEarlyOut == 1) is only valid when
  // one segment per operand covers the low half, i.e. segmentCount <= 2; for finer slicing
  // the needed products are interleaved in column order, so the shortcut is gated off and
  // the full schedule runs. It requires the upper half of BOTH operands to be zero.
  protected val aHiZero = io.packet.src1(dataWidth - 1, dataWidth / 2) === 0.U
  protected val bHiZero = io.packet.src2(dataWidth - 1, dataWidth / 2) === 0.U
  protected val earlyOutBothHalfZero =
    aHiZero && bHiZero && parameters.earlyOutUpper16IsZero.B && (segmentCount <= 2).B

  // Operation ends when phase reaches phaseTarget.
  protected val phaseTarget = Mux1H(Seq(
    (isLow && earlyOutBothHalfZero) -> (ceilDiv(ppLowEarlyOut, nSlice) - 1).U,
    (isLow && !earlyOutBothHalfZero) -> (ceilDiv(ppLow, nSlice) - 1).U,
    (!isLow && earlyOutBothHalfZero) -> 0.U,
    (!isLow && !earlyOutBothHalfZero) -> (ceilDiv(ppTotal, nSlice) - 1).U,
    (earlyOutOne || earlyOutZero) -> 0.U
  ))

  // Slice multipliers - one per lane.
  protected val slices = Seq.fill(nSlice)(Module(new SliceMultiplier(sliceWidth)))
  slices.foreach { slice =>
    slice.io.a := DontCare
    slice.io.b := DontCare
    slice.io.aIsMsb := DontCare
    slice.io.bIsMsb := DontCare
    slice.io.aIsSigned := aIsSigned
    slice.io.bIsSigned := bIsSigned
    slice.io.en := DontCare
  }

  protected val aSegment = SegVec(io.packet.src1, sliceWidth)
  protected val bSegment = SegVec(io.packet.src2, sliceWidth)

  // Phase FSM
  protected val phase    = RegInit(0.U(phaseWidth.W))
  protected val running  = RegInit(false.B)
  protected val phaseCur = WireInit(0.U(phaseWidth.W))
  phaseCur := Mux(io.start, 0.U, phase)
  protected val done = WireDefault(false.B)

  // Route each phase's partial products onto the slice multipliers. The slice outputs are
  // consumed by the subclass accumulator.
  for (p <- 0 until maxPhase) {
    for (lane <- 0 until nSlice) {
      val idx = p * nSlice + lane
      if (idx < ppTotal) {
        val (aPos, bPos) = sliceIndexTable(idx)._2
        when(phaseCur === p.U) {
          slices(lane).io.en := true.B
          slices(lane).io.a := aSegment(SegIdx(aPos))
          slices(lane).io.b := bSegment(SegIdx(bPos))
          slices(lane).io.aIsMsb := (aPos == msbIdx).B
          slices(lane).io.bIsMsb := (bPos == msbIdx).B
        }
      }
    }
  }

  when(io.start) {
    phase := 1.U
    running := phaseTarget =/= 0.U
    when(phaseTarget === 0.U) {
      done := true.B
    }
  }.elsewhen(running) {
    phase := phase + 1.U
    when(phase === phaseTarget) {
      done := true.B
      running := false.B
    }
  }

  io.busy := running
  io.done := done

  protected val accW = 2 * dataWidth

  /** This phase's active partial products, each sign-extended (via the slice's untruncated
    * pFull) and shifted to its place in the full 2*dataWidth product. Lanes with no product
    * this phase, and the unused high lanes of the last phase, are zero. */
  protected def alignedPartialProducts(): Vec[UInt] = {
    val pp = WireInit(VecInit(Seq.fill(nSlice)(0.U(accW.W))))
    for (p <- 0 until maxPhase) {
      for (lane <- 0 until nSlice) {
        val idx = p * nSlice + lane
        if (idx < ppTotal) {
          val shiftAmount = sliceIndexTable(idx)._1
          when(phaseCur === p.U) {
            val aligned = Wire(SInt(accW.W))
            aligned := (slices(lane).io.pFull << shiftAmount)
            pp(lane) := aligned.asUInt
          }
        }
      }
    }
    pp
  }
}

/** Carry-propagate accumulation core.
  *
  * Each phase sums its active partial products into a running accumulator with ordinary
  * carry-propagate adders, so the accumulator always holds the resolved partial product.
  * The single-slice single-phase case (32,1) needs no accumulation - the lone partial
  * product is the whole product - so it skips the accumulator entirely and stays
  * zero-cycle.
  */
class IterativeAdderCore(parameters: MultiplierParams) extends MulCore(parameters) {
  private val ppFull = alignedPartialProducts()
  private val singleProduct = ppTotal == 1 // exactly one partial product (e.g. 32,1): no add needed

  if (singleProduct) {
    io.result := Mux(isLow, ppFull(0)(dataWidth - 1, 0), ppFull(0)(2 * dataWidth - 1, dataWidth))
  } else {
    val accumulator = RegInit(0.U(accW.W))
    // Resolved running sum through this phase (carry-propagate fold).
    val phaseSum = ppFull.foldLeft(accumulator)(_ + _)

    accumulator := 0.U
    when(io.start || running) { accumulator := phaseSum }
    when(done) { accumulator := 0.U }

    io.result := Mux(isLow, phaseSum(dataWidth - 1, 0), phaseSum(2 * dataWidth - 1, dataWidth))
  }
}
