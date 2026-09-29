package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.common.tilelink.TLBundle
import udacore.core.design.modules.{InstBusAdapter, InstructionCache}
import udacore.core.design.shared._

/** Test-only harness: the real InstructionCache wired to the real InstBusAdapter as in CoreTop; the
  * ICache request/translation/answer/invalidate ports and the TileLink instBus are ports. Building it
  * runs firtool over the connected pair (combinational-cycle check). */
class ICacheBusHarness(val cp: CoreParams) extends Module {
  val io = IO(new Bundle {
    val req    = Flipped(Decoupled(new ICacheReq(cp.fetch.fetchGenWidth, cp.vAddrWidth)))
    val trans  = Flipped(Decoupled(new Translation(cp.fetch.fetchGenWidth, cp.pAddrWidth)))
    val resp   = Decoupled(new ICacheResp(cp.fetch.fetchGenWidth, cp.fetch.fetchWidth))
    val inv    = Flipped(Decoupled(new CacheMaintenance))
    val bus    = new TLBundle(cp.instBusParams)
  })
  val ic  = Module(new InstructionCache(cp))
  val iba = Module(new InstBusAdapter(cp))
  ic.io.iCacheReqIn <> io.req
  ic.io.iCacheTranslationIn <> io.trans
  io.resp <> ic.io.iCacheRespOut
  ic.io.iCacheInvalidateIn <> io.inv
  iba.io.instMemReqIn <> ic.io.instMemReqOut
  ic.io.instMemRespIn <> iba.io.instMemRespOut
  io.bus.a <> iba.io.instBus.a
  iba.io.instBus.d <> io.bus.d
}

/** Integration SpecTests of the instruction-side memory path on real RTL, against a TileLink memory. */
object ICacheBusSpecTests {
  import InstructionCacheSpecTests.{Tr, Resp, HIT, FNone, FAccess, word, block, VA, PA}
  val cp = CoreParams()

  case class Get(opcode: Int, size: Int, address: Long, source: Int)

  class Drv(val dut: ICacheBusHarness) {
    val io = dut.io
    var cyc = 0
    val reqQ = Queue[(Long, Tr)](); val transQ = Queue[(Int, Tr)]()
    var inv = false
    var deny: Long => Boolean = _ => false
    var dStall: Int => Boolean = _ => false
    val dq = Queue[(Int, Long, Boolean)]() // (size, data, denied)
    val gets = ArrayBuffer[(Int, Get)](); val resps = ArrayBuffer[Resp](); val dFires = ArrayBuffer[Int]()
    var invFire = false
    def cycle(): Unit = {
      io.req.valid.poke(reqQ.nonEmpty.B)
      reqQ.headOption.foreach { case (va, t) => io.req.bits.vaddr.poke(va.U); io.req.bits.reqId.poke(t.reqId.U) }
      val tv = transQ.nonEmpty && transQ.head._1 <= cyc
      io.trans.valid.poke(tv.B)
      transQ.headOption.foreach { case (_, t) =>
        io.trans.bits.reqId.poke(t.reqId.U); io.trans.bits.status.poke(t.status.U); io.trans.bits.paddr.poke(t.paddr.U)
        io.trans.bits.cacheable.poke(t.cacheable.B)
      }
      io.inv.valid.poke(inv.B); io.inv.bits.op.poke(0.U)
      io.resp.ready.poke(true.B)
      io.bus.a.ready.poke(true.B)
      val dv = dq.nonEmpty && !dStall(cyc)
      io.bus.d.valid.poke(dv.B)
      dq.headOption.foreach { case (sz, dat, den) =>
        val b = io.bus.d.bits
        b.opcode.poke(1.U); b.param.poke(0.U); b.size.poke(sz.U); b.source.poke(0.U); b.sink.poke(0.U)
        b.denied.poke(den.B); b.data.poke(dat.U); b.corrupt.poke(false.B)
      }
      val reqFire = reqQ.nonEmpty && io.req.ready.peek().litToBoolean
      val trFire = tv && io.trans.ready.peek().litToBoolean
      invFire = inv && io.inv.ready.peek().litToBoolean
      if (io.resp.valid.peek().litToBoolean) {
        val b = io.resp.bits
        resps += Resp(b.reqId.peek().litValue.toInt, (0 until cp.fetch.fetchWidth).map(i => b.data(i).peek().litValue.toLong),
          b.fault.peek().litValue.toInt)
      }
      if (io.bus.a.valid.peek().litToBoolean) {
        val a = io.bus.a.bits
        val g = Get(a.opcode.peek().litValue.toInt, a.size.peek().litValue.toInt, a.address.peek().litValue.toLong,
          a.source.peek().litValue.toInt)
        gets += ((cyc, g))
        (0 until (1 << g.size) / 4).foreach(i => dq.enqueue((g.size, word(g.address + 4L * i), deny(g.address) && i == 2)))
      }
      if (dv && io.bus.d.ready.peek().litToBoolean) { dq.dequeue(); dFires += cyc }
      if (reqFire) { val (_, t) = reqQ.dequeue(); transQ.enqueue((cyc + 1, t)) }
      if (trFire) transQ.dequeue()
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def until(n: Int): Unit = { var k = 0; while (resps.size < n && k < 400) { cycle(); k += 1 } }
    def access(va: Long, pa: Long, id: Int, cacheable: Boolean = true): Unit = reqQ.enqueue((va, Tr(id, HIT, pa, cacheable)))
    def invalidate(): Unit = { inv = true; var k = 0; cycle(); while (!invFire && k < 20) { cycle(); k += 1 }; inv = false }
    def getsOnly: Boolean = gets.forall(g => g._2.opcode == 4 && g._2.source == 0)
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new ICacheBusHarness(cp)) { dut => val d = new Drv(dut); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  def ok(id: Int, pa: Long): Resp = Resp(id, block(pa), FNone)

  val fillAndHits = new SpecTest("icbus.fillAndHits", Seq("funcICacheMissFill", "funcInstBusGet", "propInstBusGetOnly",
      "propICacheReadOnly")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.dStall = c => c % 3 == 0
      d.access(VA + 0x14, PA + 0x14, 1); d.until(1)
      val beats = d.dFires.size
      (0 until 4).foreach(b => d.access(VA + 16L * b, PA + 16L * b, 2 + b)); d.until(5); d.run(3)
      Seq(
        chk(d.gets.map(_._2).toSeq == Seq(Get(4, 6, PA & ~63L, 0)) && beats == 16,
          "a cold miss is one TileLink Get of the 64-byte line with 16 D beats", s"${d.gets} $beats"),
        chk(d.resps.toSeq == ok(1, PA + 0x10) +: (0 until 4).map(b => ok(2 + b, PA + 16L * b)),
          "the requested block answers first; all four blocks then hit with no further TileLink traffic", s"${d.resps}"),
        chk(d.getsOnly, "channel A carried only Get from source 0", s"${d.gets}"))
    }
  }

  val uncached = new SpecTest("icbus.uncached", Seq("funcICacheUncachedFetch", "funcInstBusGet")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.access(VA + 0x24, PA + 0x24, 1, cacheable = false); d.until(1)
      d.access(VA + 0x24, PA + 0x24, 2, cacheable = false); d.until(2); d.run(3)
      Seq(
        chk(d.gets.map(_._2).toSeq == Seq(Get(4, 4, PA + 0x20, 0), Get(4, 4, PA + 0x20, 0)) && d.dFires.size == 8,
          "each uncached fetch is a 16-byte Get with 4 beats and creates no later hit", s"${d.gets}"),
        chk(d.resps.toSeq == Seq(ok(1, PA + 0x20), ok(2, PA + 0x20)), "the answers carry the block", s"${d.resps}"))
    }
  }

  val denied = new SpecTest("icbus.denied", Seq("funcICacheMissFill", "funcInstBusGet")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.deny = a => a == (PA & ~63L)
      d.access(VA, PA, 1); d.until(1)
      d.deny = _ => false
      d.access(VA, PA, 2); d.until(2); d.run(3)
      Seq(
        chk(d.resps.headOption.exists(r => r.reqId == 1 && r.fault == FAccess), "a denied TileLink beat becomes InstAccessFault",
          s"${d.resps}"),
        chk(d.gets.size == 2 && d.resps.lift(1).contains(ok(2, PA)), "nothing was installed: the next access refills", s"${d.gets}"))
    }
  }

  val invalidate = new SpecTest("icbus.invalidate", Seq("funcICacheInvalidate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.access(VA, PA, 1); d.until(1)
      d.invalidate()
      d.access(VA, PA, 2); d.until(2)
      d.dStall = c => c % 2 == 0
      d.access(VA + 0x40, PA + 0x40, 3)
      while (d.dFires.size < 32 + 5 && d.cyc < 500) d.cycle()
      d.invalidate()                                    // during the outstanding fill
      d.until(3); d.dStall = _ => false
      d.access(VA + 0x40, PA + 0x40, 4); d.until(4); d.run(3)
      Seq(
        chk(d.gets.map(_._2.address).toSeq == Seq(PA, PA, PA + 0x40, PA + 0x40),
          "an invalidate forces a refill; one during an outstanding fill prevents its installation", s"${d.gets}"),
        chk(d.resps.toSeq == Seq(ok(1, PA), ok(2, PA), ok(3, PA + 0x40), ok(4, PA + 0x40)),
          "every request is still answered with its block", s"${d.resps}"),
        chk(d.getsOnly, "channel A carried only Get", s"${d.gets}"))
    }
  }

  val all: Seq[SpecTest] = Seq(fillAndHits, uncached, denied, invalidate)
}
