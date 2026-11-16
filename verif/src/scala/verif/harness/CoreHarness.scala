package verif.harness

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.common.tilelink.TLBundle
import udacore.core.design.shared.CoreParams
import udacore.core.design.top.CoreTop
import scala.collection.mutable

/** A program's final memory image and architectural retirement observations. */
case class RunResult(
    stores: mutable.Map[Long, Long],
    pcStream: Seq[Long],
    done: Boolean,
    cycles: Int,
    derailed: Boolean
) {
  def result(off: Long): Int           = (stores.getOrElse(
    MemModel.RESULT_BASE + off,
    MemModel.SENTINEL
  ) & 0xffffffffL).toInt
  def resultStored(off: Long): Boolean =
    stores.contains(MemModel.RESULT_BASE + off)
}

class HarnessNotReady(msg: String) extends RuntimeException(msg)

/** Drives a CoreTop against two independent TileLink memory links. */
object CoreHarness {
  case class IrqPulse(kind: Char, at: Int, dur: Int)
  case class Scenario(
      name: String,
      m: MemModel,
      maxCycles: Int = 600,
      verbose: Boolean = false,
      irq: Seq[IrqPulse] = Nil
  )

  private case class DBeat(
      due: Int,
      opcode: Int,
      size: Int,
      source: Int,
      data: Long,
      completesProgram: Boolean
  )
  private case class PutState(address: Long, size: Int, count: Int)

  /** One ordered response queue per physical link. A PutLine repeats its line
    * address on A.
    */
  private class Bus(
      val port: TLBundle,
      val mem: MemModel,
      val instruction: Boolean
  ) {
    private val responses = mutable.Queue[DBeat]()
    private val puts      = mutable.Map[Int, PutState]()
    private val beatBytes = port.params.maskBits
    var wroteDone         = false

    private def queue(
        cycle: Int,
        latency: Int,
        opcode: Int,
        size: Int,
        source: Int,
        data: Seq[Long],
        completesProgram: Boolean = false
    ): Unit = {
      val first = math.max(
        cycle + math.max(1, latency),
        responses.lastOption.map(_.due + 1).getOrElse(0)
      )
      data.zipWithIndex.foreach { case (word, i) =>
        responses.enqueue(
          DBeat(first + i, opcode, size, source, word, completesProgram)
        )
      }
    }

    def drive(cycle: Int): Unit = {
      port.a.ready.poke(true.B)
      val head = responses.headOption.filter(_.due <= cycle)
      port.d.valid.poke(head.nonEmpty.B)
      val b    = port.d.bits
      b.opcode.poke(head.map(_.opcode).getOrElse(0).U)
      b.param.poke(0.U)
      b.size.poke(head.map(_.size).getOrElse(0).U)
      b.source.poke(head.map(_.source).getOrElse(0).U)
      b.sink.poke(0.U)
      b.data.poke(head.map(_.data).getOrElse(0L).U)
      b.denied.poke(false.B)
      b.corrupt.poke(false.B)
    }

    def sample(cycle: Int): Unit = {
      val dFire = responses.headOption.exists(x =>
        x.due <= cycle && port.d.ready.peek().litToBoolean
      )
      if (port.a.valid.peek().litToBoolean) {
        val a       = port.a.bits
        val opcode  = a.opcode.peek().litValue.toInt
        val size    = a.size.peek().litValue.toInt
        val source  = a.source.peek().litValue.toInt
        val address = a.address.peek().litValue.toLong
        val data    = a.data.peek().litValue.toLong
        val mask    = a.mask.peek().litValue.toLong
        val latency = if (instruction) mem.iLat else mem.dLatOf(address)
        opcode match {
          case 4     =>
            val beats = math.max(1, (1 << size) / beatBytes)
            queue(
              cycle,
              latency,
              1,
              size,
              source,
              (0 until beats).map(i => mem.load(address + i * beatBytes))
            )
          case 0     =>
            val old   = puts.getOrElse(source, PutState(address, size, 0))
            require(
              old.address == address && old.size == size,
              "PutFullData changed its line identity"
            )
            mem.storeStrb(address + old.count * beatBytes, data, mask)
            val count = old.count + 1
            if (count == math.max(1, (1 << size) / beatBytes)) {
              puts.remove(source)
              queue(cycle, latency, 0, size, source, Seq(0L))
            } else puts(source) = old.copy(count = count)
          case 1     =>
            mem.storeStrb(address, data, mask)
            queue(
              cycle,
              latency,
              0,
              size,
              source,
              Seq(0L),
              address == MemModel.DONE
            )
          case other =>
            throw new IllegalStateException(
              s"unsupported TileLink A opcode $other"
            )
        }
      }
      if (dFire && responses.dequeue().completesProgram) wroteDone = true
    }
  }

  private def withDut[A](p: CoreParams)(body: CoreTop => A): A = {
    require(p.usingRvvi, "CoreHarness requires the verification retire stream")
    var out: Option[A] = None
    simulate(new CoreTop(p)) { dut => out = Some(body(dut)) }
    out.get
  }

  private def runScenario(
      dut: CoreTop,
      s: Scenario,
      stopOnDone: Boolean = true,
      observe: Int => Unit = _ => ()
  ): RunResult = {
    val io       = dut.io
    val inst     = new Bus(io.instBus, s.m, instruction = true)
    val data     = new Bus(io.dataBus, s.m, instruction = false)
    val pcs      = mutable.ArrayBuffer[Long]()
    var done     = false
    var derailed = false
    var cycle    = 0

    def drive(reset: Boolean): Unit = {
      io.bootAddrIn.poke(MemModel.PROG_BASE.U)
      io.hartEnIn.poke((!reset).B)
      def active(kind: Char): Boolean = !reset && s.irq.exists(p =>
        p.kind == kind && cycle >= p.at && cycle < p.at + p.dur
      )
      io.interruptIn.meip.poke(active('e').B)
      io.interruptIn.mtip.poke(active('t').B)
      io.interruptIn.msip.poke(active('s').B)
      Seq(io.interruptIn.seip, io.interruptIn.stip, io.interruptIn.ssip)
        .foreach(_.poke(false.B))
      io.debugReqIn.debugReq.poke(false.B)
      io.retireStreamOut.get.ready.poke(true.B)
      inst.drive(cycle)
      data.drive(cycle)
    }

    dut.reset.poke(true.B)
    (0 until 2).foreach { _ => drive(reset = true); dut.clock.step() }
    dut.reset.poke(false.B)
    while (cycle < s.maxCycles && (!stopOnDone || !done)) {
      drive(reset = false)
      observe(cycle)
      val retire = io.retireStreamOut.get
      if (retire.valid.peek().litToBoolean) {
        val pc = retire.bits.pc.peek().litValue.toLong
        pcs += pc
        if (pc < MemModel.PROG_BASE) derailed = true
      }
      inst.sample(cycle)
      data.sample(cycle)
      if (data.wroteDone) done = true
      dut.clock.step()
      cycle += 1
    }
    RunResult(s.m.mem, pcs.toSeq, done, cycle, derailed)
  }

  /** Uses exactly the scenario memory/reset driver; observation happens before
    * the clock edge.
    */
  def trace(m: MemModel, cycles: Int, irq: Seq[IrqPulse] = Nil)(implicit
      p: CoreParams
  ): (RunResult, Seq[Cycle]) = {
    require(
      p.usingRvvi,
      "CoreHarness trace requires the verification retire stream"
    )
    require(cycles >= 0, "trace cycle count must be non-negative")
    val rows                      = mutable.ArrayBuffer[Cycle]()
    var result: Option[RunResult] = None
    simulate(new CoreTraceTop(p)) { dut =>
      result = Some(
        runScenario(
          dut,
          Scenario("trace", m, cycles, irq = irq),
          stopOnDone = false,
          observe = i => rows += Probe.sample(dut, m, i)
        )
      )
    }
    (result.get, rows.toSeq)
  }

  def run(
      name: String,
      m: MemModel,
      maxCycles: Int = 600,
      verbose: Boolean = false,
      irq: Seq[IrqPulse] = Nil
  )(implicit p: CoreParams): RunResult =
    withDut(p)(dut =>
      runScenario(dut, Scenario(name, m, maxCycles, verbose, irq))
    )

  def runBatch(scenarios: Seq[Scenario])(implicit
      p: CoreParams
  ): Seq[RunResult] =
    withDut(p)(dut => scenarios.map(s => runScenario(dut, s)))

  def runServer(
      next: () => Option[Scenario]
  )(onResult: (Scenario, RunResult) => Unit)(implicit p: CoreParams): Unit =
    withDut(p) { dut =>
      var live = true
      while (live) next() match {
        case Some(s) => onResult(s, runScenario(dut, s))
        case None    => live = false
      }
    }
}
