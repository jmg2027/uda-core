package udacore.core.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.frontend.design.shared.FetchFault
import udacore.core.spec.modules.InstructionCacheSpecs._

/** InstructionCache (spec: InstructionCacheSpecs; ADR-019 D-19.4, D-19.12).
  *
  * A read-only VIPT L1 I-cache: sets indexed by va[idx] (inside the 4 KiB page offset, so equal to
  * the physical index), tags compared with pa[pAddrWidth-1:12], 64-byte lines, a 16-byte block
  * answer selected by va[5:4]. v0: 16 KiB, 4 ways, 64 sets, tree pseudo-LRU.
  *
  * Request / translation pairing: an ICacheReq is held in a one-entry pairing register until its
  * Translation (in request order, possibly cycles later) arrives; the next ICacheReq is accepted in
  * the cycle the held one is consumed, so hits sustain one lookup per cycle. A lookup needs the
  * answer holder to be free (or draining this cycle), no miss in progress and no invalidate token
  * this cycle.
  *
  * One blocking miss context: a cacheable miss Gets the 64-byte line (size 6), an uncached fetch
  * (Translation Hit, cacheable = false) Gets only the 16-byte block (size 4) and installs nothing.
  * Beats fill a line buffer in order; a denied beat anywhere makes the answer InstAccessFault and
  * installs nothing. A cacheable fill installs on its final beat in the invalid-first / pseudo-LRU
  * victim way unless an invalidate was accepted during the fill (fillPoison) or in the final-beat
  * cycle (invalidate wins). Translation faults answer at once and never reach the bus.
  *
  * Answers leave through one stable holder in request order. The cache does not observe
  * RecoveryEvent: requests are never canceled here, a wrong-path fill installs normally, and stale
  * answers are dropped by the FetchUnit generation (ADR-019 D-19.12).
  */
@LocalSpec(contInstructionCache)
class InstructionCache(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfICacheReqIn)
    val iCacheReqIn = Flipped(Decoupled(new ICacheReq(params.fetch.fetchGenWidth, params.vAddrWidth)))

    @LocalSpec(intfICacheTranslationIn)
    val iCacheTranslationIn = Flipped(Decoupled(new Translation(params.fetch.fetchGenWidth, params.pAddrWidth)))

    @LocalSpec(intfICacheRespOut)
    val iCacheRespOut = Decoupled(new ICacheResp(params.fetch.fetchGenWidth, params.fetch.fetchWidth))

    @LocalSpec(intfICacheInvalidateIn)
    val iCacheInvalidateIn = Flipped(Decoupled(new CacheMaintenance))

    @LocalSpec(intfInstMemReqOut)
    val instMemReqOut = Decoupled(new InstMemReq(params.pAddrWidth, params.instBusParams.sizeBits))

    @LocalSpec(intfInstMemRespIn)
    val instMemRespIn = Flipped(Decoupled(new InstMemResp(params.dataWidth)))
  })

  // ---- Geometry ---------------------------------------------------------------------------------
  private val geo       = params.contract.icache
  private val sets      = geo.sets
  private val ways      = geo.ways
  private val lineBytes = geo.blockBytes
  private val beatBytes = params.dataWidth / 8
  private val lineWords = lineBytes / beatBytes
  private val fw        = params.fetch.fetchWidth         // words per fetch block
  private val offBits   = log2Ceil(lineBytes)             // 6
  private val idxBits   = log2Ceil(sets)                  // 6
  private val blkBits   = log2Ceil(params.fetch.fetchBytes) // 4
  private val pageBits  = 12                               // Sv32 page offset
  private val tagBits   = params.pAddrWidth - pageBits     // pa[33:12]
  private val genW      = params.fetch.fetchGenWidth
  require(offBits + idxBits <= pageBits, "VIPT index must lie inside the page offset (propViptGeometryLegal)")
  require(ways == 4, "v0 tree pseudo-LRU is 4-way")
  private val lineSize  = offBits.U   // InstMemReq.size of a line fill
  private val blockSize = blkBits.U   // InstMemReq.size of an uncached block

  private def setOf(va: UInt): UInt   = va(offBits + idxBits - 1, offBits)
  private def tagOf(pa: UInt): UInt   = pa(params.pAddrWidth - 1, pageBits)
  private def blockOf(va: UInt): UInt = va(offBits - 1, blkBits)
  private def blockWords(line: Vec[UInt], blk: UInt): Vec[UInt] =
    VecInit((0 until fw).map(i => line(Cat(blk, i.U(log2Ceil(fw).W)))))

  // ---- State ------------------------------------------------------------------------------------
  val valid = RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.fill(ways)(false.B)))))
  val tags  = Mem(sets, Vec(ways, UInt(tagBits.W)))
  val data  = Mem(sets, Vec(ways, Vec(lineWords, UInt(32.W))))
  /** Tree pseudo-LRU per set: bit0 picks the pair (0: ways 0/1, 1: ways 2/3), bit1 / bit2 the way. */
  val plru  = RegInit(VecInit(Seq.fill(sets)(0.U(3.W))))
  private def plruVictim(b: UInt): UInt = Mux(b(0), Mux(b(2), 3.U, 2.U), Mux(b(1), 1.U, 0.U))
  private def plruTouch(b: UInt, w: UInt): UInt = {
    val left = w < 2.U
    Cat(Mux(left, b(2), w === 2.U), Mux(left, w === 0.U, b(1)), left)
  }

  // Pairing register (the request whose Translation is awaited).
  val pendValid = RegInit(false.B)
  val pendVaddr = Reg(UInt(params.vAddrWidth.W))
  val pendReqId = Reg(UInt(genW.W))
  // Answer holder.
  val holdValid = RegInit(false.B)
  val hold      = Reg(new ICacheResp(genW, fw))
  // Miss context.
  val sIdle :: sReq :: sCollect :: sResp :: Nil = Enum(4)
  val state      = RegInit(sIdle)
  val mReqId     = Reg(UInt(genW.W))
  val mVaddr     = Reg(UInt(params.vAddrWidth.W))
  val mPaddr     = Reg(UInt(params.pAddrWidth.W))
  val mCacheable = Reg(Bool())
  val fillBuf    = Reg(Vec(lineWords, UInt(32.W)))
  val beatIdx    = RegInit(0.U(log2Ceil(lineWords + 1).W))
  val deniedAcc  = RegInit(false.B)
  val fillPoison = RegInit(false.B)

  private val req   = io.iCacheReqIn
  private val tr    = io.iCacheTranslationIn
  private val resp  = io.iCacheRespOut
  private val inv   = io.iCacheInvalidateIn
  private val mreq  = io.instMemReqOut
  private val mresp = io.instMemRespIn

  private val holderFree = !holdValid || resp.fire
  private val invFire    = inv.fire
  /** A holder write this cycle (one source per cycle: a lookup answer or the miss answer). */
  private val holdWrite = Wire(Bool()); holdWrite := false.B
  private val holdNext  = Wire(new ICacheResp(genW, fw)); holdNext := hold

  // ---- funcICacheViptLookup ---------------------------------------------------------------------
  tr.ready  := pendValid && state === sIdle && !inv.valid && holderFree
  req.ready := !pendValid || tr.fire
  private val consume = tr.fire
  private val lSet    = setOf(pendVaddr)
  private val lTags   = tags(lSet)
  private val lHits   = VecInit((0 until ways).map(w => valid(lSet)(w) && lTags(w) === tagOf(tr.bits.paddr)))
  private val lHitWay = OHToUInt(lHits)
  private val isHit   = tr.bits.status === TranslationStatus.Hit
  private val lHit    = isHit && tr.bits.cacheable && lHits.asUInt.orR

  @LocalSpec(funcICacheViptLookup)
  val iCacheViptLookup: Unit = {
    when(req.fire) { pendValid := true.B; pendVaddr := req.bits.vaddr; pendReqId := req.bits.reqId }
      .elsewhen(consume) { pendValid := false.B }
    when(consume) {
      assert(tr.bits.reqId === pendReqId, "ICacheTranslation: the Translation reqId differs from the paired request")
      assert(tr.bits.status =/= TranslationStatus.Miss, "ICacheTranslation: status Miss on the InstructionTlb edge")
      assert(PopCount(lHits) <= 1.U, "ICacheLookup: more than one way hits")
      holdNext.reqId := tr.bits.reqId
      holdNext.data  := VecInit(Seq.fill(fw)(0.U(32.W)))
      holdNext.fault := FetchFault.None
      when(tr.bits.status === TranslationStatus.PageFault) {
        holdWrite := true.B; holdNext.fault := FetchFault.InstPageFault
      }.elsewhen(tr.bits.status === TranslationStatus.AccessFault) {
        holdWrite := true.B; holdNext.fault := FetchFault.InstAccessFault
      }.elsewhen(lHit) {
        holdWrite := true.B
        holdNext.data := blockWords(data(lSet)(lHitWay), blockOf(pendVaddr))
        plru(lSet) := plruTouch(plru(lSet), lHitWay)
      }.otherwise { // cacheable miss or uncached fetch: open the miss context
        state := sReq; mReqId := tr.bits.reqId; mVaddr := pendVaddr; mPaddr := tr.bits.paddr
        mCacheable := tr.bits.cacheable; deniedAcc := false.B; fillPoison := false.B; beatIdx := 0.U
      }
    }
  }

  // ---- funcICacheMissFill / funcICacheUncachedFetch ---------------------------------------------
  private val beats   = Mux(mCacheable, lineWords.U, fw.U)
  private val lastIn  = mresp.fire && beatIdx === beats - 1.U
  private val beatDen = mresp.bits.denied
  private val lineNow = VecInit((0 until lineWords).map(i => Mux(beatIdx === i.U, mresp.bits.data, fillBuf(i))))
  private val mSet    = setOf(mVaddr)
  private val invalid = VecInit((0 until ways).map(w => !valid(mSet)(w)))
  private val victim  = Mux(invalid.asUInt.orR, PriorityEncoder(invalid), plruVictim(plru(mSet)))

  @LocalSpec(funcICacheMissFill)
  val iCacheMissFill: Unit = {
    mreq.valid := state === sReq
    when(mreq.fire) { state := sCollect }

    mresp.ready := state === sCollect
    when(mresp.fire) {
      assert(mresp.bits.last === (beatIdx === beats - 1.U), "ICacheFill: InstMemResp.last does not mark the final beat")
      fillBuf(beatIdx(log2Ceil(lineWords) - 1, 0)) := mresp.bits.data
      beatIdx := beatIdx + 1.U
      deniedAcc := deniedAcc || beatDen
    }
    when(lastIn) {
      val denied = deniedAcc || beatDen
      when(mCacheable && !denied && !fillPoison && !invFire) {
        val oh = UIntToOH(victim, ways).asBools
        tags.write(mSet, VecInit(Seq.fill(ways)(tagOf(mPaddr))), oh)
        data.write(mSet, VecInit(Seq.fill(ways)(lineNow)), oh)
        valid(mSet)(victim) := true.B
        plru(mSet) := plruTouch(plru(mSet), victim)
      }
      state := sResp
    }
    // The held request's answer, once the holder is free.
    when(state === sResp && holderFree) {
      holdWrite := true.B
      holdNext.reqId := mReqId
      holdNext.data  := Mux(deniedAcc, VecInit(Seq.fill(fw)(0.U(32.W))),
        Mux(mCacheable, blockWords(fillBuf, blockOf(mVaddr)), VecInit(fillBuf.take(fw))))
      holdNext.fault := Mux(deniedAcc, FetchFault.InstAccessFault, FetchFault.None)
      state := sIdle
    }
  }

  /** The bus request of the miss context: a cacheable miss Gets the 64-byte aligned line, an uncached
    * fetch only the 16-byte aligned block (4 beats), whose answer is the beats themselves and which
    * the install guard (mCacheable) never writes into the arrays. */
  @LocalSpec(funcICacheUncachedFetch)
  val iCacheUncachedFetch: Unit = {
    mreq.bits.paddr := Mux(mCacheable, Cat(mPaddr(params.pAddrWidth - 1, offBits), 0.U(offBits.W)),
      Cat(mPaddr(params.pAddrWidth - 1, blkBits), 0.U(blkBits.W)))
    mreq.bits.size  := Mux(mCacheable, lineSize, blockSize)
  }

  // ---- Answer holder ----------------------------------------------------------------------------
  resp.valid := holdValid
  resp.bits  := hold
  when(resp.fire) { holdValid := false.B }
  when(holdWrite) { holdValid := true.B; hold := holdNext }

  // ---- funcICacheInvalidate ---------------------------------------------------------------------
  @LocalSpec(funcICacheInvalidate)
  val iCacheInvalidate: Unit = {
    inv.ready := true.B
    when(inv.valid) { assert(inv.bits.op === MaintOp.ICacheInvalidateAll, "ICacheInvalidate: maintenance op is not ICacheInvalidateAll") }
    when(invFire) {
      for (s <- 0 until sets; w <- 0 until ways) valid(s)(w) := false.B
      when(state === sReq || state === sCollect) { fillPoison := true.B }
    }
  }

  // ---- Properties -------------------------------------------------------------------------------
  @LocalSpec(propICacheReadOnly)
  val iCacheReadOnly: Unit =
    assert(!mreq.valid || mreq.bits.size === lineSize || mreq.bits.size === blockSize,
      "ICacheReadOnly: the I-cache requests only line or block Gets (InstMemReq carries no write data)")

  @LocalSpec(propICacheResponseOrder)
  val iCacheResponseOrder: Unit = {
    // Sequence numbers independent of reqId (a fetch generation, not unique): every accepted request
    // is numbered, the number travels with it, and answers must leave with consecutive numbers.
    val w        = 3
    val acceptSq = RegInit(0.U(w.W))
    val answerSq = RegInit(0.U(w.W))
    val pendSq   = Reg(UInt(w.W))
    val missSq   = Reg(UInt(w.W))
    val holdSq   = Reg(UInt(w.W))
    when(req.fire) { pendSq := acceptSq; acceptSq := acceptSq + 1.U }
    when(consume && !lHit && isHit) { missSq := pendSq }
    when(holdWrite) { holdSq := Mux(state === sResp, missSq, pendSq) }
    when(resp.fire) {
      assert(holdSq === answerSq, "ICacheResponseOrder: an answer left out of request order")
      answerSq := answerSq + 1.U
    }
    assert((acceptSq - answerSq) <= 3.U, "ICacheResponseOrder: more requests in flight than pairing + miss + holder")
  }
}
