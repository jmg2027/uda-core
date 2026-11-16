package verif.harness

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.core.design.shared.CoreParams

/** One offered fetch lane and its expected backing-memory instruction, sampled
  * before the rising edge. Compare only when translation and fault state permit
  * it.
  */
case class FetchSample(
    lane: Int,
    pc: Long,
    instRaw: Long,
    expInst: Long,
    fault: Int,
    compareValid: Boolean
) {
  def desync: Boolean = compareValid && instRaw != expInst
}

case class RetireSample(
    order: BigInt,
    pc: Long,
    insn: Long,
    rd: Int,
    wdata: Long,
    wen: Boolean,
    trap: Boolean,
    source: Int,
    cause: Int,
    tval: Long
)
case class RecoverySample(
    kind: Int,
    cause: Int,
    target: Long,
    robIdx: Int,
    robWrap: Boolean
)

/** Sampled before each post-reset rising edge; fetch lanes are offers, not
  * retirement events.
  */
case class Cycle(
    i: Int,
    reqAddr: Long,
    pc: Long,
    valid: Boolean,
    instRaw: Long,
    expInst: Long,
    flush: Boolean,
    jump: Boolean,
    xcpt: Boolean,
    stall: Boolean,
    divBusy: Boolean,
    rd: Int,
    aluWB: Boolean,
    reqValid: Boolean,
    fetchReady: Boolean,
    fetch: Seq[FetchSample],
    retire: Option[RetireSample],
    recovery: Option[RecoverySample]
) {
  def desync: Boolean = fetch.exists(_.desync)
}

/** Simulation-only taps. Expected bytes compare mapped Bare/M-mode fetches with
  * backing memory; faults and virtual translations have compareValid=false. A
  * mismatch is diagnostic, since self-modifying code may legally retain older
  * I-cache bytes before FENCE.I.
  */
object Probe {
  val taps = Seq(
    "i",
    "pc",
    "instRaw",
    "expInst",
    "valid",
    "flush",
    "jump",
    "xcpt",
    "stall",
    "divBusy",
    "rd",
    "aluWB",
    "reqAddr",
    "reqValid",
    "fetchReady",
    "fetch",
    "retire",
    "recovery",
    "desync"
  )

  private[harness] def sample(dut: CoreTraceTop, m: MemModel, i: Int): Cycle = {
    val t        = dut.trace
    val identity = !t.translation.satpMode.peek().litToBoolean ||
      t.translation.priv.peek().litValue == 3
    val fetch    =
      if (!t.fetchValid.peek().litToBoolean) Nil
      else
        t.fetch.insts.indices
          .filter(n => t.fetch.valid(n).peek().litToBoolean)
          .map { n =>
            val inst       = t.fetch.insts(n)
            val pc         = inst.pc.peek().litValue.toLong
            val fault      = inst.fault.peek().litValue.toInt
            val comparable = identity && fault == 0 && m.contains(pc)
            FetchSample(
              n,
              pc,
              inst.inst.peek().litValue.toLong,
              if (comparable) m.load(pc) else 0L,
              fault,
              comparable
            )
          }
    val r        = dut.io.retireStreamOut.get
    val retire   =
      if (!r.valid.peek().litToBoolean) None
      else {
        val b = r.bits
        Some(
          RetireSample(
            b.order.peek().litValue,
            b.pc.peek().litValue.toLong,
            b.insn.peek().litValue.toLong,
            b.rd.peek().litValue.toInt,
            b.wdata.peek().litValue.toLong,
            b.wen.peek().litToBoolean,
            b.trap.peek().litToBoolean,
            b.source.peek().litValue.toInt,
            b.cause.peek().litValue.toInt,
            b.tval.peek().litValue.toLong
          )
        )
      }
    val e        = t.recovery
    val recovery =
      if (!e.valid.peek().litToBoolean) None
      else
        Some(
          RecoverySample(
            e.kind.peek().litValue.toInt,
            e.cause.peek().litValue.toInt,
            e.target.peek().litValue.toLong,
            e.robTag.idx.peek().litValue.toInt,
            e.robTag.wrap.peek().litToBoolean
          )
        )
    val reqValid = dut.io.instBus.a.valid.peek().litToBoolean
    val ready    = t.fetchReady.peek().litToBoolean
    Cycle(
      i,
      if (reqValid) dut.io.instBus.a.bits.address.peek().litValue.toLong
      else 0L,
      fetch.headOption.map(_.pc).getOrElse(0L),
      fetch.nonEmpty,
      fetch.headOption.map(_.instRaw).getOrElse(0L),
      fetch.headOption.map(_.expInst).getOrElse(0L),
      recovery.nonEmpty,
      t.jump.peek().litToBoolean,
      retire.exists(_.trap),
      fetch.nonEmpty && !ready,
      t.divBusy.peek().litToBoolean,
      retire.filter(_.wen).map(_.rd).getOrElse(0),
      t.aluWB.peek().litToBoolean,
      reqValid,
      ready,
      fetch,
      retire,
      recovery
    )
  }

  def trace(
      m: MemModel,
      cycles: Int = 40,
      irq: Seq[CoreHarness.IrqPulse] = Nil
  )(implicit p: CoreParams): Seq[Cycle] = CoreHarness.trace(m, cycles, irq)._2
}
