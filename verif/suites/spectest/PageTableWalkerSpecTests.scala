package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.core.design.modules.PageTableWalker
import udacore.core.design.shared._

/** L1 SpecTests for the ADR-019 PageTableWalker (PageTableWalkerSpecs, Sv32Specs, ADR-019 D-19.6).
  *
  * The driver plays both TLBs (walk requests with a captured TranslationContext, result consumers
  * with backpressure), the CommitUnit (SfenceVma tokens), both TLB flush consumers, and the
  * DataCache physical-read port (a word-addressed PTE memory with latency, backpressure and denied
  * reads). A software Sv32 walker is the reference.
  */
object PageTableWalkerSpecTests {
  val LEAF = 0; val WPF = 1; val WAF = 2; val RETRY = 3
  val cp = CoreParams()

  // ---- PTE encoding and the reference walker ------------------------------------------------------
  case class P(ppn: Long, v: Boolean = true, r: Boolean = false, w: Boolean = false, x: Boolean = false,
      u: Boolean = false, g: Boolean = false, a: Boolean = true, d: Boolean = true, rsw: Int = 0) {
    def word: Long = (ppn << 10) | (rsw.toLong << 8) | b(d) << 7 | b(a) << 6 | b(g) << 5 | b(u) << 4 | b(x) << 3 |
      b(w) << 2 | b(r) << 1 | b(v)
    private def b(x: Boolean): Long = if (x) 1L else 0L
  }
  def ptr(ppn: Long, g: Boolean = false): P = P(ppn, g = g)                     // non-leaf
  def leaf(ppn: Long, g: Boolean = false, r: Boolean = true, w: Boolean = true, x: Boolean = false, u: Boolean = false,
      a: Boolean = true, d: Boolean = true): P = P(ppn, r = r, w = w, x = x, u = u, g = g, a = a, d = d)
  def fields(w: Long): P = P(w >>> 10, (w & 1) != 0, (w >> 1 & 1) != 0, (w >> 2 & 1) != 0, (w >> 3 & 1) != 0,
    (w >> 4 & 1) != 0, (w >> 5 & 1) != 0, (w >> 6 & 1) != 0, (w >> 7 & 1) != 0, ((w >> 8) & 3).toInt)

  case class Ctx(asid: Int = 1, root: Long = 0x100, satp: Boolean = true, priv: Int = 1, dataPriv: Int = 1,
      sum: Boolean = false, mxr: Boolean = false)
  case class Ent(valid: Boolean, vpn: Long, superpage: Boolean, ppn: Long, asid: Int, global: Boolean, r: Boolean,
      w: Boolean, x: Boolean, u: Boolean, a: Boolean, d: Boolean, pma: (Boolean, Boolean, Boolean, Boolean))
  case class Res(status: Int, vpn: Long, ent: Ent)

  def l1Addr(root: Long, vpn: Long): Long = (root << 12) + (((vpn >> 10) & 0x3ff) << 2)
  def l0Addr(ppn: Long, vpn: Long): Long = (ppn << 12) + ((vpn & 0x3ff) << 2)

  /** Reference Sv32 walk: (status, leaf entry if Leaf, PTE addresses read). */
  def refWalk(mem: Long => Long, denied: Long => Boolean, pma: PmaMap, c: Ctx, vpn: Long): (Int, Option[Ent], Seq[Long]) = {
    var level = 1; var a = l1Addr(c.root, vpn); var g = false; val reads = ArrayBuffer[Long]()
    while (true) {
      if (!pma.find(BigInt(a)).exists(_.readable)) return (WAF, None, reads.toSeq)
      reads += a
      if (denied(a)) return (WAF, None, reads.toSeq)
      val p = fields(mem(a)); g ||= p.g
      if (!p.v || (!p.r && p.w)) return (WPF, None, reads.toSeq)
      if (p.r || p.x) {
        if (level == 1 && (p.ppn & 0x3ff) != 0) return (WPF, None, reads.toSeq)
        val base = if (level == 1) ((p.ppn >> 10) << 22) | ((vpn & 0x3ff) << 12) else p.ppn << 12
        val at = pma.find(BigInt(base))
        val pa = (at.exists(_.cacheable), at.exists(_.executable), at.exists(_.readable), at.exists(_.writable))
        return (LEAF, Some(Ent(true, vpn, level == 1, p.ppn, c.asid, g, p.r, p.w, p.x, p.u, p.a, p.d, pa)), reads.toSeq)
      }
      if (level == 0) return (WPF, None, reads.toSeq)
      level = 0; a = l0Addr(p.ppn, vpn)
    }
    throw new IllegalStateException
  }

  class Drv(val dut: PageTableWalker, val pma: PmaMap) {
    val io = dut.io
    var cyc = 0
    val mem = mutable.Map[Long, Long]().withDefaultValue(0L)
    var denied: Long => Boolean = _ => false
    val iQ, dQ = Queue[(Long, Ctx)]()
    var iReady: Int => Boolean = _ => true; var dReady: Int => Boolean = _ => true
    var memReady: Int => Boolean = _ => true; var memLatency = 2
    var sfence = false
    var iFlushReady: Int => Boolean = _ => true; var dFlushReady: Int => Boolean = _ => true
    val memQ = Queue[(Int, Long, Boolean)]()       // (due, word, accessFault)
    // Records.
    val iFires, dFires = ArrayBuffer[(Int, Long, Ctx)]()
    val iOffered, dOffered, iOuts, dOuts = ArrayBuffer[(Int, Res)]()
    val memOffers, memFires = ArrayBuffer[(Int, Long)]()
    val sfFires, iFlushFires, dFlushFires = ArrayBuffer[Int]()
    val iFlushOffers, dFlushOffers = ArrayBuffer[Int]()
    val readyLog = ArrayBuffer[(Int, Boolean, Boolean)]() // (cyc, iReady, dReady) with both valid
    val flushPayloads = ArrayBuffer[(Long, Boolean, Long, Boolean)]()
    var sfPayload = (0L, false, 0L, false)
    var sfFire = false; var memRespFire = false
    private def b(x: Bool) = x.peek().litToBoolean
    private def l(x: UInt) = x.peek().litValue.toLong

    def pokeReq(r: chisel3.util.DecoupledIO[WalkReq], q: Queue[(Long, Ctx)]): Unit = {
      r.valid.poke(q.nonEmpty.B)
      q.headOption.foreach { case (vpn, c) =>
        r.bits.vpn.poke(vpn.U); val t = r.bits.context
        t.satpMode.poke(c.satp.B); t.asid.poke(c.asid.U); t.rootPpn.poke(c.root.U); t.priv.poke(c.priv.U)
        t.dataPriv.poke(c.dataPriv.U); t.sum.poke(c.sum.B); t.mxr.poke(c.mxr.B)
      }
    }
    def peekRes(o: chisel3.util.DecoupledIO[WalkResp]): Res = {
      val e = o.bits.entry
      Res(l(o.bits.status).toInt, l(o.bits.vpn), Ent(b(e.valid), l(e.vpn), b(e.superpage), l(e.ppn), l(e.asid).toInt,
        b(e.global), b(e.r), b(e.w), b(e.x), b(e.u), b(e.a), b(e.d),
        (b(e.pma.cacheable), b(e.pma.executable), b(e.pma.readable), b(e.pma.writable))))
    }
    def cycle(): Unit = {
      pokeReq(io.itlbWalkReqIn, iQ); pokeReq(io.dtlbWalkReqIn, dQ)
      val ir = iReady(cyc); val dr = dReady(cyc)
      io.itlbWalkRespOut.ready.poke(ir.B); io.dtlbWalkRespOut.ready.poke(dr.B)
      val mr = memReady(cyc); io.ptwMemReqOut.ready.poke(mr.B)
      val mv = memQ.nonEmpty && memQ.head._1 <= cyc
      io.ptwMemRespIn.valid.poke(mv.B)
      memQ.headOption.foreach { case (_, w, f) =>
        val p = fields(w); val t = io.ptwMemRespIn.bits.pte
        t.ppn1.poke((p.ppn >> 10).U); t.ppn0.poke((p.ppn & 0x3ff).U); t.rsw.poke(p.rsw.U); t.d.poke(p.d.B); t.a.poke(p.a.B)
        t.g.poke(p.g.B); t.u.poke(p.u.B); t.x.poke(p.x.B); t.w.poke(p.w.B); t.r.poke(p.r.B); t.v.poke(p.v.B)
        io.ptwMemRespIn.bits.accessFault.poke(f.B)
      }
      val s = io.sfenceVmaIn
      s.valid.poke(sfence.B); s.bits.vaddr.poke(sfPayload._1.U); s.bits.vaddrValid.poke(sfPayload._2.B)
      s.bits.asid.poke(sfPayload._3.U); s.bits.asidValid.poke(sfPayload._4.B)
      val ifr = iFlushReady(cyc); val dfr = dFlushReady(cyc)
      io.itlbFlushOut.ready.poke(ifr.B); io.dtlbFlushOut.ready.poke(dfr.B)

      val iRdy = b(io.itlbWalkReqIn.ready); val dRdy = b(io.dtlbWalkReqIn.ready)
      if (iQ.nonEmpty && dQ.nonEmpty) readyLog += ((cyc, iRdy, dRdy))
      val iFire = iQ.nonEmpty && iRdy; val dFire = dQ.nonEmpty && dRdy
      sfFire = sfence && b(s.ready)
      if (b(io.itlbFlushOut.valid)) { iFlushOffers += cyc; if (ifr) iFlushFires += cyc }
      if (b(io.dtlbFlushOut.valid)) { dFlushOffers += cyc; if (dfr) dFlushFires += cyc }
      if (b(io.itlbFlushOut.valid) && ifr) {
        val f = io.itlbFlushOut.bits; flushPayloads += ((l(f.vaddr), b(f.vaddrValid), l(f.asid), b(f.asidValid)))
      }
      if (b(io.ptwMemReqOut.valid)) {
        val a = l(io.ptwMemReqOut.bits.paddr); memOffers += ((cyc, a))
        if (mr) { memFires += ((cyc, a)); memQ.enqueue((cyc + memLatency, mem(a), denied(a))) }
      }
      memRespFire = mv && b(io.ptwMemRespIn.ready)
      if (b(io.itlbWalkRespOut.valid)) { val r = peekRes(io.itlbWalkRespOut); iOffered += ((cyc, r)); if (ir) iOuts += ((cyc, r)) }
      if (b(io.dtlbWalkRespOut.valid)) { val r = peekRes(io.dtlbWalkRespOut); dOffered += ((cyc, r)); if (dr) dOuts += ((cyc, r)) }
      if (iFire) { val (v, c) = iQ.dequeue(); iFires += ((cyc, v, c)) }
      if (dFire) { val (v, c) = dQ.dequeue(); dFires += ((cyc, v, c)) }
      if (sfFire) sfFires += cyc
      if (memRespFire) memQ.dequeue()
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def until(n: Int)(p: => Boolean): Boolean = { var k = 0; while (!p && k < n) { cycle(); k += 1 }; p }
    def outs = iOuts.map(x => (x._1, 'I', x._2)) ++ dOuts.map(x => (x._1, 'D', x._2))
    /** One walk on one side; returns its result. */
    def walk(vpn: Long, c: Ctx, dSide: Boolean = false): Option[Res] = {
      val o = if (dSide) dOuts else iOuts; val n0 = o.size
      (if (dSide) dQ else iQ).enqueue((vpn, c))
      until(200)(o.size > n0); o.lift(n0).map(_._2)
    }
    def ref(vpn: Long, c: Ctx) = refWalk(mem, denied, pma, c, vpn)
    def put(a: Long, p: P): Unit = mem(a) = p.word
  }

  def withDrv(t: SpecTest, p: CoreParams = cp)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new PageTableWalker(p)) { dut => val d = new Drv(dut, p.pma); d.cycle(); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  val C = Ctx()
  val VPN = 0x12345L                                    // vpn1 = 0x48, vpn0 = 0x345
  def leafRes(r: Option[Res], d: Drv, vpn: Long, c: Ctx): Boolean = {
    val (st, e, _) = d.ref(vpn, c); r.exists(x => x.status == st && x.vpn == vpn && (st != LEAF || e.contains(x.ent)))
  }
  /** Two-level mapping of vpn under root: level-1 pointer to ptPpn, level-0 PTE p. */
  def map2(d: Drv, c: Ctx, vpn: Long, ptPpn: Long, p: P, g1: Boolean = false): Unit = {
    d.put(l1Addr(c.root, vpn), ptr(ptPpn, g1)); d.put(l0Addr(ptPpn, vpn), p)
  }

  // ---- Arbitration ----------------------------------------------------------------------------------
  val arbitrate = new SpecTest("ptw.arbitrate", Seq("funcPtwArbitrate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      map2(d, C, VPN, 0x200, leaf(0x300)); map2(d, C, VPN + 1, 0x200, leaf(0x301))
      val i = d.walk(VPN, C); val dd = d.walk(VPN + 1, C, dSide = true)
      // Both at once, several times: alternate.
      (0 until 6).foreach { k => d.iQ.enqueue((VPN, C)); d.dQ.enqueue((VPN + 1, C)) }
      d.until(400)(d.iOuts.size == 7 && d.dOuts.size == 7)
      val order = (d.iFires.map(x => (x._1, 'I')) ++ d.dFires.map(x => (x._1, 'D'))).sortBy(_._1).map(_._2).drop(2)
      val alternates = order.sliding(2).forall(p => p.size < 2 || p(0) != p(1))
      val losers = d.readyLog.filter(x => x._2 && x._3)
      Seq(
        chk(leafRes(i, d, VPN, C) && leafRes(dd, d, VPN + 1, C), "an ITLB-only and a DTLB-only request are each served", s"$i $dd"),
        chk(order.size == 12 && alternates, "sustained contention alternates ITLB and DTLB (no starvation)", s"$order"),
        chk(losers.isEmpty, "never both walk inputs ready in one cycle (the loser stays backpressured)", s"$losers"),
        chk(d.iOuts.forall(_._2.vpn == VPN) && d.dOuts.forall(_._2.vpn == VPN + 1), "each result returns to its requester",
          s"${d.iOuts} ${d.dOuts}"))
    }
  }

  // ---- Addresses ------------------------------------------------------------------------------------
  val addresses = new SpecTest("ptw.addresses", Seq("funcSv32Walk", "funcPtwPhysicalAccess", "propPtwNoRecursiveTranslation")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      map2(d, C, VPN, 0x2ab, leaf(0x300))
      d.walk(VPN, C)
      val a1 = d.memFires.map(_._2).toSeq
      // Max VPN with a large root inside RAM; and the max legal root PPN (top of the 34-bit space: unmapped -> AF, no read).
      val cMax = C.copy(root = 0xeffffL)
      map2(d, cMax, 0xfffffL, 0xeff00L, leaf(0x1234))
      val n0 = d.memFires.size; d.walk(0xfffffL, cMax); val a2 = d.memFires.drop(n0).map(_._2).toSeq
      val cTop = C.copy(root = 0x3fffffL); val n1 = d.memFires.size; val top = d.walk(0x00001L, cTop)
      Seq(
        chk(a1 == Seq((0x100L << 12) + (0x48L << 2), (0x2abL << 12) + (0x345L << 2)),
          "level-1 PTE at root * 4096 + VPN[1] * 4, level-0 at PPN * 4096 + VPN[0] * 4", a1.map(_.toHexString).mkString(" ")),
        chk(a2 == Seq((0xeffffL << 12) + (0x3ffL << 2), (0xeff00L << 12) + (0x3ffL << 2)), "max VPN with a large root",
          a2.map(_.toHexString).mkString(" ")),
        chk(top.exists(_.status == WAF) && d.memFires.size == n1, "the max root PPN addresses unmapped PMA: AccessFault, no read",
          s"$top"),
        chk(d.memOffers.forall(_._2 % 4 == 0), "every PtwMemReq is 4-byte aligned", ""))
    }
  }

  // ---- PTE structure ----------------------------------------------------------------------------------
  val structure = new SpecTest("ptw.structure", Seq("funcSv32Walk", "propWalkFaultTyping")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      def v(k: Int) = (k.toLong << 10) | 5        // distinct VPN[1], VPN[0] = 5
      d.put(l1Addr(C.root, v(1)), P(0x5400, v = false, r = true))            // V = 0 at level 1 (else a legal leaf)
      d.put(l1Addr(C.root, v(2)), P(0x5400, r = false, w = true, x = true))  // W without R (X set: else a leaf)
      map2(d, C, v(3), 0x201, leaf(0x300))                                    // pointer -> 4K leaf
      d.put(l1Addr(C.root, v(4)), leaf(0x5400))                               // aligned 4M leaf (PPN[0] = 0)
      d.put(l1Addr(C.root, v(5)), leaf(0x5401))                               // misaligned superpage
      map2(d, C, v(6), 0x202, ptr(0x203))                                     // level-0 pointer
      map2(d, C, v(7), 0x204, leaf(0x301, r = false, w = false, x = true))   // X-only leaf
      map2(d, C, v(8), 0x205, leaf(0x302, a = false))                         // A = 0
      map2(d, C, v(9), 0x206, leaf(0x303, d = false, w = false))              // D = 0
      map2(d, C, v(10), 0x207, P(0x304, v = false, r = true))                 // V = 0 at level 0
      map2(d, C, v(11), 0x208, P(0x305, r = false, w = true, x = true))       // W without R at level 0
      val rs = (1 to 11).map(k => d.walk(v(k), C))
      val st = rs.map(_.map(_.status).getOrElse(-1))
      Seq(
        chk(st == Seq(WPF, WPF, LEAF, LEAF, WPF, WPF, LEAF, LEAF, LEAF, WPF, WPF),
          "V = 0 / W without R / misaligned superpage / level-0 pointer fault; X-only, A = 0, D = 0 stay Leaf", s"$st"),
        chk((1 to 11).forall(k => leafRes(rs(k - 1), d, v(k), C)), "every result equals the reference walk", s"$rs"),
        chk(rs(3).exists(r => r.ent.superpage && r.ent.ppn == 0x5400) && rs(2).exists(r => !r.ent.superpage && r.ent.ppn == 0x300),
          "a level-1 leaf is a superpage, a level-0 leaf is 4 KiB", s"${rs(3)} ${rs(2)}"),
        chk(d.memFires.count(x => (x._2 >> 12) == 0x203) == 0, "a level-0 pointer is not followed", ""))
    }
  }

  // ---- Global accumulation ---------------------------------------------------------------------------------
  val global = new SpecTest("ptw.global", Seq("funcSv32Walk")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      map2(d, C, 0x00401L, 0x210, leaf(0x310), g1 = true)                      // non-leaf G, leaf no G
      map2(d, C, 0x00802L, 0x211, leaf(0x311, g = true))                       // leaf G only
      map2(d, C, 0x00c03L, 0x212, leaf(0x312))                                 // no G
      d.put(l1Addr(C.root, 0x01004L), leaf(0x1400, g = true))                  // superpage G
      val r = Seq(0x00401L, 0x00802L, 0x00c03L, 0x01004L).map(d.walk(_, C))
      Seq(chk(r.map(_.exists(_.ent.global)) == Seq(true, true, false, true),
        "global = OR of G over every PTE read (non-leaf G makes the leaf global; leaf G alone too; none: local)", s"$r"))
    }
  }

  // ---- Leaf entry contents ----------------------------------------------------------------------------
  val entry = new SpecTest("ptw.entry", Seq("funcSv32Walk", "funcPmaCheck")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val c = C.copy(asid = 0x1a5)
      map2(d, c, 0x00405L, 0x220, leaf(0xf0001L, u = true, x = true, w = false))   // target in the device window
      d.put(l1Addr(c.root, 0x00c07L), leaf(0x3c00, x = true))                      // superpage; VPN[0] = 7
      map2(d, c, 0x01009L, 0x221, leaf(0x200000L))                                  // target above 4 GiB
      val r = Seq(0x00405L, 0x00c07L, 0x01009L).map(d.walk(_, c))
      Seq(
        chk(r.zip(Seq(0x00405L, 0x00c07L, 0x01009L)).forall { case (x, v) => leafRes(x, d, v, c) },
          "entry = {vpn, superpage, leaf PPN, captured ASID, R/W/X/U/A/D, PMA of the walked page}", s"$r"),
        chk(r(0).exists(x => x.ent.pma == (false, false, true, true)) && r(1).exists(_.ent.pma._1) &&
          r(2).exists(x => x.status == LEAF && x.ent.pma == (false, false, false, false)),
          "entry.pma is advisory; an unmapped target page does not fail the walk", s"$r"))
    }
  }

  // ---- PMA and memory -----------------------------------------------------------------------------------
  val pmaCheck = new SpecTest("ptw.pma", Seq("funcPtwPhysicalAccess", "funcPmaCheck", "propWalkFaultTyping")) {
    def run(): Seq[TCheck] = {
      val m = PmaMap(Seq(
        PmaRegion(0, 0x10000000L, cacheable = true, executable = true, readable = true, writable = true),
        PmaRegion(0x20000000L, 0x1000000L, cacheable = false, executable = false, readable = true, writable = true),
        PmaRegion(0x30000000L, 0x1000000L, cacheable = false, executable = false, readable = false, writable = true)))
      withDrv(this, CoreParams(CoreContractParams(pma = m))) { d =>
        val cU = C.copy(root = 0x20000)           // root page table in the readable uncacheable region
        map2(d, cU, VPN, 0x201, leaf(0x300))
        val u = d.walk(VPN, cU); val uReads = d.memFires.map(_._2).toSeq
        val cN = C.copy(root = 0x30000); val n0 = d.memFires.size
        val nr = d.walk(VPN, cN); val nrReads = d.memFires.size - n0
        val cX = C.copy(root = 0x18000); val n1 = d.memFires.size
        val un = d.walk(VPN, cX); val unReads = d.memFires.size - n1
        // Level-0 table in the non-readable region: one level-1 read, then AccessFault.
        d.put(l1Addr(C.root, VPN), ptr(0x30001)); val n2 = d.memFires.size
        val l0 = d.walk(VPN, C); val l0Reads = d.memFires.size - n2
        Seq(
          chk(u.exists(_.status == LEAF) && uReads.size == 2 && uReads.head == l1Addr(0x20000, VPN),
            "a readable uncacheable PTE address is still read (cacheable is not required)", s"$u $uReads"),
          chk(nr.exists(_.status == WAF) && nrReads == 0, "a non-readable PTE address: AccessFault, no PtwMemReq", s"$nr"),
          chk(un.exists(_.status == WAF) && unReads == 0, "an unmapped PTE address: AccessFault, no PtwMemReq", s"$un"),
          chk(l0.exists(_.status == WAF) && l0Reads == 1, "the level-0 address is checked too", s"$l0 $l0Reads"))
      }
    }
  }

  val denied = new SpecTest("ptw.denied", Seq("funcPtwPhysicalAccess", "propWalkFaultTyping")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      map2(d, C, VPN, 0x201, leaf(0x300))
      d.denied = a => a == l1Addr(C.root, VPN); val r1 = d.walk(VPN, C); val n1 = d.memFires.size
      d.denied = a => a == l0Addr(0x201, VPN); val r0 = d.walk(VPN, C); val n0 = d.memFires.size - n1
      // A denied read whose data looks like a valid leaf must not be used.
      d.denied = _ => false
      d.put(l1Addr(C.root, 0x00402L), leaf(0x400)); d.denied = a => a == l1Addr(C.root, 0x00402L)
      val r2 = d.walk(0x00402L, C)
      Seq(
        chk(r1.exists(_.status == WAF) && n1 == 1, "a denied level-1 read: AccessFault after one read", s"$r1"),
        chk(r0.exists(_.status == WAF) && n0 == 2, "a denied level-0 read: AccessFault", s"$r0"),
        chk(r2.exists(_.status == WAF), "the PTE data of a denied read is ignored", s"$r2"))
    }
  }

  val memBackpressure = new SpecTest("ptw.memBackpressure", Seq("funcPtwPhysicalAccess")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      map2(d, C, VPN, 0x201, leaf(0x300))
      d.memReady = c => c % 5 == 4; d.memLatency = 6
      val r = d.walk(VPN, C)
      val runs = d.memOffers.groupBy(_._2).map { case (a, xs) => (a, xs.size) }
      val outstanding = d.memFires.sliding(2).forall(p => p.size < 2 || p(1)._1 - p(0)._1 >= 6)
      Seq(
        chk(leafRes(r, d, VPN, C) && d.memFires.size == 2, "the walk completes with exactly two reads", s"$r ${d.memFires}"),
        chk(d.memOffers.map(_._2).distinct.size == 2 && runs.values.forall(_ >= 2),
          "a backpressured PtwMemReq stays valid with the same address until accepted", s"${d.memOffers}"),
        chk(outstanding, "no second read is issued before the first answer", s"${d.memFires}"))
    }
  }

  // ---- Outputs ------------------------------------------------------------------------------------------
  val outputs = new SpecTest("ptw.outputs", Seq("funcPtwArbitrate", "funcSv32Walk")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      map2(d, C, VPN, 0x201, leaf(0x300)); map2(d, C, VPN + 1, 0x201, leaf(0x301))
      d.iReady = c => c > 30
      d.iQ.enqueue((VPN, C)); d.dQ.enqueue((VPN + 1, C))
      d.until(100)(d.iOuts.nonEmpty && d.dOuts.nonEmpty)
      val iHold = d.iOffered.map(_._2).distinct
      val noOverlap = d.iOffered.map(_._1).toSet.intersect(d.dOffered.map(_._1).toSet).isEmpty
      val dStart = d.dFires.headOption.map(_._1).getOrElse(-1)
      Seq(
        chk(iHold.size == 1 && d.iOffered.size > 10 && leafRes(d.iOuts.headOption.map(_._2), d, VPN, C),
          "a backpressured ITLB result stays stable (status, vpn, entry)", s"$iHold"),
        chk(dStart > d.iOuts.head._1, "no new walk is accepted while a result is pending", s"$dStart ${d.iOuts}"),
        chk(noOverlap && d.dOuts.forall(_._2.vpn == VPN + 1), "results appear only on the requester's edge, one at a time", ""))
    }
  }

  // ---- SFENCE.VMA -------------------------------------------------------------------------------------------
  val flushIdle = new SpecTest("ptw.flushIdle", Seq("funcPtwFlush")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.sfPayload = (0x1234L, true, 0x55L, true)
      d.sfence = true; d.cycle(); d.sfence = false; val f1 = (d.sfFires.size, d.iFlushFires.size, d.dFlushFires.size)
      d.dFlushReady = c => c > 10
      d.sfence = true; d.cycle(); d.until(20)(d.sfFire); d.sfence = false
      val iBefore = d.iFlushFires.filter(_ < 10); val iOff = d.iFlushOffers.filter(c => c > 1 && c < 10)
      Seq(
        chk(f1 == (1, 1, 1) && d.flushPayloads.headOption.contains((0x1234L, true, 0x55L, true)),
          "an idle SFENCE fires both TLB flush tokens in its transfer cycle, payload intact", s"$f1 ${d.flushPayloads}"),
        chk(d.iFlushFires.size == 2 && d.dFlushFires.size == 2 && d.iFlushFires.last == d.dFlushFires.last &&
          d.iFlushFires.last == d.sfFires.last && iBefore.size == 1,
          "with the DTLB flush not ready, neither flush fires; both fire together later", s"${d.iFlushFires} ${d.dFlushFires}"),
        chk(iOff.isEmpty, "no TLB flush is even offered while the other cannot transfer", s"$iOff"))
    }
  }

  /** A walk overlapping an SFENCE answers Retry to its requester, whatever its memory reads returned. */
  def staleCase(t: SpecTest, label: String, kind: Int, dSide: Boolean = false)(during: Drv => Unit): Seq[TCheck] =
    withDrv(t) { d =>
      kind match {
        case 0 => map2(d, C, VPN, 0x201, leaf(0x300))
        case 1 => map2(d, C, VPN, 0x201, P(0x300, v = false))
        case _ => map2(d, C, VPN, 0x201, leaf(0x300)); d.denied = a => a == l0Addr(0x201, VPN)
      }
      d.memLatency = 6
      (if (dSide) d.dQ else d.iQ).enqueue((VPN, C))
      during(d)
      val outs = if (dSide) d.dOuts else d.iOuts
      d.until(100)(outs.nonEmpty)
      val n = d.memFires.size
      Seq(
        chk(outs.map(_._2.status).toSeq == Seq(RETRY) && (if (dSide) d.iOuts else d.dOuts).isEmpty,
          s"$label: exactly one Retry to the requester", s"${d.iOuts} ${d.dOuts}"),
        chk(n == d.memFires.map(_._2).distinct.size && n >= 1 && d.memOffers.groupBy(_._2).size == n,
          s"$label: every issued read completed once; nothing was retracted or repeated", s"${d.memOffers} ${d.memFires}"),
        chk(d.sfFires.size == 1 && d.iFlushFires == d.sfFires && d.dFlushFires == d.sfFires, s"$label: the flush fired atomically",
          s"${d.sfFires}"))
    }
  def flushAt(d: Drv, cond: => Boolean): Unit = { d.until(60)(cond); d.sfence = true; d.cycle(); d.until(20)(d.sfFire); d.sfence = false }
  val flushIssue = new SpecTest("ptw.flushIssue", Seq("funcPtwFlush")) {
    def run(): Seq[TCheck] = staleCase(this, "SFENCE while the PtwMemReq is backpressured", 0) { d =>
      d.memReady = c => c > 20
      flushAt(d, d.memOffers.nonEmpty)
    }
  }
  val flushWait = new SpecTest("ptw.flushWait", Seq("funcPtwFlush")) {
    def run(): Seq[TCheck] = staleCase(this, "SFENCE while waiting for PtwMemResp", 0, dSide = true) { d =>
      flushAt(d, d.memFires.nonEmpty)
    }
  }
  val flushBetween = new SpecTest("ptw.flushBetween", Seq("funcPtwFlush")) {
    def run(): Seq[TCheck] = staleCase(this, "SFENCE after the level-1 pointer, before the level-0 request", 0) { d =>
      d.memReady = c => d.memFires.isEmpty || c > 40
      flushAt(d, d.memFires.nonEmpty && d.memQ.isEmpty)
    }
  }
  val flushFault = new SpecTest("ptw.flushFault", Seq("funcPtwFlush", "propWalkFaultTyping")) {
    def run(): Seq[TCheck] =
      staleCase(this, "old PageFault", 1) { d => flushAt(d, d.memFires.nonEmpty) } ++
      staleCase(this, "old AccessFault", 2, dSide = true) { d => flushAt(d, d.memFires.nonEmpty) }
  }

  val flushBlocked = new SpecTest("ptw.flushBlocked", Seq("funcPtwFlush")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      map2(d, C, VPN, 0x201, leaf(0x300))
      d.iReady = c => c > 25
      d.iQ.enqueue((VPN, C))
      d.until(40)(d.iOffered.nonEmpty)
      d.sfence = true; d.until(60)(d.sfFire); d.sfence = false
      val held = d.iOffered.map(_._2).distinct
      Seq(
        chk(held.size == 1 && held.head.status == LEAF, "a presented, blocked Leaf is never changed into Retry", s"$held"),
        chk(d.sfFires.size == 1 && d.iOuts.nonEmpty && d.sfFires.head > d.iOuts.head._1,
          "SFENCE waits until the blocked result has transferred (not even in the same cycle)", s"${d.sfFires} ${d.iOuts}"))
    }
  }

  val flushPriority = new SpecTest("ptw.flushPriority", Seq("funcPtwFlush", "funcPtwArbitrate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      map2(d, C, VPN, 0x201, leaf(0x300)); map2(d, C, VPN + 1, 0x201, leaf(0x301))
      d.walk(VPN, C)                                   // last grant ITLB: DTLB is preferred next
      d.iQ.enqueue((VPN, C)); d.dQ.enqueue((VPN + 1, C)); d.sfence = true
      d.cycle(); d.sfence = false
      val c0 = d.cyc - 1
      val firedThen = d.iFires.exists(_._1 == c0) || d.dFires.exists(_._1 == c0)
      d.cycle()
      Seq(
        chk(d.sfFires.contains(c0) && !firedThen, "SFENCE wins the cycle; no walk is accepted", s"${d.sfFires} ${d.iFires} ${d.dFires}"),
        chk(d.dFires.exists(_._1 == c0 + 1) && !d.iFires.exists(_._1 == c0 + 1),
          "arbitration resumes next cycle with the round-robin state unchanged (DTLB)", s"${d.iFires} ${d.dFires}"))
    }
  }

  val contextCapture = new SpecTest("ptw.context", Seq("funcSv32Walk", "funcPtwArbitrate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // The request's context is captured at acceptance: the walk follows its root and ASID only.
      val cA = C.copy(asid = 3, root = 0x150); map2(d, cA, VPN, 0x201, leaf(0x300))
      val r = d.walk(VPN, cA, dSide = true)
      // Back to back from one TLB with different contexts: while the first walks, the port already
      // presents the second request; the first must still use its own captured context.
      val cB = C.copy(asid = 4, root = 0x160); map2(d, cB, VPN, 0x202, leaf(0x310))
      d.memLatency = 5
      d.iQ.enqueue((VPN, cA)); d.iQ.enqueue((VPN, cB)); d.until(100)(d.iOuts.size == 2)
      val two = d.iOuts.map(_._2).toSeq
      Seq(chk(r.exists(x => x.status == LEAF && x.ent.asid == 3) && d.memFires.head._2 == l1Addr(0x150, VPN),
        "the walk uses the captured root and ASID", s"$r ${d.memFires}"),
        chk(two.map(x => (x.ent.asid, x.ent.ppn)) == Seq((3, 0x300L), (4, 0x310L)),
          "back-to-back walks each use their own captured context, not the next request's", s"$two"))
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
  val negative = new SpecTest("ptw.negative", Seq("funcPtwPhysicalAccess")) {
    def run(): Seq[TCheck] = expectAssert(this, "PtwMemResp", "an unsolicited PtwMemResp fires the protocol assertion") { d =>
      d.memQ.enqueue((0, leaf(1).word, false))
    }
  }

  /** Reference model: thousands of walks from both TLBs with random tables, PMA, latency, faults,
    * backpressure and SFENCE races. Every accepted walk yields exactly one result to its requester,
    * equal to the reference (or Retry when an SFENCE overlapped it). */
  val random = new SpecTest("ptw.random", Seq("funcPtwArbitrate", "funcSv32Walk", "funcPtwPhysicalAccess", "funcPtwFlush",
      "propPtwNoRecursiveTranslation", "propWalkFaultTyping")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      var seed = 77L
      def rnd(n: Int): Int = { seed = seed * 6364136223846793005L + 1442695040888963407L; ((seed >>> 33) % n).toInt }
      def bit(k: Int = 4): Boolean = rnd(k) != 0
      val roots = Seq(0x100L, 0x180L, 0xf0010L, 0x200000L)          // RAM, RAM, device (readable), unmapped
      val vpns = ArrayBuffer[Long]()
      for (root <- roots.take(3); k <- 0 until 12) {
        val vpn = (rnd(1024).toLong << 10) | rnd(1024)
        vpns += vpn
        val a1 = l1Addr(root, vpn)
        rnd(6) match {
          case 0 => d.put(a1, P(rnd(0x3fffff), v = bit(), r = bit(), w = bit(), x = bit(), g = bit()))   // anything
          case 1 => d.put(a1, leaf(((rnd(0x3ff) + 1).toLong << 10) | (if (rnd(4) == 0) rnd(1024) else 0), g = bit(3),
              r = bit(), w = bit(), x = bit(), a = bit(), d = bit()))
          case _ =>
            val pt = if (rnd(8) == 0) 0x200000L + rnd(16) else if (rnd(8) == 0) 0xf0020L + rnd(16) else 0x400L + rnd(0x1000)
            d.put(a1, ptr(pt, g = rnd(4) == 0))
            val p0 = if (rnd(6) == 0) P(rnd(0x3fffff), v = bit(), r = bit(), w = bit(), x = bit())
              else leaf(rnd(0x3fffff), g = rnd(4) == 0, r = bit(), w = bit(), x = bit(), u = bit(2), a = bit(), d = bit())
            d.put(l0Addr(pt, vpn), p0)
        }
      }
      val deniedSet = mutable.Set[Long](); d.denied = a => deniedSet(a)
      d.memReady = c => (c / 3) % 3 != 1; d.iReady = c => (c / 4) % 3 != 2; d.dReady = c => (c / 5) % 4 != 3
      d.iFlushReady = c => c % 7 != 3; d.dFlushReady = c => c % 5 != 1
      // In flight: requests accepted, not yet answered: (side, vpn, ctx, acceptCycle).
      val pend = ArrayBuffer[(Char, Long, Ctx, Int)]()
      var bad = List.empty[String]; var done = 0; var retries = 0; var k = 0
      var statuses = Map.empty[Int, Int].withDefaultValue(0)
      while (done < 3000 && k < 200000) {
        k += 1
        d.memLatency = 1 + rnd(5)
        if (pend.isEmpty && d.memQ.isEmpty && d.iQ.isEmpty && d.dQ.isEmpty && rnd(10) == 0) { deniedSet.clear(); if (rnd(2) == 0) deniedSet += l1Addr(roots(rnd(3)), vpns(rnd(vpns.size))) }
        def newReq(): (Long, Ctx) = {
          val root = roots(if (rnd(20) == 0) 3 else rnd(3))
          val vpn = if (rnd(10) == 0) rnd(1 << 20).toLong else vpns(rnd(vpns.size))
          (vpn, Ctx(asid = rnd(512), root = root, priv = rnd(2), dataPriv = rnd(4)))
        }
        if (d.iQ.isEmpty && rnd(3) == 0) d.iQ.enqueue(newReq())
        if (d.dQ.isEmpty && rnd(3) == 0) d.dQ.enqueue(newReq())
        d.sfence = d.sfence || rnd(50) == 0
        val i0 = d.iFires.size; val d0 = d.dFires.size; val io0 = d.iOuts.size; val do0 = d.dOuts.size
        d.cycle()
        if (d.sfFire) d.sfence = false
        if (d.sfFire) for (j <- pend.indices) pend(j) = pend(j).copy(_4 = -1)   // overlapped: Retry
        d.iFires.drop(i0).foreach(x => pend += (('I', x._2, x._3, x._1)))
        d.dFires.drop(d0).foreach(x => pend += (('D', x._2, x._3, x._1)))
        val outs = d.iOuts.drop(io0).map(x => ('I', x._2)) ++ d.dOuts.drop(do0).map(x => ('D', x._2))
        for ((side, r) <- outs) pend.find(_._1 == side) match {
          case None => bad ::= s"unexpected result on $side: $r"
          case Some(p) =>
            pend -= p; done += 1; statuses += (r.status -> (statuses(r.status) + 1))
            if (p._4 < 0) { retries += 1; if (r.status != RETRY) bad ::= s"walk overlapping an SFENCE answered $r" }
            else {
              val (st, e, _) = d.ref(p._2, p._3)
              if (r.status != st || r.vpn != p._2 || (st == LEAF && !e.contains(r.ent))) bad ::= s"$r expected $st $e for $p"
            }
        }
        if (pend.size > 1) bad ::= s"more than one walk in flight: $pend"
      }
      Seq(
        chk(bad.isEmpty, s"every result equals the reference Sv32 walk or Retry ($done walks)", bad.reverse.take(3).mkString("; ")),
        chk(done >= 3000, "liveness: every accepted walk answered", s"$done"),
        chk(d.iFlushFires == d.sfFires && d.dFlushFires == d.sfFires && d.sfFires.size > 30, "every SFENCE flushed both TLBs atomically",
          s"${d.sfFires.size} ${d.iFlushFires.size} ${d.dFlushFires.size}"),
        chk(d.memOffers.forall(_._2 % 4 == 0), "every PtwMemReq is 4-byte aligned", ""),
        chk(Seq(LEAF, WPF, WAF).forall(statuses(_) > 100) && retries > 30 && d.iOuts.size > 1000 && d.dOuts.size > 1000,
          "coverage: Leaf, PageFault, AccessFault, Retry, both requesters", s"$statuses $retries ${d.iOuts.size} ${d.dOuts.size}"))
    }
  }

  val all: Seq[SpecTest] = Seq(arbitrate, addresses, structure, global, entry, pmaCheck, denied, memBackpressure, outputs,
    flushIdle, flushIssue, flushWait, flushBetween, flushFault, flushBlocked, flushPriority, contextCapture, negative, random)
}
