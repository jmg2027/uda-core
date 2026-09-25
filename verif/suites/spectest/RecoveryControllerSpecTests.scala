package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.modules.RecoveryController
import udacore.backend.design.shared.{BackendParams, CfiType, RecoveryCause, RecoveryKind}

/** L1 SpecTests for the ADR-019 RecoveryController (ADR-018 Spec-TDD; spec 242feaf +
  * ADR-019A).
  *
  * Scope: funcRecoverySelect, funcRecoveryPublish, propSingleRecoveryPerCycle,
  * propArchRedirectWins. The test plays the BranchUnit (BranchResolution) and the
  * TrapController (ArchRedirect) and observes the RecoveryEvent broadcast. Both inputs
  * are always ready and a request is published or discarded in the cycle it is offered,
  * so every expectation is read in the offering cycle and re-checked one cycle later
  * (no queue, no late publication).
  */
object RecoveryControllerSpecTests {

  val p = BackendParams()
  val D = p.robDepth

  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)

  case class Br(s: Int, ckpt: Int, ftq: Int, pc: Long, cfiType: Int, slot: Int, taken: Boolean,
      outTarget: Long, redirect: Long, cause: UInt)
  case class Ar(s: Int, ftq: Int, target: Long, cause: UInt)

  /** The observed event. */
  case class Ev(valid: Boolean, kind: Int, tag: (Boolean, Int), ckpt: Int, target: Long, ftq: Int,
      cfiType: Int, slot: Int, taken: Boolean, cfiTarget: Long, cause: Int)

  class Drv(val dut: RecoveryController) {
    val io = dut.io

    def idle(): Unit = {
      io.branchResolutionIn.valid.poke(false.B)
      io.archRedirectIn.valid.poke(false.B)
    }

    def pokeBr(b: Br): Unit = {
      val r = io.branchResolutionIn.bits
      io.branchResolutionIn.valid.poke(true.B)
      r.robTag.wrap.poke(tagOf(b.s)._1.B); r.robTag.idx.poke(tagOf(b.s)._2.U)
      r.checkpointId.id.poke(b.ckpt.U)
      r.ftqIdx.poke(b.ftq.U)
      r.pc.poke(b.pc.U)
      r.outcome.cfiType.poke(b.cfiType.U)
      r.outcome.slot.poke(b.slot.U)
      r.outcome.taken.poke(b.taken.B)
      r.outcome.target.poke(b.outTarget.U)
      r.redirectTarget.poke(b.redirect.U)
      r.cause.poke(b.cause)
    }

    def pokeAr(a: Ar): Unit = {
      val r = io.archRedirectIn.bits
      io.archRedirectIn.valid.poke(true.B)
      r.robTag.wrap.poke(tagOf(a.s)._1.B); r.robTag.idx.poke(tagOf(a.s)._2.U)
      r.ftqIdx.poke(a.ftq.U)
      r.target.poke(a.target.U)
      r.cause.poke(a.cause)
    }

    def event(): Ev = {
      val e = io.recoveryEventOut
      def b(x: Bool) = x.peek().litToBoolean
      def i(x: UInt) = x.peek().litValue.toInt
      def l(x: UInt) = x.peek().litValue.toLong
      Ev(b(e.valid), i(e.kind), (b(e.robTag.wrap), i(e.robTag.idx)), i(e.checkpointId.id), l(e.target),
        i(e.ftqIdx), i(e.cfiOutcome.cfiType), i(e.cfiOutcome.slot), b(e.cfiOutcome.taken),
        l(e.cfiOutcome.target), i(e.cause))
    }

    def readies(): (Boolean, Boolean) =
      (io.branchResolutionIn.ready.peek().litToBoolean, io.archRedirectIn.ready.peek().litToBoolean)

    /** Offer the requests for one cycle: returns (event in that cycle, readies, event next cycle). */
    def cycle(br: Option[Br], ar: Option[Ar]): (Ev, (Boolean, Boolean), Ev) = {
      br.foreach(pokeBr); ar.foreach(pokeAr)
      val e = event(); val r = readies()
      dut.clock.step()
      idle()
      val after = event()
      (e, r, after)
    }
  }

  private def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] = t.sim(new RecoveryController(p)) { dut =>
    val d = new Drv(dut); d.idle(); dut.clock.step(); body(d)
  }

  private def mustAssert(t: SpecTest, label: String, expect: String)(body: Drv => Unit): TCheck = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.dut.clock.step(3); reachedEnd = true; Nil }
      TCheck(false, label, "simulation ended normally: the design assertion did not fire")
    } catch {
      case e: NotImplementedError => throw e
      case e: Throwable =>
        val log   = chisel3.simulator.CachedSimulator.lastSimulationLog
        val fired = log.linesIterator.filter(_.contains("Assertion failed")).toSeq
        val ok    = !reachedEnd && fired.exists(_.contains(expect))
        val why   = if (fired.isEmpty) s"no assertion in the simulation log (${e.getClass.getSimpleName})"
                    else fired.head.trim.take(200)
        TCheck(ok, label, if (ok) "" else s"expected '$expect'; got: $why")
    }
  }

  private def chk(ok: Boolean, label: String, detail: => String): TCheck =
    TCheck(ok, label, if (ok) "" else detail)

  private def code(u: UInt) = u.litValue.toInt
  private val BM = code(RecoveryKind.BranchMispredict)
  private val AR = code(RecoveryKind.ArchRedirect)

  val br1 = Br(s = 5, ckpt = 3, ftq = 9, pc = 0x1000, cfiType = code(CfiType.Branch), slot = 2, taken = false,
    outTarget = 0x1800, redirect = 0x1004, cause = RecoveryCause.DirectionMispredict)
  val ar1 = Ar(s = 2, ftq = 7, target = 0x80000000L, cause = RecoveryCause.Trap)

  // ---- funcRecoverySelect ---------------------------------------------------------

  val select = new SpecTest("rc.select", Seq("funcRecoverySelect")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val none = d.cycle(None, None)
      val b    = d.cycle(Some(br1), None)
      val a    = d.cycle(None, Some(ar1))
      val both = d.cycle(Some(br1), Some(ar1))
      Seq(
        chk(!none._1.valid, "no request, no event", s"$none"),
        chk(b._1.valid && b._1.kind == BM && b._1.tag == tagOf(5), "a lone BranchResolution is published", s"$b"),
        chk(a._1.valid && a._1.kind == AR && a._1.tag == tagOf(2), "a lone ArchRedirect is published", s"$a"),
        chk(both._1.valid && both._1.kind == AR && both._1.tag == tagOf(2),
          "with both, the ArchRedirect is published", s"$both"),
        chk(!both._3.valid, "the same-cycle BranchResolution is discarded, not published later", s"${both._3}"),
        chk(Seq(none, b, a, both).forall(_._2 == ((true, true))), "both inputs are always ready", s"${Seq(none, b, a, both).map(_._2)}")
      )
    }
  }

  // ---- funcRecoveryPublish ---------------------------------------------------------

  val publish = new SpecTest("rc.publish", Seq("funcRecoveryPublish")) {
    def run(): Seq[TCheck] = {
      val main = withDrv(this) { d =>
        val taken = br1.copy(s = 20, ckpt = 1, ftq = 30, taken = true, cfiType = code(CfiType.Jalr), slot = 3,
          outTarget = 0x2468, redirect = 0x2468, cause = RecoveryCause.TargetMispredict)
        val (e1, _, n1) = d.cycle(Some(br1), None)
        val (e2, _, _)  = d.cycle(Some(taken), None)
        val (e3, _, n3) = d.cycle(None, Some(ar1.copy(s = 31, ftq = 4, target = 0x1234, cause = RecoveryCause.Refetch)))
        Seq(
          chk(e1 == Ev(true, BM, tagOf(5), 3, 0x1004, 9, code(CfiType.Branch), 2, false, 0x1800,
            code(RecoveryCause.DirectionMispredict)),
            "BranchMispredict = {robTag, checkpointId, target = redirectTarget, ftqIdx, cfiOutcome with slot, cause}", s"$e1"),
          chk(e2.target == 0x2468 && e2.tag == tagOf(20) && e2.ckpt == 1 && e2.ftq == 30 && e2.slot == 3 &&
            e2.taken && e2.cfiType == code(CfiType.Jalr) && e2.cause == code(RecoveryCause.TargetMispredict),
            "a taken target mispredict carries its own fields (wrap bit included)", s"$e2"),
          chk(e3.valid && e3.kind == AR && e3.tag == tagOf(31) && e3.target == 0x1234 && e3.ftq == 4 &&
            e3.cause == code(RecoveryCause.Refetch),
            "ArchRedirect = {robTag, target, ftqIdx, cause}", s"$e3"),
          chk(!n1.valid && !n3.valid, "the event is published in the request cycle only", s"$n1 $n3")
        )
      }
      main ++ Seq(
        mustAssert(this, "a BranchResolution with an architectural cause stops the run",
          "RecoveryPublish: request cause does not match its kind") { d =>
          d.cycle(Some(br1.copy(cause = RecoveryCause.Trap)), None)
        },
        mustAssert(this, "an ArchRedirect with a branch cause stops the run",
          "RecoveryPublish: request cause does not match its kind") { d =>
          d.cycle(None, Some(ar1.copy(cause = RecoveryCause.DirectionMispredict)))
        }
      )
    }
  }

  // ---- Randomized request stream ----------------------------------------------------

  private val brCauses = Seq(RecoveryCause.DirectionMispredict, RecoveryCause.TargetMispredict, RecoveryCause.UnpredictedCfi)
  private val arCauses = Seq(RecoveryCause.Trap, RecoveryCause.Interrupt, RecoveryCause.XRet, RecoveryCause.Refetch)

  /** Random request pairs; the published event must be exactly the selected request. */
  private def randomRun(d: Drv, seed: Long, steps: Int): (Seq[String], Int, Int) = {
    val rnd = new scala.util.Random(seed)
    var bad = Seq.empty[String]
    var collisions, events = 0
    for (step <- 0 until steps) {
      val br = if (rnd.nextInt(3) > 0) Some(Br(rnd.nextInt(2 * D), rnd.nextInt(p.checkpointCount), rnd.nextInt(32),
        rnd.nextInt(1 << 20).toLong * 4, code(CfiType.Branch), rnd.nextInt(4), rnd.nextBoolean(),
        rnd.nextInt(1 << 20).toLong * 4, rnd.nextInt(1 << 20).toLong * 4, brCauses(rnd.nextInt(3)))) else None
      val ar = if (rnd.nextInt(3) == 0) Some(Ar(rnd.nextInt(2 * D), rnd.nextInt(32),
        rnd.nextInt(1 << 20).toLong * 4, arCauses(rnd.nextInt(4)))) else None
      val (e, r, _) = d.cycle(br, ar)
      if (br.nonEmpty && ar.nonEmpty) collisions += 1
      if (e.valid) events += 1
      val expect: Option[Ev] = ar.map(a => (a.s, a.ftq, a.target, a.cause, AR)).orElse(br.map(b =>
        (b.s, b.ftq, b.redirect, b.cause, BM))).map { case (s, f, t, c, k) =>
        Ev(true, k, tagOf(s), 0, t, f, 0, 0, false, 0, code(c)) }
      val got = e.copy(ckpt = 0, cfiType = 0, slot = 0, taken = false, cfiTarget = 0)
      if (expect.isEmpty && e.valid) bad :+= s"step $step: event without request $e"
      expect.foreach(x => if (got != x) bad :+= s"step $step: got $e expected $x")
      if (br.nonEmpty && ar.isEmpty && e.ckpt != br.get.ckpt) bad :+= s"step $step: checkpointId $e"
      if (r != ((true, true))) bad :+= s"step $step: readies $r"
    }
    (bad, collisions, events)
  }

  // ---- propSingleRecoveryPerCycle -----------------------------------------------------

  val single = new SpecTest("rc.single", Seq("propSingleRecoveryPerCycle")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val (bad, coll, ev) = randomRun(d, 23L, 400)
      Seq(
        chk(bad.isEmpty, "each cycle carries at most one event, identical to the selected request", bad.take(4).mkString("; ")),
        chk(coll > 20 && ev > 200, "the stream exercised collisions and events", s"collisions=$coll events=$ev")
      )
    }
  }

  // ---- propArchRedirectWins -------------------------------------------------------------

  val archWins = new SpecTest("rc.archWins", Seq("propArchRedirectWins")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // Every collision shape: causes x (older/younger/equal tags); the ArchRedirect always wins.
      val shapes = for (ac <- arCauses; bc <- brCauses; (as, bs) <- Seq((2, 9), (9, 2), (4, 4), (15, 16)))
        yield (Ar(as, 1, 0x100, ac), Br(bs, 2, 3, 0x200, code(CfiType.Branch), 1, true, 0x300, 0x300, bc))
      val res = shapes.map { case (a, b) => (a, b, d.cycle(Some(b), Some(a))) }
      val lost = res.filterNot { case (a, _, (e, _, n)) => e.valid && e.kind == AR && e.tag == tagOf(a.s) && !n.valid }
      val (bad, coll, _) = randomRun(d, 29L, 300)
      Seq(
        chk(lost.isEmpty, s"with both inputs valid the event kind is ArchRedirect (${shapes.size} shapes)",
          lost.take(3).map(x => s"${x._3}").mkString("; ")),
        chk(bad.isEmpty && coll > 20, "random collisions agree", bad.take(3).mkString("; ") + s" collisions=$coll")
      )
    }
  }

  val all: Seq[SpecTest] = Seq(select, publish, single, archWins)
}
