package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import udacore.core.design.shared._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.LoadStoreQueueSpecs._

/** LoadState (bndLoadQueueEntry.state). */
object LoadState {
  val width              = 3
  val AddrPending        = 0.U(width.W)
  val TranslationPending = 1.U(width.W)
  val Ready              = 2.U(width.W)
  val Issued             = 3.U(width.W) // lookup in flight, or uncacheable awaiting its grant/bus answer
  val WaitStoreData      = 4.U(width.W)
  val WaitStoreDrain     = 5.U(width.W)
  val Done               = 6.U(width.W)
  val Faulted            = 7.U(width.W)
}

/** StoreState (bndStoreQueueEntry.state). */
object StoreState {
  val width              = 2
  val AddrPending        = 0.U(width.W)
  val TranslationPending = 1.U(width.W)
  val Ready              = 2.U(width.W) // paddr known (addrKnown)
  val Faulted            = 3.U(width.W)
}

/** One LQ entry (bndLoadQueueEntry) plus its publication and uncached bookkeeping. */
class LqEntry(val params: BackendParams) extends BackendBundle {
  val valid       = Bool()
  val robTag      = new RobTag(params)
  val state       = UInt(LoadState.width.W)
  val vaddr       = UInt(vAddrWidth.W)
  val paddr       = UInt(pAddrWidth.W)
  val size        = UInt(2.W)
  val signed      = Bool()
  val prd         = UInt(physRegIdWidth.W)
  val uncacheable = Bool()
  val exception   = new ExceptionInfo(params)
  val data        = UInt(xLen.W)
  val resPending  = Bool() // a completion (value or exception) awaits MemResultOut
  val hxPending   = Bool() // a headExecute notice awaits MemResultOut
  val granted     = Bool()
  val ucIssued    = Bool()
  val sawRefill   = Bool() // a matching refill arrived while the lookup was in flight
}

/** One SQ entry (bndStoreQueueEntry) plus its translation, publication, and uncached bookkeeping. */
class SqEntry(val params: BackendParams) extends BackendBundle {
  val valid       = Bool()
  val robTag      = new RobTag(params)
  val state       = UInt(StoreState.width.W)
  val vaddr       = UInt(vAddrWidth.W)
  val paddr       = UInt(pAddrWidth.W)
  val size        = UInt(2.W)
  val data        = UInt(xLen.W)
  val mask        = UInt((xLen / 8).W)
  val dataKnown   = Bool()
  val cacheable   = Bool()
  val exception   = new ExceptionInfo(params)
  val xlReq       = Bool() // a DtlbReq{Store} is owed
  val xlWait      = Bool() // its answer is in flight
  val walkWait    = Bool() // answered Miss; waits for the refill of its VPN
  val sawRefill   = Bool()
  val resPending  = Bool()
  val hxPending   = Bool()
  val granted     = Bool()
  val ucIssued    = Bool()
  val ucPerformed = Bool()
}

/** LoadStoreQueue (spec: LoadStoreQueueSpecs; ADR-019 D-19.5, D-19.12).
  *
  * LQ and SQ are circular program-order FIFOs (head/tail pointers with a wrap bit); cross-queue
  * age is funcRobOlder on robTag. Stores translate through DtlbReq{Store}; loads issue a paired
  * DtlbReq{Load} + DCacheLoadReq once every older store has a physical address. Forwarding is
  * decided in the D-cache answer cycle: the SQ bytes of older stores (by physical address) and
  * the StoreBuffer answer of that same cycle (StoreForwardDataIn must answer the query
  * combinationally) are merged over the cache word; so a store moving SQ -> StoreBuffer ->
  * cache is never missed. Uncacheable accesses run only at the ROB head after HeadMemGrant and
  * StoreBufferEmpty, exactly once. Words travel lane-aligned.
  *
  * Recovery stance (rawSpeculativeHolder): killed entries (funcRecoveryKills) are removed in
  * the event cycle and each tail rewinds past the survivors; in-flight D-cache and DTLB answers
  * for removed or reallocated entries are dropped by the per-entry generation
  * (propGenerationTagScope); the held MemResult of a killed entry is dropped.
  */
@LocalSpec(contLoadStoreQueue)
class LoadStoreQueue(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfLsqAllocIn)
    val lsqAllocIn = Flipped(Decoupled(new LsqAllocation(params)))

    @LocalSpec(intfMemAddressIn)
    val memAddressIn = Flipped(Decoupled(new MemAddress(params)))

    @LocalSpec(intfDtlbReqOut)
    val dtlbReqOut = Decoupled(new TranslateReq(params.lsqReqIdWidth, vAddrWidth))

    @LocalSpec(intfDtlbStoreRespIn)
    val dtlbStoreRespIn = Flipped(Decoupled(new Translation(params.lsqReqIdWidth, pAddrWidth)))

    @LocalSpec(intfDtlbRefillIn)
    val dtlbRefillIn = Flipped(Decoupled(new WalkResp))

    @LocalSpec(intfDCacheLoadReqOut)
    val dCacheLoadReqOut = Decoupled(new DCacheLoadReq(params.lqIdxWidth, params.lsqGenWidth, vAddrWidth))

    @LocalSpec(intfDCacheLoadRespIn)
    val dCacheLoadRespIn = Flipped(Decoupled(new DCacheLoadResp(params.lqIdxWidth, params.lsqGenWidth, pAddrWidth, xLen)))

    @LocalSpec(intfStoreForwardQueryOut)
    val storeForwardQueryOut = Decoupled(new StoreForwardQuery(params))

    @LocalSpec(intfStoreForwardDataIn)
    val storeForwardDataIn = Flipped(Decoupled(new StoreForwardData(params)))

    @LocalSpec(intfMemResultOut)
    val memResultOut = Decoupled(new MemResult(params))

    @LocalSpec(intfStoreCommitIn)
    val storeCommitIn = Flipped(Decoupled(new StoreCommit(params)))

    @LocalSpec(intfCommittedStoreOut)
    val committedStoreOut = Decoupled(new CommittedStore(params))

    @LocalSpec(intfRobStatusIn)
    val robStatusIn = Input(new RobStatus(params))

    @LocalSpec(intfHeadMemGrantIn)
    val headMemGrantIn = Flipped(Decoupled(new HeadMemGrant(params)))

    @LocalSpec(intfUncachedLoadReqOut)
    val uncachedLoadReqOut = Decoupled(new UncachedLoadReq(params.lqIdxWidth, params.lsqGenWidth, pAddrWidth))

    @LocalSpec(intfUncachedLoadRespIn)
    val uncachedLoadRespIn = Flipped(Decoupled(new UncachedLoadResp(params.lqIdxWidth, params.lsqGenWidth, xLen)))

    @LocalSpec(intfUncachedStoreReqOut)
    val uncachedStoreReqOut = Decoupled(new UncachedStoreReq(params.sqIdxWidth, pAddrWidth, xLen))

    @LocalSpec(intfUncachedStoreRespIn)
    val uncachedStoreRespIn = Flipped(Decoupled(new UncachedStoreResp(params.sqIdxWidth)))

    @LocalSpec(intfStoreBufferEmptyIn)
    val storeBufferEmptyIn = Input(Bool())

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))
  })

  private val nL   = params.tuning.loadQueueDepth
  private val nS   = params.tuning.storeQueueDepth
  private val lW   = params.lqIdxWidth
  private val sW   = params.sqIdxWidth
  private val gW   = params.lsqGenWidth
  private val xW   = params.lsqReqIdWidth - 1 - gW // index field width of a reqId
  private val ev   = io.recoveryEventIn
  require(isPow2(nL) && isPow2(nS), "LQ/SQ depths must be powers of two")

  // Causes (RISC-V).
  private val LoadMisaligned = 4.U; private val LoadAccess = 5.U; private val StoreMisaligned = 6.U
  private val StoreAccess = 7.U; private val LoadPage = 13.U; private val StorePage = 15.U

  val lq    = RegInit(VecInit(Seq.fill(nL)(0.U.asTypeOf(new LqEntry(params)))))
  val sq    = RegInit(VecInit(Seq.fill(nS)(0.U.asTypeOf(new SqEntry(params)))))
  val lqGen = RegInit(VecInit(Seq.fill(nL)(0.U(gW.W))))
  val sqGen = RegInit(VecInit(Seq.fill(nS)(0.U(gW.W))))
  val lqHead, lqTail = RegInit(0.U((lW + 1).W))
  val sqHead, sqTail = RegInit(0.U((sW + 1).W))

  private def idx(p: UInt, w: Int): UInt = p(w - 1, 0)
  private val lqFull = lqHead(lW) =/= lqTail(lW) && idx(lqHead, lW) === idx(lqTail, lW)
  private val sqFull = sqHead(sW) =/= sqTail(sW) && idx(sqHead, sW) === idx(sqTail, sW)

  private val lqKilled = VecInit(lq.map(e => e.valid && RobOrder.recoveryKills(ev, e.robTag)))
  private val sqKilled = VecInit(sq.map(e => e.valid && RobOrder.recoveryKills(ev, e.robTag)))
  private val lqLive   = VecInit(lq.zip(lqKilled).map { case (e, k) => e.valid && !k })
  private val sqLive   = VecInit(sq.zip(sqKilled).map { case (e, k) => e.valid && !k })

  // ---- Byte-lane helpers ------------------------------------------------------------------------

  private def maskOf(size: UInt, addr: UInt): UInt =
    (MuxLookup(size, "b1111".U(4.W))(Seq(0.U -> "b0001".U(4.W), 1.U -> "b0011".U(4.W))) << addr(1, 0))(3, 0)
  private def misaligned(size: UInt, addr: UInt): Bool =
    (size === 1.U && addr(0)) || (size === 2.U && addr(1, 0) =/= 0.U)
  private def extend(word: UInt, addr: UInt, size: UInt, signed: Bool): UInt = {
    val sh   = (word >> (addr(1, 0) ## 0.U(3.W)))(31, 0)
    val byte = sh(7, 0); val half = sh(15, 0)
    MuxLookup(size, sh)(Seq(
      0.U -> Cat(Fill(24, signed && byte(7)), byte),
      1.U -> Cat(Fill(16, signed && half(15)), half)))
  }
  private def bytesOf(w: UInt): Seq[UInt] = (0 until 4).map(b => w(8 * b + 7, 8 * b))
  private def sameWord(a: UInt, b: UInt): Bool = a(pAddrWidth - 1, 2) === b(pAddrWidth - 1, 2)

  // ---- funcLsqAllocate ------------------------------------------------------------------------------

  private val alloc = io.lsqAllocIn
  @LocalSpec(funcLsqAllocate)
  val lsqAllocate: Unit = {
    alloc.ready := Mux(alloc.bits.isLoad, !lqFull, !sqFull)
    // An allocation in a RecoveryEvent cycle is always younger than the event, so it is killed.
    when(alloc.fire && !ev.valid) {
      when(alloc.bits.isLoad) {
        val i = idx(lqTail, lW)
        val e = WireInit(0.U.asTypeOf(new LqEntry(params)))
        e.valid := true.B; e.robTag := alloc.bits.robTag; e.state := LoadState.AddrPending
        e.size := alloc.bits.size; e.signed := alloc.bits.signed; e.prd := alloc.bits.prd
        lq(i) := e
        lqGen(i) := lqGen(i) + 1.U
      }.otherwise {
        val i = idx(sqTail, sW)
        val e = WireInit(0.U.asTypeOf(new SqEntry(params)))
        e.valid := true.B; e.robTag := alloc.bits.robTag; e.state := StoreState.AddrPending; e.size := alloc.bits.size
        sq(i) := e
        sqGen(i) := sqGen(i) + 1.U
      }
    }
    assert(!(alloc.valid && alloc.bits.isLoad === alloc.bits.isStore), "LsqAllocate: exactly one of isLoad/isStore")
    // The RenameUnit renames nothing in a RecoveryEvent cycle; the guard above is defensive.
    assert(!(alloc.valid && ev.valid), "LsqAllocate: an allocation was offered in a RecoveryEvent cycle")
  }

  // ---- Result publication (one-entry holder, killed token dropped) -------------------------------------

  private val holder = new ResultHolder(new MemResult(params), (r: MemResult) => r.robTag, ev, io.memResultOut)
  private val lqWants = VecInit(lq.indices.map(i => lqLive(i) && (lq(i).resPending || lq(i).hxPending)))
  private val sqWants = VecInit(sq.indices.map(i => sqLive(i) && (sq(i).resPending || sq(i).hxPending)))
  private val candTags  = lq.map(_.robTag) ++ sq.map(_.robTag)
  private val candValid = lqWants ++ sqWants
  private val (anyCand, pickIdx) = candValid.zipWithIndex.map { case (v, i) => (v, i.U(log2Ceil(nL + nS).W)) }
    .reduce { (x, y) =>
      val tags = VecInit(candTags)
      val takeY = y._1 && (!x._1 || RobOrder.robOlder(tags(y._2), tags(x._2)))
      (x._1 || y._1, Mux(takeY, y._2, x._2))
    }
  private val publishNow = anyCand && holder.canAccept
  locally {
    val r = Wire(new MemResult(params))
    r := 0.U.asTypeOf(r)
    val isL  = pickIdx < nL.U
    val li   = pickIdx(lW - 1, 0)
    val si   = (pickIdx - nL.U)(sW - 1, 0)
    val le   = lq(li); val se = sq(si)
    val lHx  = le.hxPending; val sHx = se.hxPending
    r.robTag      := Mux(isL, le.robTag, se.robTag)
    r.prd         := le.prd
    r.headExecute := Mux(isL, lHx, sHx)
    r.exception   := Mux(isL, le.exception, se.exception)
    r.wen         := isL && !lHx && !le.exception.valid && le.prd =/= 0.U
    r.data        := le.data
    holder.load(publishNow, r)
    when(publishNow) {
      when(isL) { when(lHx) { lq(li).hxPending := false.B }.otherwise { lq(li).resPending := false.B } }
        .otherwise { when(sHx) { sq(si).hxPending := false.B }.otherwise { sq(si).resPending := false.B } }
    }
  }

  // ---- funcAddressCapture ---------------------------------------------------------------------------

  private val ma = io.memAddressIn
  @LocalSpec(funcAddressCapture)
  val addressCapture: Unit = {
    ma.ready := true.B
    val lHit = VecInit(lq.indices.map(i => lqLive(i) && lq(i).robTag.asUInt === ma.bits.robTag.asUInt))
    val sHit = VecInit(sq.indices.map(i => sqLive(i) && sq(i).robTag.asUInt === ma.bits.robTag.asUInt))
    when(ma.fire) {
      for (i <- 0 until nL) when(lHit(i)) {
        lq(i).vaddr := ma.bits.vaddr
        when(ma.bits.misaligned || misaligned(lq(i).size, ma.bits.vaddr)) {
          lq(i).state := LoadState.Faulted; lq(i).resPending := true.B
          lq(i).exception.valid := true.B; lq(i).exception.cause := LoadMisaligned; lq(i).exception.tval := ma.bits.vaddr
        }.otherwise { lq(i).state := LoadState.Ready }
      }
      for (i <- 0 until nS) when(sHit(i)) {
        sq(i).vaddr := ma.bits.vaddr
        sq(i).mask  := maskOf(sq(i).size, ma.bits.vaddr)
        sq(i).data  := (ma.bits.storeData << (ma.bits.vaddr(1, 0) ## 0.U(3.W)))(31, 0)
        sq(i).dataKnown := true.B
        when(ma.bits.misaligned || misaligned(sq(i).size, ma.bits.vaddr)) {
          sq(i).state := StoreState.Faulted; sq(i).resPending := true.B
          sq(i).exception.valid := true.B; sq(i).exception.cause := StoreMisaligned; sq(i).exception.tval := ma.bits.vaddr
        }.otherwise {
          sq(i).state := StoreState.TranslationPending; sq(i).xlReq := true.B
        }
      }
      assert(PopCount(lHit) + PopCount(sHit) <= 1.U, "AddressCapture: MemAddress matches more than one entry")
      for (i <- 0 until nL) assert(!lHit(i) || lq(i).state === LoadState.AddrPending, "AddressCapture: second address for a load")
      for (i <- 0 until nS) assert(!sHit(i) || sq(i).state === StoreState.AddrPending, "AddressCapture: second address for a store")
    }
  }

  // ---- Age order helpers ------------------------------------------------------------------------

  private val lqH = idx(lqHead, lW)
  private val sqH = idx(sqHead, sW)
  /** The first index in queue order from `head` satisfying `cond`. */
  private def oldest(cond: Seq[Bool], head: UInt, w: Int): (Bool, UInt) = {
    val n   = cond.size
    val rot = VecInit((0 until n).map(k => VecInit(cond)((head + k.U)(w - 1, 0))))
    (rot.asUInt.orR, (head + PriorityEncoder(rot.asUInt))(w - 1, 0))
  }

  // ---- funcConservativeDisambig -----------------------------------------------------------------------

  /** Every older SQ entry has a physical address or is Faulted. */
  @LocalSpec(funcConservativeDisambig)
  val disambigOk: Vec[Bool] = VecInit(lq.map { l =>
    !sq.map(s => s.valid && RobOrder.robOlder(s.robTag, l.robTag) &&
      s.state =/= StoreState.Ready && s.state =/= StoreState.Faulted).reduce(_ || _)
  })

  // ---- funcLoadIssue (and the store translation request) -------------------------------------------------

  private val dq = io.dtlbReqOut
  private val dc = io.dCacheLoadReqOut
  private val (stSel, stIdx) = oldest(sq.indices.map(j => sqLive(j) && sq(j).xlReq), sqH, sW)
  private val (ldSel, ldIdx) = oldest(lq.indices.map(i => lqLive(i) && lq(i).state === LoadState.Ready && disambigOk(i)), lqH, lW)

  @LocalSpec(funcLoadIssue)
  val loadIssue: Bool = {
    val loadGo = !stSel && ldSel
    // Store translations first (they unblock loads); a load's DtlbReq and D-cache lookup fire
    // together: each valid waits only for the other port's ready.
    dq.valid := stSel || (loadGo && dc.ready)
    dc.valid := loadGo && dq.ready
    dq.bits.access := Mux(stSel, AccessType.Store, AccessType.Load)
    dq.bits.vaddr  := Mux(stSel, sq(stIdx).vaddr, lq(ldIdx).vaddr)
    dq.bits.reqId  := Mux(stSel, Cat(1.U(1.W), stIdx.pad(xW), sqGen(stIdx)), Cat(0.U(1.W), ldIdx.pad(xW), lqGen(ldIdx)))
    dc.bits.lqIdx := ldIdx
    dc.bits.lqGen := lqGen(ldIdx)
    dc.bits.vaddr := lq(ldIdx).vaddr
    dc.bits.size  := lq(ldIdx).size
    when(dq.fire && stSel) { sq(stIdx).xlReq := false.B; sq(stIdx).xlWait := true.B; sq(stIdx).sawRefill := false.B }
    when(dc.fire) { lq(ldIdx).state := LoadState.Issued; lq(ldIdx).sawRefill := false.B }
    dc.fire
  }

  // ---- funcTranslationWait -------------------------------------------------------------------------------

  private val rf      = io.dtlbRefillIn
  private def vpnOf(va: UInt): UInt = va(vAddrWidth - 1, 12)
  private val refillV = rf.valid
  @LocalSpec(funcTranslationWait)
  val translationWait: Unit = {
    rf.ready := true.B
    when(refillV) {
      for (i <- 0 until nL) when(lq(i).valid && vpnOf(lq(i).vaddr) === rf.bits.vpn) {
        when(lq(i).state === LoadState.TranslationPending) { lq(i).state := LoadState.Ready }
        when(lq(i).state === LoadState.Issued && !lq(i).uncacheable) { lq(i).sawRefill := true.B }
      }
      for (j <- 0 until nS) when(sq(j).valid && vpnOf(sq(j).vaddr) === rf.bits.vpn) {
        when(sq(j).walkWait) { sq(j).walkWait := false.B; sq(j).xlReq := true.B }
        when(sq(j).xlWait) { sq(j).sawRefill := true.B }
      }
    }
    // Store translation answers.
    val t     = io.dtlbStoreRespIn
    val rid   = t.bits.reqId
    val j     = rid(gW + sW - 1, gW)
    val tLive = rid(gW + xW) && sq(j).valid && sqGen(j) === rid(gW - 1, 0) && sq(j).xlWait && !sqKilled(j)
    t.ready := true.B
    when(t.fire && tLive) {
      sq(j).xlWait := false.B
      switch(t.bits.status) {
        is(TranslationStatus.Hit) {
          sq(j).paddr := t.bits.paddr; sq(j).cacheable := t.bits.cacheable; sq(j).state := StoreState.Ready
          when(t.bits.cacheable) { sq(j).resPending := true.B }.otherwise { sq(j).hxPending := true.B }
        }
        is(TranslationStatus.Miss) {
          when(sq(j).sawRefill || (refillV && vpnOf(sq(j).vaddr) === rf.bits.vpn)) { sq(j).xlReq := true.B }
            .otherwise { sq(j).walkWait := true.B }
        }
        is(TranslationStatus.PageFault) {
          sq(j).state := StoreState.Faulted; sq(j).resPending := true.B
          sq(j).exception.valid := true.B; sq(j).exception.cause := StorePage; sq(j).exception.tval := sq(j).vaddr
        }
        is(TranslationStatus.AccessFault) {
          sq(j).state := StoreState.Faulted; sq(j).resPending := true.B
          sq(j).exception.valid := true.B; sq(j).exception.cause := StoreAccess; sq(j).exception.tval := sq(j).vaddr
        }
      }
    }
  }

  // ---- funcStoreToLoadForward + funcLoadComplete (the D-cache answer cycle) -----------------------------------

  private val dr     = io.dCacheLoadRespIn
  private val di     = dr.bits.lqIdx
  private val dl     = lq(di)
  private val drLive = dl.valid && lqGen(di) === dr.bits.lqGen && dl.state === LoadState.Issued && !dl.uncacheable && !lqKilled(di)
  private val isData = dr.bits.status === LoadStatus.Data
  private val lmask  = maskOf(dl.size, dr.bits.paddr)
  private val fq     = io.storeForwardQueryOut
  private val fd     = io.storeForwardDataIn

  /** Per load byte: (covered by an older SQ store, youngest such byte), in SQ age order. */
  private def sqSrc(j: UInt, b: Int): Bool = {
    val s = sq(j)
    s.valid && RobOrder.robOlder(s.robTag, dl.robTag) && s.state === StoreState.Ready &&
      sameWord(s.paddr, dr.bits.paddr) && s.mask(b) && lmask(b)
  }
  private val sqFwd: Seq[(Bool, UInt)] = (0 until 4).map { b =>
    (0 until nS).foldLeft((false.B, 0.U(8.W))) { case ((cov, byte), k) =>
      val j = (sqH + k.U)(sW - 1, 0)
      val hit = sqSrc(j, b)
      (cov || hit, Mux(hit, bytesOf(sq(j).data)(b), byte))
    }
  }
  private val sqCov      = VecInit(sqFwd.map(_._1)).asUInt
  private val dataMissing = sq.map(s => s.valid && RobOrder.robOlder(s.robTag, dl.robTag) && s.state === StoreState.Ready &&
    sameWord(s.paddr, dr.bits.paddr) && (s.mask & lmask).orR && !s.dataKnown).reduce(_ || _)

  @LocalSpec(funcStoreToLoadForward)
  val storeToLoadForward: UInt = {
    fq.valid      := dr.valid && drLive && isData
    fq.bits.paddr := dr.bits.paddr
    fq.bits.mask  := lmask
    fd.ready      := true.B
    dr.ready      := !(drLive && isData) || (fq.ready && fd.valid)
    val merged = VecInit((0 until 4).map { b =>
      Mux(sqCov(b), sqFwd(b)._2, Mux(fd.bits.hitMask(b), bytesOf(fd.bits.data)(b), bytesOf(dr.bits.data)(b)))
    }).asUInt
    merged
  }

  @LocalSpec(funcLoadComplete)
  val loadComplete: Unit = {
    val drainWait = fd.bits.partial && (lmask & ~sqCov).orR
    when(dr.fire && drLive) {
      switch(dr.bits.status) {
        is(LoadStatus.Data) {
          lq(di).paddr := dr.bits.paddr
          when(dataMissing) { lq(di).state := LoadState.WaitStoreData }
            .elsewhen(drainWait) { lq(di).state := LoadState.WaitStoreDrain }
            .otherwise {
              lq(di).state := LoadState.Done; lq(di).resPending := true.B
              lq(di).data := extend(storeToLoadForward, dr.bits.paddr, dl.size, dl.signed)
            }
        }
        is(LoadStatus.TlbMiss) {
          when(dl.sawRefill || (refillV && vpnOf(dl.vaddr) === rf.bits.vpn)) { lq(di).state := LoadState.Ready }
            .otherwise { lq(di).state := LoadState.TranslationPending }
        }
        is(LoadStatus.Replay) { lq(di).state := LoadState.Ready }
        is(LoadStatus.PageFault) {
          lq(di).state := LoadState.Faulted; lq(di).resPending := true.B
          lq(di).exception.valid := true.B; lq(di).exception.cause := LoadPage; lq(di).exception.tval := dl.vaddr
        }
        is(LoadStatus.AccessFault) {
          lq(di).state := LoadState.Faulted; lq(di).resPending := true.B
          lq(di).exception.valid := true.B; lq(di).exception.cause := LoadAccess; lq(di).exception.tval := dl.vaddr
        }
        is(LoadStatus.Uncacheable) {
          lq(di).paddr := dr.bits.paddr; lq(di).uncacheable := true.B; lq(di).hxPending := true.B
        }
      }
    }
    // An older store's data always arrives with its address (bndMemAddress), so WaitStoreData
    // re-issues at once; WaitStoreDrain re-issues when the StoreBuffer has drained.
    for (i <- 0 until nL) {
      when(lq(i).valid && lq(i).state === LoadState.WaitStoreData) { lq(i).state := LoadState.Ready }
      when(lq(i).valid && lq(i).state === LoadState.WaitStoreDrain && io.storeBufferEmptyIn) { lq(i).state := LoadState.Ready }
    }
  }

  // ---- funcStoreComplete: completions are published by the holder above (resPending/hxPending) ------------

  @LocalSpec(funcStoreComplete)
  val storeComplete: Vec[Bool] = sqWants

  // ---- funcUncacheableAtHead -----------------------------------------------------------------------------

  private val g  = io.headMemGrantIn
  private val ul = io.uncachedLoadReqOut
  private val us = io.uncachedStoreReqOut
  private val (ulSel, ulIdx) = oldest(lq.indices.map(i => lqLive(i) && lq(i).granted && !lq(i).ucIssued), lqH, lW)
  private val (usSel, usIdx) = oldest(sq.indices.map(j => sqLive(j) && sq(j).granted && !sq(j).ucIssued), sqH, sW)

  @LocalSpec(funcUncacheableAtHead)
  val uncacheableAtHead: Unit = {
    g.ready := true.B
    val gL = VecInit(lq.indices.map(i => lqLive(i) && lq(i).uncacheable && lq(i).state === LoadState.Issued &&
      lq(i).robTag.asUInt === g.bits.robTag.asUInt))
    val gS = VecInit(sq.indices.map(j => sqLive(j) && !sq(j).cacheable && sq(j).state === StoreState.Ready &&
      sq(j).robTag.asUInt === g.bits.robTag.asUInt))
    when(g.fire) {
      for (i <- 0 until nL) when(gL(i)) { lq(i).granted := true.B }
      for (j <- 0 until nS) when(gS(j)) { sq(j).granted := true.B }
      assert(PopCount(gL) + PopCount(gS) === 1.U, "UncacheableAtHead: a HeadMemGrant names no waiting uncacheable uop")
    }
    // Older committed stores are ordered before it: wait for StoreBufferEmpty.
    ul.valid := ulSel && io.storeBufferEmptyIn
    ul.bits.lqIdx := ulIdx; ul.bits.lqGen := lqGen(ulIdx); ul.bits.paddr := lq(ulIdx).paddr; ul.bits.size := lq(ulIdx).size
    when(ul.fire) { lq(ulIdx).ucIssued := true.B }
    us.valid := usSel && io.storeBufferEmptyIn
    us.bits.sqIdx := usIdx; us.bits.paddr := sq(usIdx).paddr; us.bits.data := sq(usIdx).data; us.bits.mask := sq(usIdx).mask
    when(us.fire) { sq(usIdx).ucIssued := true.B }

    val lr = io.uncachedLoadRespIn
    val li = lr.bits.lqIdx
    lr.ready := true.B
    when(lr.fire && lq(li).valid && lqGen(li) === lr.bits.lqGen && lq(li).ucIssued && lq(li).state === LoadState.Issued) {
      lq(li).resPending := true.B
      when(lr.bits.accessFault) {
        lq(li).state := LoadState.Faulted
        lq(li).exception.valid := true.B; lq(li).exception.cause := LoadAccess; lq(li).exception.tval := lq(li).vaddr
      }.otherwise {
        lq(li).state := LoadState.Done; lq(li).data := extend(lr.bits.data, lq(li).paddr, lq(li).size, lq(li).signed)
      }
    }
    val sr = io.uncachedStoreRespIn
    val si = sr.bits.sqIdx
    sr.ready := true.B
    when(sr.fire && sq(si).valid && sq(si).ucIssued && !sq(si).ucPerformed && sq(si).state === StoreState.Ready) {
      sq(si).resPending := true.B
      when(sr.bits.accessFault) {
        sq(si).state := StoreState.Faulted
        sq(si).exception.valid := true.B; sq(si).exception.cause := StoreAccess; sq(si).exception.tval := sq(si).vaddr
      }.otherwise { sq(si).ucPerformed := true.B }
    }
  }

  // ---- funcStoreCommitHandoff -----------------------------------------------------------------------------

  private val sc = io.storeCommitIn
  private val cs = io.committedStoreOut
  private val he = sq(sqH)
  private val cacheableHead = he.cacheable && !he.ucPerformed
  @LocalSpec(funcStoreCommitHandoff)
  val storeCommitHandoff: Bool = {
    cs.valid      := sc.valid && cacheableHead
    cs.bits.paddr := he.paddr
    cs.bits.data  := he.data
    cs.bits.mask  := he.mask
    sc.ready      := !cacheableHead || cs.ready
    when(sc.fire) { sq(sqH).valid := false.B }
    assert(!sc.valid || (he.valid && he.robTag.asUInt === sc.bits.robTag.asUInt),
      "StoreCommitHandoff: StoreCommit does not name the SQ head")
    assert(!sc.valid || (he.state === StoreState.Ready && (he.cacheable || he.ucPerformed) && !he.resPending && !he.hxPending),
      "StoreCommitHandoff: StoreCommit for a store that has not completed")
    sc.fire
  }

  // ---- LQ release at retirement (RobStatus) ------------------------------------------------------------------

  private val lhe    = lq(lqH)
  private val rob    = io.robStatusIn
  private val lqRel  = lhe.valid && !lqKilled(lqH) && (rob.empty || RobOrder.robOlder(lhe.robTag, rob.headTag))
  when(lqRel) { lq(lqH).valid := false.B }
  assert(!lqRel || (!lhe.resPending && !lhe.hxPending && (lhe.state === LoadState.Done || lhe.state === LoadState.Faulted)),
    "LsqRelease: a load retired before its completion was published")

  // ---- funcLsqRecovery -------------------------------------------------------------------------------------------

  @LocalSpec(funcLsqRecovery)
  val lsqRecovery: Unit = {
    for (i <- 0 until nL) when(lqKilled(i)) { lq(i).valid := false.B }
    for (j <- 0 until nS) when(sqKilled(j)) { sq(j).valid := false.B }
    lqHead := lqHead + lqRel.asUInt
    sqHead := sqHead + storeCommitHandoff.asUInt
    when(ev.valid) {
      // Killed entries are the youngest suffix: the tail rewinds to just past the survivors.
      lqTail := lqHead + PopCount(lqLive)
      sqTail := sqHead + PopCount(sqLive)
    }.otherwise {
      lqTail := lqTail + (alloc.fire && alloc.bits.isLoad).asUInt
      sqTail := sqTail + (alloc.fire && alloc.bits.isStore).asUInt
    }
    def suffix(killed: Seq[Bool], valid: Seq[Bool], head: UInt, w: Int): Bool = {
      val n = killed.size
      val k = VecInit((0 until n).map(i => VecInit(killed)((head + i.U)(w - 1, 0))))
      val v = VecInit((0 until n).map(i => VecInit(valid)((head + i.U)(w - 1, 0))))
      (0 until n - 1).map(i => !(k(i) && v(i + 1) && !k(i + 1))).reduce(_ && _)
    }
    assert(suffix(lqKilled, lq.map(_.valid), lqH, lW) && suffix(sqKilled, sq.map(_.valid), sqH, sW),
      "LsqRecovery: killed entries are not the youngest suffix of a queue")
  }

  // ---- Properties (simulation assertions) ------------------------------------------------------------------------

  private val started = RegNext(true.B, false.B)

  @LocalSpec(propLsqRecoveryKeepsOlder)
  val lsqRecoveryKeepsOlder: Unit = {
    val lqGone = RegNext(VecInit(lq.indices.map(i => lqKilled(i) || (lqRel && lqH === i.U))))
    val sqGone = RegNext(VecInit(sq.indices.map(j => sqKilled(j) || (storeCommitHandoff && sqH === j.U))))
    val lqWas  = RegNext(VecInit(lq.map(_.valid)))
    val sqWas  = RegNext(VecInit(sq.map(_.valid)))
    for (i <- 0 until nL) assert(!started || !(lqWas(i) && !lq(i).valid) || lqGone(i),
      "LsqRecoveryKeepsOlder: an LQ entry vanished without being killed or retired")
    for (j <- 0 until nS) assert(!started || !(sqWas(j) && !sq(j).valid) || sqGone(j),
      "LsqRecoveryKeepsOlder: an SQ entry vanished without being killed or committed")
  }

  @LocalSpec(propNoPassUnresolvedStore)
  val noPassUnresolvedStore: Unit =
    assert(!dc.fire || disambigOk(ldIdx), "NoPassUnresolvedStore: a load issued past an older unresolved store")

  @LocalSpec(propPhysicalOrderingAuthority)
  val physicalOrderingAuthority: Unit =
    assert(!(dr.fire && drLive && isData) || disambigOk(di),
      "PhysicalOrderingAuthority: a load was forwarded while an older store had no physical address")

  @LocalSpec(propWrongPathLoadNoResult)
  val wrongPathLoadNoResult: Unit =
    assert(!io.memResultOut.valid || !RobOrder.recoveryKills(ev, io.memResultOut.bits.robTag),
      "WrongPathLoadNoResult: a killed uop offered a MemResult")

  @LocalSpec(propNoWrongPathStoreVisible)
  val noWrongPathStoreVisible: Unit = {
    assert(!cs.fire || sc.fire, "NoWrongPathStoreVisible: CommittedStoreOut without its StoreCommit")
    assert(!us.fire || (sq(usIdx).granted && !sqKilled(usIdx)), "NoWrongPathStoreVisible: an uncached store without its HeadMemGrant")
  }

  @LocalSpec(propUncachedPerformedOnce)
  val uncachedPerformedOnce: Unit = {
    assert(!ul.fire || (lq(ulIdx).granted && !lq(ulIdx).ucIssued), "UncachedPerformedOnce: a second or ungranted uncached load")
    assert(!us.fire || !sq(usIdx).ucIssued, "UncachedPerformedOnce: a second uncached store")
    assert(!cs.fire || (he.cacheable && !he.ucPerformed), "UncachedPerformedOnce: an uncacheable store entered the StoreBuffer")
  }
}
