package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.core.design.shared.{CoreParams, CorePrivateParams, CoreTuningParams}
import udacore.core.design.top.CoreTop

/** Whole-graph boot and instruction retirement through both real core rawTops. */
object CoreTopSpecTests {
  val cp = CoreParams(
    tuning = CoreTuningParams(usingRvvi = true),
    priv = CorePrivateParams(bootCycles = 2))
  case class Req(op: Int, size: Int, source: Int, address: Long)
  case class Tok(order: Long, pc: Long, insn: Long, trap: Boolean)
  case class Beat(size: Int, source: Int, data: Long)

  class Drv(val dut: CoreTop) {
    val io = dut.io
    val instBeats = Queue[Beat]()
    val instReqs = ArrayBuffer[Req]()
    val dataReqs = ArrayBuffer[Req]()
    val retired = ArrayBuffer[Tok]()
    var enabled = false
    var cycles = 0

    def cycle(): Unit = {
      io.bootAddrIn.poke(0x1000.U)
      io.hartEnIn.poke(enabled.B)
      val irq = io.interruptIn
      Seq(irq.meip, irq.mtip, irq.msip, irq.seip, irq.stip, irq.ssip)
        .foreach(_.poke(false.B))
      io.debugReqIn.debugReq.poke(false.B)
      io.instBus.a.ready.poke(true.B)
      io.dataBus.a.ready.poke(true.B)
      val dValid = instBeats.nonEmpty
      io.instBus.d.valid.poke(dValid.B)
      instBeats.headOption.foreach { x =>
        val b = io.instBus.d.bits
        b.opcode.poke(1.U); b.param.poke(0.U); b.size.poke(x.size.U)
        b.source.poke(x.source.U); b.sink.poke(0.U); b.data.poke(x.data.U)
        b.denied.poke(false.B); b.corrupt.poke(false.B)
      }
      io.dataBus.d.valid.poke(false.B)
      io.retireStreamOut.get.ready.poke(true.B)

      if (io.instBus.a.valid.peek().litToBoolean) {
        val a = io.instBus.a.bits
        val req = Req(a.opcode.peek().litValue.toInt, a.size.peek().litValue.toInt,
          a.source.peek().litValue.toInt, a.address.peek().litValue.toLong)
        instReqs += req
        if (req.op == 4) {
          val beats = math.max(1, (1 << req.size) / (cp.dataWidth / 8))
          (0 until beats).foreach(_ => instBeats.enqueue(Beat(req.size, req.source, 0x13L)))
        }
      }
      if (io.dataBus.a.valid.peek().litToBoolean) {
        val a = io.dataBus.a.bits
        dataReqs += Req(a.opcode.peek().litValue.toInt, a.size.peek().litValue.toInt,
          a.source.peek().litValue.toInt, a.address.peek().litValue.toLong)
      }
      if (io.retireStreamOut.get.valid.peek().litToBoolean) {
        val t = io.retireStreamOut.get.bits
        retired += Tok(t.order.peek().litValue.toLong, t.pc.peek().litValue.toLong,
          t.insn.peek().litValue.toLong, t.trap.peek().litToBoolean)
      }
      if (dValid && io.instBus.d.ready.peek().litToBoolean) instBeats.dequeue()
      dut.clock.step(); cycles += 1
    }
    def until(done: => Boolean, limit: Int = 500): Boolean = {
      var k = 0
      while (!done && k < limit) { cycle(); k += 1 }
      done
    }
  }

  val bootRetire = new SpecTest("coreTop.bootRetire", Seq("contCoreTop")) {
    def run(): Seq[TCheck] = sim(new CoreTop(cp)) { dut =>
      val d = new Drv(dut)
      (0 until 5).foreach(_ => d.cycle())
      val quiet = d.instReqs.isEmpty && d.retired.isEmpty
      d.enabled = true
      d.until(d.retired.size >= 4)
      Seq(
        TCheck(quiet && d.instReqs.headOption.contains(Req(4, 6, 0, 0x1000L)),
          "hart enable triggers one aligned instruction TileLink line Get",
          if (quiet && d.instReqs.headOption.contains(Req(4, 6, 0, 0x1000L))) ""
          else s"quiet=$quiet requests=${d.instReqs.take(4)}"),
        TCheck(d.retired.take(4).map(t => (t.pc, t.insn, t.trap)).toSeq ==
          Seq(0x1000L, 0x1004L, 0x1008L, 0x100cL).map(pc => (pc, 0x13L, false)) &&
          d.dataReqs.isEmpty,
          "four NOPs retire in PC order without data-bus traffic",
          s"retired=${d.retired.take(4)} data=${d.dataReqs}")
      )
    }
  }

  val all: Seq[SpecTest] = Seq(bootRetire)
}
