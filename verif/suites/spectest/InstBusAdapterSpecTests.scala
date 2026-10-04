package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.common.tilelink.TLMessages
import udacore.core.design.modules.InstBusAdapter
import udacore.core.design.shared.CoreParams

/** L1 SpecTests for the ADR-019 InstBusAdapter (ADR-016/019; InstBusAdapterSpecs).
  *
  * The driver plays the InstructionCache (InstMemReq producer, InstMemResp consumer) and a TileLink
  * slave on instBus: it accepts A with a programmable ready and answers each Get with its D beats
  * from a queue, which tests may also fill directly with malformed beats.
  */
object InstBusAdapterSpecTests {
  val cp = CoreParams()
  val beatBytes = cp.dataWidth / 8

  case class A(opcode: Int, param: Int, size: Int, source: Int, address: Long, mask: Int, data: Long, corrupt: Boolean)
  case class D(opcode: Int = 1, size: Int, source: Int = 0, data: Long, denied: Boolean = false, corrupt: Boolean = false)
  case class R(data: Long, last: Boolean, denied: Boolean)

  def word(addr: Long): Long = (addr * 2654435761L + 0x1234567L) & 0xffffffffL

  class Drv(val dut: InstBusAdapter) {
    val io = dut.io
    var cyc = 0
    var req: Option[(Long, Int)] = None
    var aReady = true
    var respReady = true
    var dStall: Int => Boolean = _ => false
    var autoRespond = true
    var denyBeat: Set[Int] = Set.empty
    var corruptBeat: Set[Int] = Set.empty
    val dq = Queue[D]()
    val as = ArrayBuffer[(Int, A)]()
    val aOffers = ArrayBuffer[(Int, A)]()
    val rs = ArrayBuffer[(Int, R)]()
    var reqFire, reqReady = false

    def peekA(): A = {
      val a = io.instBus.a.bits
      A(a.opcode.peek().litValue.toInt, a.param.peek().litValue.toInt, a.size.peek().litValue.toInt,
        a.source.peek().litValue.toInt, a.address.peek().litValue.toLong, a.mask.peek().litValue.toInt,
        a.data.peek().litValue.toLong, a.corrupt.peek().litToBoolean)
    }
    def cycle(): Unit = {
      io.instMemReqIn.valid.poke(req.nonEmpty.B)
      req.foreach { case (pa, sz) => io.instMemReqIn.bits.paddr.poke(pa.U); io.instMemReqIn.bits.size.poke(sz.U) }
      io.instBus.a.ready.poke(aReady.B)
      io.instMemRespOut.ready.poke(respReady.B)
      val dv = dq.nonEmpty && !dStall(cyc)
      io.instBus.d.valid.poke(dv.B)
      dq.headOption.foreach { d =>
        val b = io.instBus.d.bits
        b.opcode.poke(d.opcode.U); b.param.poke(0.U); b.size.poke(d.size.U); b.source.poke(d.source.U); b.sink.poke(0.U)
        b.denied.poke(d.denied.B); b.data.poke(d.data.U); b.corrupt.poke(d.corrupt.B)
      }
      reqReady = io.instMemReqIn.ready.peek().litToBoolean
      reqFire = req.nonEmpty && reqReady
      val av = io.instBus.a.valid.peek().litToBoolean
      if (av) aOffers += ((cyc, peekA()))
      val aFire = av && aReady
      if (aFire) {
        val a = peekA(); as += ((cyc, a))
        if (autoRespond) {
          val n = math.max(1, (1 << a.size) / beatBytes)
          (0 until n).foreach(i => dq.enqueue(D(size = a.size, data = word(a.address + 4L * i), denied = denyBeat(i),
            corrupt = corruptBeat(i))))
        }
      }
      val o = io.instMemRespOut
      if (o.valid.peek().litToBoolean && respReady)
        rs += ((cyc, R(o.bits.data.peek().litValue.toLong, o.bits.last.peek().litToBoolean, o.bits.denied.peek().litToBoolean)))
      val dFire = dv && io.instBus.d.ready.peek().litToBoolean
      if (dFire) dq.dequeue()
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def send(pa: Long, sz: Int): Unit = { req = Some((pa, sz)); var k = 0; cycle(); while (!reqFire && k < 60) { cycle(); k += 1 }; req = None }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new InstBusAdapter(cp)) { dut => val d = new Drv(dut); body(d) }

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  def getA(addr: Long, size: Int): A = A(4, 0, size, 0, addr, (1 << beatBytes) - 1, 0, corrupt = false)
  def beats(addr: Long, n: Int): Seq[R] = (0 until n).map(i => R(word(addr + 4L * i), i == n - 1, denied = false))

  val get16 = new SpecTest("iba.get16", Seq("funcInstBusGet", "propInstBusGetOnly")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.send(0x80000010L, 4); d.run(8)
      Seq(
        chk(d.as.map(_._2).toSeq == Seq(getA(0x80000010L, 4)),
          "one A Get: opcode Get, param 0, source 0, address, size 4, full mask, data 0, corrupt 0", s"${d.as}"),
        chk(d.rs.map(_._2).toSeq == beats(0x80000010L, 4), "four InstMemResp beats in order, last only on the fourth", s"${d.rs}"))
    }
  }

  val get64 = new SpecTest("iba.get64", Seq("funcInstBusGet")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.send(0x40001040L, 6); d.run(22)
      Seq(
        chk(d.as.map(_._2).toSeq == Seq(getA(0x40001040L, 6)), "one A Get of size 6", s"${d.as}"),
        chk(d.rs.map(_._2).toSeq == beats(0x40001040L, 16), "sixteen beats, last only on the sixteenth (not the fourth)", s"${d.rs}"))
    }
  }

  val aBackpressure = new SpecTest("iba.aBackpressure", Seq("funcInstBusGet")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.aReady = false; d.send(0x1000L, 4); d.run(4)
      val held = d.aOffers.map(_._2).distinct.toSeq; val n0 = d.aOffers.size
      d.aReady = true; d.run(8)
      Seq(
        chk(n0 >= 4 && held == Seq(getA(0x1000L, 4)), "under A backpressure the Get is offered every cycle, bit for bit stable", s"${d.aOffers}"),
        chk(d.as.size == 1 && d.rs.map(_._2).toSeq == beats(0x1000L, 4), "it transfers once and completes", s"${d.as} ${d.rs}"))
    }
  }

  val dBackpressure = new SpecTest("iba.dBackpressure", Seq("funcInstBusGet")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.dStall = c => c % 3 == 1
      d.send(0x2000L, 6)
      (0 until 60).foreach { k => d.respReady = k % 4 != 2; d.cycle() }
      Seq(chk(d.rs.map(_._2).toSeq == beats(0x2000L, 16),
        "with D gaps and InstMemResp backpressure every beat is forwarded exactly once, in order, last on the final beat", s"${d.rs}"))
    }
  }

  val denied = new SpecTest("iba.denied", Seq("funcInstBusGet")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.denyBeat = Set(1); d.send(0x3000L, 4); d.run(8)
      d.denyBeat = Set.empty; d.corruptBeat = Set(2); d.send(0x3010L, 4); d.run(8)
      val r = d.rs.map(_._2).toSeq
      Seq(chk(r.map(_.denied) == Seq(false, true, false, false, false, false, true, false) && r.count(_.last) == 2,
        "D denied and D corrupt both set the beat's denied flag; the burst still completes", s"$r"))
    }
  }

  val noSecond = new SpecTest("iba.noSecond", Seq("funcInstBusGet")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.dStall = c => c < 12
      d.send(0x4000L, 4)
      d.req = Some((0x5000L, 4))
      var readyWhileBusy = false; var k = 0
      while (d.req.nonEmpty && k < 60) { d.cycle(); if (d.reqFire) { if (d.rs.size < 3) readyWhileBusy = true; d.req = None }; k += 1 }
      d.run(10)
      Seq(
        chk(!readyWhileBusy, "no second request is accepted while the first transaction is in flight", ""),
        chk(d.as.map(_._2.address).toSeq == Seq(0x4000L, 0x5000L) && d.rs.map(_._2).toSeq == beats(0x4000L, 4) ++ beats(0x5000L, 4),
          "the second Get follows the first transaction's final beat", s"${d.as} ${d.rs}"))
    }
  }

  /** propInstBusGetOnly structurally: the instruction link elaborates no B/C/E channel. */
  val structure = new SpecTest("iba.structure", Seq("propInstBusGetOnly")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val bus = d.dut.io.instBus
      Seq(chk(!cp.instBusParams.hasBCE && bus.b.isEmpty && bus.c.isEmpty && bus.e.isEmpty,
        "the instruction link has hasBCE = false and no B/C/E channels", s"${cp.instBusParams}"))
    }
  }

  def expectAssert(t: SpecTest, tag: String, label: String)(body: Drv => Unit): Seq[TCheck] = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.run(10); reachedEnd = true; Nil }
      Seq(TCheck(false, label, "simulation ended normally"))
    } catch {
      case e: NotImplementedError => throw e
      case _: Throwable =>
        val fired = chisel3.simulator.CachedSimulator.lastSimulationLog.linesIterator.filter(_.contains("Assertion failed")).toSeq
        Seq(chk(!reachedEnd && fired.exists(_.contains(tag)), label, fired.headOption.getOrElse("no assertion")))
    }
  }

  val malformedD = new SpecTest("iba.malformedD", Seq("funcInstBusGet")) {
    def run(): Seq[TCheck] =
      expectAssert(this, "InstBusD", "a D beat with opcode AccessAck (not AccessAckData) fires the D-channel assertion") { d =>
        d.autoRespond = false; d.send(0x6000L, 4); d.run(2); d.dq.enqueue(D(opcode = 0, size = 4, data = 0))
      } ++ expectAssert(this, "InstBusD", "a D beat with source 1 fires the D-channel assertion") { d =>
        d.autoRespond = false; d.send(0x6000L, 4); d.run(2); d.dq.enqueue(D(size = 4, source = 1, data = 0))
      } ++ expectAssert(this, "InstBusD", "a D beat whose size differs from the active Get fires the D-channel assertion") { d =>
        d.autoRespond = false; d.send(0x6000L, 4); d.run(2); d.dq.enqueue(D(size = 6, data = 0))
      } ++ expectAssert(this, "InstBusD", "a D beat without an active request fires the D-channel assertion") { d =>
        d.autoRespond = false; d.run(2); d.dq.enqueue(D(size = 4, data = 0))
      }
  }

  val all: Seq[SpecTest] = Seq(get16, get64, aBackpressure, dBackpressure, denied, noSecond, structure, malformedD)
}
