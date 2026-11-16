package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.core.design.modules.DataBusAdapter
import udacore.core.design.shared.{CoreParams, DataMemOp}

/** TileLink-side L1 checks for the non-coherent data link. The driver is a DataCache request
  * producer and a D-channel manager that may return different sources in any order.
  */
object DataBusAdapterSpecTests {
  val cp = CoreParams()
  val beatBytes = cp.dataWidth / 8
  val fullMask = (1 << beatBytes) - 1
  def op(x: UInt): Int = x.litValue.toInt

  case class Req(opcode: Int, source: Int, address: Long, size: Int, data: Long = 0,
      mask: Int = fullMask, last: Boolean = true)
  case class A(opcode: Int, size: Int, source: Int, address: Long, mask: Int, data: Long)
  case class D(opcode: Int, source: Int, size: Int, data: Long = 0,
      denied: Boolean = false, corrupt: Boolean = false)
  case class R(source: Int, data: Long, last: Boolean, denied: Boolean)

  class Drv(val dut: DataBusAdapter) {
    val io = dut.io
    var cyc = 0
    var req: Option[Req] = None
    var aReady = true
    var respReady = true
    val dq = Queue[D]()
    val as = ArrayBuffer[A]()
    val aOffers = ArrayBuffer[A]()
    val rs = ArrayBuffer[R]()
    var reqFire = false

    def peekA(): A = {
      val a = io.dataBus.a.bits
      A(a.opcode.peek().litValue.toInt, a.size.peek().litValue.toInt,
        a.source.peek().litValue.toInt, a.address.peek().litValue.toLong,
        a.mask.peek().litValue.toInt, a.data.peek().litValue.toLong)
    }
    def cycle(): Unit = {
      val in = io.dataMemReqIn
      in.valid.poke(req.nonEmpty.B)
      req.foreach { x =>
        in.bits.op.poke(x.opcode.U); in.bits.source.poke(x.source.U); in.bits.paddr.poke(x.address.U)
        in.bits.size.poke(x.size.U); in.bits.data.poke(x.data.U); in.bits.mask.poke(x.mask.U)
        in.bits.last.poke(x.last.B)
      }
      io.dataBus.a.ready.poke(aReady.B)
      io.dataMemRespOut.ready.poke(respReady.B)
      io.dataBus.d.valid.poke(dq.nonEmpty.B)
      dq.headOption.foreach { x =>
        val d = io.dataBus.d.bits
        d.opcode.poke(x.opcode.U); d.param.poke(0.U); d.size.poke(x.size.U)
        d.source.poke(x.source.U); d.sink.poke(0.U); d.data.poke(x.data.U)
        d.denied.poke(x.denied.B); d.corrupt.poke(x.corrupt.B)
      }
      reqFire = req.nonEmpty && in.ready.peek().litToBoolean
      if (io.dataBus.a.valid.peek().litToBoolean) {
        val a = peekA(); aOffers += a
        if (aReady) as += a
      }
      val out = io.dataMemRespOut
      if (out.valid.peek().litToBoolean && respReady)
        rs += R(out.bits.source.peek().litValue.toInt, out.bits.data.peek().litValue.toLong,
          out.bits.last.peek().litToBoolean, out.bits.denied.peek().litToBoolean)
      if (dq.nonEmpty && io.dataBus.d.ready.peek().litToBoolean) dq.dequeue()
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def send(x: Req): Unit = {
      req = Some(x); var k = 0; cycle()
      while (!reqFire && k < 50) { cycle(); k += 1 }
      req = None
    }
  }

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new DataBusAdapter(cp)) { dut => body(new Drv(dut)) }

  val readsInterleave = new SpecTest("dba.readsInterleave", Seq("funcDataBusTransaction")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.send(Req(op(DataMemOp.GetLine), 0, 0x1000, 6))
      d.send(Req(op(DataMemOp.GetUncached), 1, 0x2000, 2))
      d.dq.enqueue(D(1, 1, 2, 0x12345678L))
      (0 until 16).foreach(i => d.dq.enqueue(D(1, 0, 6, i.toLong)))
      d.run(18)
      Seq(
        chk(d.as.toSeq == Seq(A(4, 6, 0, 0x1000, fullMask, 0), A(4, 2, 1, 0x2000, fullMask, 0)),
          "GetLine and GetUncached become A Gets with separate sources", s"${d.as}"),
        chk(d.rs.size == 17 && d.rs.head == R(1, 0x12345678L, true, false) &&
            d.rs.drop(1).zipWithIndex.forall { case (r, i) => r == R(0, i.toLong, i == 15, false) },
          "interleaved D sources retain their data and independent last-beat counts", s"${d.rs}")
      )
    }
  }

  val putLine = new SpecTest("dba.putLine", Seq("funcDataBusTransaction")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val first = Req(op(DataMemOp.PutLine), 2, 0x3000, 6, 0x100, last = false)
      d.req = Some(first); d.aReady = false; d.run(3)
      val stable = d.aOffers.size == 3 && d.aOffers.distinct.size == 1 && !d.reqFire
      d.aReady = true; d.cycle(); d.req = None
      (1 until 16).foreach(i => d.send(first.copy(data = 0x100 + i, last = i == 15)))
      d.dq.enqueue(D(0, 2, 6)); d.run(2)
      Seq(
        chk(stable, "the first PutLine beat remains stable under A backpressure", s"${d.aOffers}"),
        chk(d.as.size == 16 && d.as.zipWithIndex.forall { case (a, i) =>
          a == A(0, 6, 2, 0x3000, fullMask, 0x100 + i)
        }, "PutLine sends sixteen contiguous full-data A beats", s"${d.as}"),
        chk(d.rs.toSeq == Seq(R(2, 0, true, false)), "one AccessAck completes the line writeback", s"${d.rs}")
      )
    }
  }

  val uncached = new SpecTest("dba.uncached", Seq("funcDataBusTransaction")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.send(Req(op(DataMemOp.GetUncached), 3, 0x4001, 0))
      d.dq.enqueue(D(1, 3, 0, 0x55, denied = true)); d.run(2)
      d.send(Req(op(DataMemOp.PutUncached), 3, 0x5000, 2, 0x11223344L, mask = 5))
      d.dq.enqueue(D(0, 3, 2, corrupt = true)); d.run(2)
      Seq(
        chk(d.as.toSeq == Seq(A(4, 0, 3, 0x4001, 2, 0), A(1, 2, 3, 0x5000, 5, 0x11223344L)),
          "uncached byte Get and partial Put preserve size, lane mask, and data", s"${d.as}"),
        chk(d.rs.toSeq == Seq(R(3, 0x55, true, true), R(3, 0, true, true)),
          "denied and corrupt both become denied responses; source 3 is reusable after acknowledgement", s"${d.rs}")
      )
    }
  }

  val sourceLifetime = new SpecTest("dba.sourceLifetime", Seq("funcDataBusTransaction")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.send(Req(op(DataMemOp.GetUncached), 0, 0x6000, 2))
      d.req = Some(Req(op(DataMemOp.GetUncached), 0, 0x7000, 2))
      d.run(2)
      val blocked = !d.reqFire && d.as.size == 1
      d.dq.enqueue(D(1, 0, 2, 0x1234))
      d.respReady = false
      d.run(3)
      val held = d.dq.size == 1 && d.rs.isEmpty && d.as.size == 1
      d.respReady = true
      d.cycle()
      val stillBlockedOnResponseFire = !d.reqFire && d.as.size == 1
      d.cycle()
      d.req = None
      Seq(
        chk(blocked && held && stillBlockedOnResponseFire,
          "a source cannot be reused before its final D transfer, including D backpressure",
          s"blocked=$blocked held=$held responseFireBlocked=$stillBlockedOnResponseFire"),
        chk(d.reqFire && d.as.size == 2 && d.as.last.address == 0x7000 &&
            d.rs.toSeq == Seq(R(0, 0x1234, true, false)),
          "the source becomes available after the D transfer", s"A=${d.as} R=${d.rs}")
      )
    }
  }

  val structure = new SpecTest("dba.structure", Seq("propDataBusCoherenceSeparate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val bus = d.io.dataBus
      Seq(chk(!cp.contract.dataCoherence && !cp.dataBusParams.hasBCE &&
        bus.b.isEmpty && bus.c.isEmpty && bus.e.isEmpty,
        "v0 dataBus has no B/C/E channels despite the D-cache", s"${cp.dataBusParams}"))
    }
  }

  val all: Seq[SpecTest] = Seq(readsInterleave, putLine, uncached, sourceLifetime, structure)
}
