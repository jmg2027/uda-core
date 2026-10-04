package udacore.frontend.design.modules

import chisel3._
import chisel3.util._
import udacore.backend.design.shared.RecoveryEvent
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.FetchBufferSpecs._

/** FetchBuffer (spec: FetchBufferSpecs; ADR-019 D-19.2, ADR-019G E-6).
  *
  * A FetchBufferEntries-deep FIFO of individual FetchInsts (circular head/tail plus an occupancy
  * count). An accepted FetchBlock is compacted: its valid slots, a contiguous run startSlot..lastSlot
  * (one slot for a fault block), are appended in slot order at tail + (number of valid slots before
  * the slot), never at the physical slot number. Each instruction keeps its real slot and PC
  * (basePc + 4 * slot); the last valid slot is blockEnd and carries the block's exit prediction.
  *
  * FetchBlockIn.ready = no RecoveryEvent and free entries >= PopCount(slotValid): a block is
  * accepted whole or not at all. Capacity is conservative: it is the free space at the start of the
  * cycle, so entries a same-cycle FetchPacket transfer frees are usable from the next cycle.
  * FetchPacketOut offers the oldest min(DecodeWidth, occupancy) instructions as a lane prefix; they
  * leave together only when the packet transfers, and the offer (lane count and payload) is stable
  * under backpressure. A packet may span two blocks.
  *
  * Speculative-holder stance: every buffered instruction is younger than every backend uop, so any
  * RecoveryEvent empties the buffer in its cycle (pointers and count reset, no transfer on either
  * side in that cycle).
  */
@LocalSpec(contFetchBuffer)
class FetchBuffer(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfFetchBlockIn)
    val fetchBlockIn = Flipped(Decoupled(new FetchBlock(params)))

    @LocalSpec(intfFetchPacketOut)
    val fetchPacketOut = Decoupled(new FetchPacket(params.decodeWidth, params.vAddrWidth, params.ftqIdxWidth, params.slotWidth))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params.backend))
  })

  private val n   = params.tuning.fetchBufferEntries
  private val pw  = math.max(1, log2Ceil(n))
  private val dw  = params.decodeWidth
  private def newInst = new FetchInst(params.vAddrWidth, params.ftqIdxWidth, params.slotWidth)
  /** (p + k) modulo n for p < n and k <= n, the sum widened so the carry reaches the compare. */
  private def ptrAdd(p: UInt, k: UInt): UInt = {
    val s = p +& k
    Mux(s >= n.U, s - n.U, s)(pw - 1, 0)
  }

  val entries = Reg(Vec(n, newInst))
  val head    = RegInit(0.U(pw.W))
  val tail    = RegInit(0.U(pw.W))
  val count   = RegInit(0.U(log2Ceil(n + 1).W))

  private val ev    = io.recoveryEventIn.valid
  private val blk   = io.fetchBlockIn.bits
  private val sv    = blk.slotValid
  private val need  = PopCount(sv)
  private val inFire  = io.fetchBlockIn.fire
  private val outFire = io.fetchPacketOut.fire

  // ---- funcFetchBufferEnqueue -------------------------------------------------------------------
  io.fetchBlockIn.ready := !ev && (n.U - count) >= need

  @LocalSpec(funcFetchBufferEnqueue)
  val fetchBufferEnqueue: Unit = when(inFire) {
    for (s <- 0 until fetchWidth) {
      val offset = if (s == 0) 0.U else PopCount(sv.take(s))
      val last   = sv(s) && !(if (s == fetchWidth - 1) false.B else sv.drop(s + 1).reduce(_ || _))
      val fi     = Wire(newInst)
      fi.inst            := blk.insts(s)
      fi.pc              := FetchPc.slotPc(blk.basePc, s.U)
      fi.ftqIdx          := blk.ftqIdx
      fi.slot            := s.U
      fi.blockEnd        := last
      fi.predictedTaken  := blk.exitTaken && last
      fi.predictedTarget := Mux(blk.exitTaken && last, blk.exitTarget, 0.U)
      fi.fault           := blk.fault
      when(sv(s)) { entries(ptrAdd(tail, offset)) := fi }
    }
  }

  // ---- funcFetchPacketDequeue -------------------------------------------------------------------
  /** A presented, unaccepted packet keeps its lane count until it transfers: instructions enqueued
    * behind it do not widen it (no dequeue happens while it is held, so its entries are unchanged). */
  val heldValid = RegInit(false.B)
  val heldN     = RegInit(0.U(log2Ceil(dw + 1).W))
  private val outN = Mux(heldValid, heldN, Mux(count >= dw.U, dw.U, count))

  @LocalSpec(funcFetchPacketDequeue)
  val fetchPacketDequeue: Unit = {
    io.fetchPacketOut.valid := !ev && count =/= 0.U
    heldValid := io.fetchPacketOut.valid && !io.fetchPacketOut.ready
    heldN     := outN
    for (i <- 0 until dw) {
      io.fetchPacketOut.bits.valid(i) := outN > i.U
      io.fetchPacketOut.bits.insts(i) := entries(ptrAdd(head, i.U))
    }
  }

  // ---- funcFetchBufferRecovery ------------------------------------------------------------------
  @LocalSpec(funcFetchBufferRecovery)
  val fetchBufferRecovery: Unit = {
    val enqN = Mux(inFire, need, 0.U)
    val deqN = Mux(outFire, outN, 0.U)
    when(ev) {
      head := 0.U; tail := 0.U; count := 0.U
    }.otherwise {
      head  := ptrAdd(head, deqN)
      tail  := ptrAdd(tail, enqN)
      count := count + enqN - deqN
    }
  }

  // ---- Assertions -------------------------------------------------------------------------------
  /** FetchUnit contract (ADR-019G E-6): one contiguous run of valid slots; exactly one for a fault. */
  val fetchBlockSlotShape: Unit = when(inFire) {
    val starts = PopCount((0 until fetchWidth).map(s => sv(s) && (if (s == 0) true.B else !sv(s - 1))))
    assert(Mux(blk.fault =/= FetchFault.None, need === 1.U, starts === 1.U),
      "FetchBlockSlots: an accepted FetchBlock is not one contiguous run of valid slots (exactly one for a fault)")
  }

  @LocalSpec(propFetchBufferProgramOrder)
  val fetchBufferProgramOrder: Unit = {
    val p     = io.fetchPacketOut.bits
    val iw    = params.ftqIdxWidth
    /** b directly follows a in program order: the next slot of the same block, or the first
      * delivered slot of the next FTQ block after a blockEnd. */
    def follows(a: FetchInst, b: FetchInst): Bool =
      (b.ftqIdx === a.ftqIdx && !a.blockEnd && b.slot === (a.slot +& 1.U) && b.pc === a.pc + 4.U) ||
        (a.blockEnd && b.ftqIdx === (a.ftqIdx + 1.U)(iw - 1, 0))
    val prevValid = RegInit(false.B)
    val prev      = Reg(newInst)
    for (i <- 1 until dw) assert(!p.valid(i) || p.valid(i - 1), "FetchBufferProgramOrder: packet lanes are not a prefix")
    assert(!(ev && outFire), "FetchBufferProgramOrder: a packet transferred in a RecoveryEvent cycle")
    when(outFire) {
      assert(!prevValid || follows(prev, p.insts(0)),
        "FetchBufferProgramOrder: lane 0 does not follow the previous delivered instruction")
      for (i <- 1 until dw)
        assert(!p.valid(i) || follows(p.insts(i - 1), p.insts(i)), "FetchBufferProgramOrder: packet lanes out of program order")
      prevValid := true.B
      prev      := p.insts(0)
      for (i <- 1 until dw) when(p.valid(i)) { prev := p.insts(i) }
    }
    when(ev) { prevValid := false.B }
  }
}
