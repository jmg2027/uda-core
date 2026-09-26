package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.shared.RecoveryKind
import udacore.frontend.design.modules.BranchPredictor
import verif.spectest.FrontendTestKit._

/** L1 SpecTests for the ADR-019 BranchPredictor (ADR-018; spec 242feaf + ADR-019A..G).
  *
  * The driver plays FetchPcGen (PredictReq, NextPc ready), the FTQ (Prediction ready,
  * HistoryRestore, PredictorTrain), and the RecoveryEvent broadcast. BTB contents are created
  * only through PredictorTrain, the single training point.
  */
object BranchPredictorSpecTests {

  case class Train(fetchPc: Long, committed: Outcome, predicted: Option[Pred] = None, ghr: BigInt = 0, meta: Meta = Meta())
  case class Restore(cp: Checkpoint, apply: Boolean, outcome: Outcome, pc: Long)

  class Drv(val dut: BranchPredictor) {
    val io  = dut.io
    var cyc = 0
    var req: Option[Long] = None
    var predReady, nextReady = true
    var restore: Option[Restore] = None
    var train: Option[Train] = None
    var event: Option[Event] = None
    val preds   = ArrayBuffer[(Int, Pred)]()
    val nextPcs = ArrayBuffer[(Int, Long)]()
    var reqFire, restoreFire, trainFire = false
    var forkViolations = Seq.empty[Int]

    def cycle(): Unit = {
      io.predictReqIn.valid.poke(req.nonEmpty.B); io.predictReqIn.bits.fetchPc.poke(req.getOrElse(0L).U)
      io.predictionOut.ready.poke(predReady.B); io.nextPcOut.ready.poke(nextReady.B)
      val r = io.historyRestoreIn
      r.valid.poke(restore.nonEmpty.B)
      restore.foreach { x => pokeCheckpoint(r.bits.checkpoint, x.cp); r.bits.applyOutcome.poke(x.apply.B)
        pokeOutcome(r.bits.outcome, x.outcome); r.bits.pc.poke(x.pc.U) }
      val t = io.predictorTrainIn
      t.valid.poke(train.nonEmpty.B)
      train.foreach { x => t.bits.fetchPc.poke(x.fetchPc.U); t.bits.ghr.poke(x.ghr.U); pokeMeta(t.bits.meta, x.meta)
        pokePred(t.bits.predicted, x.predicted.getOrElse(Pred(x.fetchPc))); pokeOutcome(t.bits.committed, x.committed) }
      pokeEvent(io.recoveryEventIn, event)
      reqFire     = req.nonEmpty && b(io.predictReqIn.ready)
      restoreFire = restore.nonEmpty && b(r.ready)
      trainFire   = train.nonEmpty && b(t.ready)
      val pf = b(io.predictionOut.valid) && predReady
      val nf = b(io.nextPcOut.valid) && nextReady
      if (pf != nf) forkViolations :+= cyc
      if (pf) preds += ((cyc, peekPred(io.predictionOut.bits)))
      if (nf) nextPcs += ((cyc, l(io.nextPcOut.bits.fetchPc)))
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())

    /** Offer one PredictReq and return the Prediction it produces. */
    def predict(pc: Long): Option[Pred] = {
      val n0 = preds.size
      req = Some(pc); var k = 0
      cycle(); while (!reqFire && k < 20) { cycle(); k += 1 }
      req = None
      k = 0; while (preds.size == n0 && k < 20) { cycle(); k += 1 }
      preds.drop(n0).headOption.map(_._2)
    }
    def trainOne(x: Train): Unit = { train = Some(x); var k = 0; cycle(); while (!trainFire && k < 20) { cycle(); k += 1 }; train = None; cycle() }
    def restoreOne(x: Restore): Unit = { restore = Some(x); var k = 0; cycle(); while (!restoreFire && k < 20) { cycle(); k += 1 }; restore = None; cycle() }
    def recover(e: Event, x: Restore): Unit = { event = Some(e); cycle(); event = None; restoreOne(x) }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new BranchPredictor(fp)) { dut => val d = new Drv(dut); d.cycle(); body(d) }

  /** A committed taken exit at `slot` of the block at `base`. */
  def taken(base: Long, t: Int, slot: Int, target: Long): Train = Train(base, Outcome(t, slot, taken = true, target))

  // ---- ADR-019G E-8: mid-block fetch-PC semantics ---------------------------------------------------

  val midBlockSlot0 = new SpecTest("bp.midBlockSlot0", Seq("funcBtbLookup", "funcBlockExitSelect")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val cold = d.predict(0x1000)
      d.trainOne(taken(0x1000, JAL, 1, 0x2000))
      val hot = d.predict(0x1000)
      Seq(
        chk(cold.exists(p => p.fetchPc == 0x1000 && !p.cfiValid && !p.taken && p.nextPc == 0x1010),
          "a slot-0 request with no BTB entry falls through to the next block", s"$cold"),
        chk(hot.exists(p => p.cfiValid && p.cfiSlot == 1 && p.cfiType == JAL && p.taken && p.target == 0x2000 && p.nextPc == 0x2000),
          "a slot-0 request selects the trained JAL at slot 1 and redirects to its target", s"$hot"),
        chk(d.forkViolations.isEmpty, "Prediction and NextPc fire together", s"${d.forkViolations}"))
    }
  }

  val midBlockFallThrough = new SpecTest("bp.midBlockFallThrough", Seq("funcBlockExitSelect")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val s2 = d.predict(0x1008); val s3 = d.predict(0x100c); val s1 = d.predict(0x2004)
      val nexts = d.nextPcs.map(_._2).toSeq
      Seq(
        chk(s2.exists(p => p.fetchPc == 0x1008 && !p.cfiValid && p.nextPc == 0x1010),
          "a slot-2 request keeps its requested fetchPc 0x1008 and falls through to blockBase + 16 = 0x1010", s"$s2"),
        chk(s3.exists(p => p.fetchPc == 0x100c && p.nextPc == 0x1010) && s1.exists(p => p.fetchPc == 0x2004 && p.nextPc == 0x2010),
          "slot-3 and slot-1 requests also fall through to the next block boundary", s"$s3 $s1"),
        chk(nexts == Seq(0x1010L, 0x1010L, 0x2010L), "NextPc carries the same next block boundary", s"$nexts"))
    }
  }

  val midBlockEarlierSlot = new SpecTest("bp.midBlockEarlierSlot", Seq("funcBtbLookup")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.trainOne(taken(0x1000, JAL, 1, 0x2000))
      val s2 = d.predict(0x1008); val s1 = d.predict(0x1004)
      Seq(
        chk(s2.exists(p => !p.cfiValid && !p.taken && p.nextPc == 0x1010),
          "a slot-2 request ignores the BTB CFI at slot 1 (cfiSlot < startSlot)", s"$s2"),
        chk(s1.exists(p => p.cfiValid && p.cfiSlot == 1 && p.nextPc == 0x2000),
          "a slot-1 request still selects it (cfiSlot = startSlot is eligible)", s"$s1"))
    }
  }

  val midBlockLaterSlot = new SpecTest("bp.midBlockLaterSlot", Seq("funcBtbLookup", "funcPredictorTraining")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.trainOne(taken(0x1000, JAL, 3, 0x3000))
      val only3 = d.predict(0x1008)
      d.trainOne(taken(0x1000, JAL, 1, 0x2000))
      val both2 = d.predict(0x1008); val both0 = d.predict(0x1000)
      Seq(
        chk(only3.exists(p => p.fetchPc == 0x1008 && p.cfiValid && p.cfiSlot == 3 && p.taken && p.nextPc == 0x3000),
          "a slot-2 request selects the BTB CFI at slot 3 (trained with blockBase 0x1000)", s"$only3"),
        chk(both2.exists(p => p.cfiSlot == 3 && p.nextPc == 0x3000) && both0.exists(p => p.cfiSlot == 1 && p.nextPc == 0x2000),
          "with CFIs at slots 1 and 3, a slot-2 request takes slot 3 and a slot-0 request takes slot 1", s"$both2 $both0"))
    }
  }

  val midBlockCallPush = new SpecTest("bp.midBlockCallPush", Seq("funcRasPredict")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.trainOne(taken(0x1000, CALL, 3, 0x4000))
      d.trainOne(taken(0x5000, RET, 2, 0x9999))
      val call = d.predict(0x1008); val ret = d.predict(0x5008)
      Seq(
        chk(call.exists(p => p.cfiValid && p.cfiType == CALL && p.cfiSlot == 3 && p.nextPc == 0x4000),
          "a slot-2 request predicts the Call at slot 3", s"$call"),
        chk(ret.exists(p => p.cfiType == RET && p.target == 0x1010 && p.nextPc == 0x1010) &&
            ret.exists(p => p.cp.ras(p.cp.rasTop) == 0x1010),
          "the Call pushed blockBase + 4 * 3 + 4 = 0x1010 (not requestedPc + 16 = 0x1018); the Ret pops it", s"$ret"))
    }
  }

  val midBlockRestorePc = new SpecTest("bp.midBlockRestorePc", Seq("funcHistoryRestore")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.trainOne(taken(0x5000, RET, 0, 0x9999))
      val start = d.predict(0x1008).map(_.cp).getOrElse(Checkpoint())
      // The FTQ reports the recovering Call at slot 3 of block 0x1000: pc = blockBase + 12.
      d.recover(Event(RecoveryKind.BranchMispredict, 0x4000, 0, Outcome(CALL, 3, taken = true, 0x4000)),
        Restore(start, apply = true, Outcome(CALL, 3, taken = true, 0x4000), 0x100c))
      val ret = d.predict(0x5000)
      Seq(chk(ret.exists(p => p.target == 0x1010 && p.cp.ras(p.cp.rasTop) == 0x1010),
        "a restored Call at HistoryRestore.pc 0x100c pushes 0x1010", s"$ret"))
    }
  }

  val all: Seq[SpecTest] = Seq(midBlockSlot0, midBlockFallThrough, midBlockEarlierSlot, midBlockLaterSlot, midBlockCallPush,
    midBlockRestorePc)
}
