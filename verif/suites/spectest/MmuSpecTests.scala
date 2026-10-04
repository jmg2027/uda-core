package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.backend.design.shared.TranslationContext
import udacore.core.design.modules.{DataTlb, InstructionTlb, PageTableWalker}
import udacore.core.design.shared._

/** Test-only harness: the real InstructionTlb and DataTlb sharing the real PageTableWalker (as in
  * CoreTop). The TLB request/answer sides, the PTW physical PTE port, the SFENCE token and the
  * TranslationContext are ports; walk requests/results and the flush tokens are internal and mirrored
  * on monitor outputs. Building it runs firtool over the three. */
class MmuHarness(val cp: CoreParams) extends Module {
  val itlb = Module(new InstructionTlb(cp))
  val dtlb = Module(new DataTlb(cp))
  val ptw  = Module(new PageTableWalker(cp))
  private def port[T <: Data](x: T): T = IO(chiselTypeOf(x))
  val iReq    = port(itlb.io.iTlbReqIn)
  val iOut    = port(itlb.io.iCacheTranslationOut)
  val dReq    = port(dtlb.io.dtlbReqIn)
  val dLoad   = port(dtlb.io.dCacheTranslationOut)
  val dStore  = port(dtlb.io.dtlbStoreRespOut)
  val dRefill = port(dtlb.io.dtlbRefillOut)
  val memReq  = port(ptw.io.ptwMemReqOut)
  val memResp = port(ptw.io.ptwMemRespIn)
  val sfence  = port(ptw.io.sfenceVmaIn)
  val ctx     = IO(Input(new TranslationContext))
  val mon = IO(Output(new Bundle {
    val iWalk = Bool(); val dWalk = Bool(); val iResult = Bool(); val dResult = Bool()
    val resultStatus = UInt(WalkStatus.width.W); val iFlush = Bool(); val dFlush = Bool()
  }))
  itlb.io.iTlbReqIn <> iReq; iOut <> itlb.io.iCacheTranslationOut
  dtlb.io.dtlbReqIn <> dReq; dLoad <> dtlb.io.dCacheTranslationOut; dStore <> dtlb.io.dtlbStoreRespOut
  dRefill <> dtlb.io.dtlbRefillOut
  itlb.io.translationContextIn := ctx; dtlb.io.translationContextIn := ctx
  ptw.io.itlbWalkReqIn <> itlb.io.itlbWalkReqOut; itlb.io.itlbWalkRespIn <> ptw.io.itlbWalkRespOut
  ptw.io.dtlbWalkReqIn <> dtlb.io.dtlbWalkReqOut; dtlb.io.dtlbWalkRespIn <> ptw.io.dtlbWalkRespOut
  itlb.io.itlbFlushIn <> ptw.io.itlbFlushOut; dtlb.io.dtlbFlushIn <> ptw.io.dtlbFlushOut
  memReq <> ptw.io.ptwMemReqOut; ptw.io.ptwMemRespIn <> memResp; ptw.io.sfenceVmaIn <> sfence
  mon.iWalk := ptw.io.itlbWalkReqIn.fire; mon.dWalk := ptw.io.dtlbWalkReqIn.fire
  mon.iResult := ptw.io.itlbWalkRespOut.fire; mon.dResult := ptw.io.dtlbWalkRespOut.fire
  mon.resultStatus := Mux(ptw.io.itlbWalkRespOut.valid, ptw.io.itlbWalkRespOut.bits.status, ptw.io.dtlbWalkRespOut.bits.status)
  mon.iFlush := ptw.io.itlbFlushOut.fire; mon.dFlush := ptw.io.dtlbFlushOut.fire
}

/** Integration SpecTests of the MMU on real RTL: ITLB + DTLB + PTW with a synthetic physical PTE memory. */
object MmuSpecTests {
  import PageTableWalkerSpecTests.{P, ptr, leaf, fields, l1Addr, l0Addr, LEAF, WPF, WAF, RETRY}
  val cp = CoreParams()
  val HIT = 0; val MISS = 1; val PF = 2; val AF = 3
  val S = 1; val ROOT = 0x100L
  def rid(store: Boolean, idx: Int): Int = ((if (store) 1 else 0) << 5) | (idx << 2)

  case class Tr(reqId: Int, status: Int, paddr: Long, cacheable: Boolean)

  class Drv(val dut: MmuHarness) {
    var cyc = 0
    val mem = mutable.Map[Long, Long]().withDefaultValue(0L)
    var memLatency = 2
    var asid = 1; var root = ROOT
    val iQ = Queue[(Int, Long)](); val dQ = Queue[(Int, Long, Boolean)]()
    var sfence = false
    val memQ = Queue[(Int, Long)]()
    val iOuts = ArrayBuffer[(Int, Tr)](); val dOuts = ArrayBuffer[(Int, Tr, Boolean)]()
    val notices = ArrayBuffer[(Int, Long, Int)]()
    val iWalks, dWalks, iResults, dResults, sfFires, iFl, dFl = ArrayBuffer[Int]()
    val results = ArrayBuffer[(Int, Char, Int)]()
    val memReads = ArrayBuffer[(Int, Long)]()
    val iReadyLow = ArrayBuffer[Int]()
    var sfFire = false
    private def b(x: Bool) = x.peek().litToBoolean
    private def l(x: UInt) = x.peek().litValue.toLong
    private def tr(t: Translation): Tr = Tr(l(t.reqId).toInt, l(t.status).toInt, l(t.paddr), b(t.cacheable))
    def cycle(): Unit = {
      val h = dut
      h.iReq.valid.poke(iQ.nonEmpty.B); iQ.headOption.foreach { case (id, va) =>
        h.iReq.bits.reqId.poke(id.U); h.iReq.bits.vaddr.poke(va.U); h.iReq.bits.access.poke(0.U) }
      h.dReq.valid.poke(dQ.nonEmpty.B); dQ.headOption.foreach { case (id, va, st) =>
        h.dReq.bits.reqId.poke(id.U); h.dReq.bits.vaddr.poke(va.U); h.dReq.bits.access.poke((if (st) 2 else 1).U) }
      h.iOut.ready.poke(true.B); h.dLoad.ready.poke(true.B); h.dStore.ready.poke(true.B); h.dRefill.ready.poke(true.B)
      val c = h.ctx
      c.satpMode.poke(true.B); c.asid.poke(asid.U); c.rootPpn.poke(root.U); c.priv.poke(S.U); c.dataPriv.poke(S.U)
      c.sum.poke(false.B); c.mxr.poke(false.B)
      h.sfence.valid.poke(sfence.B); h.sfence.bits.vaddr.poke(0.U); h.sfence.bits.vaddrValid.poke(false.B)
      h.sfence.bits.asid.poke(0.U); h.sfence.bits.asidValid.poke(false.B)
      h.memReq.ready.poke(true.B)
      val mv = memQ.nonEmpty && memQ.head._1 <= cyc
      h.memResp.valid.poke(mv.B)
      memQ.headOption.foreach { case (_, w) =>
        val p = fields(w); val t = h.memResp.bits.pte
        t.ppn1.poke((p.ppn >> 10).U); t.ppn0.poke((p.ppn & 0x3ff).U); t.rsw.poke(0.U); t.d.poke(p.d.B); t.a.poke(p.a.B)
        t.g.poke(p.g.B); t.u.poke(p.u.B); t.x.poke(p.x.B); t.w.poke(p.w.B); t.r.poke(p.r.B); t.v.poke(p.v.B)
        h.memResp.bits.accessFault.poke(false.B)
      }
      val iFire = iQ.nonEmpty && b(h.iReq.ready); val dFire = dQ.nonEmpty && b(h.dReq.ready)
      if (iQ.nonEmpty && !iFire) iReadyLow += cyc
      sfFire = sfence && b(h.sfence.ready)
      if (b(h.iOut.valid)) iOuts += ((cyc, tr(h.iOut.bits)))
      if (b(h.dLoad.valid)) dOuts += ((cyc, tr(h.dLoad.bits), false))
      if (b(h.dStore.valid)) dOuts += ((cyc, tr(h.dStore.bits), true))
      if (b(h.dRefill.valid)) notices += ((cyc, l(h.dRefill.bits.vpn), l(h.dRefill.bits.status).toInt))
      if (b(h.memReq.valid)) { val a = l(h.memReq.bits.paddr); memReads += ((cyc, a)); memQ.enqueue((cyc + memLatency, mem(a))) }
      if (b(h.mon.iWalk)) iWalks += cyc
      if (b(h.mon.dWalk)) dWalks += cyc
      if (b(h.mon.iResult)) { iResults += cyc; results += ((cyc, 'I', l(h.mon.resultStatus).toInt)) }
      if (b(h.mon.dResult)) { dResults += cyc; results += ((cyc, 'D', l(h.mon.resultStatus).toInt)) }
      if (b(h.mon.iFlush)) iFl += cyc
      if (b(h.mon.dFlush)) dFl += cyc
      if (sfFire) sfFires += cyc
      if (iFire) iQ.dequeue()
      if (dFire) dQ.dequeue()
      if (mv && b(h.memResp.ready)) memQ.dequeue()
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def until(n: Int)(p: => Boolean): Boolean = { var k = 0; while (!p && k < n) { cycle(); k += 1 }; p }
    def fetch(va: Long, id: Int = 1): Option[Tr] = { val n0 = iOuts.size; iQ.enqueue((id, va)); until(200)(iOuts.size > n0); iOuts.lift(n0).map(_._2) }
    def data(va: Long, st: Boolean, id: Int): Option[Tr] = {
      val n0 = dOuts.size; dQ.enqueue((rid(st, id), va, st)); until(200)(dOuts.size > n0); dOuts.lift(n0).map(_._2)
    }
    /** LSQ behavior: on Miss, wait for a notice after the request and retry. */
    def dataFinal(va: Long, st: Boolean, id: Int): Option[Tr] = {
      var k = 0; var n0 = notices.size; var t = data(va, st, id)
      while (t.exists(_.status == MISS) && k < 6) { until(200)(notices.size > n0); n0 = notices.size; t = data(va, st, id); k += 1 }
      t
    }
    def doSfence(): Unit = { sfence = true; cycle(); until(30)(sfFire); sfence = false }
    def map4k(va: Long, pt: Long, ppn: Long, g1: Boolean = false, g: Boolean = false, r: Long = root): Unit = {
      val vpn = va >>> 12
      mem(l1Addr(r, vpn)) = ptr(pt, g1).word; mem(l0Addr(pt, vpn)) = leaf(ppn, x = true, g = g).word
    }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new MmuHarness(cp)) { dut => val d = new Drv(dut); d.cycle(); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  // ---- 1-5: concurrent misses, routing, blocking vs non-blocking, both levels ---------------------------
  val concurrent = new SpecTest("mmu.concurrent", Seq("funcPtwArbitrate", "funcSv32Walk")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.map4k(0x00400000L, 0x200, 0x300)                                  // fetch page: two-level 4K
      d.mem(l1Addr(ROOT, 0x00800L)) = leaf(0x1400, x = true).word        // data page: 4M superpage
      d.map4k(0x00c00000L, 0x201, 0x301)                                  // resident data page
      d.dataFinal(0x00c00010L, false, 0)
      d.memLatency = 10
      val c0 = d.cyc
      d.iQ.enqueue((1, 0x00400010L)); d.iQ.enqueue((2, 0x00400014L)); d.dQ.enqueue((rid(false, 1), 0x00800020L, false))
      d.until(5)(d.iWalks.exists(_ >= c0) && d.dWalks.exists(_ >= c0))
      // While the PTW serves the walks, DTLB hits keep answering; the ITLB stream is held.
      val hits = (0 until 3).map(k => d.data(0x00c00000L + 4 * k, k % 2 == 1, 2 + k))
      d.until(200)(d.iOuts.exists(_._1 > c0))
      val i = d.iOuts.find(_._1 > c0).map(_._2)
      val dd = d.dataFinal(0x00800020L, false, 1)
      val walkOrder = (d.iWalks.filter(_ >= c0).map(c => (c, 'I')) ++ d.dWalks.filter(_ >= c0).map(c => (c, 'D'))).sortBy(_._1)
      Seq(
        chk(walkOrder.map(_._2).distinct.size == 2 && d.results.filter(_._1 >= c0).map(_._2).take(2).toSet == Set('I', 'D'),
          "concurrent ITLB and DTLB misses are both walked and each result returns to its TLB", s"$walkOrder ${d.results}"),
        chk(i.contains(Tr(1, HIT, 0x300010L, true)), "the ITLB request resolves after its two-level 4K walk", s"$i"),
        chk(dd.contains(Tr(rid(false, 1), HIT, (0x5L << 22) | 0x20L, true)), "the DTLB retry hits the installed superpage", s"$dd"),
        chk(hits.forall(_.exists(_.status == HIT)), "DTLB hits keep answering while the PTW is busy", s"$hits"),
        chk(d.iReadyLow.count(_ > c0) > 10 && d.iOuts.find(_._2.reqId == 2).exists(_._2 == Tr(2, HIT, 0x300014L, true)),
          "the ITLB holds its stream while its walk is outstanding, then the next fetch hits", s"${d.iReadyLow} ${d.iOuts}"))
    }
  }

  // ---- 6: non-leaf G reaches both TLBs ------------------------------------------------------------------
  val globalBoth = new SpecTest("mmu.global", Seq("funcSv32Walk")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.map4k(0x00400000L, 0x200, 0x300, g1 = true); d.map4k(0x00c00000L, 0x201, 0x301, g1 = true)
      d.fetch(0x00400000L); d.dataFinal(0x00c00000L, false, 0)
      val w0 = d.iWalks.size + d.dWalks.size
      d.asid = 2
      val i = d.fetch(0x00400008L, 2); val dd = d.data(0x00c00008L, true, 1)
      Seq(chk(i.exists(_.status == HIT) && dd.exists(_.status == HIT) && d.iWalks.size + d.dWalks.size == w0,
        "a level-1 G pointer makes both refills global: another ASID hits without a walk", s"$i $dd"))
    }
  }

  // ---- 7-9: SFENCE ------------------------------------------------------------------------------------
  val sfence = new SpecTest("mmu.sfence", Seq("funcPtwFlush", "propSfenceFlushesAll")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.map4k(0x00400000L, 0x200, 0x300); d.map4k(0x00c00000L, 0x201, 0x301)
      d.fetch(0x00400000L); d.dataFinal(0x00c00000L, false, 0)
      // Remap both pages, then SFENCE: both TLBs drop the old translations and re-walk.
      d.map4k(0x00400000L, 0x202, 0x350); d.map4k(0x00c00000L, 0x203, 0x351)
      val stale = (d.fetch(0x00400004L, 2), d.data(0x00c00004L, false, 1))
      d.doSfence()
      val after = (d.fetch(0x00400004L, 3), d.dataFinal(0x00c00004L, false, 2))
      Seq(
        chk(stale._1.exists(_.paddr == 0x300004L) && stale._2.exists(_.paddr == 0x301004L),
          "before the SFENCE both TLBs still hold the old translations", s"$stale"),
        chk(d.sfFires.size == 1 && d.iFl == d.sfFires && d.dFl == d.sfFires, "the SFENCE flushed both TLBs in one cycle",
          s"${d.sfFires} ${d.iFl} ${d.dFl}"),
        chk(after._1.contains(Tr(3, HIT, 0x350004L, true)) && after._2.contains(Tr(rid(false, 2), HIT, 0x351004L, true)),
          "after the SFENCE both re-walk and see the new mappings", s"$after"))
    }
  }

  val sfenceOverlap = new SpecTest("mmu.sfenceOverlap", Seq("funcPtwFlush", "propSfenceFlushesAll")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.map4k(0x00400000L, 0x200, 0x300); d.map4k(0x00c00000L, 0x201, 0x301)
      d.memLatency = 12
      // A DTLB walk overlapped by an SFENCE (with a remap): Retry notice, nothing installed.
      val m = d.data(0x00c00000L, false, 0)
      d.until(20)(d.memReads.nonEmpty)
      d.map4k(0x00c00000L, 0x205, 0x355); d.doSfence()
      d.until(100)(d.notices.nonEmpty)
      val n = d.notices.headOption
      d.memLatency = 2
      val dd = d.dataFinal(0x00c00000L, false, 1)
      // An ITLB walk overlapped by an SFENCE: Retry, the ITLB re-walks on its own and resolves with the new mapping.
      d.memLatency = 12; val c1 = d.cyc
      d.iQ.enqueue((5, 0x00400000L)); d.until(20)(d.memReads.exists(_._1 >= c1))
      d.map4k(0x00400000L, 0x206, 0x356); d.doSfence(); d.memLatency = 2
      d.until(200)(d.iOuts.exists(_._1 > c1))
      val i = d.iOuts.find(_._1 > c1).map(_._2)
      Seq(
        chk(m.exists(_.status == MISS) && n.exists(x => x._2 == 0x00c00L && x._3 == RETRY),
          "the overlapped DTLB walk ends in a Retry notice", s"$m $n"),
        chk(dd.contains(Tr(rid(false, 1), HIT, 0x355000L, true)), "the DTLB installed nothing stale: the retry walks the new mapping", s"$dd"),
        chk(d.results.exists(r => r._2 == 'I' && r._3 == RETRY) && i.contains(Tr(5, HIT, 0x356000L, true)),
          "the overlapped ITLB walk returns Retry; the ITLB re-walks and resolves with the new mapping", s"${d.results} $i"))
    }
  }

  // ---- 10: PMA-illegal PTE address --------------------------------------------------------------------
  val pmaFault = new SpecTest("mmu.pmaFault", Seq("funcPtwPhysicalAccess", "propWalkFaultTyping")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.mem(l1Addr(ROOT, 0x00400L)) = ptr(0x200000L).word     // level-0 table above 4 GiB: unmapped
      d.mem(l1Addr(ROOT, 0x00c00L)) = ptr(0x200001L).word
      val i = d.fetch(0x00400000L); val dd = d.dataFinal(0x00c00000L, true, 0)
      Seq(
        chk(i.contains(Tr(1, AF, 0, false)) && dd.contains(Tr(rid(true, 0), AF, 0, false)),
          "a PMA-illegal PTE address is a walk AccessFault: instruction / store access fault", s"$i $dd"),
        chk(d.memReads.size == 2, "only the two level-1 PTEs were read", s"${d.memReads}"))
    }
  }

  val all: Seq[SpecTest] = Seq(concurrent, globalBoth, sfence, sfenceOverlap, pmaFault)
}
