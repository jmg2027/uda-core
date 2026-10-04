package udacore.core.design.modules

import chisel3._
import chisel3.util._
import udacore.common.tilelink._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.InstBusAdapterSpecs._

/** InstBusAdapter (spec: InstBusAdapterSpecs; ADR-016 as amended by ADR-019).
  *
  * The only agent on the instruction TileLink link (CoreParams.instBusParams: one source id, no
  * B/C/E). One transaction at a time and no buffering: an accepted InstMemReq is held as one A-channel
  * Get (stable until A transfers), then every AccessAckData beat on D is forwarded as one InstMemResp
  * (D.ready = InstMemResp.ready). TileLink D carries no last flag, so the final beat is derived from
  * the active request size and the count of D beats that transferred. The next request is accepted
  * once the final beat has transferred.
  *
  * Recovery stance: an accepted request is an uncancelable bus transaction and always completes
  * (propGenerationTagScope); the adapter does not observe RecoveryEvent.
  */
@LocalSpec(contInstBusAdapter)
class InstBusAdapter(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfInstMemReqIn)
    val instMemReqIn = Flipped(Decoupled(new InstMemReq(params.pAddrWidth, params.instBusParams.sizeBits)))

    @LocalSpec(intfInstMemRespOut)
    val instMemRespOut = Decoupled(new InstMemResp(params.dataWidth))

    @LocalSpec(intfInstBus)
    val instBus = new TLBundle(params.instBusParams)
  })

  private val tl        = params.instBusParams
  private val beatShift = log2Ceil(tl.maskBits) // log2(beat bytes)
  private val a         = io.instBus.a
  private val d         = io.instBus.d

  /** A transaction is active from InstMemReq acceptance until its final D beat transfers. */
  val busy     = RegInit(false.B)
  val aPending = RegInit(false.B)
  val reqAddr  = Reg(UInt(tl.addressBits.W))
  val reqSize  = Reg(UInt(tl.sizeBits.W))
  /** Beats of the longest instruction-link transfer (one I-cache line). */
  private val maxBeats = math.max(1, params.contract.icache.blockBytes / tl.maskBits)
  val beatCnt  = RegInit(0.U(math.max(1, log2Ceil(maxBeats + 1)).W))

  /** Beats of a 2^size-byte transfer on a beat-wide link (at least one). */
  private def beatsOf(size: UInt): UInt =
    Mux(size > beatShift.U, 1.U << (size - beatShift.U), 1.U)
  private val lastBeat = beatCnt === beatsOf(reqSize) - 1.U

  @LocalSpec(funcInstBusGet)
  val instBusGet: Unit = {
    io.instMemReqIn.ready := !busy
    when(io.instMemReqIn.fire) {
      busy := true.B; aPending := true.B; beatCnt := 0.U
      reqAddr := io.instMemReqIn.bits.paddr; reqSize := io.instMemReqIn.bits.size
    }

    a.valid        := aPending
    a.bits.opcode  := TLMessages.Get
    a.bits.param   := 0.U
    a.bits.size    := reqSize
    a.bits.source  := 0.U
    a.bits.address := reqAddr
    a.bits.mask    := Fill(tl.maskBits, 1.U(1.W))
    a.bits.data    := 0.U
    a.bits.corrupt := false.B
    when(a.fire) { aPending := false.B }

    io.instMemRespOut.valid       := d.valid
    io.instMemRespOut.bits.data   := d.bits.data
    io.instMemRespOut.bits.last   := lastBeat
    io.instMemRespOut.bits.denied := d.bits.denied || d.bits.corrupt
    d.ready := io.instMemRespOut.ready
    when(d.fire) {
      beatCnt := beatCnt + 1.U
      when(lastBeat) { busy := false.B }
    }

    when(d.valid) {
      assert(busy && !aPending, "InstBusD: a D beat without an active, transferred Get")
      assert(d.bits.opcode === TLMessages.AccessAckData, "InstBusD: D opcode is not AccessAckData")
      assert(d.bits.source === 0.U, "InstBusD: D source is not the single instruction-link source 0")
      assert(d.bits.size === reqSize, "InstBusD: D size differs from the active Get")
    }
    assert(!d.fire || beatCnt < beatsOf(reqSize), "InstBusD: more D beats than the active Get transfers")
  }

  @LocalSpec(propInstBusGetOnly)
  val instBusGetOnly: Unit = {
    require(!tl.hasBCE && io.instBus.b.isEmpty && io.instBus.c.isEmpty && io.instBus.e.isEmpty,
      "InstBusGetOnly: the instruction link must not elaborate B/C/E")
    assert(!a.valid || a.bits.opcode === TLMessages.Get, "InstBusGetOnly: channel A carries a message other than Get")
  }
}
