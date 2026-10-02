package udacore.core.design.modules

import chisel3._
import chisel3.util._
import udacore.backend.design.shared.TranslationContext
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.DataTlbSpecs._

/** DataTlb (spec: DataTlbSpecs; Sv32Specs; ADR-019 D-19.5, D-19.6).
  *
  * A 16-entry fully associative, non-blocking load/store TLB. Every accepted request is resolved in
  * its accept cycle into one of two independent stable answer holders: loads (TranslateReq.access =
  * Load) to the DataCache, stores (access = Store) to the LoadStoreQueue. DtlbReqIn.ready depends only
  * on a flush token and on the request's own answer holder, never on a walk, so a miss stalls only the
  * requesting LSQ entry (propDtlbMissLocal). The reqId is opaque and echoed; only an assertion reads its
  * LSQ isStore bit (CoreLsqView) to check it agrees with the access type.
  *
  * Resolution: Bare (satp.MODE = Bare or dataPriv = M) -> pa = va; Sv32 hit -> pa composed from the
  * entry, then the data permission check with dataPriv (Load: (R or (MXR and X)) and A; Store: W and A
  * and D by Svade; U mode needs U; S mode needs U = 0 or SUM) -> PageFault, then the static PMA map on
  * the actual pa (Load: mapped and readable; Store: mapped and writable) -> AccessFault. Faults carry
  * paddr 0 / cacheable false; cacheable comes only from the PMA map.
  *
  * Miss: answered at once with status Miss (paddr 0, cacheable false); the request is not retained.
  * If no walk is active, the committed TranslationContext is captured and one WalkReq{vpn, context}
  * is offered, stable until it transfers; otherwise nothing more happens (the LSQ retries after the
  * next refill notice). A miss on the {vpn, asid} of the one-entry walk-fault record is answered with
  * the recorded fault instead.
  *
  * Walk completion: a WalkResp is consumed only when the refill-notice holder can take the notice, so
  * every completed walk yields exactly one stable DtlbRefillOut {walked vpn, status}. Leaf: installed
  * (conflicting live entries invalidated; slot = conflicting, else invalid, else the tree pseudo-LRU
  * victim). PageFault / AccessFault: written to the fault record with the walk's captured ASID.
  * Retry: nothing kept. Every non-stale result replaces the record (a Leaf or Retry clears it).
  *
  * SFENCE.VMA (v0: full flush): a flush token is always accepted; it clears every entry and the fault
  * record in its cycle, and no request is accepted that cycle. A walk overlapping the flush is stale:
  * its WalkReq (if not yet accepted) stays stable per the ready/valid rule, and its result installs
  * nothing, records nothing, and is announced as Retry so the LSQ re-walks with the post-flush context.
  */
@LocalSpec(contDataTlb)
class DataTlb(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDtlbReqIn)
    val dtlbReqIn = Flipped(Decoupled(new TranslateReq(params.lsq.reqIdWidth, params.vAddrWidth)))

    @LocalSpec(intfDCacheTranslationOut)
    val dCacheTranslationOut = Decoupled(new Translation(params.lsq.reqIdWidth, params.pAddrWidth))

    @LocalSpec(intfDtlbStoreRespOut)
    val dtlbStoreRespOut = Decoupled(new Translation(params.lsq.reqIdWidth, params.pAddrWidth))

    @LocalSpec(intfDtlbRefillOut)
    val dtlbRefillOut = Decoupled(new WalkResp)

    @LocalSpec(intfDtlbWalkReqOut)
    val dtlbWalkReqOut = Decoupled(new WalkReq)

    @LocalSpec(intfDtlbWalkRespIn)
    val dtlbWalkRespIn = Flipped(Decoupled(new WalkResp))

    @LocalSpec(intfDtlbFlushIn)
    val dtlbFlushIn = Flipped(Decoupled(new TlbFlush(params.vAddrWidth)))

    @LocalSpec(intfTranslationContextIn)
    val translationContextIn = Input(new TranslationContext)
  })

  private val n      = params.contract.dtlb.entries
  private val levels = log2Ceil(n)
  require(n >= 2 && isPow2(n), "the tree pseudo-LRU needs a power-of-two entry count")
  private val ridW  = params.lsq.reqIdWidth
  private val PrivU = 0.U(2.W); private val PrivM = 3.U(2.W)

  // ---- State ------------------------------------------------------------------------------------
  val entries = RegInit(VecInit(Seq.fill(n)(0.U.asTypeOf(new TlbEntry))))
  /** Tree pseudo-LRU: heap node k (1..n-1) is bit k-1; a set bit sends the victim search right. */
  val plru    = RegInit(0.U((n - 1).W))
  val ldValid = RegInit(false.B)
  val ldOut   = Reg(new Translation(ridW, params.pAddrWidth))
  val stValid = RegInit(false.B)
  val stOut   = Reg(new Translation(ridW, params.pAddrWidth))
  val wIdle :: wReq :: wWait :: Nil = Enum(3)
  val wState  = RegInit(wIdle)
  val wVpn    = Reg(UInt(20.W))
  val wCtx    = Reg(new TranslationContext)
  /** The active walk overlapped a flush: its result is announced as Retry and otherwise discarded. */
  val stale   = RegInit(false.B)
  val nValid  = RegInit(false.B)
  val nOut    = Reg(new WalkResp)
  /** One-entry walk-fault record {vpn, asid, status} (status is a TranslationStatus fault code). */
  val fValid  = RegInit(false.B)
  val fVpn    = Reg(UInt(20.W))
  val fAsid   = Reg(UInt(9.W))
  val fStatus = Reg(UInt(TranslationStatus.width.W))

  private val ctx    = io.translationContextIn
  private val req    = io.dtlbReqIn
  private val resp   = io.dtlbWalkRespIn
  private val flushV = io.dtlbFlushIn.valid
  private val flushF = io.dtlbFlushIn.fire
  private val ldFree = !ldValid || io.dCacheTranslationOut.fire
  private val stFree = !stValid || io.dtlbStoreRespOut.fire
  private val nFree  = !nValid || io.dtlbRefillOut.fire

  private def plruVictim(b: UInt): UInt         = TlbLogic.plruVictim(b, levels)
  private def plruTouch(b: UInt, w: UInt): UInt = TlbLogic.plruTouch(b, w, levels)

  // ---- funcDtlbTranslate ------------------------------------------------------------------------
  private val va      = req.bits.vaddr
  private val vpn     = va(31, 12)
  private val isStore = req.bits.access === AccessType.Store
  private val bare    = !ctx.satpMode || ctx.dataPriv === PrivM
  private val matches = VecInit(entries.map(TlbLogic.matches(_, vpn, ctx.asid)))
  private val hitAny  = matches.asUInt.orR
  private val hitIdx  = OHToUInt(matches)
  private val e       = Mux1H(matches, entries)
  private val accOk   = Mux(isStore, e.w && e.d, e.r || (ctx.mxr && e.x)) && e.a
  private val privOk  = Mux(ctx.dataPriv === PrivU, e.u, !e.u || ctx.sum)
  private val paLook  = Mux(bare, va.pad(params.pAddrWidth), TlbLogic.compose(e, va))
  private val (pmaHit, pmaAttr) = PmaLookup(params.pma, paLook)
  private val pmaOk   = pmaHit && Mux(isStore, pmaAttr.writable, pmaAttr.readable)
  private val lStatus = Mux(!bare && !(accOk && privOk), TranslationStatus.PageFault,
    Mux(!pmaOk, TranslationStatus.AccessFault, TranslationStatus.Hit))
  private val miss    = !bare && !hitAny
  private val recHit  = fValid && fVpn === vpn && fAsid === ctx.asid

  @LocalSpec(funcDtlbTranslate)
  val dtlbTranslate: Unit = {
    req.ready := !flushV && Mux(isStore, stFree, ldFree)
    io.dCacheTranslationOut.valid := ldValid
    io.dCacheTranslationOut.bits  := ldOut
    io.dtlbStoreRespOut.valid     := stValid
    io.dtlbStoreRespOut.bits      := stOut
    when(io.dCacheTranslationOut.fire) { ldValid := false.B }
    when(io.dtlbStoreRespOut.fire) { stValid := false.B }
    val ans = Wire(new Translation(ridW, params.pAddrWidth))
    val isHit = !miss && lStatus === TranslationStatus.Hit
    ans.reqId     := req.bits.reqId
    ans.status    := Mux(miss, Mux(recHit, fStatus, TranslationStatus.Miss), lStatus)
    ans.paddr     := Mux(isHit, paLook, 0.U)
    ans.cacheable := isHit && pmaAttr.cacheable
    when(req.fire) {
      when(isStore) { stValid := true.B; stOut := ans }.otherwise { ldValid := true.B; ldOut := ans }
      when(isHit && !bare) { plru := plruTouch(plru, hitIdx) }
      assert(req.bits.access === AccessType.Load || isStore, "DtlbReq: a Fetch access on the DataTlb")
      assert(req.bits.reqId(ridW - 1) === isStore, "DtlbReq: the reqId isStore bit disagrees with the access type")
    }
    when(req.valid && !bare) { assert(PopCount(matches) <= 1.U, "DtlbMatch: more than one entry matches the lookup") }
  }

  // ---- funcDtlbMissNonBlocking ------------------------------------------------------------------
  @LocalSpec(funcDtlbMissNonBlocking)
  val dtlbMissNonBlocking: Unit = {
    io.dtlbWalkReqOut.valid        := wState === wReq
    io.dtlbWalkReqOut.bits.vpn     := wVpn
    io.dtlbWalkReqOut.bits.context := wCtx
    when(req.fire && miss && !recHit && wState === wIdle) { wState := wReq; wVpn := vpn; wCtx := ctx; stale := false.B }
    when(io.dtlbWalkReqOut.fire) { wState := wWait }
    when(flushF && wState =/= wIdle) { stale := true.B }
    when(resp.fire) { wState := wIdle }
  }

  // ---- funcDtlbRefill ---------------------------------------------------------------------------
  private val dead = stale || flushF // the completing walk overlapped a flush
  private val newE = Wire(new TlbEntry)
  newE := resp.bits.entry; newE.valid := true.B; newE.vpn := wVpn
  private val conflict = VecInit(entries.map(x => x.valid && TlbLogic.conflicts(x, newE)))
  private val invalid  = VecInit(entries.map(!_.valid))
  private val slot = Mux(conflict.asUInt.orR, PriorityEncoder(conflict),
    Mux(invalid.asUInt.orR, PriorityEncoder(invalid), plruVictim(plru)))

  @LocalSpec(funcDtlbRefill)
  val dtlbRefill: Unit = {
    resp.ready := wState === wWait && nFree
    io.dtlbRefillOut.valid := nValid
    io.dtlbRefillOut.bits  := nOut
    when(io.dtlbRefillOut.fire) { nValid := false.B }
    when(resp.fire) {
      assert(resp.bits.vpn === wVpn, "DtlbWalkResp: the WalkResp VPN differs from the active walk")
      nValid := true.B
      nOut := resp.bits; nOut.vpn := wVpn; nOut.status := Mux(dead, WalkStatus.Retry, resp.bits.status)
      when(!dead) {
        val isFault = resp.bits.status === WalkStatus.PageFault || resp.bits.status === WalkStatus.AccessFault
        fValid := isFault; fVpn := wVpn; fAsid := wCtx.asid
        fStatus := Mux(resp.bits.status === WalkStatus.PageFault, TranslationStatus.PageFault, TranslationStatus.AccessFault)
        when(resp.bits.status === WalkStatus.Leaf) {
          for (i <- 0 until n) when(conflict(i)) { entries(i).valid := false.B }
          entries(slot) := newE
          plru := plruTouch(plru, slot)
        }
      }
    }
  }

  // ---- funcDtlbFlush ----------------------------------------------------------------------------
  @LocalSpec(funcDtlbFlush)
  val dtlbFlush: Unit = {
    io.dtlbFlushIn.ready := true.B
    when(flushF) {
      for (i <- 0 until n) entries(i).valid := false.B
      fValid := false.B
    }
  }

  // ---- propDtlbMissLocal ------------------------------------------------------------------------
  @LocalSpec(propDtlbMissLocal)
  val dtlbMissLocal: Unit = {
    assert(!(req.valid && !flushV && Mux(isStore, stFree, ldFree) && !req.ready),
      "DtlbMissLocal: DtlbReqIn.ready is low although no flush is pending and the answer holder is free")
    val firedLd = RegNext(req.fire && !isStore, false.B)
    val firedSt = RegNext(req.fire && isStore, false.B)
    assert(!firedLd || ldValid, "DtlbMissLocal: an accepted load request was not answered")
    assert(!firedSt || stValid, "DtlbMissLocal: an accepted store request was not answered")
  }

  // ---- Structural assertions --------------------------------------------------------------------
  for (i <- 0 until n; j <- i + 1 until n)
    assert(!(entries(i).valid && entries(j).valid && TlbLogic.conflicts(entries(i), entries(j))),
      "DtlbNoDuplicate: two live entries can match the same lookup")
  TlbLogic.assertNoFaultCaching(entries, resp.fire && !dead && resp.bits.status === WalkStatus.Leaf, "Dtlb")
  TlbLogic.assertFlushed(entries, flushF, "Dtlb")
}
