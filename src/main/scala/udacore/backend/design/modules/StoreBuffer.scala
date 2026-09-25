package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import udacore.core.design.shared.{StoreDrainReq, StoreDrainResp}
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.StoreBufferSpecs._

/** StoreBuffer (spec: StoreBufferSpecs; ADR-003 as amended by ADR-019 D-19.12).
  *
  * A FIFO of StoreBufferDepth committed cacheable stores. The head is offered as StoreDrainReq
  * whenever no drain is outstanding and is freed only by its StoreDrainResp, so an entry being
  * drained still forwards until its bytes are in the array. A forwarding query is answered in
  * its own cycle (the LSQ merges this answer with its SQ bytes in the D-cache answer cycle).
  * Entries are single aligned words with byte masks, so every overlap merges exactly and
  * partial is never raised in v0. Recovery-exempt: no RecoveryEvent input.
  */
@LocalSpec(contStoreBuffer)
class StoreBuffer(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfCommittedStoreIn)
    val committedStoreIn = Flipped(Decoupled(new CommittedStore(params)))

    @LocalSpec(intfStoreDrainReqOut)
    val storeDrainReqOut = Decoupled(new StoreDrainReq(pAddrWidth, xLen))

    @LocalSpec(intfStoreDrainRespIn)
    val storeDrainRespIn = Flipped(Decoupled(new StoreDrainResp))

    @LocalSpec(intfStoreForwardQueryIn)
    val storeForwardQueryIn = Flipped(Decoupled(new StoreForwardQuery(params)))

    @LocalSpec(intfStoreForwardDataOut)
    val storeForwardDataOut = Decoupled(new StoreForwardData(params))

    @LocalSpec(intfStoreBufferDrainReqIn)
    val storeBufferDrainReqIn = Flipped(Decoupled(new HandshakeToken))

    @LocalSpec(intfStoreBufferDrainRespOut)
    val storeBufferDrainRespOut = Decoupled(new HandshakeToken)

    @LocalSpec(intfStoreBufferEmptyOut)
    val storeBufferEmptyOut = Output(Bool())
  })

  private val n = params.tuning.storeBufferDepth
  private val w = log2Ceil(n)
  require(isPow2(n), "StoreBufferDepth must be a power of two")

  val entries     = Reg(Vec(n, new CommittedStore(params)))
  val head, tail  = RegInit(0.U((w + 1).W))
  val outstanding = RegInit(false.B)
  val fencePending = RegInit(false.B)
  private val count = tail - head
  private val full  = count === n.U
  private val empty = count === 0.U
  private val hIdx  = head(w - 1, 0)

  // ---- funcCommitOrderDrain ----------------------------------------------------------------------------

  private val cin = io.committedStoreIn
  private val dq  = io.storeDrainReqOut
  private val dr  = io.storeDrainRespIn
  @LocalSpec(funcCommitOrderDrain)
  val commitOrderDrain: Unit = {
    cin.ready := !full
    when(cin.fire) { entries(tail(w - 1, 0)) := cin.bits; tail := tail + 1.U }
    dq.valid      := !empty && !outstanding
    dq.bits.paddr := entries(hIdx).paddr
    dq.bits.data  := entries(hIdx).data
    dq.bits.mask  := entries(hIdx).mask
    when(dq.fire) { outstanding := true.B }
    dr.ready := true.B
    when(dr.fire) { outstanding := false.B; head := head + 1.U }
    assert(!dr.valid || outstanding, "CommitOrderDrain: a StoreDrainResp without an outstanding drain")
  }

  // ---- funcCommittedForward ----------------------------------------------------------------------------------

  private val fq = io.storeForwardQueryIn
  private val fd = io.storeForwardDataOut
  @LocalSpec(funcCommittedForward)
  val committedForward: Unit = {
    fq.ready := fd.ready
    fd.valid := fq.valid
    val word = fq.bits.paddr(pAddrWidth - 1, 2)
    // Buffer order is age order: fold from the head so younger entries override.
    val (hit, data) = (0 until n).foldLeft((VecInit(Seq.fill(4)(false.B)), VecInit(Seq.fill(4)(0.U(8.W))))) { case ((h, d), k) =>
      val slot = (head + k.U)(w - 1, 0)
      val e    = entries(slot)
      val live = k.U < count && e.paddr(pAddrWidth - 1, 2) === word
      val nh = VecInit((0 until 4).map(b => h(b) || (live && e.mask(b) && fq.bits.mask(b))))
      val nd = VecInit((0 until 4).map(b => Mux(live && e.mask(b) && fq.bits.mask(b), e.data(8 * b + 7, 8 * b), d(b))))
      (nh, nd)
    }
    fd.bits.hitMask := hit.asUInt
    fd.bits.data    := data.asUInt
    fd.bits.partial := false.B
  }

  // ---- funcDrainFence -----------------------------------------------------------------------------------------

  private val fr = io.storeBufferDrainReqIn
  private val fs = io.storeBufferDrainRespOut
  @LocalSpec(funcDrainFence)
  val drainFence: Unit = {
    fr.ready := !fencePending
    when(fr.fire) { fencePending := true.B }
    fs.valid := fencePending && empty
    when(fs.fire) { fencePending := false.B }
    assert(!(cin.fire && fencePending), "DrainFence: a store was accepted while a drain fence is pending")
  }

  io.storeBufferEmptyOut := empty

  // ---- Properties (simulation assertions) -------------------------------------------------------------------

  @LocalSpec(propInOrderDrain)
  val inOrderDrain: Unit = {
    // The offered drain is always the oldest entry, and it stays stable until it transfers.
    val heldReq = RegNext(dq.valid && !dq.ready, false.B)
    assert(!heldReq || (dq.valid && dq.bits.asUInt === RegNext(dq.bits.asUInt)), "InOrderDrain: an offered drain changed before it transferred")
    assert(!dr.fire || !empty, "InOrderDrain: a drain completed with an empty buffer")
  }

  @LocalSpec(propCommittedSurvivesRecovery)
  val committedSurvivesRecovery: Unit = {
    // Occupancy changes only by a commit (+1) or a completed drain (-1): nothing else removes an entry.
    val started = RegNext(true.B, false.B)
    val expect  = RegNext(count + cin.fire.asUInt - dr.fire.asUInt)
    assert(!started || count === expect, "CommittedSurvivesRecovery: an entry appeared or vanished outside commit/drain")
  }

  @LocalSpec(propStoreBufferLiveness)
  val storeBufferLiveness: Unit =
    assert(empty || outstanding || dq.valid, "StoreBufferLiveness: a buffered store is not offered to the D-cache")
}
