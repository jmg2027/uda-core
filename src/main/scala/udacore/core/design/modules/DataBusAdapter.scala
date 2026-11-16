package udacore.core.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.common.tilelink.{TLBundle, TLMessages}
import udacore.core.design.shared._
import udacore.core.spec.modules.DataBusAdapterSpecs._

/** DataBusAdapter (spec: DataBusAdapterSpecs; ADR-019 D-19.13).
  *
  * The DataCache owns source IDs. Each accepted first A beat reserves its source until the final
  * D beat transfers. A PutLine's A beats remain contiguous; independent D sources may interleave.
  * This vertex does not observe RecoveryEvent because every accepted bus transaction completes.
  */
@LocalSpec(contDataBusAdapter)
class DataBusAdapter(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDataMemReqIn)
    val dataMemReqIn = Flipped(Decoupled(new DataMemReq(params.pAddrWidth, params.dataWidth,
      params.dataBusParams.sourceBits, params.dataBusParams.sizeBits)))

    @LocalSpec(intfDataMemRespOut)
    val dataMemRespOut = Decoupled(new DataMemResp(params.dataWidth, params.dataBusParams.sourceBits))

    @LocalSpec(intfDataBus)
    val dataBus = new TLBundle(params.dataBusParams)
  })

  private val tl = params.dataBusParams
  private val beatBytes = tl.maskBits
  private val beatShift = log2Ceil(beatBytes)
  private val lineBytes = params.contract.dcache.blockBytes
  private val lineSize = log2Ceil(lineBytes)
  private val maxBeats = math.max(1, lineBytes / beatBytes)
  private val countWidth = math.max(1, log2Ceil(maxBeats + 1))
  private val sources = params.contract.dcache.mshrs + 2
  private val in = io.dataMemReqIn
  private val out = io.dataMemRespOut
  private val a = io.dataBus.a
  private val d = io.dataBus.d

  val active = RegInit(VecInit(Seq.fill(sources)(false.B)))
  val sizes = Reg(Vec(sources, UInt(tl.sizeBits.W)))
  val reads = Reg(Vec(sources, Bool()))
  val dCount = RegInit(VecInit(Seq.fill(sources)(0.U(countWidth.W))))
  val putOpen = RegInit(false.B)
  val putSource = Reg(UInt(tl.sourceBits.W))
  val putAddr = Reg(UInt(tl.addressBits.W))
  val putSize = Reg(UInt(tl.sizeBits.W))
  val putCount = RegInit(0.U(countWidth.W))

  private def beatsOf(size: UInt): UInt = Mux(size > beatShift.U, 1.U << (size - beatShift.U), 1.U)
  private val isGetLine = in.bits.op === DataMemOp.GetLine
  private val isPutLine = in.bits.op === DataMemOp.PutLine
  private val isGetUncached = in.bits.op === DataMemOp.GetUncached
  private val isPutUncached = in.bits.op === DataMemOp.PutUncached
  private val isGet = isGetLine || isGetUncached
  private val sourceValid = in.bits.source < sources.U
  private val continuation = putOpen && isPutLine && in.bits.source === putSource
  private val canOffer = Mux(putOpen, continuation, sourceValid && !active(in.bits.source))

  @LocalSpec(funcDataBusTransaction)
  val dataBusTransaction: Unit = {
    in.ready := a.ready && canOffer
    a.valid := in.valid && canOffer
    a.bits.opcode := Mux(isGet, TLMessages.Get,
      Mux(isPutLine, TLMessages.PutFullData, TLMessages.PutPartialData))
    a.bits.param := 0.U
    a.bits.size := in.bits.size
    a.bits.source := in.bits.source
    a.bits.address := in.bits.paddr
    val bytesMask = Mux(in.bits.size === 0.U, 1.U(beatBytes.W),
      Mux(in.bits.size === 1.U, 3.U(beatBytes.W), Fill(beatBytes, 1.U(1.W))))
    val getMask = (bytesMask << in.bits.paddr(beatShift - 1, 0))(beatBytes - 1, 0)
    a.bits.mask := Mux(isGetLine || isPutLine, Fill(beatBytes, 1.U(1.W)),
      Mux(isGetUncached, getMask, in.bits.mask))
    a.bits.data := Mux(isGet, 0.U, in.bits.data)
    a.bits.corrupt := false.B

    when(in.valid) {
      assert(sourceValid, "DataBusA: source outside the configured source range")
      when(putOpen) {
        assert(continuation && in.bits.paddr === putAddr && in.bits.size === putSize,
          "DataBusA: PutLine beats must be contiguous with one source, address and size")
      }.otherwise {
        when(isGetLine || isPutLine) {
          assert(in.bits.size === lineSize.U && in.bits.paddr(lineSize - 1, 0) === 0.U,
            "DataBusA: a line request needs the configured aligned line size")
        }.otherwise {
          assert(in.bits.size <= beatShift.U, "DataBusA: uncached request exceeds one beat")
        }
      }
    }
    when(a.fire) {
      when(putOpen) {
        assert(in.bits.last === (putCount === beatsOf(putSize) - 1.U),
          "DataBusA: PutLine last does not match the final beat")
        putCount := putCount + 1.U
        when(in.bits.last) { putOpen := false.B }
      }.otherwise {
        assert(!active(in.bits.source), "DataBusA: source reused before its D response completed")
        assert(Mux(isPutLine, in.bits.last === (beatsOf(in.bits.size) === 1.U), in.bits.last),
          "DataBusA: single-token last or PutLine first-beat last is wrong")
        active(in.bits.source) := true.B
        sizes(in.bits.source) := in.bits.size
        reads(in.bits.source) := isGet
        dCount(in.bits.source) := 0.U
        when(isPutLine && !in.bits.last) {
          putOpen := true.B
          putSource := in.bits.source
          putAddr := in.bits.paddr
          putSize := in.bits.size
          putCount := 1.U
        }
      }
    }

    val dSourceValid = d.bits.source < sources.U
    val sameCycleFirst = a.fire && !putOpen && a.bits.source === d.bits.source
    val dActive = active(d.bits.source) || sameCycleFirst
    val dSize = Mux(sameCycleFirst, in.bits.size, sizes(d.bits.source))
    val dRead = Mux(sameCycleFirst, isGet, reads(d.bits.source))
    val beatIndex = Mux(sameCycleFirst, 0.U, dCount(d.bits.source))
    val dLast = !dRead || beatIndex === beatsOf(dSize) - 1.U
    out.valid := d.valid
    out.bits.source := d.bits.source
    out.bits.data := d.bits.data
    out.bits.last := dLast
    out.bits.denied := d.bits.denied || d.bits.corrupt
    d.ready := out.ready
    when(d.valid) {
      assert(dSourceValid && dActive, "DataBusD: answer without an active source")
      assert(d.bits.size === dSize, "DataBusD: answer size differs from the request")
      assert(d.bits.opcode === Mux(dRead, TLMessages.AccessAckData, TLMessages.AccessAck),
        "DataBusD: answer opcode differs from the request kind")
      assert(!(putOpen && d.bits.source === putSource), "DataBusD: PutLine answered before its final A beat")
      assert(beatIndex < beatsOf(dSize), "DataBusD: too many response beats")
    }
    when(d.fire) {
      when(dLast) { active(d.bits.source) := false.B }
        .otherwise { dCount(d.bits.source) := beatIndex + 1.U }
    }
  }

  @LocalSpec(propDataBusCoherenceSeparate)
  val dataBusCoherenceSeparate: Unit = {
    require(tl.hasBCE == params.contract.dataCoherence,
      "DataBusCoherenceSeparate: link coherence must follow DataCoherence")
    require(!tl.hasBCE && io.dataBus.b.isEmpty && io.dataBus.c.isEmpty && io.dataBus.e.isEmpty,
      "DataBusCoherenceSeparate: v0 TL-UH must not elaborate B/C/E")
    assert(!a.valid || (a.bits.opcode === TLMessages.Get || a.bits.opcode === TLMessages.PutFullData ||
      a.bits.opcode === TLMessages.PutPartialData), "DataBusCoherenceSeparate: v0 issued a coherent A opcode")
  }
}
