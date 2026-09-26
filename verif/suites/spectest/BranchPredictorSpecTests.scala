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

  // ---- funcTageDirection / funcPredictorTraining: an FTQ-like closed loop ------------------------------

  /** Run `n` iterations of one block at `pc` whose Branch at `slot` (target = pc) resolves per
    * `outcome(i)`; a wrong direction is recovered (event + restore with the outcome) and every
    * block is trained at commit in order. Returns the per-iteration misprediction flags. */
  def branchLoop(d: Drv, pc: Long, slot: Int, n: Int)(outcome: Int => Boolean): Seq[Boolean] = (0 until n).map { i =>
    val p = d.predict(pc).get
    val o = outcome(i)
    val tracked = p.cfiValid && p.cfiType == BRANCH && p.cfiSlot == slot
    val guess = tracked && p.taken
    val exit = if (o) Outcome(BRANCH, slot, taken = true, pc) else Outcome()
    if (guess != o)
      d.recover(Event(RecoveryKind.BranchMispredict, if (o) pc else pc + 16, 0, Outcome(BRANCH, slot, o, pc)),
        Restore(p.cp, apply = true, Outcome(BRANCH, slot, o, pc), (pc & ~15L) + 4 * slot))
    d.trainOne(Train(pc & ~15L, exit, Some(p), p.cp.ghr, p.meta))
    guess != o
  }

  val tageBias = new SpecTest("bp.tageBias", Seq("funcTageDirection", "funcPredictorTraining")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val t = branchLoop(d, 0x1000, 2, 12)(_ => true)
      val n = branchLoop(d, 0x2004, 1, 12)(_ => false)
      Seq(
        chk(t.drop(4).forall(!_), "an always-taken Branch is learned (no mispredictions after warm-up)", s"$t"),
        chk(n.forall(!_), "a never-taken block never predicts taken", s"$n"))
    }
  }

  val tageHistory = new SpecTest("bp.tageHistory", Seq("funcTageDirection", "funcSpeculativeHistoryUpdate", "funcHistoryRestore")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val alt  = branchLoop(d, 0x1000, 0, 60)(i => i % 2 == 0)
      val per3 = branchLoop(d, 0x3000, 1, 90)(i => i % 3 != 2)
      Seq(
        chk(alt.drop(40).count(identity) == 0,
          s"an alternating Branch is learned through the global history (late mispredictions ${alt.drop(40).count(identity)})",
          alt.map(if (_) 'x' else '.').mkString),
        chk(per3.drop(60).count(identity) == 0,
          s"a period-3 pattern (T T N) is learned (late mispredictions ${per3.drop(60).count(identity)})",
          per3.map(if (_) 'x' else '.').mkString))
    }
  }

  val tageUseAlt = new SpecTest("bp.tageUseAlt", Seq("funcTageDirection")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // An always-taken Branch saturates the history to all ones and the bimodal base to taken.
      val warm = branchLoop(d, 0x1000, 0, 70)(_ => true)
      // One not-taken outcome allocates a weak, not-yet-useful entry for the all-ones history.
      val once = branchLoop(d, 0x1000, 0, 1)(_ => false)
      // When the short history is all ones again the new entry matches; being weak with useful 0,
      // the alternate (taken) decides, so the taken stream is not disturbed.
      val after = branchLoop(d, 0x1000, 0, 24)(_ => true)
      Seq(
        chk(warm.drop(10).forall(!_) && once == Seq(true), "warm-up learns taken; the single not-taken outcome mispredicts",
          s"${warm.map(if (_) 'x' else '.').mkString} $once"),
        chk(after.forall(!_), "a weak provider with useful 0 defers to the alternate prediction (no new mispredictions)",
          after.map(if (_) 'x' else '.').mkString))
    }
  }

  // ---- funcSpeculativeHistoryUpdate / funcRasPredict -----------------------------------------------------

  val specUpdate = new SpecTest("bp.specUpdate", Seq("funcSpeculativeHistoryUpdate", "funcRasPredict", "funcBlockExitSelect")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // One block per BTB set (set = pc[9:4]) so no training evicts another.
      (0 until 3).foreach(_ => d.trainOne(taken(0x1010, BRANCH, 1, 0x2020)))
      d.trainOne(taken(0x2020, JAL, 2, 0x3030)); d.trainOne(taken(0x3030, CALL, 0, 0x4040))
      d.trainOne(taken(0x4040, CALL, 3, 0x5050)); d.trainOne(taken(0x5050, RET, 1, 0))
      d.trainOne(taken(0x6060, JALR, 0, 0xc000))
      val a = d.predict(0x1010).get; val b7 = d.predict(0x2020).get; val c = d.predict(0x3030).get
      val e = d.predict(0x4040).get; val r1 = d.predict(0x5050).get; val r2 = d.predict(0x5050).get
      val nt = d.predict(0x7070).get; val j = d.predict(0x6060).get
      val g0 = a.cp.ghr
      val depth = fp.tuning.rasDepth
      Seq(
        chk(a.taken && a.nextPc == 0x2020 && b7.cp.ghr == ((g0 << 1) | 1) % (BigInt(1) << fp.ghrLength),
          "a predicted-taken Branch shifts one 1 into the GHR", s"$a $b7"),
        chk(c.cp.ghr == b7.cp.ghr && e.cp.ghr == b7.cp.ghr && r1.cp.ghr == b7.cp.ghr && nt.cp.ghr == b7.cp.ghr,
          "Jal / Call / Ret / fall-through blocks leave the GHR unchanged", s"$b7 $c $e $r1 $nt"),
        chk(b7.taken && b7.nextPc == 0x3030 && c.nextPc == 0x4040 && e.nextPc == 0x5050 && j.taken && j.nextPc == 0xc000,
          "Jal, Call, and a non-return Jalr are always taken to their BTB target", s"$b7 $c $e $j"),
        chk(r1.target == 0x4050 && r2.target == 0x3034 && r1.nextPc == 0x4050,
          "Ret pops the RAS in LIFO order (0x4050 from the slot-3 Call, then 0x3034 from the slot-0 Call)", s"$r1 $r2"),
        chk(r2.cp.rasTop == (r1.cp.rasTop + depth - 1) % depth && e.cp.rasTop == (c.cp.rasTop + 1) % depth,
          "the checkpoint RAS top moves by one per push / pop", s"$c $e $r1 $r2"))
    }
  }

  // ---- funcHistoryRestore / propHistoryRestoreExact --------------------------------------------------------

  val restoreExact = new SpecTest("bp.restoreExact", Seq("funcHistoryRestore", "propHistoryRestoreExact")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.trainOne(taken(0x2000, CALL, 1, 0x2000)); d.trainOne(taken(0x3000, RET, 0, 0))
      (0 until 3).foreach(_ => d.trainOne(taken(0x4000, BRANCH, 0, 0x4000)))
      (0 until 3).foreach(_ => d.predict(0x2000))
      val x = d.predict(0x4000).get // the recovering block
      // Wrong path: wrap the RAS with pushes, pop some, shift the GHR.
      (0 until 11).foreach(_ => d.predict(0x2000)); (0 until 3).foreach(_ => d.predict(0x3000)); (0 until 5).foreach(_ => d.predict(0x4000))
      // Event: the next request is held until the restore; nothing is predicted in between.
      d.event = Some(Event(RecoveryKind.BranchMispredict, 0x4010, 0, Outcome(BRANCH, 0, taken = false, 0x4000)))
      val n0 = d.preds.size
      d.req = Some(0x5000); d.cycle(); d.event = None
      d.run(4); val held = d.preds.size == n0 && !d.reqFire
      d.req = None
      d.restoreOne(Restore(x.cp, apply = true, Outcome(BRANCH, 0, taken = false, 0x4000), 0x4000))
      val after = d.predict(0x5000).get
      val expGhr = (x.cp.ghr << 1) % (BigInt(1) << fp.ghrLength)
      // ArchRedirect: restore only.
      d.event = Some(Event(RecoveryKind.ArchRedirect, 0x6000, 0)); d.cycle(); d.event = None
      d.restoreOne(Restore(x.cp, apply = false, Outcome(CALL, 1, taken = true, 0x2000), 0x2004))
      val ar = d.predict(0x5000).get
      // A restored Call pushes pc + 4; a restored Ret pops.
      d.event = Some(Event()); d.cycle(); d.event = None
      d.restoreOne(Restore(x.cp, apply = true, Outcome(CALL, 1, taken = true, 0x2000), 0x2004))
      val call = d.predict(0x5000).get
      d.event = Some(Event()); d.cycle(); d.event = None
      d.restoreOne(Restore(x.cp, apply = true, Outcome(RET, 0, taken = true, 0x9990), 0x3000))
      val ret = d.predict(0x5000).get
      val depth = fp.tuning.rasDepth
      Seq(
        chk(held, "in and after a RecoveryEvent cycle the predictor holds PredictReq and predicts nothing until the HistoryRestore", s"${d.preds.drop(n0)}"),
        chk(after.cp.ghr == expGhr && after.cp.rasTop == x.cp.rasTop && after.cp.ras == x.cp.ras,
          "GHR = checkpoint with the resolved not-taken bit; RAS top and every entry = checkpoint despite wrong-path wrap",
          s"x ${x.cp} after ${after.cp}"),
        chk(ar.cp == x.cp, "an ArchRedirect restore (applyOutcome = false) restores the checkpoint exactly", s"${ar.cp}"),
        chk(call.cp.rasTop == (x.cp.rasTop + 1) % depth && call.cp.ras(call.cp.rasTop) == 0x2008 && call.cp.ghr == x.cp.ghr &&
            call.cp.ras.indices.filter(_ != call.cp.rasTop).forall(i => call.cp.ras(i) == x.cp.ras(i)),
          "a resolved Call pushes pc + 4 after the restore (GHR unchanged)", s"${call.cp}"),
        chk(ret.cp.rasTop == (x.cp.rasTop + depth - 1) % depth && ret.cp.ras == x.cp.ras, "a resolved Ret pops after the restore",
          s"${ret.cp}"))
    }
  }

  // ---- funcPredictorTraining: BTB allocation, update, replacement ------------------------------------------

  val btbTraining = new SpecTest("bp.btbTraining", Seq("funcPredictorTraining", "funcBtbLookup")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.trainOne(Train(0x6000, Outcome()))
      val ft = d.predict(0x6000)
      d.trainOne(taken(0x6000, JALR, 1, 0x1111L << 2)); d.trainOne(taken(0x6000, JALR, 1, 0x2222L << 2))
      val upd = d.predict(0x6000)
      // Three blocks in one fresh BTB set (set 1, 2 ways): 0x1010 -> way 0, 0x1410 -> way 1, 0x1010
      // re-trained (way 0 most recent), then 0x1810 must replace way 1 (0x1410), not way 0.
      d.trainOne(taken(0x1010, JAL, 0, 0xa0)); d.trainOne(taken(0x1410, JAL, 0, 0xb0)); d.trainOne(taken(0x1010, JAL, 0, 0xa0))
      d.trainOne(taken(0x1810, JAL, 0, 0xc0))
      val h0 = d.predict(0x1010); val h1 = d.predict(0x1410); val h2 = d.predict(0x1810)
      val g = d.predict(0x6000).get.cp
      d.trainOne(taken(0x7000, CALL, 0, 0x100)); d.trainOne(taken(0x7010, BRANCH, 0, 0x100))
      val g2 = d.predict(0x6000).get.cp
      Seq(
        chk(ft.exists(!_.cfiValid), "a committed fall-through allocates nothing", s"$ft"),
        chk(upd.exists(p => p.cfiType == JALR && p.target == (0x2222L << 2)), "re-training the same slot updates its target in place", s"$upd"),
        chk(h0.exists(_.nextPc == 0xa0) && h1.exists(!_.cfiValid) && h2.exists(_.nextPc == 0xc0),
          "a full set replaces its least recently trained way (0x1410)", s"$h0 $h1 $h2"),
        chk(g2 == g, "training (here a Call and a Branch block) never changes the speculative GHR or RAS", s"$g $g2"))
    }
  }

  // ---- Interface protocol: atomic fork and backpressure (propPredictFromPcOnly, propOneTakenCfiPerBlock) ----

  val fork = new SpecTest("bp.fork", Seq("propPredictFromPcOnly", "propOneTakenCfiPerBlock", "funcBlockExitSelect")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.trainOne(taken(0x2000, CALL, 2, 0x3000))
      d.predReady = false; d.req = Some(0x2000); d.run(3); val blocked1 = d.preds.isEmpty && d.nextPcs.isEmpty && !d.reqFire
      d.predReady = true; d.nextReady = false; d.run(3); val blocked2 = d.preds.isEmpty && d.nextPcs.isEmpty && !d.reqFire
      d.nextReady = true; d.cycle(); d.req = None
      val first = d.preds.headOption.map(_._2)
      val again = d.predict(0x2000)
      val s = d.predict(0x2008); val s3 = d.predict(0x200c)
      Seq(
        chk(blocked1 && blocked2, "neither output fires (and the request is not taken) unless both are ready", s"${d.preds} ${d.nextPcs}"),
        chk(first.exists(p => p.cfiSlot == 2 && p.nextPc == 0x3000) && d.nextPcs.headOption.exists(_._2 == 0x3000) && d.forkViolations.isEmpty,
          "the prediction and its NextPc fire together once both are ready", s"$first ${d.nextPcs}"),
        chk(again.exists(p => p.cp.rasTop == (first.get.cp.rasTop + 1) % fp.tuning.rasDepth),
          "the blocked cycles made no speculative update (exactly one push)", s"$first $again"),
        chk(s.exists(p => p.cfiSlot == 2 && p.fetchPc == 0x2008) && s3.exists(p => !p.cfiValid && p.nextPc == 0x2010),
          "the CFI is named only at or after startSlot; past it the block falls through", s"$s $s3"))
    }
  }

  val all: Seq[SpecTest] = Seq(midBlockSlot0, midBlockFallThrough, midBlockEarlierSlot, midBlockLaterSlot, midBlockCallPush,
    midBlockRestorePc, tageBias, tageHistory, tageUseAlt, specUpdate, restoreExact, btbTraining, fork)
}
