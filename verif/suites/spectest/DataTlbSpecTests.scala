package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.core.design.modules.DataTlb
import udacore.core.design.shared._

/** L1 SpecTests for the ADR-019 DataTlb (DataTlbSpecs, Sv32Specs, ADR-019 D-19.5/D-19.6).
  *
  * The driver plays the LoadStoreQueue (TranslateReq stream with LSQ-encoded reqIds, the store answer
  * consumer and the refill-notice consumer, retrying a missed request after a notice), the DataCache
  * (load answer consumer), the CsrController (TranslationContext) and the PageTableWalker (page-table
  * model with latency and fault / Retry / wrong-VPN injection; entry.pma is filled all-false so the
  * DTLB must use the static PMA map).
  */
object DataTlbSpecTests {
  import InstructionTlbSpecTests.{Pte, PageTable, compose, ram, U, S, M, HIT, MISS, PF, AF, LEAF, WPF, WAF, RETRY}
  val cp = CoreParams()
  val L = cp.lsq
  /** {isStore, index padded to indexWidth, generation}: the canonical LSQ reqId encoding. */
  def rid(store: Boolean, idx: Int, gen: Int = 0): Int =
    ((if (store) 1 else 0) << (L.indexWidth + L.generationWidth)) | (idx << L.generationWidth) | gen

  case class DCtx(satp: Boolean = false, asid: Int = 0, priv: Int = M, dataPriv: Int = M, sum: Boolean = false,
      mxr: Boolean = false, root: Long = 0)
  case class Tr(reqId: Int, status: Int, paddr: Long, cacheable: Boolean)
  case class WReq(vpn: Long, ctx: DCtx)
  case class WResp(vpn: Long, status: Int, pte: Pte, asid: Int)
  case class Notice(vpn: Long, status: Int)

  /** Reference result of a data access (TranslationMode with dataPriv, Sv32PermissionCheck, PmaCheck). */
  def expect(pt: PageTable, pma: PmaMap, c: DCtx, va: Long, store: Boolean, id: Int): Tr = {
    def pmaRes(pa: Long): Tr = pma.find(BigInt(pa)) match {
      case Some(r) if (if (store) r.writable else r.readable) => Tr(id, HIT, pa, r.cacheable)
      case _ => Tr(id, AF, 0, cacheable = false)
    }
    if (!c.satp || c.dataPriv == M) pmaRes(va)
    else pt.find(c.asid, va >> 12) match {
      case None => Tr(id, PF, 0, cacheable = false)
      case Some(p) =>
        val acc = if (store) p.w && p.d else p.r || (c.mxr && p.x)
        val priv = if (c.dataPriv == U) p.u else !p.u || c.sum
        if (!(acc && p.a && priv)) Tr(id, PF, 0, cacheable = false) else pmaRes(compose(p, va))
    }
  }

  class Drv(val dut: DataTlb, val pma: PmaMap) {
    val io = dut.io
    val pt = new PageTable
    var cyc = 0
    var ctx = DCtx()
    val reqQ = Queue[(Int, Long, Boolean)]()        // (reqId, va, store)
    var access: Option[Int] = None                    // override the access code (illegal-input tests)
    var flush = false
    var wReady: Int => Boolean = _ => true
    var loadReady: Int => Boolean = _ => true
    var storeReady: Int => Boolean = _ => true
    var refillReady: Int => Boolean = _ => true
    var walkLatency = 3
    var walker: WReq => WResp = w => pt.find(w.ctx.asid, w.vpn) match {
      case Some(p) => WResp(w.vpn, LEAF, p, w.ctx.asid)
      case None    => WResp(w.vpn, WPF, Pte(0), w.ctx.asid)
    }
    val respQ = Queue[(Int, WResp)]()
    val loadOuts, storeOuts = ArrayBuffer[(Int, Tr)]()
    val loadOffered, storeOffered = ArrayBuffer[(Int, Tr)]()
    val notices = ArrayBuffer[(Int, Notice)](); val noticeOffered = ArrayBuffer[(Int, Notice)]()
    val walks = ArrayBuffer[(Int, WReq)](); val walkOffers = ArrayBuffer[(Int, WReq)]()
    val reqFires = ArrayBuffer[Int](); val readyLow = ArrayBuffer[Int](); val respFires = ArrayBuffer[Int]()
    var reqFire, flushFire, respFire = false

    def pokeCtx(): Unit = {
      val t = io.translationContextIn
      t.satpMode.poke(ctx.satp.B); t.asid.poke(ctx.asid.U); t.rootPpn.poke(ctx.root.U); t.priv.poke(ctx.priv.U)
      t.dataPriv.poke(ctx.dataPriv.U); t.sum.poke(ctx.sum.B); t.mxr.poke(ctx.mxr.B)
    }
    def peekTr(o: chisel3.util.DecoupledIO[Translation]): Tr =
      Tr(o.bits.reqId.peek().litValue.toInt, o.bits.status.peek().litValue.toInt, o.bits.paddr.peek().litValue.toLong,
        o.bits.cacheable.peek().litToBoolean)
    def pokeResp(r: WResp): Unit = {
      val b = io.dtlbWalkRespIn.bits; val e = b.entry; val p = r.pte
      b.vpn.poke(r.vpn.U); b.status.poke(r.status.U)
      e.valid.poke(true.B); e.vpn.poke(r.vpn.U); e.superpage.poke(p.superpage.B); e.ppn.poke(p.ppn.U); e.asid.poke(r.asid.U)
      e.global.poke(p.global.B); e.r.poke(p.r.B); e.w.poke(p.w.B); e.x.poke(p.x.B); e.u.poke(p.u.B); e.a.poke(p.a.B); e.d.poke(p.d.B)
      e.pma.cacheable.poke(false.B); e.pma.executable.poke(false.B); e.pma.readable.poke(false.B); e.pma.writable.poke(false.B)
    }
    def cycle(): Unit = {
      val rq = io.dtlbReqIn
      rq.valid.poke(reqQ.nonEmpty.B)
      reqQ.headOption.foreach { case (id, va, st) =>
        rq.bits.reqId.poke(id.U); rq.bits.vaddr.poke(va.U)
        rq.bits.access.poke(access.getOrElse(if (st) 2 else 1).U)
      }
      pokeCtx()
      io.dtlbFlushIn.valid.poke(flush.B); io.dtlbFlushIn.bits.vaddr.poke(0.U); io.dtlbFlushIn.bits.vaddrValid.poke(false.B)
      io.dtlbFlushIn.bits.asid.poke(0.U); io.dtlbFlushIn.bits.asidValid.poke(false.B)
      val wr = wReady(cyc); io.dtlbWalkReqOut.ready.poke(wr.B)
      val rv = respQ.nonEmpty && respQ.head._1 <= cyc
      io.dtlbWalkRespIn.valid.poke(rv.B); respQ.headOption.foreach(x => pokeResp(x._2))
      val lr = loadReady(cyc); val sr = storeReady(cyc); val fr = refillReady(cyc)
      io.dCacheTranslationOut.ready.poke(lr.B); io.dtlbStoreRespOut.ready.poke(sr.B); io.dtlbRefillOut.ready.poke(fr.B)

      val rdy = rq.ready.peek().litToBoolean
      reqFire  = reqQ.nonEmpty && rdy
      if (reqQ.nonEmpty && !rdy) readyLow += cyc
      flushFire = flush && io.dtlbFlushIn.ready.peek().litToBoolean
      respFire  = rv && io.dtlbWalkRespIn.ready.peek().litToBoolean
      if (io.dtlbWalkReqOut.valid.peek().litToBoolean) {
        val b = io.dtlbWalkReqOut.bits; val c = b.context
        val w = WReq(b.vpn.peek().litValue.toLong, DCtx(c.satpMode.peek().litToBoolean, c.asid.peek().litValue.toInt,
          c.priv.peek().litValue.toInt, c.dataPriv.peek().litValue.toInt, c.sum.peek().litToBoolean, c.mxr.peek().litToBoolean,
          c.rootPpn.peek().litValue.toLong))
        walkOffers += ((cyc, w))
        if (wr) { walks += ((cyc, w)); respQ.enqueue((cyc + walkLatency, walker(w))) }
      }
      if (io.dCacheTranslationOut.valid.peek().litToBoolean) {
        val t = peekTr(io.dCacheTranslationOut); loadOffered += ((cyc, t)); if (lr) loadOuts += ((cyc, t))
      }
      if (io.dtlbStoreRespOut.valid.peek().litToBoolean) {
        val t = peekTr(io.dtlbStoreRespOut); storeOffered += ((cyc, t)); if (sr) storeOuts += ((cyc, t))
      }
      if (io.dtlbRefillOut.valid.peek().litToBoolean) {
        val n = Notice(io.dtlbRefillOut.bits.vpn.peek().litValue.toLong, io.dtlbRefillOut.bits.status.peek().litValue.toInt)
        noticeOffered += ((cyc, n)); if (fr) notices += ((cyc, n))
      }
      if (reqFire) { reqQ.dequeue(); reqFires += cyc }
      if (respFire) { respQ.dequeue(); respFires += cyc }
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def outs(store: Boolean) = if (store) storeOuts else loadOuts
    /** One request; returns its answer (Miss included). */
    def ask(va: Long, store: Boolean, id: Int): Tr = {
      val n0 = outs(store).size; reqQ.enqueue((id, va, store))
      var k = 0; while (outs(store).size == n0 && k < 100) { cycle(); k += 1 }
      outs(store).lift(n0).map(_._2).getOrElse(Tr(-1, -1, 0, false))
    }
    /** LSQ behavior: on Miss wait for the next refill notice and retry, until a final answer. */
    def resolve(va: Long, store: Boolean, id: Int): Tr = {
      var n0 = notices.size; var t = ask(va, store, id); var k = 0
      while (t.status == MISS && k < 8) {
        var w = 0; while (notices.size == n0 && w < 200) { cycle(); w += 1 }   // a notice since the request: retry now
        n0 = notices.size; t = ask(va, store, id); k += 1
      }
      t
    }
    def exp(va: Long, store: Boolean, id: Int): Tr = expect(pt, pma, ctx, va, store, id)
    def doFlush(): Unit = { flush = true; var k = 0; cycle(); while (!flushFire && k < 20) { cycle(); k += 1 }; flush = false }
  }

  def withDrv(t: SpecTest, p: CoreParams = cp)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new DataTlb(p)) { dut => val d = new Drv(dut, p.pma); d.cycle(); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  val sS = DCtx(satp = true, asid = 1, priv = S, dataPriv = S)
  val sU = sS.copy(priv = U, dataPriv = U)
  val VA = 0x12345678L; val PPN = 0x20019L
  def pg(k: Int): Long = 0x01000000L + (k.toLong << 12)

  // ---- Bare / PMA ---------------------------------------------------------------------------------------
  val bare = new SpecTest("dtlb.bare", Seq("funcDtlbTranslate", "funcTranslationMode", "funcPmaCheck")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = DCtx(satp = true, priv = S, dataPriv = M)   // dataPriv M: Bare although satp is on and priv is S
      val a = d.ask(0x80001234L, store = false, rid(false, 1)); val b = d.ask(0x80001238L, store = true, rid(true, 2))
      val c = d.ask(0xf0000010L, store = false, rid(false, 3)); val e = d.ask(0xf0000014L, store = true, rid(true, 4))
      Seq(
        chk(a == Tr(rid(false, 1), HIT, 0x80001234L, true) && b == Tr(rid(true, 2), HIT, 0x80001238L, true),
          "dataPriv M is Bare even with satp on and priv S: RAM load / store hit cacheable", s"$a $b"),
        chk(c == Tr(rid(false, 3), HIT, 0xf0000010L, false) && e == Tr(rid(true, 4), HIT, 0xf0000014L, false),
          "the device window answers Hit with cacheable = false for load and store", s"$c $e"),
        chk(d.walks.isEmpty, "Bare never walks", s"${d.walks}"))
    }
  }

  val barePma = new SpecTest("dtlb.barePma", Seq("funcDtlbTranslate", "funcPmaCheck")) {
    def run(): Seq[TCheck] = {
      val m = PmaMap(Seq(ram(0, 0x10000000L),
        PmaRegion(0x20000000L, 0x1000L, cacheable = false, executable = true, readable = false, writable = false),
        PmaRegion(0x30000000L, 0x1000L, cacheable = true, executable = false, readable = true, writable = false)))
      withDrv(this, CoreParams(CoreContractParams(pma = m))) { d =>
        val a = d.ask(0x20000010L, store = false, rid(false, 0)); val b = d.ask(0x30000010L, store = true, rid(true, 0))
        val c = d.ask(0x18000000L, store = false, rid(false, 1)); val e = d.ask(0x18000000L, store = true, rid(true, 1))
        val f = d.ask(0x30000010L, store = false, rid(false, 2))
        Seq(
          chk(a.status == AF && b.status == AF, "a load from a non-readable region / a store to a non-writable region: AccessFault", s"$a $b"),
          chk(c == Tr(rid(false, 1), AF, 0, false) && e == Tr(rid(true, 1), AF, 0, false), "unmapped load / store: AccessFault", s"$c $e"),
          chk(f == Tr(rid(false, 2), HIT, 0x30000010L, true), "the read-only region still serves loads", s"$f"))
      }
    }
  }

  // ---- Translated permissions ---------------------------------------------------------------------------
  val perms = new SpecTest("dtlb.perms", Seq("funcDtlbTranslate", "funcSv32PermissionCheck")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.pt.map(1, pg(0) >> 12, Pte(PPN, r = true, w = true, d = true))             // RW
      d.pt.map(1, pg(1) >> 12, Pte(PPN, r = false, x = true))                       // X only
      d.pt.map(1, pg(2) >> 12, Pte(PPN, r = true, w = true, d = false))             // D = 0
      d.pt.map(1, pg(3) >> 12, Pte(PPN, r = true, w = true, d = true, a = false))   // A = 0
      d.pt.map(1, pg(4) >> 12, Pte(PPN, r = true, w = false))                        // R only
      d.pt.map(1, pg(6) >> 12, Pte(0x200000L, r = true, w = true, d = true, a = false)) // A = 0 and pa above 4 GiB
      d.ctx = sS
      def t(k: Int, st: Boolean, id: Int) = d.resolve(pg(k), st, rid(st, id))
      val rw = (t(0, false, 0), t(0, true, 0)); val xo0 = t(1, false, 1)
      d.ctx = sS.copy(mxr = true); val xo1 = t(1, false, 2); val xoS = t(1, true, 2)
      d.ctx = sS
      val d0 = (t(2, false, 3), t(2, true, 3)); val a0 = (t(3, false, 4), t(3, true, 4)); val ro = (t(4, false, 5), t(4, true, 5))
      val both = (t(6, false, 6), t(6, true, 6))
      Seq(
        chk(rw._1.status == HIT && rw._2.status == HIT, "R/W/D page: load and store hit", s"$rw"),
        chk(xo0.status == PF && xo1.status == HIT, "an X-only page loads only with MXR", s"$xo0 $xo1"),
        chk(xoS.status == PF, "MXR has no effect on a store", s"$xoS"),
        chk(d0._1.status == HIT && d0._2.status == PF, "D = 0: load hits, store page-faults (Svade)", s"$d0"),
        chk(a0._1.status == PF && a0._2.status == PF, "A = 0: load and store page-fault (Svade)", s"$a0"),
        chk(ro._1.status == HIT && ro._2.status == PF, "W = 0: the store page-faults", s"$ro"),
        chk(both._1.status == PF && both._2.status == PF, "a permission fault outranks a PMA fault on the same access", s"$both"))
    }
  }

  val privilege = new SpecTest("dtlb.privilege", Seq("funcSv32PermissionCheck", "funcTranslationMode")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.pt.map(1, pg(0) >> 12, Pte(PPN, u = true, w = true, d = true)); d.pt.map(1, pg(1) >> 12, Pte(PPN, u = false, w = true, d = true))
      d.pt.map(1, pg(2) >> 12, Pte(PPN, u = false))
      def t(k: Int, st: Boolean, id: Int) = d.resolve(pg(k), st, rid(st, id))
      d.ctx = sU; val uu = (t(0, false, 0), t(0, true, 0)); val us = (t(1, false, 1), t(1, true, 1))
      d.ctx = sS; val su0 = (t(0, false, 2), t(0, true, 2)); val ss = (t(1, false, 3), t(1, true, 3))
      d.ctx = sS.copy(sum = true); val su1 = (t(0, false, 4), t(0, true, 4))
      d.ctx = sS.copy(priv = M); val dp = t(2, false, 5)            // priv M, dataPriv S: translated (fresh page walks)
      d.ctx = sS.copy(priv = U); val pU = (t(1, false, 6), t(0, false, 6))   // priv U, dataPriv S: S rules
      d.ctx = sU.copy(priv = S); val pS = (t(1, false, 7), t(0, false, 7))   // priv S, dataPriv U: U rules
      Seq(
        chk(uu._1.status == HIT && uu._2.status == HIT, "U mode loads and stores a U page", s"$uu"),
        chk(us._1.status == PF && us._2.status == PF, "U mode may not touch a supervisor page", s"$us"),
        chk(su0._1.status == PF && su0._2.status == PF && su1._1.status == HIT && su1._2.status == HIT,
          "S mode touches a U page only with SUM", s"$su0 $su1"),
        chk(ss._1.status == HIT && ss._2.status == HIT, "S mode loads and stores a supervisor page", s"$ss"),
        chk(dp.status == HIT && d.walks.exists(_._2.ctx.priv == M), "translation follows dataPriv, not priv", s"$dp ${d.walks}"),
        chk(pU._1.status == HIT && pU._2.status == PF && pS._1.status == PF && pS._2.status == HIT,
          "the U/S permission rule follows dataPriv, not priv", s"$pU $pS"))
    }
  }

  val match_ = new SpecTest("dtlb.match", Seq("funcTlbMatch", "funcSv32Decompose", "funcDtlbTranslate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS
      d.pt.map(1, 0x00c35678L >> 12, Pte((0x12L << 10) | 0x2abL, superpage = true, w = true, d = true))
      d.pt.map(1, VA >> 12, Pte(PPN, w = true, d = true)); d.pt.map(2, VA >> 12, Pte(0x30011L, w = true, d = true))
      d.pt.map(1, pg(9) >> 12, Pte(0x30022L, global = true))
      val sp = d.resolve(0x00c35678L, false, rid(false, 0)); val sp2 = d.ask(0x00c01004L, true, rid(true, 0))
      val a = d.resolve(VA, false, rid(false, 1)); d.resolve(pg(9), false, rid(false, 2)); val w0 = d.walks.size
      d.ctx = sS.copy(asid = 2)
      val b = d.resolve(VA, false, rid(false, 3)); val g = d.ask(pg(9) + 8, false, rid(false, 4))
      Seq(
        chk(sp.paddr == 0x04835678L && sp2 == Tr(rid(true, 0), HIT, 0x04801004L, true), "superpage composition {PPN[1], va[21:0]}",
          s"$sp $sp2"),
        chk(a.paddr == 0x20019678L && b.paddr == 0x30011678L && d.walks.size == w0 + 1, "an ASID-1 entry misses in ASID 2",
          s"$a $b ${d.walks}"),
        chk(g == Tr(rid(false, 4), HIT, 0x30022008L, true), "a global entry hits in every ASID", s"$g"))
    }
  }

  // ---- Non-blocking miss (propDtlbMissLocal) --------------------------------------------------------------
  val nonBlocking = new SpecTest("dtlb.nonBlocking", Seq("funcDtlbMissNonBlocking", "propDtlbMissLocal")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS
      val A = pg(0); val B = pg(1); val C = pg(2)
      Seq(A, B, C).zipWithIndex.foreach { case (v, k) => d.pt.map(1, v >> 12, Pte(PPN + k, w = true, d = true)) }
      d.resolve(C, false, rid(false, 7))                       // C is resident
      val w0 = d.walks.size
      d.walkLatency = 30
      val c0 = d.cyc
      val a = d.ask(A, false, rid(false, 1))                   // miss: Miss now, walk A
      val b = d.ask(B, true, rid(true, 2))                     // miss while A walks: Miss now, no walk
      val c = d.ask(C + 4, false, rid(false, 3))               // hit while A walks
      val c2 = d.ask(C + 8, true, rid(true, 4))
      val walksDuring = d.walks.size - w0
      val lowDuring = d.readyLow.filter(_ >= c0)
      while (!d.notices.exists(_._1 >= c0) && d.cyc < 300) d.cycle()
      val nA = d.notices.find(_._1 >= c0).map(_._2)
      d.walkLatency = 3
      val b2 = d.ask(B, true, rid(true, 2))                    // retry: still a miss, now walks B
      val bFinal = d.resolve(B, true, rid(true, 2))
      Seq(
        chk(a.status == MISS && b.status == MISS && a.reqId == rid(false, 1) && b.reqId == rid(true, 2),
          "both misses are answered Miss at once, each on its own port with its reqId", s"$a $b"),
        chk(walksDuring == 1 && d.walks(w0)._2.vpn == (A >> 12), "only the first miss starts a walk", s"${d.walks}"),
        chk(c.status == HIT && c2.status == HIT, "hits continue (load and store) while the walk is outstanding", s"$c $c2"),
        chk(lowDuring.isEmpty, "DtlbReqIn.ready never drops because a walk is outstanding", s"$lowDuring"),
        chk(nA.contains(Notice(A >> 12, LEAF)), "the walk ends in one refill notice for VPN A", s"${d.notices}"),
        chk(b2.status == MISS && d.walks.last._2.vpn == (B >> 12) && bFinal.status == HIT,
          "the retried B misses again, starts the next walk, and then hits", s"$b2 $bFinal ${d.walks}"))
    }
  }

  val routing = new SpecTest("dtlb.routing", Seq("funcDtlbTranslate", "propDtlbMissLocal")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS; d.pt.map(1, pg(0) >> 12, Pte(PPN, w = true, d = true))
      d.resolve(pg(0), false, rid(false, 0))
      // Load port blocked: stores still translate.
      d.loadReady = _ => false
      d.ask(pg(0), false, rid(false, 1)); val l0 = d.loadOuts.size
      val s1 = d.ask(pg(0) + 4, true, rid(true, 3)); val s2 = d.ask(pg(1), true, rid(true, 4))
      val heldL = d.loadOffered.map(_._2).distinct.takeRight(1)
      d.loadReady = _ => true; d.run(2); val l0b = d.loadOuts.size
      // Store port blocked: loads still translate.
      d.storeReady = _ => false
      d.ask(pg(0), true, rid(true, 5)); val s0 = d.storeOuts.size
      val l1 = d.ask(pg(0) + 8, false, rid(false, 6)); val l2 = d.ask(pg(2), false, rid(false, 7))
      d.storeReady = _ => true; d.run(3); val s0b = d.storeOuts.size
      Seq(
        chk(s1.status == HIT && s2.status == MISS && l0b == l0 + 1, "with the load port held, stores still answer",
          s"$s1 $s2"),
        chk(l1.status == HIT && l2.status == MISS && s0b == s0 + 1, "with the store port held, loads still answer",
          s"$l1 $l2"),
        chk(d.loadOuts.forall(o => (o._2.reqId >> (L.indexWidth + L.generationWidth)) == 0) &&
            d.storeOuts.forall(o => (o._2.reqId >> (L.indexWidth + L.generationWidth)) == 1),
          "every load answer (hit, miss, fault) goes to the DataCache, every store answer to the LSQ", s"${d.loadOuts} ${d.storeOuts}"),
        chk(heldL == Seq(Tr(rid(false, 1), HIT, (PPN << 12), true)), "the held load answer stayed stable", s"$heldL"))
    }
  }

  // ---- Fault record ----------------------------------------------------------------------------------------
  val faultRecord = new SpecTest("dtlb.faultRecord", Seq("funcDtlbMissNonBlocking", "funcDtlbFlush")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS
      val X = pg(5); val Y = pg(6)
      d.walker = w => WResp(w.vpn, if (w.vpn == (X >> 12)) WPF else WAF, Pte(0), w.ctx.asid)
      val m = d.ask(X, false, rid(false, 0)); while (d.notices.isEmpty && d.cyc < 100) d.cycle()
      val w1 = d.walks.size
      val l = d.ask(X + 4, false, rid(false, 1)); val s = d.ask(X + 8, true, rid(true, 1))
      val noWalk = d.walks.size == w1
      d.ctx = sS.copy(asid = 2); val other = d.ask(X, false, rid(false, 2)); val w2 = d.walks.size
      while (d.notices.size < 2 && d.cyc < 200) d.cycle()
      d.ctx = sS
      d.resolve(Y, true, rid(true, 3))                                    // walk AccessFault replaces the record
      val ya = d.ask(Y, false, rid(false, 3)); val xAfter = d.ask(X, false, rid(false, 4))
      d.doFlush()
      val yFlushed = d.ask(Y, false, rid(false, 5))
      Seq(
        chk(m.status == MISS && l == Tr(rid(false, 1), PF, 0, false) && s == Tr(rid(true, 1), PF, 0, false) && noWalk,
          "a walk PageFault answers later loads and stores of that VPN/ASID at once, on their own ports, without a walk", s"$m $l $s"),
        chk(other.status == MISS && w2 == w1 + 1, "the same VPN in another ASID misses and walks", s"$other ${d.walks}"),
        chk(ya == Tr(rid(false, 3), AF, 0, false) && xAfter.status == MISS, "the next walk fault replaces the one-entry record",
          s"$ya $xAfter"),
        chk(yFlushed.status == MISS, "a flush clears the record", s"$yFlushed"))
    }
  }

  val faultRecordAsid = new SpecTest("dtlb.faultRecordAsid", Seq("funcDtlbMissNonBlocking")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS; d.walkLatency = 8
      d.reqQ.enqueue((rid(false, 0), VA, false))
      while (d.walks.isEmpty && d.cyc < 50) d.cycle()
      d.ctx = sS.copy(asid = 3)                         // context changes while the walk is outstanding
      while (d.notices.isEmpty && d.cyc < 100) d.cycle()
      val now = d.ask(VA, false, rid(false, 1))          // live ASID 3: not the record's ASID
      d.ctx = sS; val back = d.ask(VA, false, rid(false, 2))
      Seq(chk(now.status == MISS && back.status == PF, "the record is keyed by the walk's captured ASID (1), not the live one",
        s"$now $back"))
    }
  }

  val retryNotCached = new SpecTest("dtlb.retryNotCached", Seq("funcDtlbMissNonBlocking", "funcDtlbRefill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS; d.pt.map(1, VA >> 12, Pte(PPN))
      var n = 0
      d.walker = w => { n += 1; if (n == 1) WResp(w.vpn, RETRY, Pte(0), w.ctx.asid) else WResp(w.vpn, LEAF, d.pt.find(1, w.vpn).get, 1) }
      val m = d.ask(VA, false, rid(false, 0)); while (d.notices.isEmpty && d.cyc < 100) d.cycle()
      val r = d.ask(VA, false, rid(false, 1)); val f = d.resolve(VA, false, rid(false, 1))
      Seq(
        chk(d.notices.headOption.map(_._2).contains(Notice(VA >> 12, RETRY)), "a Retry walk still sends its refill notice", s"${d.notices}"),
        chk(r.status == MISS && d.walks.size == 2 && f.status == HIT, "Retry is not cached: the retry misses and walks again", s"$r $f"))
    }
  }

  val notices = new SpecTest("dtlb.notices", Seq("funcDtlbRefill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS
      val st = Seq(LEAF, WPF, WAF, RETRY)
      d.walker = w => WResp(w.vpn, st((w.vpn & 3).toInt), Pte(PPN), w.ctx.asid)
      d.refillReady = c => c % 7 == 6
      (0 until 4).foreach { k =>
        val v = 0x02000000L + (k.toLong << 12)
        d.ask(v, false, rid(false, k)); var w = 0; while (d.notices.size == k && w < 200) { d.cycle(); w += 1 }
      }
      val runs = d.noticeOffered.map(_._2).distinct
      Seq(
        chk(d.notices.map(_._2).toSeq == (0 until 4).map(k => Notice((0x02000000L >> 12) + k, st(k))),
          "every walk outcome (Leaf, PageFault, AccessFault, Retry) sends exactly one notice with the walked VPN", s"${d.notices}"),
        chk(runs == d.notices.map(_._2).distinct && d.noticeOffered.size > d.notices.size,
          "a backpressured notice stays stable until it transfers", s"$runs"))
    }
  }

  // ---- Refill identity / replacement -----------------------------------------------------------------------
  val conflicts = new SpecTest("dtlb.conflicts", Seq("funcDtlbRefill", "funcTlbMatch")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS.copy(asid = 2); d.pt.map(2, VA >> 12, Pte(0x30011L)); d.resolve(VA, false, rid(false, 0))
      d.ctx = sS.copy(asid = 1); d.pt.map(1, VA >> 12, Pte(0x30044L, global = true)); d.resolve(VA, false, rid(false, 1))
      d.ctx = sS.copy(asid = 2); val a = d.ask(VA, false, rid(false, 2))
      val X = 0x00800000L + 0x3000; val Y = 0x00800000L + 0x9000
      d.ctx = sS.copy(asid = 1); d.pt.map(1, X >> 12, Pte(0x30055L)); d.resolve(X, false, rid(false, 3))
      d.pt.local.remove((1, X >> 12)); d.pt.map(1, Y >> 12, Pte(0x14L << 10, superpage = true)); d.resolve(Y, false, rid(false, 4))
      val b = d.ask(X, false, rid(false, 5))
      Seq(
        chk(a == Tr(rid(false, 2), HIT, 0x30044678L, true), "the global refill removed ASID 2's local entry (one match, no walk)", s"$a"),
        chk(b == Tr(rid(false, 5), HIT, (0x14L << 22) | (X & 0x3fffff), true), "the superpage refill removed the contained 4K entry", s"$b"))
    }
  }

  val replacement = new SpecTest("dtlb.replacement", Seq("funcDtlbRefill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS
      def va(k: Int) = 0x02000000L + (k.toLong << 12)
      (0 until 17).foreach(k => d.pt.map(1, va(k) >> 12, Pte(0x20000L + k)))
      (0 until 16).foreach(k => d.resolve(va(k), false, rid(false, k % 8)))
      val w16 = d.walks.size
      (0 until 16).foreach(k => d.ask(va(k), false, rid(false, k % 8)))
      val noEvict = d.walks.size == w16
      val m = new InstructionTlbSpecTests.Plru16; (0 until 16).foreach(m.touch); (0 until 16).foreach(m.touch)
      Seq(3, 9, 0).foreach { k => d.ask(va(k), false, rid(false, 0)); m.touch(k) }
      val victim = m.victim
      d.resolve(va(16), false, rid(false, 1))
      val before = d.walks.size; d.resolve(va(victim), false, rid(false, 2))
      Seq(
        chk(w16 == 16 && noEvict, "16 pages fill the invalid entries first; none is evicted", s"$w16 ${d.walks.size}"),
        chk(d.walks.size - before == 1, s"the 17th page evicts the pseudo-LRU victim (page $victim)", s"${d.walks.size}"))
    }
  }

  // ---- SFENCE.VMA ------------------------------------------------------------------------------------------
  def staleCase(t: SpecTest, label: String, faultResult: Boolean = false)(during: Drv => Unit): Seq[TCheck] = withDrv(t) { d =>
    d.ctx = sS; d.walkLatency = 5
    d.walker = w => if (w.ctx.root == 0x55) WResp(w.vpn, LEAF, Pte(0x30077L), w.ctx.asid)
      else WResp(w.vpn, if (faultResult) WPF else LEAF, Pte(PPN), w.ctx.asid)
    d.reqQ.enqueue((rid(false, 0), VA, false))
    during(d)
    while (d.notices.isEmpty && d.cyc < 200) d.cycle()
    val n1 = d.notices.headOption.map(_._2)
    val after = d.ask(VA, false, rid(false, 1))
    val fin = d.resolve(VA, false, rid(false, 2))
    Seq(
      chk(n1.contains(Notice(VA >> 12, RETRY)), s"$label: the stale walk still notifies the LSQ, as Retry", s"${d.notices}"),
      chk(after.status == MISS, s"$label: it installed nothing and left no fault record", s"$after"),
      chk(fin == Tr(rid(false, 2), HIT, 0x30077678L, true) && d.walks.last._2.ctx.root == 0x55,
        s"$label: the LSQ retry walks with the post-flush context", s"$fin ${d.walks}"))
  }
  def remap(d: Drv): Unit = d.ctx = sS.copy(root = 0x55)

  val flush = new SpecTest("dtlb.flush", Seq("funcDtlbFlush")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS; (0 until 3).foreach(k => d.pt.map(1, (VA >> 12) + k, Pte(PPN + k)))
      (0 until 3).foreach(k => d.resolve(VA + (k.toLong << 12), false, rid(false, k))); val w0 = d.walks.size
      d.reqQ.enqueue((rid(false, 4), VA, false)); d.flush = true; d.cycle(); val inFlush = d.reqFire; d.flush = false
      val misses = (0 until 3).map(k => d.ask(VA + (k.toLong << 12), false, rid(false, k)))
      Seq(
        chk(w0 == 3 && !inFlush, "no request is accepted in the flush cycle", s"$inFlush"),
        chk(misses.forall(_.status == MISS), "after a flush every entry is invalid", s"$misses"))
    }
  }
  val flushWalkReq = new SpecTest("dtlb.flushWalkReq", Seq("funcDtlbFlush", "funcDtlbMissNonBlocking")) {
    def run(): Seq[TCheck] = staleCase(this, "flush while the WalkReq waits") { d =>
      d.wReady = c => c > 10
      while (d.walkOffers.isEmpty && d.cyc < 50) d.cycle()
      remap(d); d.doFlush()
    }
  }
  val flushWaitResp = new SpecTest("dtlb.flushWaitResp", Seq("funcDtlbFlush", "funcDtlbRefill")) {
    def run(): Seq[TCheck] = staleCase(this, "flush while waiting for WalkResp") { d =>
      while (d.walks.isEmpty && d.cyc < 50) d.cycle()
      remap(d); d.doFlush()
    }
  }
  val flushWalkReqFault = new SpecTest("dtlb.flushWalkReqFault", Seq("funcDtlbFlush", "funcDtlbMissNonBlocking")) {
    def run(): Seq[TCheck] = staleCase(this, "a PageFault walk whose WalkReq waited through a flush", faultResult = true) { d =>
      d.wReady = c => c > 10
      while (d.walkOffers.isEmpty && d.cyc < 50) d.cycle()
      remap(d); d.doFlush()
    }
  }
  val flushWaitRespFault = new SpecTest("dtlb.flushWaitRespFault", Seq("funcDtlbFlush", "funcDtlbMissNonBlocking")) {
    def run(): Seq[TCheck] = staleCase(this, "a PageFault walk flushed while waiting for WalkResp", faultResult = true) { d =>
      while (d.walks.isEmpty && d.cyc < 50) d.cycle()
      remap(d); d.doFlush()
    }
  }

  /** A walk completes while the previous notice is still held: its WalkResp waits, no notice is lost. */
  val noticeHold = new SpecTest("dtlb.noticeHold", Seq("funcDtlbRefill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS; d.pt.map(1, pg(0) >> 12, Pte(PPN)); d.pt.map(1, pg(1) >> 12, Pte(PPN + 1))
      d.refillReady = _ => false
      d.ask(pg(0), false, rid(false, 0))
      while (d.noticeOffered.isEmpty && d.cyc < 50) d.cycle()          // A's notice is held
      d.ask(pg(1), false, rid(false, 1))                                 // B misses; no walk active: walks
      while (d.respQ.isEmpty && d.cyc < 80) d.cycle()
      d.run(15)                                                          // B's result is due but must wait
      val heldResp = d.respQ.size; val fires = d.respFires.size
      d.refillReady = _ => true
      while (d.notices.size < 2 && d.cyc < 150) d.cycle()
      Seq(
        chk(heldResp == 1 && fires == 1, "the next WalkResp is not consumed while the previous notice is held",
          s"$heldResp $fires ${d.respFires}"),
        chk(d.notices.map(_._2).toSeq == Seq(Notice(pg(0) >> 12, LEAF), Notice(pg(1) >> 12, LEAF)),
          "both notices arrive, in walk order", s"${d.notices}"))
    }
  }

  /** The WalkReq carries the context captured at the miss, stable while it waits, even if the live
    * context changes (no flush). */
  val walkCtx = new SpecTest("dtlb.walkCtx", Seq("funcDtlbMissNonBlocking")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS.copy(root = 0x11); d.pt.map(1, VA >> 12, Pte(PPN)); d.pt.map(2, VA >> 12, Pte(0x30011L))
      d.wReady = c => c > 12
      d.ask(VA, false, rid(false, 0))
      d.ctx = sS.copy(asid = 2, root = 0x22)
      while (d.walks.isEmpty && d.cyc < 50) d.cycle()
      val offers = d.walkOffers.map(_._2).distinct
      d.ctx = sS.copy(root = 0x11)
      while (d.notices.isEmpty && d.cyc < 80) d.cycle()
      val hit = d.ask(VA, false, rid(false, 1))
      Seq(
        chk(offers.size == 1 && offers.head.ctx.asid == 1 && offers.head.ctx.root == 0x11,
          "the WalkReq keeps the captured context (ASID 1, root 0x11) while it waits", s"$offers"),
        chk(hit == Tr(rid(false, 1), HIT, (PPN << 12) | (VA & 0xfff), true), "the refill is the ASID-1 mapping", s"$hit"))
    }
  }

  def sameCycle(d: Drv): Unit = {
    while (d.walks.isEmpty && d.cyc < 50) d.cycle()
    while (!(d.respQ.nonEmpty && d.respQ.head._1 == d.cyc) && d.cyc < 100) d.cycle()
    remap(d); d.flush = true; d.cycle(); val both = d.flushFire && d.respFire; d.flush = false
    if (!both) throw new IllegalStateException("flush and walk result did not coincide")
  }
  val flushSameLeaf = new SpecTest("dtlb.flushSameLeaf", Seq("funcDtlbFlush", "funcDtlbRefill")) {
    def run(): Seq[TCheck] = staleCase(this, "flush in the cycle a Leaf arrives")(sameCycle)
  }
  val flushSameFault = new SpecTest("dtlb.flushSameFault", Seq("funcDtlbFlush", "funcDtlbMissNonBlocking")) {
    def run(): Seq[TCheck] = staleCase(this, "flush in the cycle a PageFault arrives", faultResult = true)(sameCycle)
  }

  // ---- Interface -------------------------------------------------------------------------------------------
  val outputs = new SpecTest("dtlb.outputs", Seq("funcDtlbTranslate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sS; d.pt.map(1, VA >> 12, Pte(PPN, w = true, d = true)); d.resolve(VA, false, rid(false, 0))
      d.loadReady = c => c % 4 == 0; d.storeReady = c => c % 5 == 0
      val reqs = (0 until 12).map(k => (rid(k % 2 == 1, k % 8, k % 4), VA + 4L * k, k % 2 == 1))
      val l0 = d.loadOuts.size; val s0 = d.storeOuts.size; val c0 = d.cyc
      reqs.foreach(r => d.reqQ.enqueue(r))
      var w = 0; while ((d.loadOuts.size < l0 + 6 || d.storeOuts.size < s0 + 6) && w < 300) { d.cycle(); w += 1 }
      val got = (d.loadOuts.drop(l0) ++ d.storeOuts.drop(s0)).sortBy(_._1).map(_._2)
      def stable(off: Seq[(Int, Tr)], outs: Seq[(Int, Tr)]) = off.map(_._2).distinct == outs.map(_._2).distinct
      Seq(
        chk(got.map(_.reqId).toSet == reqs.map(_._1).toSet && got.size == 12, "every request is answered once, reqId echoed",
          s"$got"),
        chk(stable(d.loadOffered.toSeq.filter(_._1 >= c0), d.loadOuts.toSeq.drop(l0)) &&
            stable(d.storeOffered.toSeq.filter(_._1 >= c0), d.storeOuts.toSeq.drop(s0)) &&
            d.loadOffered.count(_._1 >= c0) > 6 && d.storeOffered.count(_._1 >= c0) > 6,
          "each port's answer is stable until it transfers (both ports backpressured)", "unstable"))
    }
  }

  def expectAssert(t: SpecTest, tag: String, label: String)(body: Drv => Unit): Seq[TCheck] = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.run(15); reachedEnd = true; Nil }
      Seq(TCheck(false, label, "simulation ended normally"))
    } catch {
      case e: NotImplementedError => throw e
      case _: Throwable =>
        val fired = chisel3.simulator.CachedSimulator.lastSimulationLog.linesIterator.filter(_.contains("Assertion failed")).toSeq
        Seq(chk(!reachedEnd && fired.exists(_.contains(tag)), label, fired.headOption.getOrElse("no assertion")))
    }
  }
  val negative = new SpecTest("dtlb.negative", Seq("funcDtlbTranslate", "funcDtlbRefill")) {
    def run(): Seq[TCheck] =
      expectAssert(this, "DtlbReq", "a Store access whose reqId says Load fires the identity assertion") { d =>
        d.reqQ.enqueue((rid(false, 1), VA, true))
      } ++ expectAssert(this, "DtlbReq", "a Fetch access on the DTLB fires the assertion") { d =>
        d.access = Some(0); d.reqQ.enqueue((rid(false, 1), VA, false))
      } ++ expectAssert(this, "DtlbWalkResp", "a WalkResp for another VPN fires the pairing assertion") { d =>
        d.ctx = sS; d.walker = w => WResp(w.vpn + 1, LEAF, Pte(PPN), 1); d.reqQ.enqueue((rid(false, 1), VA, false))
      }
  }

  /** Reference model with LSQ-style retries: several requests in flight, misses during walks, faults,
    * Retry, flushes, backpressure on all three outputs. Every final answer must equal the reference. */
  val random = new SpecTest("dtlb.random", Seq("funcDtlbTranslate", "funcDtlbMissNonBlocking", "funcDtlbRefill",
      "funcDtlbFlush", "propDtlbMissLocal", "funcSv32PermissionCheck", "funcPmaCheck", "funcTranslationMode", "funcTlbMatch")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      var seed = 31337L
      def rnd(n: Int): Int = { seed = seed * 6364136223846793005L + 1442695040888963407L; ((seed >>> 33) % n).toInt }
      def bit(): Boolean = rnd(4) != 0
      val pages = ArrayBuffer[Long]()
      for (r <- 0 until 10) {
        val vpn1 = 0x40L + r
        if (r < 7) for (p <- 0 until 5) {
          val vpn = (vpn1 << 10) | (p * 37 % 1024)
          val ppn = if (rnd(5) == 0) 0xf0000L + rnd(16) else if (rnd(12) == 0) 0x100000L + rnd(64) else 0x1000L + rnd(0xe0000)
          val g = r % 3 == 0
          for (as <- 0 until 3 if as == 0 || !g)
            d.pt.map(as, vpn, Pte(ppn + as, global = g, r = bit(), w = bit(), x = bit(), a = bit(), d = bit(), u = rnd(2) == 0))
          pages += vpn << 12
        } else {
          for (as <- 0 until 3)
            d.pt.map(as, vpn1 << 10, Pte(((0x10L + r + as) << 10) | rnd(1024), superpage = true, r = bit(), w = bit(), x = bit(),
              a = bit(), d = bit(), u = rnd(2) == 0))
          pages += (vpn1 << 22) + (rnd(1024).toLong << 12)
        }
      }
      pages += 0x0f000000L // unmapped page: walk PageFault (fault record)
      var nRetry = 0
      d.walker = w => if (rnd(15) == 0) { nRetry += 1; WResp(w.vpn, RETRY, Pte(0), w.ctx.asid) } else d.pt.find(w.ctx.asid, w.vpn) match {
        case Some(p) => WResp(w.vpn, LEAF, p, w.ctx.asid); case None => WResp(w.vpn, WPF, Pte(0), w.ctx.asid) }
      d.loadReady = c => (c / 3) % 4 != 1; d.storeReady = c => (c / 5) % 3 != 2; d.refillReady = c => c % 6 != 5
      // In-flight model of up to 4 LSQ entries: (id, va, store, expected, waitingNotice)
      case class E(id: Int, va: Long, store: Boolean, var parked: Boolean, var sent: Boolean, var fired: Int = -1,
          var fCtx: DCtx = DCtx())
      val live = ArrayBuffer[E](); var done = 0; var bad = List.empty[String]; var issued = 0; var flushes = 0; var misses = 0
      var ctxChanges = 0; var faultsSeen = 0; var hitsSeen = 0
      var gen = 0
      var k = 0
      while (done < 2500 && k < 200000) {
        k += 1
        d.flush = rnd(60) == 0
        if (rnd(40) == 0) {
          ctxChanges += 1
          d.ctx = DCtx(satp = rnd(5) != 0, asid = rnd(3), priv = Seq(U, S, M)(rnd(3)), dataPriv = Seq(U, S, M)(rnd(3)),
            sum = rnd(2) == 0, mxr = rnd(2) == 0)
        }
        if (live.size < 4 && rnd(2) == 0) {
          val st = rnd(2) == 0; val idx = (0 until 8).find(i => !live.exists(e => e.store == st && ((e.id >> 2) & 7) == i)).get
          gen = (gen + 1) % 4; val id = rid(st, idx, gen)
          val va = pages(rnd(pages.size)) + (rnd(1024).toLong << 2)
          live += E(id, va, st, parked = false, sent = false); issued += 1
        }
        // LSQ: send one unsent, non-waiting entry (if none queued).
        if (d.reqQ.isEmpty) live.find(e => !e.sent && !e.parked).foreach { e => e.sent = true; d.reqQ.enqueue((e.id, e.va, e.store)) }
        val n0 = d.notices.size; val l0 = d.loadOuts.size; val s0 = d.storeOuts.size
        val sentNow = live.find(e => e.sent && e.fired < 0); val cNow = d.ctx
        d.cycle()
        if (d.flushFire) flushes += 1
        d.flush = false
        if (d.reqFire) sentNow.foreach { e => e.fired = d.cyc - 1; e.fCtx = cNow }
        if (d.notices.size > n0) live.foreach(e => if (e.parked) { e.parked = false; e.sent = false })
        val outs = d.loadOuts.drop(l0).map(x => (false, x._2)) ++ d.storeOuts.drop(s0).map(x => (true, x._2))
        for ((st, t) <- outs) live.find(e => e.id == t.reqId && e.sent) match {
          case None => bad ::= s"unexpected answer $t"
          case Some(e) =>
            if (e.store != st) bad ::= s"$t on the wrong port"
            if (t.status == MISS) {
              // LSQ sawRefill: a notice since the request transferred means retry at once; else wait for the next one.
              misses += 1; e.sent = false; e.parked = !d.notices.exists(_._1 >= e.fired); e.fired = -1 }
            else { val x = expect(d.pt, d.pma, e.fCtx, e.va, e.store, e.id)
              if (t != x) bad ::= s"$t expected $x (va ${e.va.toHexString} store ${e.store} ctx ${e.fCtx})"
              if (t.status == HIT) hitsSeen += 1 else faultsSeen += 1
              live -= e; done += 1 }
        }
      }
      Seq(
        chk(bad.isEmpty, s"every final answer equals the reference ($done completed)", bad.reverse.take(3).mkString("; ")),
        chk(done >= 2500, "liveness: all requests completed through retries", s"$done of $issued"),
        chk(misses > 200 && nRetry > 10 && flushes > 20 && ctxChanges > 20 && hitsSeen > 500 && faultsSeen > 200,
          "coverage: misses, Retry results, flushes, context changes, hits, faults",
          s"$misses $nRetry $flushes $ctxChanges $hitsSeen $faultsSeen"))
    }
  }

  val all: Seq[SpecTest] = Seq(bare, barePma, perms, privilege, match_, nonBlocking, routing, faultRecord, faultRecordAsid,
    retryNotCached, notices, conflicts, replacement, flush, flushWalkReq, flushWaitResp, flushSameLeaf, flushSameFault,
    flushWalkReqFault, flushWaitRespFault, noticeHold, walkCtx, outputs, negative, random)
}
