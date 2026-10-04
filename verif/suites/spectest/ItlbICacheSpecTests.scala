package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.backend.design.shared.TranslationContext
import udacore.core.design.modules.{InstructionCache, InstructionTlb}
import udacore.core.design.shared._

/** Test-only harness: the real InstructionTlb feeding the real InstructionCache (as in CoreTop). The
  * FetchUnit side (ITlbReq + ICacheReq), the PTW side, the InstMem side, the flush and invalidate
  * tokens and the TranslationContext are ports. Building it runs firtool over the pair. */
class ItlbICacheHarness(val cp: CoreParams) extends Module {
  val genW = cp.fetch.fetchGenWidth
  val io = IO(new Bundle {
    val tlbReq   = Flipped(Decoupled(new TranslateReq(genW, cp.vAddrWidth)))
    val icReq    = Flipped(Decoupled(new ICacheReq(genW, cp.vAddrWidth)))
    val resp     = Decoupled(new ICacheResp(genW, cp.fetch.fetchWidth))
    val walkReq  = Decoupled(new WalkReq)
    val walkResp = Flipped(Decoupled(new WalkResp))
    val flush    = Flipped(Decoupled(new TlbFlush(cp.vAddrWidth)))
    val inv      = Flipped(Decoupled(new CacheMaintenance))
    val ctx      = Input(new TranslationContext)
    val memReq   = Decoupled(new InstMemReq(cp.pAddrWidth, cp.instBusParams.sizeBits))
    val memResp  = Flipped(Decoupled(new InstMemResp(cp.dataWidth)))
  })
  val tlb = Module(new InstructionTlb(cp))
  val ic  = Module(new InstructionCache(cp))
  tlb.io.iTlbReqIn <> io.tlbReq
  tlb.io.translationContextIn := io.ctx
  tlb.io.itlbFlushIn <> io.flush
  io.walkReq <> tlb.io.itlbWalkReqOut
  tlb.io.itlbWalkRespIn <> io.walkResp
  ic.io.iCacheReqIn <> io.icReq
  ic.io.iCacheTranslationIn <> tlb.io.iCacheTranslationOut
  ic.io.iCacheInvalidateIn <> io.inv
  io.resp <> ic.io.iCacheRespOut
  io.memReq <> ic.io.instMemReqOut
  ic.io.instMemRespIn <> io.memResp
}

/** Integration SpecTests of the fetch translation + I-cache path on real RTL. */
object ItlbICacheSpecTests {
  import InstructionTlbSpecTests.{Ctx, Pte, PageTable, sv32S, U, S, M, LEAF, WPF}
  import InstructionCacheSpecTests.{Resp, FNone, FPage, FAccess, word, block}
  val cp = CoreParams()

  class Drv(val dut: ItlbICacheHarness) {
    val io = dut.io
    val pt = new PageTable
    var cyc = 0
    var ctx = Ctx()
    val fetchQ = Queue[(Int, Long)]()
    var flush = false
    val walkQ = Queue[(Int, (Long, Int, Pte, Int))]()   // (ready cycle, (vpn, status, pte, asid))
    val beatQ = Queue[(Long, Boolean)](); var beatsLeft = 0
    val resps = ArrayBuffer[Resp](); val walks = ArrayBuffer[Long](); val memReqs = ArrayBuffer[(Long, Int)]()
    var flushFire = false
    def cycle(): Unit = {
      // FetchUnit: ITlbReq and ICacheReq leave together (both offered only when both can transfer).
      val both = fetchQ.nonEmpty && io.tlbReq.ready.peek().litToBoolean && io.icReq.ready.peek().litToBoolean
      io.tlbReq.valid.poke(both.B); io.icReq.valid.poke(both.B)
      fetchQ.headOption.foreach { case (id, va) =>
        io.tlbReq.bits.reqId.poke(id.U); io.tlbReq.bits.vaddr.poke(va.U); io.tlbReq.bits.access.poke(0.U)
        io.icReq.bits.reqId.poke(id.U); io.icReq.bits.vaddr.poke(va.U)
      }
      val c = io.ctx
      c.satpMode.poke(ctx.satp.B); c.asid.poke(ctx.asid.U); c.rootPpn.poke(ctx.root.U); c.priv.poke(ctx.priv.U)
      c.dataPriv.poke(ctx.priv.U); c.sum.poke(ctx.sum.B); c.mxr.poke(ctx.mxr.B)
      io.flush.valid.poke(flush.B); io.flush.bits.vaddr.poke(0.U); io.flush.bits.vaddrValid.poke(false.B)
      io.flush.bits.asid.poke(0.U); io.flush.bits.asidValid.poke(false.B)
      io.inv.valid.poke(false.B); io.inv.bits.op.poke(0.U)
      io.walkReq.ready.poke(true.B)
      val wv = walkQ.nonEmpty && walkQ.head._1 <= cyc
      io.walkResp.valid.poke(wv.B)
      walkQ.headOption.foreach { case (_, (vpn, st, p, as)) =>
        val b = io.walkResp.bits; val e = b.entry
        b.vpn.poke(vpn.U); b.status.poke(st.U); e.valid.poke(true.B); e.vpn.poke(vpn.U); e.superpage.poke(p.superpage.B)
        e.ppn.poke(p.ppn.U); e.asid.poke(as.U); e.global.poke(p.global.B); e.r.poke(p.r.B); e.w.poke(p.w.B); e.x.poke(p.x.B)
        e.u.poke(p.u.B); e.a.poke(p.a.B); e.d.poke(p.d.B)
        e.pma.cacheable.poke(false.B); e.pma.executable.poke(false.B); e.pma.readable.poke(false.B); e.pma.writable.poke(false.B)
      }
      io.resp.ready.poke(true.B); io.memReq.ready.poke(true.B)
      val bv = beatQ.nonEmpty
      io.memResp.valid.poke(bv.B)
      beatQ.headOption.foreach { case (dat, den) =>
        io.memResp.bits.data.poke(dat.U); io.memResp.bits.denied.poke(den.B); io.memResp.bits.last.poke((beatsLeft == 1).B)
      }
      flushFire = flush && io.flush.ready.peek().litToBoolean
      if (io.walkReq.valid.peek().litToBoolean) {
        val vpn = io.walkReq.bits.vpn.peek().litValue.toLong; val as = io.walkReq.bits.context.asid.peek().litValue.toInt
        walks += vpn
        walkQ.enqueue((cyc + 3, pt.find(as, vpn) match { case Some(p) => (vpn, LEAF, p, as); case None => (vpn, WPF, Pte(0), as) }))
      }
      if (wv && io.walkResp.ready.peek().litToBoolean) walkQ.dequeue()
      if (io.resp.valid.peek().litToBoolean) {
        val b = io.resp.bits
        resps += Resp(b.reqId.peek().litValue.toInt, (0 until cp.fetch.fetchWidth).map(i => b.data(i).peek().litValue.toLong),
          b.fault.peek().litValue.toInt)
      }
      if (io.memReq.valid.peek().litToBoolean) {
        val pa = io.memReq.bits.paddr.peek().litValue.toLong; val sz = io.memReq.bits.size.peek().litValue.toInt
        memReqs += ((pa, sz)); val n = (1 << sz) / 4
        (0 until n).foreach(i => beatQ.enqueue((word(pa + 4L * i), false))); if (beatsLeft == 0) beatsLeft = n
      }
      if (bv && io.memResp.ready.peek().litToBoolean) { beatQ.dequeue(); beatsLeft -= 1 }
      if (both) fetchQ.dequeue()
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def fetch(va: Long, id: Int): Resp = { fetchQ.enqueue((id, va)); var k = 0; val n0 = resps.size
      while (resps.size == n0 && k < 300) { cycle(); k += 1 }; resps.last }
    def doFlush(): Unit = { flush = true; var k = 0; cycle(); while (!flushFire && k < 20) { cycle(); k += 1 }; flush = false }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new ItlbICacheHarness(cp)) { dut => val d = new Drv(dut); d.cycle(); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  val bare = new SpecTest("itic.bare", Seq("funcItlbTranslate", "funcICacheMissFill", "funcPmaCheck")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val a = d.fetch(0x80001014L, 1); val m0 = d.memReqs.size
      val b = d.fetch(0xf0000010L, 2)
      Seq(
        chk(a == Resp(1, block(0x80001010L), FNone) && d.memReqs.toSeq == Seq((0x80001000L, 6)) && d.walks.isEmpty,
          "a Bare cold fetch fills the I-cache from pa = va", s"$a ${d.memReqs}"),
        chk(b.fault == FAccess && d.memReqs.size == m0, "a PMA access fault (device window) makes no InstMem request", s"$b ${d.memReqs}"))
    }
  }

  val sv32 = new SpecTest("itic.sv32", Seq("funcItlbMissWalk", "funcItlbRefill", "funcICacheViptLookup")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S
      val va = 0x00403040L; val pa = 0x2a0005040L & 0xffffffffL // RAM page 0xa0005 keeps va[11:0]
      d.pt.map(1, va >> 12, Pte(pa >> 12))
      d.pt.map(1, 0x01c00000L >> 12, Pte(0x12L << 10, superpage = true))
      val a = d.fetch(va, 1); val w1 = d.walks.size; val m1 = d.memReqs.size
      val b = d.fetch(va + 0x10, 2); val w2 = d.walks.size; val m2 = d.memReqs.size
      val c = d.fetch(0x00500000L, 3); val m3 = d.memReqs.size          // unmapped: PageFault
      val sp = d.fetch(0x01c35670L, 4)
      Seq(
        chk(a == Resp(1, block(pa), FNone) && w1 == 1 && d.memReqs.head == ((pa & ~63L, 6)),
          "an Sv32 miss walks, translates and fills the physical line", s"$a ${d.walks} ${d.memReqs}"),
        chk(b == Resp(2, block(pa + 0x10), FNone) && w2 == 1 && m2 == m1,
          "the second fetch is a TLB hit and an I-cache hit (no walk, no InstMem request)", s"$b ${d.walks} ${d.memReqs}"),
        chk(c.fault == FPage && m3 == m1, "an instruction page fault makes no InstMem request", s"$c ${d.memReqs}"),
        chk(sp == Resp(4, block(0x04835670L), FNone) && d.memReqs.last == ((0x04835640L, 6)),
          "a superpage translation fetches the right physical line", s"$sp ${d.memReqs}"))
    }
  }

  val sfence = new SpecTest("itic.sfence", Seq("funcItlbFlush", "funcICacheViptLookup")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.ctx = sv32S; val va = 0x00403040L; val pa = 0xa0005040L
      d.pt.map(1, va >> 12, Pte(pa >> 12))
      d.fetch(va, 1); val w = d.walks.size; val m = d.memReqs.size
      d.doFlush()
      val b = d.fetch(va, 2)
      Seq(chk(b == Resp(2, block(pa), FNone) && d.walks.size == w + 1 && d.memReqs.size == m,
        "SFENCE flushes the ITLB (the VA walks again) but not the physically tagged I-cache (it still hits)",
        s"$b ${d.walks} ${d.memReqs}"))
    }
  }

  val all: Seq[SpecTest] = Seq(bare, sv32, sfence)
}
