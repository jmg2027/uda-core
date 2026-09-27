package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.shared.{FtqCommit, RecoveryCause, RecoveryEvent, RecoveryKind}
import udacore.frontend.design.modules.{BranchPredictor, FetchPcGen, FetchTargetQueue}
import udacore.frontend.design.shared._
import verif.spectest.FrontendTestKit._

/** Test-only harness: the three real prediction-loop vertices wired as in FrontendTop
  * (FetchPcGen -> BranchPredictor -> FetchTargetQueue, NextPc back to FetchPcGen, HistoryRestore and
  * PredictorTrain from the FTQ to the predictor, one RecoveryEvent to all three). Observation taps
  * only; the FetchUnit side (FetchRequest) and the CommitUnit side (FtqCommit) are ports.
  * Building it runs firtool over the closed loop, which rejects any combinational cycle. */
class PredictLoopHarness(val p: FrontendParams) extends Module {
  val io = IO(new Bundle {
    val boot        = Flipped(Decoupled(UInt(p.vAddrWidth.W)))
    val event       = Input(new RecoveryEvent(p.backend))
    val fetchReq    = Decoupled(new FetchRequest(p))
    val ftqCommit   = Flipped(Decoupled(new FtqCommit(p.backend)))
    val offerValid  = Output(Bool())
    val offerPc     = Output(UInt(p.vAddrWidth.W))
    val predFire    = Output(Bool())
    val predPc      = Output(UInt(p.vAddrWidth.W))
    val restoreFire = Output(Bool())
    val trainFire   = Output(Bool())
  })
  val fpg = Module(new FetchPcGen(p))
  val bp  = Module(new BranchPredictor(p))
  val ftq = Module(new FetchTargetQueue(p))
  fpg.io.bootAddrIn <> io.boot
  fpg.io.recoveryEventIn := io.event
  bp.io.recoveryEventIn  := io.event
  ftq.io.recoveryEventIn := io.event
  bp.io.predictReqIn <> fpg.io.predictReqOut
  fpg.io.nextPcIn <> bp.io.nextPcOut
  ftq.io.predictionIn <> bp.io.predictionOut
  bp.io.historyRestoreIn <> ftq.io.historyRestoreOut
  bp.io.predictorTrainIn <> ftq.io.predictorTrainOut
  io.fetchReq <> ftq.io.fetchRequestOut
  ftq.io.ftqCommitIn <> io.ftqCommit
  io.offerValid  := fpg.io.predictReqOut.valid
  io.offerPc     := fpg.io.predictReqOut.bits.fetchPc
  io.predFire    := bp.io.predictionOut.fire
  io.predPc      := bp.io.predictionOut.bits.fetchPc
  io.restoreFire := ftq.io.historyRestoreOut.fire
  io.trainFire   := ftq.io.predictorTrainOut.fire
}

/** Integration SpecTests of the prediction loop on real RTL (FetchPcGen + BranchPredictor + FTQ). */
object PredictLoopSpecTests {

  class Drv(val dut: PredictLoopHarness) {
    val io  = dut.io
    var cyc = 0
    var boot: Option[Long] = None
    var event: Option[Event] = None
    var commit: Option[Int] = None
    var fetchReady = true
    val preds    = ArrayBuffer[(Int, Long)]()
    val restores = ArrayBuffer[Int]()
    val events   = ArrayBuffer[Int]()
    var commitFire = false
    def cycle(): Unit = {
      io.boot.valid.poke(boot.nonEmpty.B); boot.foreach(a => io.boot.bits.poke(a.U))
      pokeEvent(io.event, event)
      io.fetchReq.ready.poke(fetchReady.B)
      io.ftqCommit.valid.poke(commit.nonEmpty.B)
      commit.foreach { i => io.ftqCommit.bits.ftqIdx.poke(i.U); pokeOutcome(io.ftqCommit.bits.exit, Outcome()) }
      commitFire = commit.nonEmpty && b(io.ftqCommit.ready)
      if (b(io.predFire)) preds += ((cyc, l(io.predPc)))
      if (b(io.restoreFire)) restores += cyc
      if (event.nonEmpty) events += cyc
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new PredictLoopHarness(fp)) { dut => val d = new Drv(dut); body(d) }

  def fallThrough(from: Long, n: Int): Seq[Long] =
    (0 until n).map(k => if (k == 0) from else (from & ~15L) + 16L * k)

  val bootFlow = new SpecTest("loop.bootFlow", Seq("funcFetchPcSelect", "funcFtqAllocate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.run(3); val quiet = d.preds.isEmpty
      d.boot = Some(0x1008); d.cycle(); d.boot = None
      d.run(12)
      val pcs = d.preds.map(_._2).toSeq; val cyc = d.preds.map(_._1).toSeq
      Seq(
        chk(quiet, "no prediction before boot", s"${d.preds}"),
        chk(pcs == fallThrough(0x1008, pcs.size) && pcs.size >= 11,
          "boot at the exact mid-block PC 0x1008, then fall-through blocks 0x1010, 0x1020, ...", s"$pcs"),
        chk(cyc.zip(cyc.drop(1)).forall { case (a, c) => c == a + 1 }, "one prediction per cycle: no bubble in the loop", s"$cyc"))
    }
  }

  val ftqFull = new SpecTest("loop.ftqFull", Seq("funcFetchPcSelect", "funcFtqAllocate", "funcFtqCommitTrain")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.boot = Some(0x2000); d.cycle(); d.boot = None
      d.run(fp.ftqDepth + 6)
      val n0 = d.preds.size
      val held = (0 until 4).map { _ => d.cycle(); (b(d.io.offerValid), l(d.io.offerPc)) }.distinct
      d.commit = Some(0); d.cycle(); while (!d.commitFire && d.cyc < 200) d.cycle(); d.commit = None
      d.run(3)
      val pcs = d.preds.map(_._2).toSeq
      val exp = 0x2000L + 16L * fp.ftqDepth
      Seq(
        chk(n0 == fp.ftqDepth, s"a full FTQ stops prediction after ${fp.ftqDepth} blocks", s"$n0"),
        chk(held == Seq((true, exp)), f"FetchPcGen holds the next PC 0x$exp%x while the queue is full", s"$held"),
        chk(pcs.drop(n0) == Seq(exp), "after one commit exactly one more block is predicted, from the held PC", s"$pcs"))
    }
  }

  def mis(ftqIdx: Int, target: Long): Event =
    Event(RecoveryKind.BranchMispredict, target, ftqIdx, Outcome(BRANCH, 0, taken = true, target), RecoveryCause.DirectionMispredict)
  def arch(ftqIdx: Int, target: Long): Event =
    Event(RecoveryKind.ArchRedirect, target, ftqIdx, Outcome(), RecoveryCause.Trap)

  val recovery = new SpecTest("loop.recovery", Seq("funcFetchPcRecovery", "funcFtqRecovery", "funcHistoryRestore")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.boot = Some(0x3000); d.cycle(); d.boot = None
      d.run(5)
      val n0 = d.preds.size
      d.event = Some(mis(2, 0x8004)); d.cycle(); d.event = None
      d.run(6)
      val ev = d.events.last; val rs = d.restores.toSeq
      val after = d.preds.drop(n0).toSeq
      Seq(
        chk(rs.size == 1 && rs.head > ev, "one HistoryRestore after the event", s"event $ev restores $rs"),
        chk(after.nonEmpty && after.forall(_._1 > rs.lastOption.getOrElse(Int.MaxValue)),
          "no prediction from the event through the cycle its HistoryRestore is accepted", s"event $ev restores $rs preds $after"),
        chk(after.map(_._2) == fallThrough(0x8004, after.size) && after.size >= 3,
          "prediction resumes once at the redirect target 0x8004", s"$after"))
    }
  }

  val backToBack = new SpecTest("loop.backToBack", Seq("funcFetchPcRecovery", "funcFtqRecovery", "funcHistoryRestore")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.boot = Some(0x4000); d.cycle(); d.boot = None
      d.run(6)
      val n0 = d.preds.size
      Seq(mis(4, 0xa000), mis(2, 0xb004), arch(1, 0xc008)).foreach { e => d.event = Some(e); d.cycle() }
      d.event = None
      d.run(10)
      val rs = d.restores.toSeq; val last = rs.lastOption.getOrElse(Int.MaxValue)
      val after = d.preds.drop(n0).toSeq
      Seq(
        chk(rs.size == 3, "three RecoveryEvents yield three HistoryRestores", s"$rs"),
        chk(after.nonEmpty && after.forall(_._1 > last), "no prediction through the cycle of the last HistoryRestore",
          s"restores $rs preds $after"),
        chk(after.map(_._2) == fallThrough(0xc008, after.size) && after.count(_._2 == 0xc008L) == 1 && after.size >= 3,
          "prediction resumes exactly once at the last redirect target 0xc008", s"$after"))
    }
  }

  val all: Seq[SpecTest] = Seq(bootFlow, ftqFull, recovery, backToBack)
}
