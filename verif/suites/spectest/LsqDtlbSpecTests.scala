package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.modules.LoadStoreQueue
import udacore.backend.design.shared.{BackendParams, RecoveryKind, TranslationContext}
import udacore.core.design.modules.DataTlb
import udacore.core.design.shared._

/** Test-only harness: the real LoadStoreQueue feeding the real DataTlb (as in CoreTop): DtlbReq,
  * the store answers and the refill notices are internal. Every other LSQ port, the DTLB load
  * answer (to the DataCache), the PTW side, the flush token and the TranslationContext are ports;
  * the internal handshakes are mirrored on monitor outputs. The CoreTop parameter-equality check
  * (CoreLsqView == BackendParams LSQ geometry) is previewed here. Building it runs firtool. */
class LsqDtlbHarness(val bp: BackendParams, val cp: CoreParams) extends Module {
  require(cp.lsq.loadQueueDepth == bp.tuning.loadQueueDepth && cp.lsq.storeQueueDepth == bp.tuning.storeQueueDepth &&
    cp.lsq.generationWidth == bp.lsqGenWidth && cp.lsq.reqIdWidth == bp.lsqReqIdWidth,
    "CoreLsqView must equal the BackendParams LSQ geometry")
  val lsq  = Module(new LoadStoreQueue(bp))
  val dtlb = Module(new DataTlb(cp))
  private def port[T <: Data](x: T): T = IO(chiselTypeOf(x))
  val lsqAllocIn           = port(lsq.io.lsqAllocIn)
  val memAddressIn         = port(lsq.io.memAddressIn)
  val dCacheLoadReqOut     = port(lsq.io.dCacheLoadReqOut)
  val dCacheLoadRespIn     = port(lsq.io.dCacheLoadRespIn)
  val storeForwardQueryOut = port(lsq.io.storeForwardQueryOut)
  val storeForwardDataIn   = port(lsq.io.storeForwardDataIn)
  val memResultOut         = port(lsq.io.memResultOut)
  val storeCommitIn        = port(lsq.io.storeCommitIn)
  val committedStoreOut    = port(lsq.io.committedStoreOut)
  val robStatusIn          = port(lsq.io.robStatusIn)
  val headMemGrantIn       = port(lsq.io.headMemGrantIn)
  val uncachedLoadReqOut   = port(lsq.io.uncachedLoadReqOut)
  val uncachedLoadRespIn   = port(lsq.io.uncachedLoadRespIn)
  val uncachedStoreReqOut  = port(lsq.io.uncachedStoreReqOut)
  val uncachedStoreRespIn  = port(lsq.io.uncachedStoreRespIn)
  val storeBufferEmptyIn   = port(lsq.io.storeBufferEmptyIn)
  val recoveryEventIn      = port(lsq.io.recoveryEventIn)
  val dCacheTranslationOut = port(dtlb.io.dCacheTranslationOut)
  val walkReq              = port(dtlb.io.dtlbWalkReqOut)
  val walkResp             = port(dtlb.io.dtlbWalkRespIn)
  val flush                = port(dtlb.io.dtlbFlushIn)
  val ctx                  = port(dtlb.io.translationContextIn)
  val mon = IO(Output(new Bundle {
    val reqFire    = Bool(); val reqId = UInt(cp.lsq.reqIdWidth.W); val reqAccess = UInt(AccessType.width.W)
    val reqVaddr   = UInt(cp.vAddrWidth.W)
    val stFire     = Bool(); val stReqId = UInt(cp.lsq.reqIdWidth.W); val stStatus = UInt(TranslationStatus.width.W)
    val noticeFire = Bool(); val noticeVpn = UInt(20.W); val noticeStatus = UInt(WalkStatus.width.W)
  }))
  lsq.io.lsqAllocIn <> lsqAllocIn; lsq.io.memAddressIn <> memAddressIn
  dCacheLoadReqOut <> lsq.io.dCacheLoadReqOut; lsq.io.dCacheLoadRespIn <> dCacheLoadRespIn
  storeForwardQueryOut <> lsq.io.storeForwardQueryOut; lsq.io.storeForwardDataIn <> storeForwardDataIn
  memResultOut <> lsq.io.memResultOut; lsq.io.storeCommitIn <> storeCommitIn
  committedStoreOut <> lsq.io.committedStoreOut; lsq.io.robStatusIn := robStatusIn
  lsq.io.headMemGrantIn <> headMemGrantIn
  uncachedLoadReqOut <> lsq.io.uncachedLoadReqOut; lsq.io.uncachedLoadRespIn <> uncachedLoadRespIn
  uncachedStoreReqOut <> lsq.io.uncachedStoreReqOut; lsq.io.uncachedStoreRespIn <> uncachedStoreRespIn
  lsq.io.storeBufferEmptyIn := storeBufferEmptyIn; lsq.io.recoveryEventIn := recoveryEventIn
  dtlb.io.dtlbReqIn <> lsq.io.dtlbReqOut
  lsq.io.dtlbStoreRespIn <> dtlb.io.dtlbStoreRespOut
  lsq.io.dtlbRefillIn <> dtlb.io.dtlbRefillOut
  dCacheTranslationOut <> dtlb.io.dCacheTranslationOut
  walkReq <> dtlb.io.dtlbWalkReqOut; dtlb.io.dtlbWalkRespIn <> walkResp
  dtlb.io.dtlbFlushIn <> flush; dtlb.io.translationContextIn := ctx
  mon.reqFire := lsq.io.dtlbReqOut.fire; mon.reqId := lsq.io.dtlbReqOut.bits.reqId
  mon.reqAccess := lsq.io.dtlbReqOut.bits.access; mon.reqVaddr := lsq.io.dtlbReqOut.bits.vaddr
  mon.stFire := dtlb.io.dtlbStoreRespOut.fire; mon.stReqId := dtlb.io.dtlbStoreRespOut.bits.reqId
  mon.stStatus := dtlb.io.dtlbStoreRespOut.bits.status
  mon.noticeFire := dtlb.io.dtlbRefillOut.fire; mon.noticeVpn := dtlb.io.dtlbRefillOut.bits.vpn
  mon.noticeStatus := dtlb.io.dtlbRefillOut.bits.status
}

/** Integration SpecTests of the load/store translation path on real RTL: LoadStoreQueue -> DataTlb.
  * The driver plays the RenameUnit, AGU, CommitUnit, RecoveryController, PageTableWalker (page-table
  * model with latency), a minimal VIPT DataCache (it pairs each DCacheLoadReq with the DTLB load
  * answer of the next cycle and answers the LSQ after a latency with the memory word), the
  * StoreBuffer (forwarding from its contents, draining later) and the uncached bus port. */
object LsqDtlbSpecTests {
  import InstructionTlbSpecTests.{Pte, PageTable, S, M, LEAF, WPF, WAF}
  import LoadStoreQueueSpecTests.{tagOf, maskOf, misaligned, merge, Alloc, Agu, Res, LoadPage, LoadAccess, StorePage, StoreAccess}
  val bp = BackendParams()
  val cp = CoreParams()
  val D  = bp.robDepth
  val BM = RecoveryKind.BranchMispredict

  case class DcPend(cyc: Int, lqIdx: Int, gen: Int, vaddr: Long)
  case class DcResp(due: Int, lqIdx: Int, gen: Int, status: UInt, paddr: Long)

  class Drv(val dut: LsqDtlbHarness) {
    var cyc = 0
    val pt  = new PageTable
    var satp = true; var asid = 1; var dataPriv = S
    var walkLatency = 4; var dcLatency = 2; var dcReady = true; var memResReady = true
    /** When set, a walk result is held until the cycle a DtlbReq for this VPN transfers (race tests). */
    var holdWalkFor: Option[Long] = None
    val mem = mutable.Map[Long, Long]().withDefault(w => ((w * 0x9e3779b1L) ^ 0x5a5a5a5aL) & 0xffffffffL)
    val sb  = mutable.Queue[(Long, Long, Int)](); private var sbCountdown = -1
    val dcPend = mutable.Queue[DcPend](); val dcq = ArrayBuffer[DcResp]()
    val walkQ = mutable.Queue[(Int, Long, Int, Pte, Int)]()   // (due, vpn, status, pte, asid)
    val ucLdq = ArrayBuffer[(Int, Int, Int, Long)](); val ucStq = ArrayBuffer[(Int, Int)]()
    var alloc: Option[Alloc] = None; var agu: Option[Agu] = None; var commit: Option[Int] = None
    var grant: Option[Int] = None; var event: Option[(UInt, Int)] = None
    var robHead: Option[Int] = Some(0)
    var allocFire, aguFire, commitFire, walkRespFire = false
    // Records.
    val results  = ArrayBuffer[Res]()
    val reqs     = ArrayBuffer[(Int, Long, Int, Long)]()   // (cyc, reqId, access, vaddr)
    val dcReqs   = ArrayBuffer[(Int, Int, Int, Long)]()    // (cyc, lqIdx, gen, vaddr)
    val pairs    = ArrayBuffer[(Int, Long, Int, Long, Boolean)]() // (cyc, reqId, status, paddr, cacheable) at the DataCache
    val stAns    = ArrayBuffer[(Int, Long, Int)]()          // (cyc, reqId, status)
    val notices  = ArrayBuffer[(Int, Long, Int)]()          // (cyc, vpn, status)
    val walks    = ArrayBuffer[(Int, Long)]()
    val ucLoads  = ArrayBuffer[(Int, Long)]()
    val bad      = ArrayBuffer[String]()
    private var near = 0
    private def b(x: Bool) = x.peek().litToBoolean
    private def l(x: UInt) = x.peek().litValue.toLong
    private def seqOf(t: (Boolean, Int)): Int = (near - D until near + D).find(s => s >= 0 && tagOf(s) == t).getOrElse(-1)
    private def pokeTag(t: udacore.backend.design.shared.RobTag, s: Int): Unit = { t.wrap.poke(tagOf(s)._1.B); t.idx.poke(tagOf(s)._2.U) }

    def cycle(): Unit = {
      val h = dut
      alloc.foreach(a => near = math.max(near, a.s))
      h.lsqAllocIn.valid.poke(alloc.nonEmpty.B)
      alloc.foreach { x => val a = h.lsqAllocIn.bits; pokeTag(a.robTag, x.s); a.isLoad.poke(x.isLoad.B); a.isStore.poke((!x.isLoad).B)
        a.size.poke(x.size.U); a.signed.poke(x.signed.B); a.prd.poke(x.prd.U) }
      h.memAddressIn.valid.poke(agu.nonEmpty.B)
      agu.foreach { x => val m = h.memAddressIn.bits; pokeTag(m.robTag, x.s); m.vaddr.poke(x.vaddr.U); m.storeData.poke(x.data.U)
        m.misaligned.poke(misaligned(x.size, x.vaddr).B) }
      val c = h.ctx
      c.satpMode.poke(satp.B); c.asid.poke(asid.U); c.rootPpn.poke(0.U); c.priv.poke(dataPriv.U); c.dataPriv.poke(dataPriv.U)
      c.sum.poke(false.B); c.mxr.poke(false.B)
      h.flush.valid.poke(false.B); h.flush.bits.vaddr.poke(0.U); h.flush.bits.vaddrValid.poke(false.B)
      h.flush.bits.asid.poke(0.U); h.flush.bits.asidValid.poke(false.B)
      h.walkReq.ready.poke(true.B)
      h.dCacheLoadReqOut.ready.poke(dcReady.B); h.dCacheTranslationOut.ready.poke(true.B)
      val dcDue = dcq.find(_.due <= cyc)
      h.dCacheLoadRespIn.valid.poke(dcDue.nonEmpty.B)
      dcDue.foreach { x => val r = h.dCacheLoadRespIn.bits
        r.lqIdx.poke(x.lqIdx.U); r.lqGen.poke(x.gen.U); r.status.poke(x.status); r.paddr.poke(x.paddr.U); r.data.poke(mem(x.paddr >>> 2).U) }
      val ucLdDue = ucLdq.find(_._1 <= cyc)
      h.uncachedLoadRespIn.valid.poke(ucLdDue.nonEmpty.B)
      ucLdDue.foreach { x => val r = h.uncachedLoadRespIn.bits; r.lqIdx.poke(x._2.U); r.lqGen.poke(x._3.U); r.data.poke(x._4.U)
        r.accessFault.poke(false.B) }
      val ucStDue = ucStq.find(_._1 <= cyc)
      h.uncachedStoreRespIn.valid.poke(ucStDue.nonEmpty.B)
      ucStDue.foreach { x => h.uncachedStoreRespIn.bits.sqIdx.poke(x._2.U); h.uncachedStoreRespIn.bits.accessFault.poke(false.B) }
      h.uncachedLoadReqOut.ready.poke(true.B); h.uncachedStoreReqOut.ready.poke(true.B)
      h.storeBufferEmptyIn.poke(sb.isEmpty.B)
      h.robStatusIn.empty.poke(robHead.isEmpty.B); pokeTag(h.robStatusIn.headTag, robHead.getOrElse(0))
      h.headMemGrantIn.valid.poke(grant.nonEmpty.B); grant.foreach(s => pokeTag(h.headMemGrantIn.bits.robTag, s))
      h.storeCommitIn.valid.poke(commit.nonEmpty.B); commit.foreach(s => pokeTag(h.storeCommitIn.bits.robTag, s))
      h.committedStoreOut.ready.poke(true.B)
      val e = h.recoveryEventIn
      e.valid.poke(event.nonEmpty.B); e.checkpointId.id.poke(0.U); e.target.poke(0.U); e.cause.poke(0.U)
      event.foreach { case (k, s) => e.kind.poke(k); pokeTag(e.robTag, s) }
      h.memResultOut.ready.poke(memResReady.B)
      // StoreBuffer forwarding: answered combinationally from its contents.
      val qv = b(h.storeForwardQueryOut.valid)
      h.storeForwardQueryOut.ready.poke(true.B); h.storeForwardDataIn.valid.poke(qv.B)
      if (qv) {
        val qa = l(h.storeForwardQueryOut.bits.paddr); val qm = l(h.storeForwardQueryOut.bits.mask).toInt
        var data = 0L; var hit = 0
        for ((a, d, m) <- sb if (a >>> 2) == (qa >>> 2)) { val ov = m & qm; data = merge(data, d, ov); hit |= ov }
        h.storeForwardDataIn.bits.data.poke(data.U); h.storeForwardDataIn.bits.hitMask.poke(hit.U)
        h.storeForwardDataIn.bits.partial.poke(false.B)
      }
      // DtlbReq of this cycle (combinational), then the PTW answer (held for a race test if asked).
      val reqFire = b(h.mon.reqFire)
      val reqVpn = l(h.mon.reqVaddr) >>> 12
      val wDue = walkQ.headOption.filter(w => w._1 <= cyc && holdWalkFor.forall(v => reqFire && reqVpn == v && w._2 == v))
      h.walkResp.valid.poke(wDue.nonEmpty.B)
      wDue.foreach { case (_, vpn, st, p, as) =>
        val r = h.walkResp.bits; val en = r.entry
        r.vpn.poke(vpn.U); r.status.poke(st.U); en.valid.poke(true.B); en.vpn.poke(vpn.U); en.superpage.poke(p.superpage.B)
        en.ppn.poke(p.ppn.U); en.asid.poke(as.U); en.global.poke(p.global.B); en.r.poke(p.r.B); en.w.poke(p.w.B); en.x.poke(p.x.B)
        en.u.poke(p.u.B); en.a.poke(p.a.B); en.d.poke(p.d.B)
        en.pma.cacheable.poke(false.B); en.pma.executable.poke(false.B); en.pma.readable.poke(false.B); en.pma.writable.poke(false.B)
      }

      // ---- Observe ----------------------------------------------------------------------------------------
      allocFire  = alloc.nonEmpty && b(h.lsqAllocIn.ready)
      aguFire    = agu.nonEmpty && b(h.memAddressIn.ready)
      commitFire = commit.nonEmpty && b(h.storeCommitIn.ready)
      walkRespFire = wDue.nonEmpty && b(h.walkResp.ready)
      val dcFire = b(h.dCacheLoadReqOut.valid) && dcReady
      if (reqFire) reqs += ((cyc, l(h.mon.reqId), l(h.mon.reqAccess).toInt, l(h.mon.reqVaddr)))
      val loadReq = reqFire && l(h.mon.reqAccess) == AccessType.Load.litValue.toLong
      if (loadReq != dcFire) bad += s"cycle $cyc: a load DtlbReq and its DCacheLoadReq did not fire together ($loadReq $dcFire)"
      // The DataCache pairs the DTLB load answer with the oldest pending lookup (one per cycle, in order).
      if (b(h.dCacheTranslationOut.valid)) {
        val t = h.dCacheTranslationOut.bits
        val (rid, st, pa, cach) = (l(t.reqId), l(t.status).toInt, l(t.paddr), b(t.cacheable))
        pairs += ((cyc, rid, st, pa, cach))
        if (dcPend.isEmpty) bad += s"cycle $cyc: a DTLB load answer with no pending D-cache lookup"
        else {
          val p = dcPend.dequeue()
          val want = (p.lqIdx.toLong << bp.lsqGenWidth) | p.gen
          if (rid != want || p.cyc != cyc - 1) bad += s"cycle $cyc: DTLB answer $rid does not pair with lookup $p"
          val ls = st match {
            case 0 => if (cach) LoadStatus.Data else LoadStatus.Uncacheable
            case 1 => LoadStatus.TlbMiss
            case 2 => LoadStatus.PageFault
            case _ => LoadStatus.AccessFault
          }
          dcq += DcResp(cyc + dcLatency, p.lqIdx, p.gen, ls, pa)
        }
      }
      if (dcFire) {
        val q = h.dCacheLoadReqOut.bits
        val p = DcPend(cyc, l(q.lqIdx).toInt, l(q.lqGen).toInt, l(q.vaddr)); dcPend.enqueue(p); dcReqs += ((cyc, p.lqIdx, p.gen, p.vaddr))
      }
      if (b(h.mon.stFire)) stAns += ((cyc, l(h.mon.stReqId), l(h.mon.stStatus).toInt))
      if (b(h.mon.noticeFire)) notices += ((cyc, l(h.mon.noticeVpn), l(h.mon.noticeStatus).toInt))
      if (b(h.walkReq.valid)) {
        val vpn = l(h.walkReq.bits.vpn); val as = l(h.walkReq.bits.context.asid).toInt
        walks += ((cyc, vpn))
        val (st, p) = pt.find(as, vpn) match { case Some(p) => (LEAF, p); case None => (WPF, Pte(0)) }
        walkQ.enqueue((cyc + walkLatency, vpn, st, p, as))
      }
      if (walkRespFire) walkQ.dequeue()
      if (dcDue.nonEmpty && b(h.dCacheLoadRespIn.ready)) dcq -= dcDue.get
      if (ucLdDue.nonEmpty && b(h.uncachedLoadRespIn.ready)) ucLdq -= ucLdDue.get
      if (ucStDue.nonEmpty && b(h.uncachedStoreRespIn.ready)) ucStq -= ucStDue.get
      val r = h.memResultOut
      if (b(r.valid) && memResReady) {
        val rs = seqOf((b(r.bits.robTag.wrap), l(r.bits.robTag.idx).toInt))
        results += Res(cyc, rs, l(r.bits.prd).toInt, b(r.bits.wen), l(r.bits.data), b(r.bits.headExecute),
          if (b(r.bits.exception.valid)) Some(l(r.bits.exception.cause).toInt) else None, l(r.bits.exception.tval))
      }
      if (b(h.committedStoreOut.valid))
        sb.enqueue((l(h.committedStoreOut.bits.paddr), l(h.committedStoreOut.bits.data), l(h.committedStoreOut.bits.mask).toInt))
      if (b(h.uncachedLoadReqOut.valid)) {
        val pa = l(h.uncachedLoadReqOut.bits.paddr); ucLoads += ((cyc, pa))
        ucLdq += ((cyc + 2, l(h.uncachedLoadReqOut.bits.lqIdx).toInt, l(h.uncachedLoadReqOut.bits.lqGen).toInt, mem(pa >>> 2)))
      }
      if (b(h.uncachedStoreReqOut.valid)) {
        val u = h.uncachedStoreReqOut.bits; val pa = l(u.paddr)
        mem(pa >>> 2) = merge(mem(pa >>> 2), l(u.data), l(u.mask).toInt); ucStq += ((cyc + 2, l(u.sqIdx).toInt))
      }
      dut.clock.step(); cyc += 1
      if (sb.nonEmpty) {
        if (sbCountdown < 0) sbCountdown = 2
        if (sbCountdown == 0) { val (pa, d, mk) = sb.dequeue(); mem(pa >>> 2) = merge(mem(pa >>> 2), d, mk); sbCountdown = -1 }
        else sbCountdown -= 1
      }
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def allocate(x: Alloc): Unit = { alloc = Some(x); var k = 0; cycle(); while (!allocFire && k < 20) { cycle(); k += 1 }; alloc = None }
    def address(x: Agu): Unit = { agu = Some(x); var k = 0; cycle(); while (!aguFire && k < 20) { cycle(); k += 1 }; agu = None }
    def storeCommit(s: Int): Boolean = { commit = Some(s); var k = 0; cycle(); while (!commitFire && k < 20) { cycle(); k += 1 }; commit = None; commitFire }
    def headGrant(s: Int): Unit = { grant = Some(s); cycle(); grant = None }
    def kill(kind: UInt, s: Int): Unit = { event = Some((kind, s)); cycle(); event = None }
    def done(s: Int): Option[Res] = results.find(r => r.s == s && !r.hx)
    def resultsFor(s: Int): Seq[Res] = results.filter(_.s == s).toSeq
    def until(n: Int)(p: => Boolean): Boolean = { var k = 0; while (!p && k < n) { cycle(); k += 1 }; p }
    def load(s: Int, va: Long, prd: Int = 40): Unit = { allocate(Alloc(s, isLoad = true, prd = prd)); address(Agu(s, va)) }
    def store(s: Int, va: Long, data: Long): Unit = { allocate(Alloc(s, isLoad = false)); address(Agu(s, va, data)) }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new LsqDtlbHarness(bp, cp)) { dut => val d = new Drv(dut); d.cycle(); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  def noBad(d: Drv): TCheck = chk(d.bad.isEmpty, "load DtlbReq / DCacheLoadReq fire together and every DTLB load answer pairs with its lookup",
    d.bad.take(3).mkString("; "))

  /** Virtual pages used below (ASID 1, S mode). */
  def page(k: Int): Long = 0x00400000L + (k.toLong << 12)
  def ppnOf(k: Int): Long = 0x00100L + k
  def mapRw(d: Drv, k: Int): Unit = d.pt.map(1, page(k) >> 12, Pte(ppnOf(k), w = true, d = true))
  def paOf(k: Int, va: Long): Long = (ppnOf(k) << 12) | (va & 0xfff)

  // ---- Parameter mirror -----------------------------------------------------------------------------------
  val params = new SpecTest("lsqdtlb.params", Seq("funcDtlbTranslate")) {
    def run(): Seq[TCheck] = {
      val v = CoreLsqView()
      val b = BackendParams()
      Seq(
        chk(v.loadQueueDepth == b.tuning.loadQueueDepth && v.storeQueueDepth == b.tuning.storeQueueDepth &&
          v.generationWidth == b.lsqGenWidth && v.reqIdWidth == b.lsqReqIdWidth && v.reqIdWidth == 6,
          "the CoreLsqView defaults equal the BackendParams LSQ geometry (LQ 8, SQ 8, generation 2, reqId 6)", s"$v"),
        chk(scala.util.Try(new LsqDtlbHarness(b, CoreParams(CoreContractParams(lsq = CoreLsqView(loadQueueDepth = 16))))).isFailure,
          "a mismatched CoreLsqView is rejected at composition", ""))
    }
  }

  // ---- 1, 2, 3: atomic fork; a miss parks only its own load; independent loads proceed -------------------
  val parkOne = new SpecTest("lsqdtlb.parkOne", Seq("funcDtlbMissNonBlocking", "propDtlbMissLocal", "funcDtlbTranslate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      (0 until 3).foreach(mapRw(d, _))
      d.load(0, page(0)); d.until(20)(d.done(0).nonEmpty)              // page 0 resident
      d.walkLatency = 25
      val w0 = d.walks.size; val c0 = d.cyc
      d.load(1, page(1) + 8)                                          // misses: walk
      d.load(2, page(0) + 4); d.load(3, page(2) + 12)                 // hit; miss during the walk (no walk)
      d.until(20)(d.done(2).nonEmpty)
      val during = d.done(2).map(_.cyc); val one = d.done(1)
      d.until(80)(d.done(1).nonEmpty && d.done(3).nonEmpty)
      Seq(
        noBad(d),
        chk(during.nonEmpty && one.isEmpty, "an independent load completes while the missing load waits for its walk", s"${d.results}"),
        chk(d.walks.filter(_._1 >= c0).map(_._2).distinct.headOption.contains(page(1) >> 12) && d.walks.size > w0,
          "the first miss walks; the second miss does not start a walk while one is active", s"${d.walks}"),
        chk(d.done(1).exists(_.data == d.mem(paOf(1, page(1) + 8) >>> 2)) && d.done(3).exists(_.data == d.mem(paOf(2, page(2) + 12) >>> 2)),
          "both missing loads retry after refill notices and complete with their translated data", s"${d.results}"))
    }
  }

  // ---- 4: store translation priority ------------------------------------------------------------------
  val storeFirst = new SpecTest("lsqdtlb.storeFirst", Seq("funcDtlbTranslate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      mapRw(d, 0)
      d.load(0, page(0)); d.until(20)(d.done(0).nonEmpty)
      d.dcReady = false                                    // hold the load at its D-cache port
      d.allocate(Alloc(1, isLoad = true)); d.allocate(Alloc(2, isLoad = false))
      d.agu = Some(Agu(1, page(0) + 4)); d.cycle(); d.agu = Some(Agu(2, page(0) + 8, 9)); d.cycle(); d.agu = None
      d.run(2); d.dcReady = true
      d.until(20)(d.done(1).nonEmpty && d.done(2).nonEmpty)
      val order = d.reqs.filter(_._1 > 0).map(r => (r._3, r._4)).filter(_._2 != page(0))
      Seq(
        noBad(d),
        chk(order.headOption.contains((AccessType.Store.litValue.toInt, page(0) + 8)),
          "a pending store translation is sent before a ready load", s"$order"),
        chk(d.done(1).nonEmpty && d.done(2).exists(_.exc.isEmpty), "both complete", s"${d.results}"))
    }
  }

  // ---- 5: store Miss -> notice -> retry ----------------------------------------------------------------
  val storeMiss = new SpecTest("lsqdtlb.storeMiss", Seq("funcDtlbMissNonBlocking", "funcDtlbRefill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      mapRw(d, 4)
      d.store(0, page(4) + 0x10, 0x55); d.until(40)(d.done(0).nonEmpty)
      val tries = d.reqs.filter(_._4 == page(4) + 0x10).map(_._1)
      val ans = d.stAns.map(_._3)
      d.storeCommit(0); d.run(8)
      Seq(
        chk(ans == Seq(TranslationStatus.Miss.litValue.toInt, 0) && tries.size == 2,
          "the store is answered Miss, then re-translates after the refill notice and hits", s"$ans $tries ${d.notices}"),
        chk(d.notices.size == 1 && d.notices.head._1 < tries(1), "one refill notice precedes the retry", s"${d.notices}"),
        chk(d.mem(paOf(4, page(4) + 0x10) >>> 2) == 0x55L, "the committed store reaches its translated paddr", ""))
    }
  }

  // ---- 6 / 17d: generation drop of a killed, reallocated entry ----------------------------------------------
  val genDrop = new SpecTest("lsqdtlb.genDrop", Seq("funcDtlbTranslate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      mapRw(d, 0); mapRw(d, 1)
      d.mem(paOf(0, page(0) + 0x60) >>> 2) = 0x11111111L; d.mem(paOf(1, page(1) + 0x64) >>> 2) = 0x22222222L
      d.load(9, page(0)); d.until(20)(d.done(9).nonEmpty); d.load(10, page(1)); d.until(30)(d.done(10).nonEmpty)
      d.robHead = Some(11)
      d.dcLatency = 10
      d.allocate(Alloc(12, isLoad = true, prd = 41)); d.address(Agu(12, page(0) + 0x60))
      d.until(5)(d.dcReqs.exists(_._4 == page(0) + 0x60))
      d.kill(BM, 11)
      d.dcLatency = 2
      d.allocate(Alloc(12, isLoad = true, prd = 42)); d.address(Agu(12, page(1) + 0x64))
      d.until(40)(d.done(12).nonEmpty); d.run(12)
      // A store killed in the cycle its DTLB answer arrives: the answer is dropped, the slot reused.
      d.robHead = Some(13)
      d.allocate(Alloc(14, isLoad = false)); d.agu = Some(Agu(14, page(0) + 0x70, 1)); d.cycle(); d.agu = None
      d.until(5)(d.reqs.exists(_._4 == page(0) + 0x70)); d.kill(BM, 13)
      d.allocate(Alloc(14, isLoad = false)); d.address(Agu(14, page(1) + 0x74, 2)); d.until(20)(d.done(14).nonEmpty)
      val st = d.resultsFor(14)
      Seq(
        noBad(d),
        chk(d.resultsFor(12).map(r => (r.prd, r.data)) == Seq((42, 0x22222222L)),
          "the reused load slot completes with its own data; the killed load's late answer is dropped by generation", s"${d.results}"),
        chk(st.size == 1 && st.head.exc.isEmpty, "the killed store's DTLB answer does not complete the reallocated store", s"$st"))
    }
  }

  // ---- 7 / 17c: one refill wakes several entries --------------------------------------------------------------
  val wakeMany = new SpecTest("lsqdtlb.wakeMany", Seq("funcDtlbRefill", "funcDtlbMissNonBlocking")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      mapRw(d, 5); d.walkLatency = 12
      d.load(0, page(5) + 4); d.load(1, page(5) + 8); d.store(2, page(5) + 12, 3)
      d.until(80)(Seq(0, 1, 2).forall(d.done(_).nonEmpty))
      Seq(
        noBad(d),
        chk(d.walks.map(_._2).distinct == Seq(page(5) >> 12) && d.notices.size == 1,
          "two loads and a store missing on one VPN cause one walk and one notice", s"${d.walks} ${d.notices}"),
        chk(Seq(0, 1).forall(s => d.done(s).exists(_.exc.isEmpty)) && d.done(2).exists(_.exc.isEmpty),
          "the one notice wakes every waiting entry; all complete", s"${d.results}"))
    }
  }

  // ---- 8: device PMA -> uncached path --------------------------------------------------------------------
  val device = new SpecTest("lsqdtlb.device", Seq("funcDtlbTranslate", "funcPmaCheck")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.pt.map(1, page(7) >> 12, Pte(0xf0000L, w = true, d = true))     // maps the device window
      d.mem(0xf0000010L >>> 2) = 0xcafef00dL
      d.robHead = Some(0)
      d.load(0, page(7) + 0x10); d.until(40)(d.resultsFor(0).nonEmpty)
      val hx = d.resultsFor(0)
      d.headGrant(0); d.until(20)(d.done(0).nonEmpty)
      Seq(
        noBad(d),
        chk(d.pairs.exists(p => p._3 == 0 && p._4 == 0xf0000010L && !p._5), "the DTLB answers Hit with cacheable = false (PMA)", s"${d.pairs}"),
        chk(hx.headOption.exists(_.hx) && d.ucLoads.map(_._2) == Seq(0xf0000010L) && d.done(0).exists(_.data == 0xcafef00dL),
          "the LSQ takes the uncached path at the head: one bus load with the translated paddr", s"$hx ${d.ucLoads} ${d.results}"))
    }
  }

  // ---- 9: precise exception typing ---------------------------------------------------------------------
  val faults = new SpecTest("lsqdtlb.faults", Seq("funcDtlbTranslate", "funcDtlbMissNonBlocking")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.pt.map(1, page(8) >> 12, Pte(ppnOf(8), w = false))               // read-only: store page-faults
      d.pt.map(1, page(9) >> 12, Pte(0x200000L, w = true, d = true))    // pa above 4 GiB: unmapped PMA
      d.load(0, page(6) + 4); d.store(1, page(6) + 8, 1)                 // no PTE: walk PageFault
      d.store(2, page(8) + 4, 1)
      d.load(3, page(9) + 4); d.store(4, page(9) + 8, 1)
      d.until(120)((0 to 4).forall(d.done(_).nonEmpty))
      def is(s: Int, c: Int, va: Long) = d.done(s).exists(r => r.exc.contains(c) && r.tval == va && !r.wen)
      Seq(
        noBad(d),
        chk(is(0, LoadPage, page(6) + 4) && is(1, StorePage, page(6) + 8),
          "a walk PageFault faults the load with LoadPageFault and the store with StorePageFault (tval = vaddr)", s"${d.results}"),
        chk(is(2, StorePage, page(8) + 4), "a store to a read-only page: StorePageFault", s"${d.done(2)}"),
        chk(is(3, LoadAccess, page(9) + 4) && is(4, StoreAccess, page(9) + 8),
          "a translated paddr outside the PMA map: LoadAccessFault / StoreAccessFault", s"${d.results}"),
        chk(d.walks.count(_._2 == (page(6) >> 12)) == 1, "the fault record answers the second access without a second walk",
          s"${d.walks}"))
    }
  }

  // ---- 17a/b: refill in the same cycle as a load / store Miss -----------------------------------------------
  def race(t: SpecTest, isLoad: Boolean, dcLat: Int): Seq[TCheck] = withDrv(t) { d =>
    mapRw(d, 3); mapRw(d, 2)
    // A first miss on page 2 starts the walk; its result is held until the DtlbReq for page 2 by the
    // second entry transfers, so that request misses in the cycle the Leaf installs.
    d.dcLatency = dcLat; d.walkLatency = 2
    d.load(0, page(2) + 4); d.until(10)(d.walks.nonEmpty)
    d.holdWalkFor = Some(page(2) >> 12)
    if (isLoad) d.load(1, page(2) + 8) else d.store(1, page(2) + 8, 7)
    d.until(20)(d.walkRespFire); d.holdWalkFor = None
    val raceCyc = d.reqs.lastOption.map(_._1)
    d.until(60)(d.done(0).nonEmpty && d.done(1).nonEmpty)
    val n = d.notices.headOption.map(_._1)
    Seq(
      noBad(d),
      chk(raceCyc.nonEmpty && n.contains(raceCyc.get + 1), "the refill notice arrives in the cycle after the racing request (its Miss answer cycle)",
        s"$raceCyc ${d.notices} ${d.reqs}"),
      chk(d.done(1).exists(_.exc.isEmpty) && d.done(0).exists(_.exc.isEmpty), "the racing entry is not stranded: it retries and completes",
        s"${d.results}"),
      chk(d.walks.size == 1, "no second walk", s"${d.walks}"))
  }
  val raceLoad    = new SpecTest("lsqdtlb.raceLoad", Seq("funcDtlbRefill")) { def run(): Seq[TCheck] = race(this, isLoad = true, dcLat = 0) }
  val raceLoadLate = new SpecTest("lsqdtlb.raceLoadLate", Seq("funcDtlbRefill")) { def run(): Seq[TCheck] = race(this, isLoad = true, dcLat = 3) }
  val raceStore   = new SpecTest("lsqdtlb.raceStore", Seq("funcDtlbRefill")) { def run(): Seq[TCheck] = race(this, isLoad = false, dcLat = 2) }

  val all: Seq[SpecTest] = Seq(params, parkOne, storeFirst, storeMiss, genDrop, wakeMany, device, faults, raceLoad, raceLoadLate, raceStore)
}
