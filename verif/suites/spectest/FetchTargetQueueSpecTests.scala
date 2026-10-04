package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.shared.RecoveryKind
import udacore.frontend.design.modules.FetchTargetQueue
import udacore.frontend.design.shared.FrontendParams
import verif.spectest.FrontendTestKit._

/** L1 SpecTests for the ADR-019 FetchTargetQueue (ADR-018; spec 242feaf +
  * ADR-019A..G).
  *
  * The driver plays the BranchPredictor (Prediction producer, HistoryRestore
  * and PredictorTrain consumer), the FetchUnit (FetchRequest consumer), the
  * CommitUnit (FtqCommit), and the RecoveryEvent broadcast.
  */
object FetchTargetQueueSpecTests {

  case class Req(
      ftqIdx: Int,
      fetchPc: Long,
      lastSlot: Int,
      exitTaken: Boolean,
      exitTarget: Long
  )
  case class Rst(cp: Checkpoint, apply: Boolean, outcome: Outcome, pc: Long)
  case class Trn(
      fetchPc: Long,
      ghr: BigInt,
      predicted: Pred,
      committed: Outcome,
      meta: Meta
  )

  class Drv(val dut: FetchTargetQueue) {
    val io                                 = dut.io
    var cyc                                = 0
    var pred: Option[Pred]                 = None
    var commit: Option[(Int, Outcome)]     = None
    var event: Option[Event]               = None
    var reqReady, restoreReady, trainReady = true
    val reqs                               = ArrayBuffer[(Int, Req)]()
    val restores                           = ArrayBuffer[(Int, Rst)]()
    val trains                             = ArrayBuffer[(Int, Trn)]()
    val allocIdx                           = ArrayBuffer[Int]()
    var predFire, commitFire               = false
    private var nextIdx                    = 0
    private var headIdx                    = 0

    def cycle(): Unit     = {
      io.predictionIn.valid.poke(pred.nonEmpty.B);
      pred.foreach(pokePred(io.predictionIn.bits, _))
      io.ftqCommitIn.valid.poke(commit.nonEmpty.B)
      commit.foreach { case (i, o) =>
        io.ftqCommitIn.bits.ftqIdx.poke(i.U);
        pokeOutcome(io.ftqCommitIn.bits.exit, o)
      }
      pokeEvent(io.recoveryEventIn, event)
      io.fetchRequestOut.ready.poke(reqReady.B);
      io.historyRestoreOut.ready.poke(restoreReady.B)
      io.predictorTrainOut.ready.poke(trainReady.B)
      predFire = pred.nonEmpty && b(io.predictionIn.ready)
      commitFire = commit.nonEmpty && b(io.ftqCommitIn.ready)
      if (predFire) {
        allocIdx += nextIdx; nextIdx = (nextIdx + 1) % (2 * dut.params.ftqDepth)
      }
      if (commitFire) headIdx = (headIdx + 1) % (2 * dut.params.ftqDepth)
      event.foreach { e =>
        nextIdx =
          if (e.kind.litValue == RecoveryKind.ArchRedirect.litValue) headIdx
          else (e.ftqIdx + 1) % (2 * dut.params.ftqDepth)
      }
      val f = io.fetchRequestOut
      if (b(f.valid) && reqReady)
        reqs += ((
          cyc,
          Req(
            l(f.bits.ftqIdx).toInt,
            l(f.bits.fetchPc),
            l(f.bits.lastSlot).toInt,
            b(f.bits.exitTaken),
            l(f.bits.exitTarget)
          )
        ))
      val r = io.historyRestoreOut
      if (b(r.valid) && restoreReady)
        restores += ((
          cyc,
          Rst(
            peekCheckpoint(r.bits.checkpoint),
            b(r.bits.applyOutcome),
            peekOutcome(r.bits.outcome),
            l(r.bits.pc)
          )
        ))
      val t = io.predictorTrainOut
      if (b(t.valid) && trainReady)
        trains += ((
          cyc,
          Trn(
            l(t.bits.fetchPc),
            t.bits.ghr.peek().litValue,
            peekPred(t.bits.predicted),
            peekOutcome(t.bits.committed),
            peekMeta(t.bits.meta)
          )
        ))
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())

    /** Enqueue one prediction; returns its ftqIdx (allocation order from
      * reset).
      */
    def enqueue(p: Pred): Int               = {
      pred = Some(p); var k = 0; cycle();
      while (!predFire && k < 20) { cycle(); k += 1 }; pred = None;
      require(predFire, "prediction timed out"); allocIdx.last
    }
    def commitOne(i: Int, o: Outcome): Unit = {
      commit = Some((i, o)); var k = 0; cycle();
      while (!commitFire && k < 20) { cycle(); k += 1 }; commit = None;
      require(commitFire, "commit timed out")
    }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new FetchTargetQueue(fp)) { dut =>
      val d = new Drv(dut); d.cycle(); body(d)
    }

  val cp0 = Checkpoint(
    ghr = BigInt("a5", 16),
    rasTop = 2,
    ras = Seq(0x100L, 0x200L, 0x300L, 0, 0, 0, 0, 0)
  )

  // ---- ADR-019G E-8: mid-block fetch-PC semantics ---------------------------------------------------

  val midBlockRequest = new SpecTest(
    "ftq.midBlockRequest",
    Seq("funcFtqAllocate", "funcFtqFetchIssue")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val i0 = d.enqueue(Pred(0x1008, nextPc = 0x1010, cp = cp0))
      val i1 = d.enqueue(
        Pred(
          0x1010,
          cfiValid = true,
          cfiSlot = 2,
          cfiType = JAL,
          taken = true,
          target = 0x2008,
          nextPc = 0x2008
        )
      )
      d.run(4)
      val rq = d.reqs.map(_._2).toSeq
      Seq(
        chk(
          rq == Seq(
            Req(i0, 0x1008, 3, false, 0),
            Req(i1, 0x1010, 2, true, 0x2008)
          ),
          "FetchRequest carries the requested fetch PC 0x1008 (not 0x1000) with lastSlot 3, then the taken-exit block",
          s"$rq"
        )
      )
    }
  }

  val midBlockRestorePc =
    new SpecTest("ftq.midBlockRestorePc", Seq("funcFtqRecovery")) {
      def run(): Seq[TCheck] = withDrv(this) { d =>
        val i0  = d.enqueue(
          Pred(
            0x1008,
            cfiValid = true,
            cfiSlot = 3,
            cfiType = BRANCH,
            nextPc = 0x1010,
            cp = cp0
          )
        )
        d.enqueue(Pred(0x1010, nextPc = 0x1020))
        d.run(2)
        val out = Outcome(BRANCH, 3, taken = true, 0x4000)
        d.event = Some(Event(RecoveryKind.BranchMispredict, 0x4000, i0, out));
        d.cycle(); d.event = None
        d.run(4)
        val rs  = d.restores.map(_._2).toSeq
        Seq(
          chk(
            rs == Seq(Rst(cp0, apply = true, out, 0x100c)),
            "HistoryRestore for the slot-3 CFI of a 0x1008 request carries pc = blockBase + 12 = 0x100c (not 0x1014) and its checkpoint",
            s"$rs"
          )
        )
      }
    }

  val midBlockTrain =
    new SpecTest("ftq.midBlockTrain", Seq("funcFtqCommitTrain")) {
      def run(): Seq[TCheck] = withDrv(this) { d =>
        val p0  = Pred(
          0x1008,
          cfiValid = true,
          cfiSlot = 3,
          cfiType = BRANCH,
          taken = true,
          target = 0x3000,
          nextPc = 0x3000,
          cp = cp0
        )
        val i0  = d.enqueue(p0)
        d.run(2)
        val out = Outcome(BRANCH, 3, taken = true, 0x3000)
        d.commitOne(i0, out); d.run(2)
        val ts  = d.trains.map(_._2).toSeq
        Seq(
          chk(
            ts.size == 1 && ts.head.fetchPc == 0x1000 && ts.head.ghr == cp0.ghr && ts.head.predicted.fetchPc == 0x1008 &&
              ts.head.committed == out,
            "PredictorTrain.fetchPc is the aligned blockBase 0x1000 although the prediction began at 0x1008 (ghr = checkpoint.ghr)",
            s"$ts"
          )
        )
      }
    }

  val capacityWrap = new SpecTest(
    "ftq.capacityWrap",
    Seq("funcFtqAllocate", "funcFtqFetchIssue", "propFtqInOrderRelease")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val checks = ArrayBuffer[TCheck]()
      for (round <- 0 until 3) {
        d.reqReady = false
        val ids = (0 until fp.ftqDepth).map { n =>
          d.enqueue(Pred(0x1000 + (round * fp.ftqDepth + n) * 16L))
        }
        d.pred = Some(Pred(0xdead0)); d.run(3)
        checks += chk(
          !d.predFire,
          "full FTQ backpressures predictions even before fetch",
          s"round $round"
        )
        d.pred = None; d.reqReady = true; d.run(fp.ftqDepth + 2)
        checks += chk(
          d.reqs.takeRight(fp.ftqDepth).map(_._2.ftqIdx).toSeq == ids,
          "each allocated block issues once in order through pointer wrap",
          s"round $round ${d.reqs}"
        )
        ids.foreach(d.commitOne(_, Outcome()))
      }
      checks += chk(
        d.reqs.size == 3 * fp.ftqDepth && d.trains.size == 3 * fp.ftqDepth,
        "fetch and commit neither duplicate nor lose entries",
        s"${d.reqs.size}/${d.trains.size}"
      )
      checks.toSeq
    }
  }

  val trainBackpressure = new SpecTest(
    "ftq.trainBackpressure",
    Seq("funcFtqCommitTrain", "propFtqInOrderRelease")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val m      = Meta(true, 1, 3, -2, true, false, 7)
      val p      = Pred(
        0x2008,
        cfiValid = true,
        cfiSlot = 3,
        cfiType = CALLRET,
        taken = true,
        target = 0x3000,
        cp = cp0,
        meta = m
      )
      val i      = d.enqueue(p); d.run(2)
      val out    = Outcome(CALLRET, 3, true, 0x3000)
      d.trainReady = false; d.commit = Some((i, out)); d.run(4)
      val held   =
        !d.commitFire && d.trains.isEmpty && b(d.io.predictorTrainOut.valid)
      d.trainReady = true; d.cycle();
      val atomic = d.commitFire && d.trains.size == 1
      d.commit = None; d.run(3)
      Seq(
        chk(
          held && atomic && d.trains.map(_._2).toSeq == Seq(
            Trn(0x2000, cp0.ghr, p, out, m)
          ),
          "commit and training transfer atomically with every prediction-time metadata field preserved",
          s"${d.trains}"
        )
      )
    }
  }

  val selectiveWrap = new SpecTest(
    "ftq.selectiveWrap",
    Seq("funcFtqRecovery", "propFtqRecoveryKeepsOlder")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // Place the survivor window across the index wrap, then recover its middle entry.
      for (n <- 0 until fp.ftqDepth - 2) {
        val i = d.enqueue(Pred(0x1000 + 16L * n)); d.run(2);
        d.commitOne(i, Outcome())
      }
      d.trains.clear(); d.reqs.clear()
      val ps = (0 until 5).map(n =>
        Pred(0x2008 + n * 16L, cp = cp0.copy(ghr = n + 10))
      )
      val ids            = ps.map(d.enqueue); d.run(3)
      val out            = Outcome(BRANCH, 3, true, 0x4000)
      d.event = Some(Event(RecoveryKind.BranchMispredict, 0x4000, ids(2), out))
      d.commit = Some((ids.head, Outcome())); d.cycle();
      val committedOlder = d.commitFire
      d.event = None; d.commit = None; d.run(3)
      val fresh          = Pred(0x4000, cp = cp0.copy(ghr = 99))
      val freshId        = d.enqueue(fresh); d.run(3)
      d.commitOne(ids(1), Outcome()); d.commitOne(ids(2), out);
      d.commitOne(freshId, Outcome())
      Seq(
        chk(
          committedOlder && freshId == (ids(2) + 1) % (2 * fp.ftqDepth) &&
            d.trains.map(_._2.predicted).toSeq == ps.take(3) :+ fresh,
          "branch recovery preserves older entries and concurrent commit, reclaims only younger entries across wrap",
          s"${d.trains}"
        ),
        chk(
          d.reqs.last._2.fetchPc == 0x4000 && d.reqs.size == 6,
          "recovery issues only newly allocated blocks, never replays survivors",
          s"${d.reqs}"
        )
      )
    }
  }

  val restoreBurst = new SpecTest(
    "ftq.restoreBurst",
    Seq("funcFtqRecovery", "propFtqRecoveryKeepsOlder")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val n        = fp.backend.checkpointCount
      val ps       = (0 until n).map(k =>
        Pred(0x3008 + k * 16L, cp = cp0.copy(ghr = 30 + k))
      )
      val ids      = ps.map(d.enqueue); d.run(3); d.restoreReady = false
      val expected = ArrayBuffer[Rst]()
      for (k <- (0 until n).reverse) {
        val out =
          Outcome(if (k == 0) CALLRET else BRANCH, 3, true, 0x5000 + k * 16L)
        d.event =
          Some(Event(RecoveryKind.BranchMispredict, out.target, ids(k), out));
        d.cycle()
        expected += Rst(ps(k).cp, true, out, (ps(k).fetchPc & ~15L) + 12)
      }
      d.event = Some(Event(RecoveryKind.ArchRedirect, 0x6000, ids.head));
      d.cycle()
      expected += Rst(ps.head.cp, false, Outcome(), 0x3000)
      d.event = None; d.run(3)
      val noneLostEarly = d.restores.isEmpty
      d.restoreReady = true; d.run(n + 5)
      val got = d.restores.map(_._2).toSeq
      Seq(
        chk(
          noneLostEarly && got.map(r =>
            (r.cp, r.apply, if (r.apply) Some((r.outcome, r.pc)) else None)
          ) ==
            expected
              .map(r =>
                (r.cp, r.apply, if (r.apply) Some((r.outcome, r.pc)) else None)
              )
              .toSeq,
          "all BranchCheckpointCount + 1 restores survive backpressure and entry reclamation in event order",
          s"$got"
        )
      )
    }
  }

  val archCommit = new SpecTest(
    "ftq.archCommit",
    Seq("funcFtqRecovery", "funcFtqCommitTrain")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val p                 = Pred(0x1008, cp = cp0); val i = d.enqueue(p)
      d.enqueue(Pred(0x2000)); d.run(3)
      d.event = Some(Event(RecoveryKind.ArchRedirect, 0x8000, i));
      d.commit = Some((i, Outcome()))
      d.cycle(); val atomic = d.commitFire
      d.event = None; d.commit = None; d.run(3)
      val j                 = d.enqueue(Pred(0x8000)); d.run(3); d.commitOne(j, Outcome())
      Seq(
        chk(
          atomic && j == i + 1 && d.trains
            .map(_._2.predicted.fetchPc)
            .toSeq == Seq(0x1008L, 0x8000L) &&
            d.restores.size == 1 && d.restores.head._2.cp == cp0 && !d.restores.head._2.apply,
          "retiring ArchRedirect trains the head and restores its pre-event checkpoint while discarding younger blocks",
          s"${d.trains} ${d.restores}"
        )
      )
    }
  }

  val archFallback = new SpecTest(
    "ftq.archFallback",
    Seq("funcFtqRecovery", "funcFtqFetchIssue")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.event = Some(Event(RecoveryKind.ArchRedirect)); d.cycle();
      d.event = None; d.run(3)
      val p      = Pred(0x9008, cp = cp0); val i = d.enqueue(p); d.run(2);
      d.commitOne(i, Outcome())
      d.event = Some(Event(RecoveryKind.ArchRedirect, ftqIdx = i)); d.cycle();
      d.event = None; d.run(3)
      d.reqReady = false; d.enqueue(Pred(0xa000)); d.enqueue(Pred(0xb000))
      val before = d.reqs.size
      d.event = Some(Event(RecoveryKind.ArchRedirect, ftqIdx = 31));
      d.reqReady = true; d.cycle()
      d.event = None; d.run(3)
      val j      = d.enqueue(Pred(0xc000)); d.run(3)
      Seq(
        chk(
          d.restores.take(2).map(_._2.cp).toSeq == Seq(Checkpoint(), cp0),
          "absent-entry ArchRedirect uses zero at reset and retains the tail checkpoint after commit",
          s"${d.restores}"
        ),
        chk(
          d.reqs.size == before + 1 && d.reqs.last._2 == Req(
            j,
            0xc000,
            3,
            false,
            0
          ),
          "architectural recovery cancels a stalled fetch offer and restarts at the new allocation",
          s"${d.reqs}"
        )
      )
    }
  }

  val releaseGuard =
    new SpecTest("ftq.releaseGuard", Seq("propFtqInOrderRelease")) {
      def run(): Seq[TCheck] = {
        var fired = false
        try
          withDrv(this) { d =>
            d.enqueue(Pred(0x1000)); val younger = d.enqueue(Pred(0x2000));
            d.run(3)
            d.commitOne(younger, Outcome()); Nil
          }
        catch {
          case _: NotImplementedError => throw new NotImplementedError
          case _: Throwable =>
            fired = chisel3.simulator.CachedSimulator.lastSimulationLog
              .contains("FtqInOrderRelease")
        }
        Seq(
          chk(
            fired,
            "out-of-order commit is rejected by the named design assertion",
            "assertion not observed"
          )
        )
      }
    }

  val parameterDepths = new SpecTest(
    "ftq.parameterDepths",
    Seq("funcFtqAllocate", "funcFtqRecovery", "propFtqInOrderRelease")
  ) {
    def run(): Seq[TCheck] = Seq(2, 4, 8, 32).flatMap { depth =>
      val p = FrontendParams(
        tuning = fp.tuning.copy(ftqDepth = depth),
        backend =
          fp.backend.copy(frontend = fp.backend.frontend.copy(ftqDepth = depth))
      )
      sim(new FetchTargetQueue(p)) { dut =>
        val d = new Drv(dut); d.cycle()
        for (round <- 0 until 3) {
          val ids = (0 until depth).map(n =>
            d.enqueue(Pred(0x1000 + (round * depth + n) * 16L))
          )
          d.run(depth + 2)
          val out = Outcome(BRANCH, 0, true, 0x8000)
          d.event =
            Some(Event(RecoveryKind.BranchMispredict, 0x8000, ids.head, out));
          d.cycle()
          d.event = None; d.run(3); d.commitOne(ids.head, out)
        }
        Seq(
          chk(
            d.reqs.size == 3 * depth && d.trains.size == 3 && d.restores.size == 3,
            s"depth $depth supports full windows, pointer wrap and recovery",
            s"${d.reqs.size}/${d.trains.size}/${d.restores.size}"
          )
        )
      }
    }
  }

  val all: Seq[SpecTest] = Seq(
    midBlockRequest,
    midBlockRestorePc,
    midBlockTrain,
    capacityWrap,
    trainBackpressure,
    selectiveWrap,
    restoreBurst,
    archCommit,
    archFallback,
    releaseGuard,
    parameterDepths
  )
}
