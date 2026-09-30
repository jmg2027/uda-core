package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.core.design.modules.InstructionTlb
import udacore.core.design.shared._

/** L1 SpecTests for the ADR-019 InstructionTlb (InstructionTlbSpecs, Sv32Specs, ADR-019 D-19.4/D-19.6).
  *
  * The driver plays the FetchUnit (TranslateReq stream), the CsrController (committed
  * TranslationContext), the InstructionCache (Translation consumer with programmable ready) and the
  * PageTableWalker: a page-table model answers each WalkReq after a latency, with optional Retry /
  * fault / wrong-VPN injection. The walker deliberately fills entry.pma with all-false attributes:
  * the ITLB must check the static PMA map on the actual physical address, never trust entry.pma.
  */
object InstructionTlbSpecTests {
  val cp = CoreParams()
  val U = 0; val S = 1; val M = 3
  val HIT = 0; val MISS = 1; val PF = 2; val AF = 3               // TranslationStatus
  val LEAF = 0; val WPF = 1; val WAF = 2; val RETRY = 3           // WalkStatus
  val Entries = cp.contract.itlb.entries

  case class Ctx(satp: Boolean = false, asid: Int = 0, priv: Int = M, sum: Boolean = false, mxr: Boolean = false, root: Long = 0)
  case class Pte(ppn: Long, superpage: Boolean = false, global: Boolean = false, x: Boolean = true, a: Boolean = true,
      u: Boolean = false, r: Boolean = true, w: Boolean = false, d: Boolean = false)
  case class Tr(reqId: Int, status: Int, paddr: Long, cacheable: Boolean)
  case class WReq(vpn: Long, ctx: Ctx)
  case class WResp(vpn: Long, status: Int, pte: Pte, asid: Int)

  /** Page-table model: per-ASID and global leaves; a superpage leaf is keyed by its VPN[1] region. */
  class PageTable {
    val local  = mutable.Map[(Int, Long), Pte]() // (asid, vpn or vpn1<<10 for a superpage)
    val global = mutable.Map[Long, Pte]()
    def map(asid: Int, vpn: Long, p: Pte): Unit =
      if (p.global) global((if (p.superpage) vpn & ~0x3ffL else vpn)) = p
      else local((asid, if (p.superpage) vpn & ~0x3ffL else vpn)) = p
    def find(asid: Int, vpn: Long): Option[Pte] =
      local.get((asid, vpn)).orElse(local.get((asid, vpn & ~0x3ffL)).filter(_.superpage))
        .orElse(global.get(vpn)).orElse(global.get(vpn & ~0x3ffL).filter(_.superpage))
  }

  def pmaOf(map: PmaMap, pa: Long): Option[PmaRegion] = map.find(BigInt(pa))
  def compose(p: Pte, va: Long): Long =
    if (p.superpage) ((p.ppn >> 10) << 22) | (va & 0x3fffffL) else (p.ppn << 12) | (va & 0xfffL)

  /** Reference translation of a fetch (the contract: TranslationMode, Sv32PermissionCheck, PmaCheck). */
  def expect(pt: PageTable, pma: PmaMap, c: Ctx, va: Long, id: Int): Tr = {
    def pmaRes(pa: Long): Tr = pmaOf(pma, pa) match {
      case Some(r) if r.executable => Tr(id, HIT, pa, r.cacheable)
      case _ => Tr(id, AF, 0, cacheable = false)
    }
    if (!c.satp || c.priv == M) pmaRes(va)
    else pt.find(c.asid, va >> 12) match {
      case None => Tr(id, PF, 0, cacheable = false)
      case Some(p) =>
        val permOk = p.x && p.a && (if (c.priv == U) p.u else !p.u)
        if (!permOk) Tr(id, PF, 0, cacheable = false) else pmaRes(compose(p, va))
    }
  }

  class Drv(val dut: InstructionTlb, val pma: PmaMap) {
    val io = dut.io
    val pt = new PageTable
    var cyc = 0
    var ctx = Ctx()
    val reqQ = Queue[(Int, Long)]()
    var flush = false
    var wReady: Int => Boolean = _ => true
    var outReady: Int => Boolean = _ => true
    var walkLatency = 3
    /** Answers a WalkReq; default: the page table (PageFault when unmapped). */
    var walker: WReq => WResp = w => pt.find(w.ctx.asid, w.vpn) match {
      case Some(p) => WResp(w.vpn, LEAF, p, w.ctx.asid)
      case None    => WResp(w.vpn, WPF, Pte(0), w.ctx.asid)
    }
    val respQ = Queue[(Int, WResp)]()
    val outs = ArrayBuffer[(Int, Tr)](); val offered = ArrayBuffer[(Int, Tr)]()
    val walks = ArrayBuffer[(Int, WReq)](); val walkOffers = ArrayBuffer[(Int, WReq)]()
    val reqFires = ArrayBuffer[Int](); val respFires = ArrayBuffer[Int](); val flushFires = ArrayBuffer[Int]()
    var reqFire, flushFire, respFire = false

    def pokeCtx(): Unit = {
      val t = io.translationContextIn
      t.satpMode.poke(ctx.satp.B); t.asid.poke(ctx.asid.U); t.rootPpn.poke(ctx.root.U); t.priv.poke(ctx.priv.U)
      t.dataPriv.poke(ctx.priv.U); t.sum.poke(ctx.sum.B); t.mxr.poke(ctx.mxr.B)
    }
    def peekWReq(): WReq = {
      val b = io.itlbWalkReqOut.bits; val c = b.context
      WReq(b.vpn.peek().litValue.toLong, Ctx(c.satpMode.peek().litToBoolean, c.asid.peek().litValue.toInt,
        c.priv.peek().litValue.toInt, c.sum.peek().litToBoolean, c.mxr.peek().litToBoolean, c.rootPpn.peek().litValue.toLong))
    }
    def peekTr(): Tr = {
      val b = io.iCacheTranslationOut.bits
      Tr(b.reqId.peek().litValue.toInt, b.status.peek().litValue.toInt, b.paddr.peek().litValue.toLong, b.cacheable.peek().litToBoolean)
    }
    def pokeResp(r: WResp): Unit = {
      val b = io.itlbWalkRespIn.bits; val e = b.entry; val p = r.pte
      b.vpn.poke(r.vpn.U); b.status.poke(r.status.U)
      e.valid.poke(true.B); e.vpn.poke(r.vpn.U); e.superpage.poke(p.superpage.B); e.ppn.poke(p.ppn.U); e.asid.poke(r.asid.U)
      e.global.poke(p.global.B); e.r.poke(p.r.B); e.w.poke(p.w.B); e.x.poke(p.x.B); e.u.poke(p.u.B); e.a.poke(p.a.B); e.d.poke(p.d.B)
      e.pma.cacheable.poke(false.B); e.pma.executable.poke(false.B); e.pma.readable.poke(false.B); e.pma.writable.poke(false.B)
    }
    def cycle(): Unit = {
      val rq = io.iTlbReqIn
      rq.valid.poke(reqQ.nonEmpty.B)
      reqQ.headOption.foreach { case (id, va) => rq.bits.reqId.poke(id.U); rq.bits.vaddr.poke(va.U); rq.bits.access.poke(0.U) }
      pokeCtx()
      io.itlbFlushIn.valid.poke(flush.B)
      io.itlbFlushIn.bits.vaddr.poke(0.U); io.itlbFlushIn.bits.vaddrValid.poke(false.B)
      io.itlbFlushIn.bits.asid.poke(0.U); io.itlbFlushIn.bits.asidValid.poke(false.B)
      val wr = wReady(cyc)
      io.itlbWalkReqOut.ready.poke(wr.B)
      val rv = respQ.nonEmpty && respQ.head._1 <= cyc
      io.itlbWalkRespIn.valid.poke(rv.B); respQ.headOption.foreach(x => pokeResp(x._2))
      val or = outReady(cyc)
      io.iCacheTranslationOut.ready.poke(or.B)

      reqFire   = reqQ.nonEmpty && rq.ready.peek().litToBoolean
      flushFire = flush && io.itlbFlushIn.ready.peek().litToBoolean
      respFire  = rv && io.itlbWalkRespIn.ready.peek().litToBoolean
      if (io.itlbWalkReqOut.valid.peek().litToBoolean) {
        val w = peekWReq(); walkOffers += ((cyc, w))
        if (wr) { walks += ((cyc, w)); respQ.enqueue((cyc + walkLatency, walker(w))) }
      }
      if (io.iCacheTranslationOut.valid.peek().litToBoolean) {
        val t = peekTr(); offered += ((cyc, t)); if (or) outs += ((cyc, t))
      }
      if (reqFire) { reqQ.dequeue(); reqFires += cyc }
      if (respFire) { respQ.dequeue(); respFires += cyc }
      if (flushFire) flushFires += cyc
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def until(n: Int, limit: Int = 300): Unit = { var k = 0; while (outs.size < n && k < limit) { cycle(); k += 1 } }
    def tr(va: Long, id: Int): Tr = { reqQ.enqueue((id, va)); until(outs.size + 1); outs.last._2 }
    def exp(va: Long, id: Int): Tr = expect(pt, pma, ctx, va, id)
    def doFlush(): Unit = { flush = true; var k = 0; cycle(); while (!flushFire && k < 20) { cycle(); k += 1 }; flush = false }
  }

  def withDrv(t: SpecTest, p: CoreParams = cp)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new InstructionTlb(p)) { dut => val d = new Drv(dut, p.pma); d.cycle(); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  def ram(b: Long, s: Long, c: Boolean = true, x: Boolean = true) = PmaRegion(b, s, c, x, readable = true, writable = true)

  val sv32S = Ctx(satp = true, asid = 1, priv = S)
  val sv32U = sv32S.copy(priv = U)
  val VA = 0x12345678L; val PPN = 0x20019L // pa 0x20019678 (RAM)

  // ---- Bare -------------------------------------------------------------------------------------------
  val bare = new SpecTest("tlb.bare", Seq("funcItlbTranslate", "funcTranslationMode", "funcPmaCheck")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val a = d.tr(0x80001234L, 1)
      d.ctx = Ctx(satp = true, priv = M); val b = d.tr(0x80001238L, 2)
      d.ctx = Ctx(satp = false, priv = S); val c = d.tr(0x00400000L, 3)
      d.ctx = Ctx(satp = false, priv = U); val e = d.tr(0xf0000010L, 4)
      Seq(
        chk(a == Tr(1, HIT, 0x80001234L, cacheable = true), "M mode, satp Bare: pa = va, executable cacheable RAM", s"$a"),
        chk(b == Tr(2, HIT, 0x80001238L, cacheable = true), "M mode stays Bare even with satp.MODE = Sv32", s"$b"),
        chk(c == Tr(3, HIT, 0x00400000L, cacheable = true), "S mode with satp Bare: pa = va", s"$c"),
        chk(e == Tr(4, AF, 0, cacheable = false), "a fetch from the non-executable device window is an access fault", s"$e"),
        chk(d.walks.isEmpty, "Bare never walks", s"${d.walks}"))
    }
  }

  val barePma = new SpecTest("tlb.barePma", Seq("funcItlbTranslate", "funcPmaCheck")) {
    def run(): Seq[TCheck] = {
      val m = PmaMap(Seq(ram(0, 0x10000000L), ram(0x20000000L, 0x1000L, c = false)))
      withDrv(this, CoreParams(CoreContractParams(pma = m))) { d =>
        val a = d.tr(0x20000ff0L, 1); val b = d.tr(0x18000000L, 2)
        Seq(
          chk(a == Tr(1, HIT, 0x20000ff0L, cacheable = false), "an executable uncacheable region answers Hit with cacheable = false", s"$a"),
          chk(b == Tr(2, AF, 0, cacheable = false), "an unmapped address is an access fault (canonical paddr 0)", s"$b"))
      }
    }
  }

  // ---- Sv32 hits ---------------------------------------------------------------------------------------
  val fourK = new SpecTest("tlb.fourK", Seq("funcItlbTranslate", "funcItlbMissWalk", "funcItlbRefill", "funcSv32Decompose")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S; d.pt.map(1, VA >> 12, Pte(PPN))
      val a = d.tr(VA, 5); val nw = d.walks.size; val b = d.tr(VA + 0x10, 6)
      Seq(
        chk(nw == 1 && d.walks.head._2 == WReq(VA >> 12, sv32S), "a miss sends one WalkReq{vpn = va[31:12], context}", s"${d.walks}"),
        chk(a == Tr(5, HIT, 0x20019678L, cacheable = true), "the refill answers pa = {PPN, va[11:0]}", s"$a"),
        chk(b == Tr(6, HIT, 0x20019688L, cacheable = true) && d.walks.size == 1, "a second access hits with no new walk", s"$b ${d.walks}"))
    }
  }

  val superpage = new SpecTest("tlb.superpage", Seq("funcItlbTranslate", "funcSv32Decompose", "funcTlbMatch")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S; d.pt.map(1, 0x00c35678L >> 12, Pte((0x12L << 10) | 0x2abL, superpage = true))
      val a = d.tr(0x00c35678L, 1); val b = d.tr(0x00c01004L, 2)
      Seq(
        chk(a == Tr(1, HIT, 0x04835678L, cacheable = true), "a 4 MiB page composes {PPN[1], va[21:12], va[11:0]} (PPN[0] ignored)", s"$a"),
        chk(b == Tr(2, HIT, 0x04801004L, cacheable = true) && d.walks.size == 1, "another 4 KiB page of the superpage hits", s"$b"))
    }
  }

  val asid = new SpecTest("tlb.asid", Seq("funcTlbMatch")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S; d.pt.map(1, VA >> 12, Pte(PPN)); d.pt.map(2, VA >> 12, Pte(0x30011L))
      d.pt.map(1, 0x00500000L >> 12, Pte(0x30022L, global = true))
      val a = d.tr(VA, 1); d.tr(0x00500000L, 2)
      d.ctx = sv32S.copy(asid = 2)
      val b = d.tr(VA, 3); val c = d.tr(0x00500010L, 4)
      Seq(
        chk(a.paddr == 0x20019678L && b == Tr(3, HIT, 0x30011678L, cacheable = true) && d.walks.size == 3,
          "an ASID-1 entry does not match in ASID 2: a new walk answers ASID 2's mapping", s"$a $b ${d.walks}"),
        chk(c == Tr(4, HIT, 0x30022010L, cacheable = true), "a global entry matches in every ASID without a walk", s"$c ${d.walks}"))
    }
  }

  val perms = new SpecTest("tlb.perms", Seq("funcSv32PermissionCheck", "funcItlbTranslate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      def pg(k: Int) = 0x01000000L + (k.toLong << 12)
      d.pt.map(1, pg(0) >> 12, Pte(PPN, x = false)); d.pt.map(1, pg(1) >> 12, Pte(PPN, a = false))
      d.pt.map(1, pg(2) >> 12, Pte(PPN, u = true)); d.pt.map(1, pg(3) >> 12, Pte(PPN, u = false))
      d.pt.map(1, pg(4) >> 12, Pte(PPN, u = true, r = false, d = false)); d.pt.map(1, pg(5) >> 12, Pte(PPN, r = false, d = true))
      d.ctx = sv32S
      val x0 = d.tr(pg(0), 1); val a0 = d.tr(pg(1), 2)
      d.ctx = sv32U
      val uu = d.tr(pg(2), 3); val us = d.tr(pg(3), 4)
      d.ctx = sv32S.copy(sum = true)
      val su = d.tr(pg(4), 5)
      d.ctx = sv32S.copy(mxr = true); val m1 = d.tr(pg(5) + 4, 6)
      d.ctx = sv32S; val m0 = d.tr(pg(5) + 8, 7)
      Seq(
        chk(x0.status == PF && a0.status == PF, "X = 0 and A = 0 (Svade) are instruction page faults", s"$x0 $a0"),
        chk(uu == Tr(3, HIT, (PPN << 12) | (pg(2) & 0xfff), cacheable = true), "U mode may fetch a U page", s"$uu"),
        chk(us.status == PF, "U mode may not fetch a supervisor page", s"$us"),
        chk(su == Tr(5, PF, 0, cacheable = false), "S mode may not fetch a U page even with SUM (canonical fault fields)", s"$su"),
        chk(m1.status == HIT && m0.status == HIT, "R, D and MXR do not affect a fetch", s"$m1 $m0"))
    }
  }

  // ---- Miss / refill -------------------------------------------------------------------------------------
  val walkBackpressure = new SpecTest("tlb.walkBackpressure", Seq("funcItlbMissWalk")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S; d.pt.map(1, VA >> 12, Pte(PPN))
      d.wReady = c => c > 12
      d.reqQ.enqueue((1, VA))
      while (d.walkOffers.size < 3 && d.cyc < 200) d.cycle()
      d.ctx = sv32S.copy(root = 0x99)              // a later satp write while the WalkReq waits
      d.until(1); val t = d.outs.last._2
      val offers = d.walkOffers.map(_._2).distinct
      Seq(
        chk(d.walkOffers.size > 5 && offers.size == 1 && d.walks.size == 1 && d.walks.head._2.ctx == sv32S,
          "a backpressured WalkReq (vpn and the context captured at the miss) is held stable and sent once",
          s"${d.walkOffers}"),
        chk(t.status == HIT, "then answered", s"$t"))
    }
  }

  val walkFaults = new SpecTest("tlb.walkFaults", Seq("funcItlbMissWalk", "funcItlbRefill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S
      d.walker = w => WResp(w.vpn, if (w.vpn == (VA >> 12)) WPF else WAF, Pte(PPN), 1)
      val a = d.tr(VA, 7); val b = d.tr(VA + 0x1000, 8); val c = d.tr(VA, 9)
      Seq(
        chk(a == Tr(7, PF, 0, cacheable = false) && b == Tr(8, AF, 0, cacheable = false),
          "walk PageFault / AccessFault answer that fault with the held reqId", s"$a $b"),
        chk(c.status == PF && d.walks.size == 3, "a faulted walk installs nothing: the page walks again", s"${d.walks}"))
    }
  }

  val retry = new SpecTest("tlb.retry", Seq("funcItlbMissWalk")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S; d.pt.map(1, VA >> 12, Pte(PPN)); d.pt.map(3, VA >> 12, Pte(0x31111L))
      var n = 0
      d.walker = w => { n += 1; if (n == 1) WResp(w.vpn, RETRY, Pte(0), w.ctx.asid) else d.pt.find(w.ctx.asid, w.vpn).map(p =>
        WResp(w.vpn, LEAF, p, w.ctx.asid)).get }
      d.walkLatency = 6
      d.reqQ.enqueue((2, VA))
      while (d.walks.isEmpty && d.cyc < 200) d.cycle()
      d.ctx = sv32S.copy(asid = 3, root = 0x777)         // the committed context changes during the first walk
      d.until(1)
      val t = d.outs.head._2
      Seq(
        chk(d.walks.map(_._2).toSeq == Seq(WReq(VA >> 12, sv32S), WReq(VA >> 12, sv32S.copy(asid = 3, root = 0x777))),
          "Retry answers nothing and re-walks with the current committed context", s"${d.walks}"),
        chk(t == Tr(2, HIT, 0x31111678L, cacheable = true), "the re-walk's Leaf answers the held request", s"$t"))
    }
  }

  // ---- Refill identity -------------------------------------------------------------------------------------
  val conflicts = new SpecTest("tlb.conflicts", Seq("funcItlbRefill", "funcTlbMatch")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // (a) global vs an ASID-local entry of the same page: the global refill removes the local one.
      d.ctx = sv32S.copy(asid = 2); d.pt.map(2, VA >> 12, Pte(0x30011L))
      d.tr(VA, 1)
      d.ctx = sv32S.copy(asid = 1); d.pt.map(1, VA >> 12, Pte(0x30044L, global = true))
      d.tr(VA, 2)
      d.ctx = sv32S.copy(asid = 2); val a = d.tr(VA, 3); val wa = d.walks.size
      // (b) a superpage refill removes a contained 4 KiB entry of the same address space.
      val X = 0x00800000L + 0x3000; val Y = 0x00800000L + 0x9000
      d.ctx = sv32S.copy(asid = 1); d.pt.map(1, X >> 12, Pte(0x30055L)); d.tr(X, 4)
      d.pt.local.remove((1, X >> 12)); d.pt.map(1, Y >> 12, Pte(0x14L << 10, superpage = true))
      d.tr(Y, 5); val b = d.tr(X, 6); val wb = d.walks.size
      // (c) a global 4 KiB refill under another ASID's superpage removes that superpage.
      val Z = 0x01c00000L + 0x2000
      d.ctx = sv32S.copy(asid = 2); d.pt.map(2, Z >> 12, Pte(0x15L << 10, superpage = true)); d.tr(Z + 0x5000, 7)
      d.ctx = sv32S.copy(asid = 1); d.pt.map(1, Z >> 12, Pte(0x30066L, global = true)); d.tr(Z, 8)
      d.ctx = sv32S.copy(asid = 2); val c = d.tr(Z + 0x5000, 9); val wc = d.walks.size
      Seq(
        chk(a == Tr(3, HIT, 0x30044678L, cacheable = true) && wa == 2,
          "after the global refill, ASID 2 hits only the global entry (its old local entry was removed; one match)", s"$a ${d.walks}"),
        chk(b == Tr(6, HIT, (0x14L << 22) | (X & 0x3fffff), cacheable = true) && wb == 4,
          "the superpage refill removed the contained 4 KiB entry: X now hits the superpage", s"$b ${d.walks}"),
        chk(c.status == HIT && wc == 7, "the global 4 KiB refill removed ASID 2's overlapping superpage: it walks again", s"$c ${d.walks}"))
    }
  }

  /** Tree pseudo-LRU for 16 ways (test-side model): heap nodes 1..15, bit 1 = the victim is on the right. */
  class Plru16 { val b = Array.fill(16)(false)
    def victim: Int = { var n = 1; while (n < 16) n = 2 * n + (if (b(n)) 1 else 0); n - 16 }
    def touch(w: Int): Unit = { var n = 1; for (lvl <- 3 to 0 by -1) { val right = ((w >> lvl) & 1) == 1; b(n) = !right; n = 2 * n + (if (right) 1 else 0) } }
  }

  val replacement = new SpecTest("tlb.replacement", Seq("funcItlbRefill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S
      def va(k: Int) = 0x02000000L + (k.toLong << 12)
      (0 until 17).foreach(k => d.pt.map(1, va(k) >> 12, Pte(0x20000L + k)))
      var id = 0
      def acc(k: Int): Tr = { id += 1; d.tr(va(k), id % 16) }
      (0 until 16).foreach(acc)                                   // invalid-first: fills slots 0..15
      val w16 = d.walks.size
      (0 until 16).foreach(acc); val noEvict = d.walks.size == w16 // all 16 still resident
      val m = new Plru16; (0 until 16).foreach(m.touch); (0 until 16).foreach(m.touch)
      Seq(3, 9, 0).foreach { k => acc(k); m.touch(k) }
      val victim = m.victim
      acc(16)                                                     // evicts the model victim
      val before = d.walks.size
      acc(victim); val refetched = d.walks.size - before
      Seq(
        chk(w16 == 16 && noEvict, "16 pages fill the 16 invalid entries first; none is evicted", s"$w16 ${d.walks.size}"),
        chk(refetched == 1, s"the 17th page evicts the pseudo-LRU victim (page $victim), which then walks again", s"${d.walks.size}"),
        chk(d.outs.forall(_._2.status == HIT), "all answers Hit", s"${d.outs.filter(_._2.status != HIT)}"))
    }
  }

  // ---- SFENCE.VMA ------------------------------------------------------------------------------------------
  val flush = new SpecTest("tlb.flush", Seq("funcItlbFlush")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S; (0 until 3).foreach(k => d.pt.map(1, (VA >> 12) + k, Pte(PPN + k)))
      (0 until 3).foreach(k => d.tr(VA + (k.toLong << 12), k)); val w0 = d.walks.size
      d.doFlush()
      (0 until 3).foreach(k => d.tr(VA + (k.toLong << 12), 4 + k))
      Seq(chk(w0 == 3 && d.walks.size == 6 && d.outs.forall(_._2.status == HIT),
        "after a flush every entry is invalid: each page walks again and refills normally", s"${d.walks}"))
    }
  }

  /** A walk that overlaps a flush never installs or answers: the held request re-walks with the post-flush
    * context; the page table changed across the flush, so a stale answer would carry the old PPN. */
  def staleCase(t: SpecTest, label: String)(during: Drv => Unit): Seq[TCheck] = withDrv(t) { d =>
    d.ctx = sv32S; d.pt.map(1, VA >> 12, Pte(PPN)); d.walkLatency = 5
    // A walk with the pre-flush root reads the old table (old PPN); the post-flush root (0x55) the new one.
    d.walker = w => WResp(w.vpn, LEAF, if (w.ctx.root == 0x55) Pte(0x30077L) else Pte(PPN), w.ctx.asid)
    d.reqQ.enqueue((1, VA))
    during(d)
    d.until(1)
    val t1 = d.outs.head._2
    val t2 = d.tr(VA + 4, 2)
    Seq(
      chk(t1 == Tr(1, HIT, 0x30077678L, cacheable = true), s"$label: the answer comes from the post-flush walk", s"$t1 ${d.walks}"),
      chk(t2 == Tr(2, HIT, 0x3007767cL, cacheable = true) && d.walks.last._2.ctx.root == 0x55,
        s"$label: only the post-flush Leaf (walked with the post-flush context) was installed",
        s"$t2 ${d.walks}"))
  }
  def remap(d: Drv): Unit = { d.pt.map(1, VA >> 12, Pte(0x30077L)); d.ctx = sv32S.copy(root = 0x55) }

  val flushWalkReq = new SpecTest("tlb.flushWalkReq", Seq("funcItlbFlush", "funcItlbMissWalk")) {
    def run(): Seq[TCheck] = staleCase(this, "flush while the WalkReq waits for acceptance") { d =>
      d.wReady = c => c > 10
      while (d.walkOffers.isEmpty && d.cyc < 200) d.cycle()
      remap(d); d.doFlush()        // the satp write commits before the SFENCE.VMA
    }
  }
  val flushWaitResp = new SpecTest("tlb.flushWaitResp", Seq("funcItlbFlush", "funcItlbMissWalk")) {
    def run(): Seq[TCheck] = staleCase(this, "flush while waiting for WalkResp") { d =>
      while (d.walks.isEmpty && d.cyc < 200) d.cycle()
      remap(d); d.doFlush()
    }
  }
  val flushSameCycle = new SpecTest("tlb.flushSameCycle", Seq("funcItlbFlush", "funcItlbRefill")) {
    def run(): Seq[TCheck] = staleCase(this, "flush in the cycle the Leaf arrives") { d =>
      while (d.walks.isEmpty && d.cyc < 200) d.cycle()
      while (!(d.respQ.nonEmpty && d.respQ.head._1 == d.cyc) && d.cyc < 200) d.cycle()
      remap(d); d.flush = true; d.cycle(); val both = d.flushFire && d.respFire; d.flush = false
      if (!both) throw new IllegalStateException(s"flush and Leaf did not coincide: ${d.flushFires} ${d.respFires}")
    }
  }

  // ---- Interface -------------------------------------------------------------------------------------------
  val outBackpressure = new SpecTest("tlb.outBackpressure", Seq("funcItlbTranslate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S; d.pt.map(1, VA >> 12, Pte(PPN)); d.pt.map(1, (VA >> 12) + 1, Pte(PPN, x = false))
      d.outReady = c => c > 25 && c % 3 == 0
      d.reqQ.enqueue((1, VA)); d.reqQ.enqueue((2, VA + 0x1000)); d.reqQ.enqueue((3, VA + 8)); d.until(3)
      val runs = d.offered.map(_._2).distinct
      Seq(
        chk(d.outs.map(_._2).toSeq == Seq(Tr(1, HIT, 0x20019678L, true), Tr(2, PF, 0, false), Tr(3, HIT, 0x20019680L, true)),
          "each translation transfers once, in request order, reqId echoed", s"${d.outs}"),
        chk(runs == d.outs.map(_._2).distinct && d.offered.size > d.outs.size, "each is offered bit for bit stable until it transfers",
          s"$runs"))
    }
  }

  val hitStream = new SpecTest("tlb.hitStream", Seq("funcItlbTranslate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S; d.pt.map(1, VA >> 12, Pte(PPN)); d.tr(VA, 0); d.run(2)
      val c0 = d.cyc
      (0 until 10).foreach(k => d.reqQ.enqueue(((k + 1) % 16, VA + 4L * k))); d.until(11); d.run(2)
      val acc = d.reqFires.filter(_ >= c0); val out = d.outs.drop(1).map(_._1)
      def consecutive(s: Seq[Int]) = s.zip(s.drop(1)).forall { case (a, b) => b == a + 1 }
      Seq(
        chk(acc.size == 10 && consecutive(acc.toSeq), "hits: one request accepted per cycle", s"$acc"),
        chk(out.size == 10 && consecutive(out.toSeq), "one translation per cycle", s"$out"))
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

  val wrongVpn = new SpecTest("tlb.wrongVpn", Seq("funcItlbMissWalk")) {
    def run(): Seq[TCheck] = expectAssert(this, "ItlbWalkResp", "a WalkResp for another VPN fires the pairing assertion") { d =>
      d.ctx = sv32S; d.walker = w => WResp(w.vpn + 1, LEAF, Pte(PPN), 1); d.reqQ.enqueue((1, VA))
    }
  }

  /** Reference model: ASIDs, privileges, Bare/Sv32, 4 KiB and superpages, A/X/U/global, PMA regions,
    * walk faults, output backpressure and periodic flushes; the ITLB must equal the page-table semantics. */
  val random = new SpecTest("tlb.random", Seq("funcItlbTranslate", "funcItlbMissWalk", "funcItlbRefill", "funcItlbFlush",
      "funcTlbMatch", "funcSv32PermissionCheck", "funcPmaCheck", "funcTranslationMode")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      var seed = 777L
      def rnd(n: Int): Int = { seed = seed * 6364136223846793005L + 1442695040888963407L; ((seed >>> 33) % n).toInt }
      def bit(): Boolean = rnd(4) != 0
      // Regions (VPN[1] units of 4 MiB): 8 of 4 KiB pages, 4 superpage regions; some ASID-local, some global.
      val pages = ArrayBuffer[Long]()
      for (r <- 0 until 12) {
        val vpn1 = 0x40L + r
        if (r < 8) for (p <- 0 until 6) {
          val vpn = (vpn1 << 10) | (p * 37 % 1024)
          val ppn = if (rnd(5) == 0) (0xf0000L + rnd(16)) else if (rnd(12) == 0) (0x100000L + rnd(64)) else (0x1000L + rnd(0xe0000))
          val g = r % 3 == 0
          for (as <- 0 until 3 if as == 0 || !g) d.pt.map(as, vpn, Pte(ppn + as, global = g, x = bit(), a = bit(), u = rnd(2) == 0))
          pages += vpn << 12
        } else {
          val g = r == 8
          for (as <- 0 until 3 if as == 0 || !g)
            d.pt.map(as, vpn1 << 10, Pte(((0x10L + r + as) << 10) | rnd(1024), superpage = true, global = g, x = bit(), a = bit(),
              u = rnd(2) == 0))
          pages += (vpn1 << 22) + (rnd(1024).toLong << 12)
        }
      }
      // Pages whose VA lies in the device window: Bare fetches there are access faults.
      for (p <- 0 until 6) {
        val vpn = (0x3c0L << 10) | (p * 11)
        for (as <- 0 until 3) d.pt.map(as, vpn, Pte(0x2000L + p + as, x = bit(), a = bit(), u = rnd(2) == 0))
        pages += vpn << 12
      }
      d.outReady = c => (c / 5) % 4 != 1
      val exp = ArrayBuffer[Tr](); var n = 0; var flushes = 0
      while (n < 2500) {
        if (rnd(60) == 0) { var k = 0; while (d.outs.size < n && k < 500) { d.cycle(); k += 1 }; d.doFlush(); flushes += 1 }
        if (rnd(25) == 0) { // context change only while idle
          var k = 0; while (d.outs.size < n && k < 500) { d.cycle(); k += 1 }
          d.ctx = Ctx(satp = rnd(5) != 0, asid = rnd(3), priv = Seq(U, S, M)(rnd(3)), sum = rnd(2) == 0, mxr = rnd(2) == 0)
        }
        val va = pages(rnd(pages.size)) + (rnd(1024).toLong << 2)
        val id = n % 16
        d.walkLatency = 1 + rnd(4)
        d.reqQ.enqueue((id, va)); exp += d.exp(va, id); n += 1
        var k = 0; while (d.reqQ.size > 2 && k < 200) { d.cycle(); k += 1 }
      }
      d.until(n, 50000); d.run(4)
      val got = d.outs.map(_._2).toSeq
      val bad = got.zip(exp).indexWhere { case (a, b) => a != b }
      val kinds = exp.groupBy(_.status).map { case (k, v) => k -> v.size }
      Seq(
        chk(got.size == n && bad < 0, s"all $n translations equal the page-table / PMA reference, in order",
          s"${got.size} answers; first mismatch at $bad: ${got.lift(bad)} vs ${exp.lift(bad)}"),
        chk(got.forall(_.status != MISS), "no Translation carries status Miss", ""),
        chk(kinds.getOrElse(HIT, 0) > 500 && kinds.getOrElse(PF, 0) > 100 && kinds.getOrElse(AF, 0) > 20 && flushes > 20,
          "coverage: hits, page faults, access faults, flushes", s"$kinds flushes $flushes"))
    }
  }

  val all: Seq[SpecTest] = Seq(bare, barePma, fourK, superpage, asid, perms, walkBackpressure, walkFaults, retry, conflicts,
    replacement, flush, flushWalkReq, flushWaitResp, flushSameCycle, outBackpressure, hitStream, wrongVpn, random)
}
