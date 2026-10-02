package udacore.core.design.modules

import chisel3._
import chisel3.util._
import udacore.backend.design.shared.TranslationContext
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.InstructionTlbSpecs._

/** InstructionTlb (spec: InstructionTlbSpecs; Sv32Specs; ADR-019 D-19.4, D-19.6).
  *
  * A 16-entry fully associative fetch TLB. One held request register and one stable Translation
  * holder: the held request is resolved when the holder is free (or draining), and a new request is
  * accepted in that same cycle, so hits sustain one translation per cycle. The reqId is opaque and is
  * echoed unchanged.
  *
  * Resolution: Bare (satp.MODE = Bare or priv = M) -> pa = va; Sv32 hit -> pa composed from the entry
  * (4 KiB: {PPN, va[11:0]}; 4 MiB: {PPN[21:10], va[21:0]}), then the fetch permission check (X, A by
  * Svade, U-mode needs U, S-mode never fetches a U page; SUM/MXR/D do not apply) -> PageFault, then the
  * static PMA map on the actual pa (PmaLookup; entry.pma is not trusted) -> AccessFault unless mapped
  * and executable. Faults carry canonical paddr 0 / cacheable false. Miss is never sent to the I-cache.
  *
  * Miss: one outstanding walk. The request stays held (no new request is accepted), the committed
  * TranslationContext is captured and one WalkReq{vpn, context} is offered, stable until it transfers.
  * Leaf: the entry is installed (every conflicting live entry is invalidated; the slot is a conflicting
  * slot, else an invalid slot, else the tree pseudo-LRU victim) and the held request is resolved by the
  * ordinary lookup next cycle, so permission and PMA always apply. PageFault / AccessFault: that fault is
  * answered, nothing installed. Retry: re-walk with the current committed context.
  *
  * SFENCE.VMA (v0: full flush): a flush token is always accepted and clears every entry in its cycle;
  * no request is accepted or resolved in that cycle. A walk that overlaps a flush is stale: its WalkReq
  * (if not yet accepted) stays stable per the ready/valid rule, but whatever it returns is neither
  * installed nor answered, and the held request re-walks with the post-flush context. A flush in the
  * cycle a Leaf arrives wins (no installation).
  */
@LocalSpec(contInstructionTlb)
class InstructionTlb(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfITlbReqIn)
    val iTlbReqIn = Flipped(Decoupled(new TranslateReq(params.fetch.fetchGenWidth, params.vAddrWidth)))

    @LocalSpec(intfICacheTranslationOut)
    val iCacheTranslationOut = Decoupled(new Translation(params.fetch.fetchGenWidth, params.pAddrWidth))

    @LocalSpec(intfItlbWalkReqOut)
    val itlbWalkReqOut = Decoupled(new WalkReq)

    @LocalSpec(intfItlbWalkRespIn)
    val itlbWalkRespIn = Flipped(Decoupled(new WalkResp))

    @LocalSpec(intfItlbFlushIn)
    val itlbFlushIn = Flipped(Decoupled(new TlbFlush(params.vAddrWidth)))

    @LocalSpec(intfTranslationContextIn)
    val translationContextIn = Input(new TranslationContext)
  })

  private val n      = params.contract.itlb.entries
  private val levels = log2Ceil(n)
  require(n >= 2 && isPow2(n), "the tree pseudo-LRU needs a power-of-two entry count")
  private val genW = params.fetch.fetchGenWidth
  private val PrivU = 0.U(2.W); private val PrivM = 3.U(2.W)

  // ---- State ------------------------------------------------------------------------------------
  val entries   = RegInit(VecInit(Seq.fill(n)(0.U.asTypeOf(new TlbEntry))))
  /** Tree pseudo-LRU: heap node k (1..n-1) is bit k-1; a set bit sends the victim search right. */
  val plru      = RegInit(0.U((n - 1).W))
  val heldValid = RegInit(false.B)
  val held      = Reg(new TranslateReq(genW, params.vAddrWidth))
  val outValid  = RegInit(false.B)
  val out       = Reg(new Translation(genW, params.pAddrWidth))
  val sIdle :: sWalkReq :: sWalkWait :: sFault :: Nil = Enum(4)
  val state     = RegInit(sIdle)
  val wCtx      = Reg(new TranslationContext)
  /** The outstanding walk overlapped a flush: its result is neither installed nor answered. */
  val stale     = RegInit(false.B)
  val fStatus   = Reg(UInt(TranslationStatus.width.W))

  private val ctx      = io.translationContextIn
  private val flushV   = io.itlbFlushIn.valid
  private val flushF   = io.itlbFlushIn.fire
  private val outFree  = !outValid || io.iCacheTranslationOut.fire
  private val resp     = io.itlbWalkRespIn

  private def plruVictim(b: UInt): UInt         = TlbLogic.plruVictim(b, levels)
  private def plruTouch(b: UInt, w: UInt): UInt = TlbLogic.plruTouch(b, w, levels)

  // ---- funcItlbTranslate ------------------------------------------------------------------------
  private val va      = held.vaddr
  private val vpn     = va(31, 12)
  private val bare    = !ctx.satpMode || ctx.priv === PrivM
  private val matches = VecInit(entries.map(TlbLogic.matches(_, vpn, ctx.asid)))
  private val hitAny  = matches.asUInt.orR
  private val hitIdx  = OHToUInt(matches)
  private val e       = Mux1H(matches, entries)
  private val paTrans = TlbLogic.compose(e, va)
  private val permOk  = e.x && e.a && Mux(ctx.priv === PrivU, e.u, !e.u)
  private val paLook  = Mux(bare, va.pad(params.pAddrWidth), paTrans)
  private val (pmaHit, pmaAttr) = PmaLookup(params.pma, paLook)
  private val lStatus = Mux(!bare && !permOk, TranslationStatus.PageFault,
    Mux(!(pmaHit && pmaAttr.executable), TranslationStatus.AccessFault, TranslationStatus.Hit))
  private val lookupOk = heldValid && state === sIdle && !flushV
  private val resolve  = lookupOk && (bare || hitAny) && outFree
  private val missNow  = lookupOk && !bare && !hitAny

  @LocalSpec(funcItlbTranslate)
  val itlbTranslate: Unit = {
    io.iTlbReqIn.ready := !flushV && state === sIdle && (!heldValid || resolve)
    when(io.iTlbReqIn.fire) { heldValid := true.B; held := io.iTlbReqIn.bits }
      .elsewhen(resolve) { heldValid := false.B }
    io.iCacheTranslationOut.valid := outValid
    io.iCacheTranslationOut.bits  := out
    when(io.iCacheTranslationOut.fire) { outValid := false.B }
    when(resolve) {
      val isHit = lStatus === TranslationStatus.Hit
      outValid := true.B
      out.reqId := held.reqId; out.status := lStatus
      out.paddr := Mux(isHit, paLook, 0.U); out.cacheable := isHit && pmaAttr.cacheable
      when(isHit && !bare) { plru := plruTouch(plru, hitIdx) }
    }
    when(heldValid) { assert(PopCount(matches) <= 1.U, "ItlbMatch: more than one entry matches the lookup") }
  }

  // ---- funcItlbMissWalk -------------------------------------------------------------------------
  @LocalSpec(funcItlbMissWalk)
  val itlbMissWalk: Unit = {
    io.itlbWalkReqOut.valid        := state === sWalkReq
    io.itlbWalkReqOut.bits.vpn     := vpn
    io.itlbWalkReqOut.bits.context := wCtx
    resp.ready := state === sWalkWait
    when(missNow) { state := sWalkReq; wCtx := ctx; stale := false.B }
    when(io.itlbWalkReqOut.fire) { state := sWalkWait }
    when(flushF && (state === sWalkReq || state === sWalkWait)) { stale := true.B }
    when(resp.fire) {
      assert(resp.bits.vpn === vpn, "ItlbWalkResp: the WalkResp VPN differs from the held request")
      when(stale || flushF) {                     // pre-flush walk: discard and re-walk
        state := sWalkReq; wCtx := ctx; stale := false.B
      }.elsewhen(resp.bits.status === WalkStatus.Leaf) {
        state := sIdle                            // installed below; the held request re-resolves
      }.elsewhen(resp.bits.status === WalkStatus.Retry) {
        state := sWalkReq; wCtx := ctx
      }.otherwise {
        state := sFault
        fStatus := Mux(resp.bits.status === WalkStatus.PageFault, TranslationStatus.PageFault, TranslationStatus.AccessFault)
      }
    }
    when(state === sFault && outFree) {
      outValid := true.B; out.reqId := held.reqId; out.status := fStatus; out.paddr := 0.U; out.cacheable := false.B
      heldValid := false.B; state := sIdle
    }
  }

  // ---- funcItlbRefill ---------------------------------------------------------------------------
  private val newE = Wire(new TlbEntry)
  newE := resp.bits.entry; newE.valid := true.B; newE.vpn := resp.bits.vpn
  private val conflict = VecInit(entries.map(x => x.valid && TlbLogic.conflicts(x, newE)))
  private val invalid  = VecInit(entries.map(!_.valid))
  private val slot = Mux(conflict.asUInt.orR, PriorityEncoder(conflict),
    Mux(invalid.asUInt.orR, PriorityEncoder(invalid), plruVictim(plru)))

  @LocalSpec(funcItlbRefill)
  val itlbRefill: Unit = when(resp.fire && !stale && !flushF && resp.bits.status === WalkStatus.Leaf) {
    for (i <- 0 until n) when(conflict(i)) { entries(i).valid := false.B }
    entries(slot) := newE
    plru := plruTouch(plru, slot)
  }

  // ---- funcItlbFlush ----------------------------------------------------------------------------
  @LocalSpec(funcItlbFlush)
  val itlbFlush: Unit = {
    io.itlbFlushIn.ready := true.B
    when(flushF) { for (i <- 0 until n) entries(i).valid := false.B }
  }

  // ---- Structural assertions --------------------------------------------------------------------
  assert(!io.iCacheTranslationOut.valid || io.iCacheTranslationOut.bits.status =/= TranslationStatus.Miss,
    "ItlbNoMiss: TranslationStatus.Miss offered to the InstructionCache")
  for (i <- 0 until n; j <- i + 1 until n)
    assert(!(entries(i).valid && entries(j).valid && TlbLogic.conflicts(entries(i), entries(j))),
      "ItlbNoDuplicate: two live entries can match the same lookup")
  TlbLogic.assertNoFaultCaching(entries, resp.fire && !stale && !flushF && resp.bits.status === WalkStatus.Leaf, "Itlb")
  TlbLogic.assertFlushed(entries, flushF, "Itlb")
}
