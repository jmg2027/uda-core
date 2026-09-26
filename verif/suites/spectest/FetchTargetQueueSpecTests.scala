package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.shared.RecoveryKind
import udacore.frontend.design.modules.FetchTargetQueue
import verif.spectest.FrontendTestKit._

/** L1 SpecTests for the ADR-019 FetchTargetQueue (ADR-018; spec 242feaf + ADR-019A..G).
  *
  * The driver plays the BranchPredictor (Prediction producer, HistoryRestore and
  * PredictorTrain consumer), the FetchUnit (FetchRequest consumer), the CommitUnit
  * (FtqCommit), and the RecoveryEvent broadcast.
  */
object FetchTargetQueueSpecTests {

  case class Req(ftqIdx: Int, fetchPc: Long, lastSlot: Int, exitTaken: Boolean, exitTarget: Long)
  case class Rst(cp: Checkpoint, apply: Boolean, outcome: Outcome, pc: Long)
  case class Trn(fetchPc: Long, ghr: BigInt, predicted: Pred, committed: Outcome)

  class Drv(val dut: FetchTargetQueue) {
    val io  = dut.io
    var cyc = 0
    var pred: Option[Pred] = None
    var commit: Option[(Int, Outcome)] = None
    var event: Option[Event] = None
    var reqReady, restoreReady, trainReady = true
    val reqs     = ArrayBuffer[(Int, Req)]()
    val restores = ArrayBuffer[(Int, Rst)]()
    val trains   = ArrayBuffer[(Int, Trn)]()
    val allocIdx = ArrayBuffer[Int]()
    var predFire, commitFire = false
    private var nextIdx = 0

    def cycle(): Unit = {
      io.predictionIn.valid.poke(pred.nonEmpty.B); pred.foreach(pokePred(io.predictionIn.bits, _))
      io.ftqCommitIn.valid.poke(commit.nonEmpty.B)
      commit.foreach { case (i, o) => io.ftqCommitIn.bits.ftqIdx.poke(i.U); pokeOutcome(io.ftqCommitIn.bits.exit, o) }
      pokeEvent(io.recoveryEventIn, event)
      io.fetchRequestOut.ready.poke(reqReady.B); io.historyRestoreOut.ready.poke(restoreReady.B)
      io.predictorTrainOut.ready.poke(trainReady.B)
      predFire   = pred.nonEmpty && b(io.predictionIn.ready)
      commitFire = commit.nonEmpty && b(io.ftqCommitIn.ready)
      if (predFire) { allocIdx += nextIdx; nextIdx = (nextIdx + 1) % (2 * fp.ftqDepth) }
      val f = io.fetchRequestOut
      if (b(f.valid) && reqReady) reqs += ((cyc, Req(l(f.bits.ftqIdx).toInt, l(f.bits.fetchPc), l(f.bits.lastSlot).toInt,
        b(f.bits.exitTaken), l(f.bits.exitTarget))))
      val r = io.historyRestoreOut
      if (b(r.valid) && restoreReady) restores += ((cyc, Rst(peekCheckpoint(r.bits.checkpoint), b(r.bits.applyOutcome),
        peekOutcome(r.bits.outcome), l(r.bits.pc))))
      val t = io.predictorTrainOut
      if (b(t.valid) && trainReady) trains += ((cyc, Trn(l(t.bits.fetchPc), t.bits.ghr.peek().litValue, peekPred(t.bits.predicted),
        peekOutcome(t.bits.committed))))
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    /** Enqueue one prediction; returns its ftqIdx (allocation order from reset). */
    def enqueue(p: Pred): Int = { pred = Some(p); var k = 0; cycle(); while (!predFire && k < 20) { cycle(); k += 1 }; pred = None; allocIdx.last }
    def commitOne(i: Int, o: Outcome): Unit = { commit = Some((i, o)); var k = 0; cycle(); while (!commitFire && k < 20) { cycle(); k += 1 }; commit = None }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new FetchTargetQueue(fp)) { dut => val d = new Drv(dut); d.cycle(); body(d) }

  val cp0 = Checkpoint(ghr = BigInt("a5", 16), rasTop = 2, ras = Seq(0x100L, 0x200L, 0x300L, 0, 0, 0, 0, 0))

  // ---- ADR-019G E-8: mid-block fetch-PC semantics ---------------------------------------------------

  val midBlockRequest = new SpecTest("ftq.midBlockRequest", Seq("funcFtqAllocate", "funcFtqFetchIssue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val i0 = d.enqueue(Pred(0x1008, nextPc = 0x1010, cp = cp0))
      val i1 = d.enqueue(Pred(0x1010, cfiValid = true, cfiSlot = 2, cfiType = JAL, taken = true, target = 0x2008, nextPc = 0x2008))
      d.run(4)
      val rq = d.reqs.map(_._2).toSeq
      Seq(chk(rq == Seq(Req(i0, 0x1008, 3, false, 0), Req(i1, 0x1010, 2, true, 0x2008)),
        "FetchRequest carries the requested fetch PC 0x1008 (not 0x1000) with lastSlot 3, then the taken-exit block", s"$rq"))
    }
  }

  val midBlockRestorePc = new SpecTest("ftq.midBlockRestorePc", Seq("funcFtqRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val i0 = d.enqueue(Pred(0x1008, cfiValid = true, cfiSlot = 3, cfiType = BRANCH, nextPc = 0x1010, cp = cp0))
      d.enqueue(Pred(0x1010, nextPc = 0x1020))
      d.run(2)
      val out = Outcome(BRANCH, 3, taken = true, 0x4000)
      d.event = Some(Event(RecoveryKind.BranchMispredict, 0x4000, i0, out)); d.cycle(); d.event = None
      d.run(4)
      val rs = d.restores.map(_._2).toSeq
      Seq(chk(rs == Seq(Rst(cp0, apply = true, out, 0x100c)),
        "HistoryRestore for the slot-3 CFI of a 0x1008 request carries pc = blockBase + 12 = 0x100c (not 0x1014) and its checkpoint",
        s"$rs"))
    }
  }

  val midBlockTrain = new SpecTest("ftq.midBlockTrain", Seq("funcFtqCommitTrain")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val p0 = Pred(0x1008, cfiValid = true, cfiSlot = 3, cfiType = BRANCH, taken = true, target = 0x3000, nextPc = 0x3000, cp = cp0)
      val i0 = d.enqueue(p0)
      d.run(2)
      val out = Outcome(BRANCH, 3, taken = true, 0x3000)
      d.commitOne(i0, out); d.run(2)
      val ts = d.trains.map(_._2).toSeq
      Seq(chk(ts.size == 1 && ts.head.fetchPc == 0x1000 && ts.head.ghr == cp0.ghr && ts.head.predicted.fetchPc == 0x1008 &&
          ts.head.committed == out,
        "PredictorTrain.fetchPc is the aligned blockBase 0x1000 although the prediction began at 0x1008 (ghr = checkpoint.ghr)", s"$ts"))
    }
  }

  val all: Seq[SpecTest] = Seq(midBlockRequest, midBlockRestorePc, midBlockTrain)
}
