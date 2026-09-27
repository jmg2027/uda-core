package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.shared.{RecoveryCause, RecoveryKind}
import udacore.frontend.design.modules.FetchPcGen
import verif.spectest.FrontendTestKit._

/** L1 SpecTests for the ADR-019 FetchPcGen (ADR-018; FetchPcGenSpecs, ADR-019G E-1).
  *
  * The driver plays the BootSequencer (BootAddr), the RecoveryEvent broadcast, and the
  * BranchPredictor: in atomic mode (the v0 predictor) the NextPc of a request is offered in the
  * cycle the request transfers and the request ready waits for NextPc ready; in delayed mode the
  * test presents NextPc tokens explicitly.
  */
object FetchPcGenSpecTests {

  class Drv(val dut: FetchPcGen) {
    val io  = dut.io
    var cyc = 0
    var boot: Option[Long] = None
    var event: Option[Event] = None
    var bpReady = true
    var atomic  = true
    /** Next fetch PC the atomic predictor answers for a request PC (fall-through by default). */
    var nextOf: Long => Long = pc => (pc & ~15L) + 16
    /** Delayed mode: the NextPc token presented this cycle. */
    var token: Option[Long] = None
    val offers = ArrayBuffer[(Int, Long)]() // cycles with PredictReq valid
    val reqs   = ArrayBuffer[(Int, Long)]() // PredictReq transfers
    var bootFire, reqFire, tokenFire = false
    var bootReady = false

    def cycle(): Unit = {
      io.bootAddrIn.valid.poke(boot.nonEmpty.B); boot.foreach(a => io.bootAddrIn.bits.poke(a.U))
      pokeEvent(io.recoveryEventIn, event)
      io.nextPcIn.valid.poke(false.B)
      val v  = b(io.predictReqOut.valid)
      val pc = l(io.predictReqOut.bits.fetchPc)
      val nr = b(io.nextPcIn.ready)
      val fire = v && bpReady && (!atomic || nr)
      io.predictReqOut.ready.poke((bpReady && (!atomic || nr)).B)
      val tok = if (atomic) (if (fire) Some(nextOf(pc)) else None) else token
      io.nextPcIn.valid.poke(tok.nonEmpty.B); tok.foreach(t => io.nextPcIn.bits.fetchPc.poke(t.U))
      tokenFire = tok.nonEmpty && b(io.nextPcIn.ready)
      bootReady = b(io.bootAddrIn.ready)
      bootFire  = boot.nonEmpty && bootReady
      reqFire   = fire
      if (v) offers += ((cyc, pc))
      if (fire) reqs += ((cyc, pc))
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def bootWith(a: Long): Unit = { boot = Some(a); var k = 0; cycle(); while (!bootFire && k < 10) { cycle(); k += 1 }; boot = None }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new FetchPcGen(fp)) { dut => val d = new Drv(dut); body(d) }

  def redirect(target: Long, kind: UInt = RecoveryKind.BranchMispredict): Event =
    Event(kind, target, 0, Outcome(BRANCH, 0, taken = true, target),
      if (kind == RecoveryKind.ArchRedirect) RecoveryCause.Trap else RecoveryCause.DirectionMispredict)

  val bootOnce = new SpecTest("fpg.bootOnce", Seq("funcFetchPcSelect")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.run(4); val quietBeforeBoot = d.offers.isEmpty
      d.bootWith(0x100c)
      d.run(3)
      d.boot = Some(0x7000); d.run(3); val secondRefused = !d.bootFire && !d.bootReady; d.boot = None
      val pcs = d.reqs.map(_._2).toSeq
      Seq(
        chk(quietBeforeBoot, "no PredictReq is offered before the boot address arrives", s"${d.offers}"),
        chk(pcs.take(3) == Seq(0x100cL, 0x1010L, 0x1020L),
          "the first request is the exact boot PC 0x100c (not rounded to 0x1000), then each NextPc", s"$pcs"),
        chk(secondRefused && !pcs.contains(0x7000L), "a second boot token is never accepted or applied", s"$pcs"))
    }
  }

  /** Atomic fork: request and NextPc in one cycle; a request every cycle without a bubble. */
  val noBubble = new SpecTest("fpg.noBubble", Seq("funcFetchPcSelect")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.bootWith(0x2000)
      d.run(8)
      val cyc = d.reqs.map(_._1).toSeq; val pcs = d.reqs.map(_._2).toSeq
      Seq(
        chk(pcs == (0 until pcs.size).map(k => 0x2000L + 16 * k) && pcs.size >= 7,
          "each NextPc becomes the next request, no PC repeated or skipped", s"$pcs"),
        chk(cyc.zip(cyc.drop(1)).forall { case (a, c) => c == a + 1 }, "one request per cycle while the predictor is ready", s"$cyc"))
    }
  }

  val backpressure = new SpecTest("fpg.backpressure", Seq("funcFetchPcSelect")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.bootWith(0x3000); d.run(2)
      d.bpReady = false; val o0 = d.offers.size; d.run(5)
      val held = d.offers.drop(o0).map(_._2).toSeq
      val firedWhileHeld = d.reqs.size
      d.bpReady = true; d.run(2)
      val pcs = d.reqs.map(_._2).toSeq
      Seq(
        chk(held.size == 5 && held.distinct == Seq(0x3020L), "the offered PC stays 0x3020 through backpressure", s"$held"),
        chk(pcs == Seq(0x3000L, 0x3010L, 0x3020L, 0x3030L) && firedWhileHeld == 2,
          "it transfers exactly once when accepted (no duplicate)", s"$pcs"))
    }
  }

  val recoveryPriority = new SpecTest("fpg.recoveryPriority", Seq("funcFetchPcSelect", "funcFetchPcRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // An event in the cycle a request would transfer with its NextPc: the event wins.
      d.bootWith(0x4000); d.run(1)
      d.event = Some(redirect(0x8004)); d.cycle(); d.event = None
      val inEvent = d.reqFire || d.tokenFire
      d.run(2)
      // An event while the request is backpressured: the held PC is dropped, never issued.
      d.bpReady = false; d.run(2)
      d.event = Some(redirect(0x9008, RecoveryKind.ArchRedirect)); d.cycle(); d.event = None
      d.run(1); d.bpReady = true; d.run(2)
      val pcs = d.reqs.map(_._2).toSeq
      Seq(
        chk(!inEvent, "nothing transfers on PredictReq/NextPc in the event cycle", s"${d.reqs}"),
        chk(pcs.take(3) == Seq(0x4000L, 0x8004L, 0x8010L),
          "the pending 0x4010 is dropped; the next request is exactly e.target (mid-block), then its NextPc", s"$pcs"),
        chk(pcs.drop(3).take(2) == Seq(0x9008L, 0x9010L) && !pcs.contains(0x8020L),
          "a backpressured PC is replaced by the redirect target and never issued", s"$pcs"))
    }
  }

  /** A delayed predictor: after a request, nothing more is offered until its NextPc arrives; a
    * NextPc that answers a pre-recovery request never overwrites the redirect PC. */
  val staleNextPc = new SpecTest("fpg.staleNextPc", Seq("funcFetchPcSelect", "funcFetchPcRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.atomic = false
      d.bootWith(0x5000); d.bpReady = true; d.cycle(); d.bpReady = false
      d.run(3); val waited = d.offers.size == 1
      d.token = Some(0x5010); d.cycle(); d.token = None; d.run(1)
      val resumed = d.offers.lastOption.map(_._2).contains(0x5010L)
      d.bpReady = true; d.cycle(); d.bpReady = false // request 0x5010 accepted; its NextPc is outstanding
      d.event = Some(redirect(0xa000)); d.cycle(); d.event = None
      d.token = Some(0x5020); d.cycle(); d.token = None // the late answer for 0x5010
      d.run(2)
      d.bpReady = true; d.cycle(); d.bpReady = false
      val pcs = d.reqs.map(_._2).toSeq
      Seq(
        chk(waited, "after a request no further PredictReq is offered until its NextPc arrives", s"${d.offers}"),
        chk(resumed, "the NextPc token becomes the next offered PC", s"${d.offers}"),
        chk(pcs == Seq(0x5000L, 0x5010L, 0xa000L), "the pre-recovery NextPc 0x5020 does not overwrite the redirect target", s"$pcs"))
    }
  }

  val backToBack = new SpecTest("fpg.backToBack", Seq("funcFetchPcRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.bootWith(0x6000); d.run(1)
      Seq(0xb000L, 0xc004L, 0xd008L).foreach { t => d.event = Some(redirect(t)); d.cycle() }; d.event = None
      val n0 = d.reqs.size
      d.run(3)
      val after = d.reqs.drop(n0).map(_._2).toSeq
      Seq(chk(after == Seq(0xd008L, 0xd010L, 0xd020L),
        "after back-to-back events prediction resumes at the last target, once", s"${d.reqs}"))
    }
  }

  /** propFetchPcAligned: a misaligned PC reaching PredictReq fires the design assertion. */
  val aligned = new SpecTest("fpg.aligned", Seq("propFetchPcAligned")) {
    def run(): Seq[TCheck] = {
      var reachedEnd = false
      try {
        withDrv(this) { d => d.bootWith(0x1002); d.run(3); reachedEnd = true; Nil }
        Seq(TCheck(false, "a misaligned PredictReq fires the FetchPcAligned assertion", "simulation ended normally"))
      } catch {
        case e: NotImplementedError => throw e
        case _: Throwable =>
          val fired = chisel3.simulator.CachedSimulator.lastSimulationLog.linesIterator.filter(_.contains("Assertion failed")).toSeq
          Seq(chk(!reachedEnd && fired.exists(_.contains("FetchPcAligned")),
            "a misaligned PredictReq fires the FetchPcAligned assertion", fired.headOption.getOrElse("no assertion")))
      }
    }
  }

  /** funcFetchPcSelect priority (1) over (2): a RecoveryEvent in the boot cycle wins, and the boot
    * address is never applied afterwards. */
  val bootVsRecovery = new SpecTest("fpg.bootVsRecovery", Seq("funcFetchPcSelect", "funcFetchPcRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.run(2)
      d.boot = Some(0x1000); d.event = Some(redirect(0x2004, RecoveryKind.ArchRedirect)); d.cycle(); d.event = None
      val bootInEvent = d.bootFire
      d.run(4); d.boot = None
      val pcs = d.reqs.map(_._2).toSeq
      Seq(
        chk(!bootInEvent, "the boot token does not transfer in the event cycle", ""),
        chk(pcs.take(3) == Seq(0x2004L, 0x2010L, 0x2020L) && !pcs.contains(0x1000L),
          "the redirect target is the first request and the boot address is never applied", s"$pcs"))
    }
  }

  val all: Seq[SpecTest] = Seq(bootOnce, noBubble, backpressure, recoveryPriority, staleNextPc, backToBack, aligned, bootVsRecovery)
}
