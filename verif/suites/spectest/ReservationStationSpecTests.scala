package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.modules.ReservationStation
import udacore.backend.design.shared.{BackendParams, FuType, RecoveryKind}

/** L1 SpecTests for the ADR-019 ReservationStation (ADR-018; spec 242feaf + ADR-019A/C).
  *
  * The test plays RenameUnit (allocations), PublishMux (wakeups), the PRF (combinational
  * operand answers, value = 0x1000 + prs), DispatchUnit (FuAvailability and
  * IssuedUopOut.ready), and the RecoveryController. Uops are named by program-order
  * sequence numbers s (robTag = {s / 16 odd, s mod 16}).
  *
  * Ready-bit timing: a wakeup in cycle t (or a ready bit captured at allocation in t) makes
  * the entry selectable from cycle t + 1; the PRF bypass covers the value.
  */
object ReservationStationSpecTests {

  val p = BackendParams()
  val D = p.robDepth
  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)
  def seqOf(t: (Boolean, Int), near: Int): Int = {
    // the live sequence number with this tag closest to `near`
    (near - D until near + D).find(s => s >= 0 && tagOf(s) == t).getOrElse(-1)
  }
  def prf(prs: Int): Long = 0x1000L + prs

  case class RA(s: Int, fu: UInt = FuType.Alu, prs1: Int = 0, prs2: Int = 0, r1: Boolean = true, r2: Boolean = true,
      prd: Int = 40, exc: Boolean = false, imm: Long = 0, pc: Long = 0x100)

  case class Iss(s: Int, fu: Int, src1: Long, src2: Long, prd: Int, pc: Long, insn: Long = 0, sysOp: Int = 0)
  /** Distinct per-allocation instruction word and sysOp (ADR-019D E-2 metadata). */
  def insnOf(s: Int): Long = 0x73L | ((0x300L + s) & 0xfffL) << 20
  def sysOpOf(s: Int): Int = s % 8
  case class Obs(allocReady: Boolean, allocFire: Boolean, issValid: Boolean, issFire: Boolean, iss: Option[Iss],
      prfReqValid: Boolean)

  class Drv(val dut: ReservationStation) {
    val io = dut.io
    var avail: Map[Int, Boolean] = Map().withDefaultValue(true)
    var issuedReady = true
    var near = 0

    def setAll(b: Boolean): Unit = avail = Map[Int, Boolean]().withDefaultValue(b)
    def set(fu: UInt, b: Boolean): Unit = avail = avail.updated(fu.litValue.toInt, b)

    def cycle(alloc: Option[RA] = None, wake: Seq[Int] = Nil, event: Option[(UInt, Int)] = None): Obs = {
      val a = io.rsAllocIn
      a.valid.poke(alloc.nonEmpty.B)
      alloc.foreach { r =>
        a.bits.robTag.wrap.poke(tagOf(r.s)._1.B); a.bits.robTag.idx.poke(tagOf(r.s)._2.U)
        a.bits.uop.fuType.poke(r.fu); a.bits.uop.op.poke(0.U); a.bits.uop.imm.poke(r.imm.U); a.bits.uop.pc.poke(r.pc.U)
        a.bits.uop.insn.poke(insnOf(r.s).U); a.bits.uop.sysOp.poke(sysOpOf(r.s).U)
        a.bits.uop.exception.valid.poke(r.exc.B); a.bits.uop.isLoad.poke((r.fu.litValue == FuType.Mem.litValue).B)
        a.bits.prs1.poke(r.prs1.U); a.bits.prs2.poke(r.prs2.U)
        a.bits.prs1Ready.poke(r.r1.B); a.bits.prs2Ready.poke(r.r2.B)
        a.bits.hasDest.poke(true.B); a.bits.newPrd.poke(r.prd.U); a.bits.checkpointId.id.poke(0.U)
        near = math.max(near, r.s)
      }
      val w = io.wakeupBroadcastIn
      require(wake.size <= 1, "one wakeup per cycle (PublishWidth 1)")
      w.valid.poke(wake.nonEmpty.B); w.prd.poke(wake.headOption.getOrElse(0).U)
      val av = io.fuAvailabilityIn
      av.alu.poke(avail(FuType.Alu.litValue.toInt).B); av.mul.poke(avail(FuType.Mul.litValue.toInt).B)
      av.div.poke(avail(FuType.Div.litValue.toInt).B); av.branch.poke(avail(FuType.Branch.litValue.toInt).B)
      av.mem.poke(avail(FuType.Mem.litValue.toInt).B); av.csr.poke(avail(FuType.Csr.litValue.toInt).B)
      val e = io.recoveryEventIn
      e.valid.poke(event.nonEmpty.B)
      event.foreach { case (k, s) => e.kind.poke(k); e.robTag.wrap.poke(tagOf(s)._1.B); e.robTag.idx.poke(tagOf(s)._2.U) }
      // PRF: combinational answer
      val q = io.registerFileReadReqOut; val r = io.registerFileReadRespIn
      val qv = q.valid.peek().litToBoolean
      r.valid.poke(qv.B)
      r.bits.src1.poke(prf(q.bits.prs1.peek().litValue.toInt).U)
      r.bits.src2.poke(prf(q.bits.prs2.peek().litValue.toInt).U)
      io.issuedUopOut.ready.poke(issuedReady.B)
      val iv = io.issuedUopOut.valid.peek().litToBoolean
      val ib = io.issuedUopOut.bits
      val iss = if (!iv) None else Some(Iss(
        seqOf((ib.robTag.wrap.peek().litToBoolean, ib.robTag.idx.peek().litValue.toInt), near),
        ib.fuType.peek().litValue.toInt, ib.src1.peek().litValue.toLong, ib.src2.peek().litValue.toLong,
        ib.prd.peek().litValue.toInt, ib.pc.peek().litValue.toLong, ib.insn.peek().litValue.toLong,
        ib.sysOp.peek().litValue.toInt))
      val o = Obs(a.ready.peek().litToBoolean, alloc.nonEmpty && a.ready.peek().litToBoolean, iv,
        iv && issuedReady, iss, qv)
      dut.clock.step()
      o
    }
    def run(n: Int): Seq[Obs] = (0 until n).map(_ => cycle())
    def issuedSeq(os: Seq[Obs]): Seq[Int] = os.filter(_.issFire).flatMap(_.iss.map(_.s))
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new ReservationStation(p)) { dut => val d = new Drv(dut); d.cycle(); body(d) }

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  def mustAssert(t: SpecTest, label: String, expect: String)(body: Drv => Unit): TCheck = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.run(3); reachedEnd = true; Nil }
      TCheck(false, label, "simulation ended normally: the design assertion did not fire")
    } catch {
      case e: NotImplementedError => throw e
      case e: Throwable =>
        val fired = chisel3.simulator.CachedSimulator.lastSimulationLog.linesIterator.filter(_.contains("Assertion failed")).toSeq
        val ok = !reachedEnd && fired.exists(_.contains(expect))
        TCheck(ok, label, if (ok) "" else s"expected '$expect'; got: ${fired.headOption.getOrElse(e.getClass.getSimpleName)}")
    }
  }

  val BM = RecoveryKind.BranchMispredict
  val AR = RecoveryKind.ArchRedirect

  // ---- funcRsWakeup ---------------------------------------------------------------------

  val wakeup = new SpecTest("rs.wakeup", Seq("funcRsWakeup")) {
    def run(): Seq[TCheck] = {
      val main = withDrv(this) { d =>
        val a0 = d.cycle(alloc = Some(RA(0, prs1 = 33, r1 = false, prs2 = 34, r2 = true, prd = 41)))
        val idle = d.run(3)
        val wk = d.cycle(wake = Seq(33))
        val iss = d.cycle()
        // A wakeup in the allocation cycle is captured.
        val a1 = d.cycle(alloc = Some(RA(1, prs1 = 35, r1 = false, prs2 = 0, prd = 42)), wake = Seq(35))
        val iss1 = d.cycle()
        // Ready bits captured at rename.
        d.cycle(alloc = Some(RA(2, prs1 = 36, prs2 = 37, prd = 43)))
        val iss2 = d.cycle()
        // A wakeup of an unrelated prd does nothing.
        d.cycle(alloc = Some(RA(3, prs1 = 38, r1 = false, prd = 44)))
        val other = d.run(2).head
        d.cycle(wake = Seq(38)); val iss3 = d.cycle()
        Seq(
          chk(a0.allocFire && idle.forall(!_.issValid), "an entry with a busy source is not issued", s"$idle"),
          chk(!wk.issValid && iss.issFire && iss.iss.exists(i => i.s == 0 && i.src1 == prf(33) && i.src2 == prf(34) && i.prd == 41),
            "a wakeup sets the source ready; the entry issues the next cycle with PRF operands", s"$wk $iss"),
          chk(iss1.iss.exists(_.s == 1), "a wakeup in the allocation cycle is captured", s"$iss1"),
          chk(Seq(iss, iss1, iss2, iss3).flatMap(_.iss).forall(i => i.insn == insnOf(i.s) && i.sysOp == sysOpOf(i.s)),
            "the entry carries insn and sysOp to the IssuedUop (ADR-019D E-2)", s"$iss $iss1 $iss2 $iss3"),
          chk(iss2.iss.exists(_.s == 2), "ready bits captured at rename allow issue the next cycle", s"$iss2"),
          chk(!other.issValid && iss3.iss.exists(_.s == 3), "only the matching prd wakes an entry", s"$other $iss3")
        )
      }
      main :+ mustAssert(this, "allocating an execution-free uop (System) stops the run",
        "ReservationStation: only needsRs uops are allocated") { d =>
        d.cycle(alloc = Some(RA(0, fu = FuType.System)))
      }
    }
  }

  // ---- funcSelectOldestReady ------------------------------------------------------------------

  val select = new SpecTest("rs.select", Seq("funcSelectOldestReady")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.setAll(false)
      d.cycle(alloc = Some(RA(3, prd = 43))); d.cycle(alloc = Some(RA(1, prd = 41))); d.cycle(alloc = Some(RA(2, prd = 42)))
      val none = d.run(2)
      d.setAll(true)
      val order = d.issuedSeq(d.run(4))
      // Busy DIV: an older ready DIV does not block a younger ALU.
      d.set(FuType.Div, false)
      d.cycle(alloc = Some(RA(4, fu = FuType.Div, prd = 44))); d.cycle(alloc = Some(RA(5, fu = FuType.Alu, prd = 45)))
      val busy = d.run(3)
      d.set(FuType.Div, true)
      val divGo = d.run(2)
      // Within a class, oldest first; other classes unavailable.
      d.set(FuType.Alu, false); d.set(FuType.Div, false)
      d.cycle(alloc = Some(RA(8, fu = FuType.Alu, prd = 47)))
      d.cycle(alloc = Some(RA(7, fu = FuType.Div, prd = 45))); d.cycle(alloc = Some(RA(6, fu = FuType.Div, prd = 46)))
      d.set(FuType.Div, true)
      val divs = d.issuedSeq(d.run(4))
      d.set(FuType.Alu, true)
      val alu8 = d.issuedSeq(d.run(2))
      Seq(
        chk(none.forall(o => !o.issValid && !o.prfReqValid), "no available class: nothing is issued and the PRF is not read", s"$none"),
        chk(order == Seq(1, 2, 3), "ready entries of an available class issue oldest first", s"$order"),
        chk(d.issuedSeq(busy) == Seq(5) && busy.forall(_.iss.forall(_.s != 4)),
          "an older ready DIV behind a busy divider does not block a younger ALU", s"${d.issuedSeq(busy)}"),
        chk(d.issuedSeq(divGo) == Seq(4), "the DIV issues once its class is available", s"${d.issuedSeq(divGo)}"),
        chk(divs == Seq(6, 7), "within one class the oldest ready entry is chosen", s"$divs"),
        chk(alu8 == Seq(8), "the ALU entry issues when ALU becomes available", s"$alu8")
      )
    }
  }

  // ---- funcRsRecovery -----------------------------------------------------------------------------

  val recovery = new SpecTest("rs.recovery", Seq("funcRsRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      for ((s, prd) <- Seq((1, 51), (3, 53), (5, 55), (6, 56)))
        d.cycle(alloc = Some(RA(s, fu = if (s == 3) FuType.Branch else FuType.Alu, prs1 = 20 + s, r1 = false, prd = prd)))
      d.cycle(event = Some((BM, 3)))
      val woke = (Seq(21, 23, 25, 26).map(w => d.cycle(wake = Seq(w))) ++ d.run(4))
      val got = d.issuedSeq(woke)
      // Capacity: the killed entries were freed (8 - 0 live = 8 allocations fit).
      val fills = (10 until 18).map(s => d.cycle(alloc = Some(RA(s, prs1 = 45, r1 = false)))).map(_.allocFire)
      val full = d.cycle(alloc = Some(RA(18, prs1 = 45, r1 = false)))
      d.cycle(event = Some((AR, 10)))
      val afterArch = d.cycle(wake = Seq(45)) +: d.run(3)
      val fillsAgain = (20 until 28).map(s => d.cycle(alloc = Some(RA(s, prs1 = 46, r1 = false)))).map(_.allocFire)
      Seq(
        chk(got == Seq(1, 3), "BranchMispredict kills only younger entries; the recovering branch and older survive", s"$got"),
        chk(fills.forall(identity) && !full.allocFire, "killed entries are freed; ready drops when all entries are live", s"$fills $full"),
        chk(afterArch.forall(!_.issValid) && fillsAgain.forall(identity), "ArchRedirect kills every entry", s"$afterArch")
      )
    }
  }

  // ---- Reference model for the properties ------------------------------------------------------------

  /** Random allocations, wakeups, availability, backpressure, and recoveries against a
    * reference RS: each cycle the offered uop must equal the model's choice. */
  def randomRun(d: Drv, seed: Long, steps: Int): Seq[TCheck] = {
    case class E(s: Int, fu: UInt, prs1: Int, prs2: Int, var r1: Boolean, var r2: Boolean, uid: Int)
    var uids = 0
    val rnd = new scala.util.Random(seed)
    val live = ArrayBuffer.empty[E]
    var next = 0
    var held: Option[Int] = None
    var bad = Seq.empty[String]
    var issued = Seq.empty[Int] // allocation ids (sequence numbers are reused after a recovery)
    var kills, events, stalls, avMiss = 0
    val fus = Seq(FuType.Alu, FuType.Mul, FuType.Div, FuType.Branch, FuType.Mem, FuType.Csr)
    val pendingPrds = ArrayBuffer.empty[Int]
    for (step <- 0 until steps) {
      val ev: Option[(UInt, Int)] =
        if (live.nonEmpty && rnd.nextInt(25) == 0) { val e = live(rnd.nextInt(live.size)); Some((BM, e.s)) }
        else if (live.nonEmpty && rnd.nextInt(150) == 0) Some((AR, live.head.s))
        else None
      // RenameUnit never allocates in a RecoveryEvent cycle.
      val alloc = if (ev.isEmpty && rnd.nextInt(3) > 0 && live.size < p.tuning.integerRsEntries && next - live.headOption.map(_.s).getOrElse(next) < D - 1) {
        val fu = fus(rnd.nextInt(fus.size))
        val p1 = 32 + rnd.nextInt(16); val p2 = 32 + rnd.nextInt(16)
        Some(RA(next, fu = fu, prs1 = p1, prs2 = p2, r1 = rnd.nextInt(3) == 0, r2 = rnd.nextInt(2) == 0, prd = 32 + (next % 16)))
      } else None
      val wake = if (rnd.nextInt(3) > 0) Seq(32 + rnd.nextInt(16)) else Nil
      d.avail = fus.map(f => f.litValue.toInt -> (rnd.nextInt(4) > 0)).toMap.withDefaultValue(false)
      d.issuedReady = rnd.nextInt(8) > 0
      // Model choice for this cycle (registered ready bits, current availability, event kills).
      def killed(s: Int) = ev.exists { case (k, es) => k.litValue == AR.litValue || s > es }
      val eligible = live.filter(e => e.r1 && e.r2 && d.avail(e.fu.litValue.toInt) && !killed(e.s))
      val expect: Option[Int] = held.filter(h => !killed(h)).orElse(eligible.map(_.s).sorted.headOption)
      val o = d.cycle(alloc, wake, ev)
      val got = o.iss.map(_.s)
      if (got != expect) bad :+= s"step $step: offered $got expected $expect"
      if (held.nonEmpty && expect.exists(x => !d.avail(live.find(_.s == x).map(_.fu.litValue.toInt).getOrElse(0)))) avMiss += 1
      // State update in cycle order.
      if (o.issFire) { issued ++= live.filter(_.s == got.get).map(_.uid); live --= live.filter(_.s == got.get) }
      held = if (o.issValid && !o.issFire) got else None
      ev.foreach { case (k, es) =>
        events += 1
        val before = live.size
        live --= live.filter(e => killed(e.s))
        kills += before - live.size
        next = es + 1
        d.near = es
      }
      if (o.issValid && !o.issFire) stalls += 1
      live.foreach { e => if (wake.contains(e.prs1)) e.r1 = true; if (wake.contains(e.prs2)) e.r2 = true }
      alloc.foreach { r =>
        if (!o.allocFire) bad :+= s"step $step: allocation refused with ${live.size} live"
        if (o.allocFire) {
          live += E(r.s, r.fu, r.prs1, r.prs2, r.r1 || wake.contains(r.prs1), r.r2 || wake.contains(r.prs2), uids); uids += 1; next += 1
        }
      }
    }
    // Drain: everything live must eventually issue once all sources wake and all classes are free.
    d.setAll(true); d.issuedReady = true
    def drainOne(o: Obs): Unit = if (o.issFire) { issued ++= live.filter(_.s == o.iss.get.s).map(_.uid); live --= live.filter(_.s == o.iss.get.s) }
    val left = live.toList
    (32 until 48).foreach(w => drainOne(d.cycle(wake = Seq(w))))
    (0 until 12).foreach(_ => drainOne(d.cycle()))
    Seq(
      chk(bad.isEmpty, s"the offered uop equals the reference choice every cycle (seed $seed)", bad.take(4).mkString("; ")),
      chk(issued.distinct.size == issued.size, s"no uop issues twice (seed $seed)", s"${issued.diff(issued.distinct)}"),
      chk(live.isEmpty && left.forall(e => issued.contains(e.uid)), s"no live entry is lost (seed $seed)", s"${live.map(_.s)}"),
      chk(issued.size > steps / 6 && kills > 0 && events > 3 && stalls > 3,
        s"history exercised issue/kill/backpressure (seed $seed)", s"issued=${issued.size} kills=$kills events=$events stalls=$stalls")
    )
  }

  // ---- Properties -------------------------------------------------------------------------------------------

  val issueOnlyReady = new SpecTest("rs.issueOnlyReady", Seq("propRsIssueOnlyReady")) {
    def run(): Seq[TCheck] = withDrv(this)(d => randomRun(d, 61L, 600))
  }

  val recoveryKeepsOlder = new SpecTest("rs.recoveryKeepsOlder", Seq("propRsRecoveryKeepsOlder")) {
    def run(): Seq[TCheck] = {
      val directed = withDrv(this) { d =>
        for (s <- 1 to 5) d.cycle(alloc = Some(RA(s, prs1 = 20 + s, r1 = false, prd = 40 + s)))
        d.cycle(event = Some((BM, 3)))
        val got = d.issuedSeq((1 to 5).map(s => d.cycle(wake = Seq(20 + s))) ++ d.run(4))
        Seq(chk(got == Seq(1, 2, 3), "entries at or older than the recovering branch keep their state and issue", s"$got"))
      }
      directed ++ withDrv(this)(d => randomRun(d, 67L, 500))
    }
  }

  val issueStable = new SpecTest("rs.issueStable", Seq("propRsIssueStable")) {
    def run(): Seq[TCheck] = {
      val directed = withDrv(this) { d =>
        d.cycle(alloc = Some(RA(1, prs1 = 21, r1 = false, prd = 41)))
        d.cycle(alloc = Some(RA(2, prd = 42)))
        d.issuedReady = false
        val h1 = d.cycle()                 // offers s2 (the only ready one), not accepted
        val h2 = d.cycle(wake = Seq(21))   // older s1 becomes ready: the offer stays s2
        val h3 = d.cycle()
        d.issuedReady = true
        val f = d.cycle()
        val f1 = d.cycle()
        // A held offer killed by a same-cycle RecoveryEvent is not issued and is freed.
        d.issuedReady = false
        d.cycle(alloc = Some(RA(3, prd = 43))); d.cycle(alloc = Some(RA(4, prd = 44)))
        val k0 = d.cycle()                 // offers s3 (held)
        d.issuedReady = true
        val k1 = d.cycle(event = Some((BM, 2)))
        val k2 = d.run(3)
        Seq(
          chk(Seq(h1, h2, h3).forall(o => o.issValid && !o.issFire && o.iss.exists(i => i.s == 2 && i.prd == 42)),
            "an offered uop stays unchanged while not accepted, even when an older entry becomes ready", s"$h1 $h2 $h3"),
          chk(f.issFire && f.iss.exists(_.s == 2) && f1.issFire && f1.iss.exists(_.s == 1), "no entry is lost: s2 then s1 issue", s"$f $f1"),
          chk(k0.iss.exists(_.s == 3) && !k1.issValid && k2.forall(!_.issValid),
            "a selected entry killed in the same cycle is not issued and is freed", s"$k0 $k1 $k2")
        )
      }
      directed ++ withDrv(this)(d => randomRun(d, 71L, 500))
    }
  }

  val all: Seq[SpecTest] = Seq(wakeup, select, recovery, issueOnlyReady, recoveryKeepsOlder, issueStable)
}
