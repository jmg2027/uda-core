package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.modules.StoreBuffer
import udacore.backend.design.shared.BackendParams

/** L1 SpecTests for the ADR-019 StoreBuffer (ADR-018; spec 242feaf + ADR-019A..E).
  *
  * The driver plays the LSQ (CommittedStoreIn pushes and forwarding queries, answered in the
  * query cycle), the DataCache store-drain port (ready knob plus a response latency), and the
  * CommitUnit drain fence. Addresses are byte addresses; data and mask are lane-aligned.
  */
object StoreBufferSpecTests {

  val p = BackendParams()
  val N = p.tuning.storeBufferDepth

  case class St(paddr: Long, data: Long, mask: Int)
  case class Ans(data: Long, hit: Int, partial: Boolean)

  class Drv(val dut: StoreBuffer) {
    val io = dut.io
    var cyc = 0
    var push: Option[St] = None
    var query: Option[(Long, Int)] = None
    var drainReady = true
    var drainLatency = 2
    var fenceReq = false
    var fenceRespReady = true
    val drained  = ArrayBuffer[(Int, St)]()
    val respDue  = ArrayBuffer[Int]()
    var pushFire, fenceReqFire, fenceRespFire, reqValid, empty = false
    var outstanding = 0
    var maxOutstanding = 0
    var answer: Option[Ans] = None
    var offerViolations = Seq.empty[Int]
    private def b(x: Bool) = x.peek().litToBoolean
    private def l(x: UInt) = x.peek().litValue.toLong

    def cycle(): Unit = {
      val c = io.committedStoreIn
      c.valid.poke(push.nonEmpty.B)
      push.foreach { s => c.bits.paddr.poke(s.paddr.U); c.bits.data.poke(s.data.U); c.bits.mask.poke(s.mask.U) }
      val q = io.storeForwardQueryIn
      q.valid.poke(query.nonEmpty.B)
      query.foreach { case (a, m) => q.bits.paddr.poke(a.U); q.bits.mask.poke(m.U) }
      io.storeForwardDataOut.ready.poke(true.B)
      io.storeDrainReqOut.ready.poke(drainReady.B)
      val due = respDue.nonEmpty && respDue.head <= cyc
      io.storeDrainRespIn.valid.poke(due.B)
      io.storeBufferDrainReqIn.valid.poke(fenceReq.B)
      io.storeBufferDrainRespOut.ready.poke(fenceRespReady.B)
      // Observe.
      pushFire = push.nonEmpty && b(c.ready)
      empty = b(io.storeBufferEmptyOut)
      answer = if (query.nonEmpty && b(io.storeForwardDataOut.valid))
        Some(Ans(l(io.storeForwardDataOut.bits.data), l(io.storeForwardDataOut.bits.hitMask).toInt, b(io.storeForwardDataOut.bits.partial)))
      else None
      if (query.nonEmpty && !(b(q.ready) && b(io.storeForwardDataOut.valid))) offerViolations :+= cyc
      val r = io.storeDrainReqOut
      reqValid = b(r.valid)
      if (!empty && outstanding == 0 && !reqValid) offerViolations :+= cyc
      if (reqValid && drainReady) {
        drained += ((cyc, St(l(r.bits.paddr), l(r.bits.data), l(r.bits.mask).toInt)))
        respDue += cyc + drainLatency; outstanding += 1; maxOutstanding = math.max(maxOutstanding, outstanding)
      }
      if (due && b(io.storeDrainRespIn.ready)) { respDue.remove(0); outstanding -= 1 }
      fenceReqFire = fenceReq && b(io.storeBufferDrainReqIn.ready)
      fenceRespFire = b(io.storeBufferDrainRespOut.valid) && fenceRespReady
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def pushOne(s: St, max: Int = 50): Boolean = { push = Some(s); var k = 0; cycle(); while (!pushFire && k < max) { cycle(); k += 1 }; push = None; pushFire }
    def ask(a: Long, m: Int): Option[Ans] = { query = Some((a, m)); cycle(); query = None; answer }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new StoreBuffer(p)) { dut => val d = new Drv(dut); d.cycle(); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  def mustAssert(t: SpecTest, label: String, expect: String)(body: Drv => Unit): TCheck = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.run(3); reachedEnd = true; Nil }
      TCheck(false, label, "simulation ended normally: the design assertion did not fire")
    } catch {
      case e: NotImplementedError => throw e
      case e: Throwable =>
        val log   = chisel3.simulator.CachedSimulator.lastSimulationLog
        val fired = log.linesIterator.filter(_.contains("Assertion failed")).toSeq
        val ok    = !reachedEnd && fired.exists(_.contains(expect))
        val why   = if (fired.isEmpty) s"no assertion in the simulation log (${e.getClass.getSimpleName})" else fired.head.trim.take(200)
        TCheck(ok, label, if (ok) "" else s"expected '$expect'; got: $why")
    }
  }

  // ---- funcCommitOrderDrain / propInOrderDrain ------------------------------------------------------------

  val drainOrder = new SpecTest("sb.drainOrder", Seq("funcCommitOrderDrain", "propInOrderDrain")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.drainReady = false
      val first = (0 until N).map(i => St(0x1000L + 4 * i, 0x100L + i, 0xf))
      val pushed = first.map(d.pushOne(_))
      d.push = Some(St(0x2000L, 1, 1)); d.run(3); val fullBlocked = !d.pushFire; d.push = None
      val emptyWhileFull = d.empty
      d.drainLatency = 4; d.drainReady = true
      d.run(2)
      val oneOutstanding = d.maxOutstanding == 1
      val stillFullDuringDrain = { d.push = Some(St(0x2000L, 1, 1)); d.cycle(); d.push = None; !d.pushFire }
      d.run(30)
      // Random stress: pushes, bus readiness, and latency all random.
      val rnd = new scala.util.Random(5)
      val stream = (0 until 120).map(i => St(0x3000L + 4 * rnd.nextInt(8), rnd.nextLong() & 0xffffffffL, 1 + rnd.nextInt(15)))
      var k = 0
      var guard = 0
      while (k < stream.size && guard < 3000) {
        guard += 1
        d.drainReady = rnd.nextInt(3) > 0; d.drainLatency = rnd.nextInt(4)
        d.push = Some(stream(k)); d.cycle(); if (d.pushFire) k += 1
      }
      d.push = None; d.drainReady = true; d.run(40)
      val order = d.drained.map(_._2).toSeq
      // The last store stays counted (not empty) until its StoreDrainResp.
      d.drainLatency = 6
      val last = St(0x5000L, 7, 0xf)
      d.pushOne(last)
      val emptyDuring = (0 until 4).map { _ => d.cycle(); d.empty }
      d.run(6)
      val emptyAfter = d.empty
      Seq(
        chk(pushed.forall(identity) && fullBlocked, s"$N committed stores fill the buffer; CommittedStoreIn.ready is then low", s"$pushed $fullBlocked"),
        chk(!emptyWhileFull, "StoreBufferEmpty is low while stores are buffered", ""),
        chk(oneOutstanding && d.maxOutstanding == 1, "one outstanding drain at a time", s"${d.maxOutstanding}"),
        chk(stillFullDuringDrain, "the head is freed only by its StoreDrainResp (an issued drain still occupies it)", ""),
        chk(order == first ++ stream, "stores drain in commit order with no duplication or omission", s"${order.size} vs ${first.size + stream.size}"),
        chk(emptyDuring.forall(!_) && emptyAfter, "StoreBufferEmpty stays low while the last drain is outstanding", s"$emptyDuring $emptyAfter"),
        chk(d.empty, "StoreBufferEmpty rises once everything has drained", ""),
        chk(d.offerViolations.isEmpty, "the head is always offered while no drain is outstanding", s"${d.offerViolations.take(5)}")
      )
    }
  }

  // ---- funcCommittedForward ---------------------------------------------------------------------------

  val forward = new SpecTest("sb.forward", Seq("funcCommittedForward")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.drainReady = false
      d.pushOne(St(0x100, 0x11223344L, 0xf)); d.pushOne(St(0x101, 0x0000aa00L, 0x2)); d.pushOne(St(0x104, 0x55000000L, 0x8))
      val w  = d.ask(0x100, 0xf)
      val hi = d.ask(0x102, 0xc)
      val nx = d.ask(0x108, 0xf)
      val nb = d.ask(0x104, 0x7)
      // An issued but unanswered drain still forwards; after its response it no longer does.
      d.drainReady = true; d.drainLatency = 5; d.cycle(); d.drainReady = false
      val during = d.ask(0x100, 0xf)
      d.run(6)
      val after = d.ask(0x100, 0xf)
      Seq(
        chk(w.contains(Ans(0x1122aa44L, 0xf, false)), "per byte the youngest covering store (byte 1 from the younger store)", s"$w"),
        chk(hi.contains(Ans(0x11220000L, 0xc, false)), "only queried bytes are reported", s"$hi"),
        chk(nx.exists(_.hit == 0) && nb.exists(_.hit == 0), "a different word, or uncovered bytes of a covered word, do not hit", s"$nx $nb"),
        chk(d.offerViolations.isEmpty, "every query is answered in its own cycle", s"${d.offerViolations}"),
        chk(during.contains(Ans(0x1122aa44L, 0xf, false)), "a store whose drain is in flight is still forwarded", s"$during"),
        chk(after.contains(Ans(0x0000aa00L, 0x2, false)), "after its StoreDrainResp the drained store no longer forwards", s"$after")
      )
    }
  }

  // ---- funcDrainFence ---------------------------------------------------------------------------------------

  val drainFence = new SpecTest("sb.drainFence", Seq("funcDrainFence")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.drainReady = false
      d.pushOne(St(0x200, 1, 0xf)); d.pushOne(St(0x204, 2, 0xf))
      d.fenceReq = true; d.cycle(); val accepted = d.fenceReqFire; d.fenceReq = false
      var early = false
      (0 until 5).foreach { _ => d.cycle(); early ||= d.fenceRespFire }
      d.drainReady = true; d.drainLatency = 3
      var respAt = -1; var k = 0
      while (respAt < 0 && k < 40) { d.cycle(); if (d.fenceRespFire) respAt = d.cyc; k += 1 }
      val allDrainedFirst = d.drained.size == 2 && d.drained.forall(_._1 < respAt)
      // Empty buffer: the fence completes at once.
      d.fenceReq = true; d.cycle(); d.fenceReq = false
      val quick = { var f = d.fenceRespFire; (0 until 2).foreach { _ => d.cycle(); f ||= d.fenceRespFire }; f }
      Seq(
        chk(accepted && !early, "a drain request is accepted and not answered while stores remain", s"$accepted $early"),
        chk(respAt > 0 && allDrainedFirst, "StoreBufferDrainResp follows the drain of every entry present at acceptance", s"$respAt ${d.drained}"),
        chk(quick, "with an empty buffer the drain completes immediately", "")
      )
    } :+ mustAssert(this, "a committed store accepted while a drain fence is pending asserts",
      "DrainFence: a store was accepted while a drain fence is pending") { d =>
      d.drainReady = false; d.pushOne(St(0x200, 1, 0xf))
      d.fenceReq = true; d.cycle(); d.fenceReq = false
      d.pushOne(St(0x204, 2, 0xf), max = 2)
    }
  }

  // ---- propStoreBufferLiveness ---------------------------------------------------------------------------------

  val liveness = new SpecTest("sb.liveness", Seq("propStoreBufferLiveness")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.drainLatency = 7
      val stream = (0 until 20).map(i => St(0x400L + 4 * (i % 3), i.toLong, 0xf))
      var k = 0; var guard = 0; var stalls = 0
      while (k < stream.size && guard < 2000) {
        guard += 1
        d.drainReady = guard % 5 == 0 // a slow bus
        d.push = Some(stream(k)); d.cycle()
        if (d.pushFire) k += 1 else stalls += 1
      }
      d.push = None
      var g2 = 0
      while (d.drained.size < stream.size && g2 < 500) { d.drainReady = g2 % 5 == 0; d.cycle(); g2 += 1 }
      d.run(10)
      Seq(
        chk(k == stream.size && stalls > 0, "with a full buffer and a slow bus, store commit stalls but always progresses", s"$k $stalls"),
        chk(d.drained.map(_._2) == stream && d.empty, "every store drains, in order", s"${d.drained.size}"),
        chk(d.offerViolations.isEmpty, "the head is offered in every cycle without an outstanding drain", s"${d.offerViolations.take(5)}")
      )
    }
  }

  // ---- propCommittedSurvivesRecovery ---------------------------------------------------------------------------

  val recoveryExempt = new SpecTest("sb.recoveryExempt", Seq("propCommittedSurvivesRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val ports = d.io.elements.keys.toSeq
      Seq(chk(!ports.exists(_.toLowerCase.contains("recovery")), "the StoreBuffer has no RecoveryEvent input (recovery-exempt)", s"$ports"))
    }
  }

  val all: Seq[SpecTest] = Seq(drainOrder, forward, drainFence, liveness, recoveryExempt)
}
