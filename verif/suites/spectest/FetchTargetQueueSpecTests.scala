package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.shared.{RecoveryCause, RecoveryKind}
import udacore.frontend.design.modules.FetchTargetQueue
import verif.spectest.FrontendTestKit._

/** L1 SpecTests for the ADR-019 FetchTargetQueue (ADR-018; spec 242feaf + ADR-019A..H).
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

  // ---- RTL block 18 red-first: the whole FetchTargetQueue contract ---------------------------------

  val depth = fp.ftqDepth
  def cpN(n: Int): Checkpoint = Checkpoint(ghr = BigInt(0x100 + n), rasTop = n % fp.tuning.rasDepth,
    ras = Seq.tabulate(fp.tuning.rasDepth)(i => 0x1000L * (n + 1) + 4 * i))
  /** A fall-through prediction for block n (fetchPc 0x10000 + 16 n) carrying checkpoint cpN(n). */
  def blk(n: Int, pcOff: Int = 0): Pred =
    Pred(0x10000L + 16 * n + pcOff, nextPc = 0x10000L + 16 * (n + 1), meta = Meta(provider = n % 5, hitMask = n % 16), cp = cpN(n))
  def mispredict(idx: Int, o: Outcome): Event = Event(RecoveryKind.BranchMispredict, o.target, idx, o)
  def archRedirect(idx: Int): Event = Event(RecoveryKind.ArchRedirect, 0x8000, idx, Outcome(), RecoveryCause.Trap)
  def fire(d: Drv, e: Event): Unit = { d.event = Some(e); d.cycle(); d.event = None }

  val allocateFull = new SpecTest("ftq.allocateFull", Seq("funcFtqAllocate", "funcFtqCommitTrain")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val idx = (0 until depth).map(n => d.enqueue(blk(n)))
      d.pred = Some(blk(depth)); d.run(3); val refusedWhenFull = !d.predFire; d.pred = None
      d.commitOne(0, Outcome())
      d.enqueue(blk(depth)); val acceptedAfterRelease = d.predFire
      d.run(4)
      val rq = d.reqs.map(_._2.ftqIdx).toSeq
      Seq(
        chk(idx == (0 until depth), s"$depth predictions take ftqIdx 0..${depth - 1} in prediction order", s"$idx"),
        chk(refusedWhenFull, "PredictionIn.ready is low while the queue holds FtqDepth live entries", ""),
        chk(acceptedAfterRelease, "releasing the head through FtqCommit makes room for one more prediction", ""),
        chk(rq == (0 to depth), s"the wrapped entry takes ftqIdx $depth ({wrap = 1, idx = 0}); requests follow in order", s"$rq"))
    }
  }

  val fetchIssueOrder = new SpecTest("ftq.fetchIssueOrder", Seq("funcFtqFetchIssue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.reqReady = false
      d.enqueue(blk(0))
      d.enqueue(Pred(0x10010, cfiValid = true, cfiSlot = 1, cfiType = BRANCH, taken = false, target = 0x7000, nextPc = 0x10020))
      d.enqueue(Pred(0x10024, cfiValid = true, cfiSlot = 2, cfiType = JAL, taken = true, target = 0x9000, nextPc = 0x9000))
      d.enqueue(Pred(0x9000, cfiValid = true, cfiSlot = 0, cfiType = RET, taken = true, target = 0x5554, nextPc = 0x5554))
      d.run(3); val none = d.reqs.isEmpty
      // Alternate ready so every request is observed under backpressure.
      (0 until 12).foreach { k => d.reqReady = k % 2 == 1; d.cycle() }
      val rq = d.reqs.map(_._2).toSeq
      Seq(
        chk(none, "no FetchRequest transfers while FetchRequestOut.ready is low", s"${d.reqs}"),
        chk(rq.map(r => (r.ftqIdx, r.fetchPc)) == Seq((0, 0x10000L), (1, 0x10010L), (2, 0x10024L), (3, 0x9000L)),
          "one request per live entry, in ftqIdx order, each carrying the requested fetch PC", s"$rq"),
        chk(rq.map(r => (r.lastSlot, r.exitTaken)) == Seq((3, false), (3, false), (2, true), (0, true)),
          "lastSlot is the predicted-taken exit slot, else FetchWidth - 1 (a not-taken branch does not end the block)", s"$rq"),
        chk(rq(2).exitTarget == 0x9000 && rq(3).exitTarget == 0x5554, "a taken exit carries its predicted target", s"$rq"))
    }
  }

  val mispredictRestore = new SpecTest("ftq.mispredictRestore",
      Seq("funcFtqRecovery", "funcFtqFetchIssue", "funcFtqAllocate", "propFtqRecoveryKeepsOlder")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      (0 until 4).foreach(n => d.enqueue(blk(n)))
      d.run(6) // all four requested
      val out = Outcome(BRANCH, 2, taken = true, 0x4000)
      fire(d, mispredict(1, out))
      d.run(2)
      d.enqueue(Pred(0x4000, nextPc = 0x4010, cp = cpN(9)))
      d.run(3)
      d.commitOne(0, Outcome()); d.commitOne(1, out); d.commitOne(2, Outcome()); d.run(2)
      val rs = d.restores.map(_._2).toSeq
      val rq = d.reqs.map(_._2).toSeq
      val tr = d.trains.map(_._2).toSeq
      Seq(
        chk(rs == Seq(Rst(cpN(1), apply = true, out, 0x10018)),
          "one HistoryRestore: checkpoint of e.ftqIdx, applyOutcome, the outcome, pc = blockBase + 4 * slot", s"$rs"),
        chk(rq.drop(4).map(r => (r.ftqIdx, r.fetchPc)) == Seq((2, 0x4000L)),
          "fetch restarts at the first entry allocated after the event, which reuses ftqIdx e.ftqIdx + 1", s"$rq"),
        chk(tr.map(_.predicted.fetchPc) == Seq(0x10000L, 0x10010L, 0x4000L) &&
            tr.map(_.ghr) == Seq(cpN(0).ghr, cpN(1).ghr, cpN(9).ghr) && tr(1).committed == out,
          "entries 0 and 1 survive unmodified and train in order; the discarded 2 and 3 never train", s"$tr"))
    }
  }

  val recoveryWrap = new SpecTest("ftq.recoveryWrap", Seq("funcFtqRecovery", "propFtqRecoveryKeepsOlder")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // Move the head to idx depth - 2 so the live window {14, 15, 16, 17} straddles the wrap.
      (0 until depth - 2).foreach { n => d.enqueue(blk(n)); d.run(2); d.commitOne(n, Outcome()) }
      val ids = (0 until 4).map(k => d.enqueue(blk(20 + k)))
      d.run(6)
      val out = Outcome(BRANCH, 1, taken = false, 0)
      fire(d, mispredict(ids(1), out)); d.run(2)
      val nid = { d.enqueue(Pred(0x6000, nextPc = 0x6010, cp = cpN(30))); d.run(4); d.reqs.last._2.ftqIdx }
      d.trains.clear()
      d.commitOne(ids(0), Outcome()); d.commitOne(ids(1), out); d.commitOne(nid, Outcome()); d.run(2)
      val tr = d.trains.map(_._2.predicted.fetchPc).toSeq
      Seq(
        chk(ids == Seq(depth - 2, depth - 1, depth, depth + 1), "the window spans the ftqIdx wrap", s"$ids"),
        chk(d.restores.map(_._2.cp).toSeq == Seq(cpN(21)), "the restore uses the pre-wrap entry's checkpoint", s"${d.restores}"),
        chk(nid == depth, s"the tail rewinds to e.ftqIdx + 1 = $depth across the wrap", s"$nid"),
        chk(tr == Seq(blk(20).fetchPc, blk(21).fetchPc, 0x6000L),
          "the older pre-wrap entries survive; the wrapped younger ones were discarded", s"$tr"))
    }
  }

  val archRedirectAll = new SpecTest("ftq.archRedirect", Seq("funcFtqRecovery", "funcFtqFetchIssue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      (0 until 3).foreach(n => d.enqueue(blk(n)))
      d.run(5)
      fire(d, archRedirect(1)); d.run(2)
      val i = { d.enqueue(Pred(0x8000, nextPc = 0x8010, cp = cpN(7))); d.run(3); d.reqs.last._2 }
      d.commitOne(i.ftqIdx, Outcome()); d.run(2)
      val rs = d.restores.map(_._2).toSeq
      Seq(
        chk(rs.size == 1 && rs.head.cp == cpN(1) && !rs.head.apply,
          "ArchRedirect: one restore of the checkpoint of live entry e.ftqIdx, without applying an outcome", s"$rs"),
        chk(i.fetchPc == 0x8000 && d.reqs.size == 4, "every entry is discarded: the next request is the new block", s"${d.reqs}"),
        chk(d.trains.map(_._2.predicted.fetchPc).toSeq == Seq(0x8000L),
          "the new block is the head: its FtqCommit is accepted and no discarded entry trains", s"${d.trains}"))
    }
  }

  /** ADR-019H E-5: exactly one HistoryRestore per RecoveryEvent, in event order, back to back. */
  val backToBack = new SpecTest("ftq.backToBack", Seq("funcFtqRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      (0 until 6).foreach(n => d.enqueue(blk(n)))
      d.run(8)
      d.restoreReady = false
      val o3 = Outcome(BRANCH, 3, taken = true, 0x4100); val o1 = Outcome(JALR, 0, taken = true, 0x4200)
      fire(d, mispredict(3, o3)); fire(d, mispredict(1, o1))
      d.run(3); val heldWhileNotReady = d.restores.isEmpty
      // The third event arrives in the cycle the first restore is accepted; then a bubble.
      d.restoreReady = true; fire(d, archRedirect(0)); d.restoreReady = false; d.cycle()
      d.restoreReady = true; d.run(6)
      val rs = d.restores.map(_._2).toSeq
      Seq(
        chk(heldWhileNotReady, "restores wait for HistoryRestoreOut.ready (none dropped, none transferred early)", s"${d.restores}"),
        chk(rs.size == 3, "three RecoveryEvents yield exactly three HistoryRestores", s"$rs"),
        chk(rs.take(2) == Seq(Rst(cpN(3), apply = true, o3, 0x1003c), Rst(cpN(1), apply = true, o1, 0x10010)),
          "the mispredict restores leave in event order, each with its own checkpoint, outcome and pc, " +
          "although the second event discarded the first event's entry", s"$rs"),
        chk(rs.lift(2).exists(r => r.cp == cpN(0) && !r.apply),
          "the event taken in the cycle a restore is accepted is queued, not lost, and leaves last", s"$rs"))
    }
  }

  val commitTrainHandshake = new SpecTest("ftq.commitTrainHandshake", Seq("funcFtqCommitTrain", "propFtqInOrderRelease")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val p0 = Pred(0x2004, cfiValid = true, cfiSlot = 2, cfiType = BRANCH, taken = true, target = 0x2400, nextPc = 0x2400,
        meta = Meta(btbHit = true, btbWay = 1, provider = 3, providerCtr = -2, altPred = true, useAlt = false, hitMask = 6), cp = cpN(4))
      d.enqueue(p0); d.enqueue(blk(1)); d.run(3)
      d.trainReady = false
      val out = Outcome(BRANCH, 2, taken = true, 0x2400)
      d.commit = Some((0, out)); d.run(4); val blocked = !d.commitFire && d.trains.isEmpty
      d.trainReady = true; d.cycle(); val together = d.commitFire && d.trains.size == 1; d.commit = None
      d.run(2)
      d.commitOne(1, Outcome()); d.run(2)
      val t = d.trains.map(_._2).toSeq
      Seq(
        chk(blocked, "while PredictorTrainOut.ready is low the FtqCommit is not accepted and nothing trains", s"${d.trains}"),
        chk(together, "FtqCommit and PredictorTrain transfer in the same cycle", s"${d.trains}"),
        chk(t.headOption.contains(Trn(0x2000, cpN(4).ghr, p0, out)),
          "the record carries blockBase, checkpoint.ghr, the prediction (with meta) and the committed exit", s"$t"),
        chk(t.size == 2 && t(1).predicted.fetchPc == blk(1).fetchPc, "the next head trains next, once", s"$t"))
    }
  }

  /** propFtqInOrderRelease: an FtqCommit naming an entry other than the head fires the design assertion. */
  val inOrderRelease = new SpecTest("ftq.inOrderRelease", Seq("propFtqInOrderRelease")) {
    def run(): Seq[TCheck] = {
      var reachedEnd = false
      try {
        withDrv(this) { d => d.enqueue(blk(0)); d.enqueue(blk(1)); d.run(3); d.commitOne(1, Outcome()); d.run(2); reachedEnd = true; Nil }
        Seq(TCheck(false, "a commit naming a non-head entry fires the FtqInOrderRelease assertion", "simulation ended normally"))
      } catch {
        case e: NotImplementedError => throw e
        case _: Throwable =>
          val fired = chisel3.simulator.CachedSimulator.lastSimulationLog.linesIterator.filter(_.contains("Assertion failed")).toSeq
          Seq(chk(!reachedEnd && fired.exists(_.contains("FtqInOrderRelease")),
            "a commit naming a non-head entry fires the FtqInOrderRelease assertion", fired.headOption.getOrElse("no assertion")))
      }
    }
  }

  val all: Seq[SpecTest] = Seq(midBlockRequest, midBlockRestorePc, midBlockTrain, allocateFull, fetchIssueOrder,
    mispredictRestore, recoveryWrap, archRedirectAll, backToBack, commitTrainHandshake, inOrderRelease)
}
