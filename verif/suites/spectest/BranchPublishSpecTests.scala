package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.modules.{AluUnit, BranchUnit, PublishMux, RecoveryController}
import udacore.backend.design.shared._
import udacore.common.ControlSignal.{AluControl, BranchControl}

/** L1 SpecTests for the ADR-019 BranchUnit and PublishMux (ADR-018; spec 242feaf + ADR-019C
  * E-4..E-7), plus the ADR-019C C-2 integration case: BranchUnit + AluUnit +
  * RecoveryController + PublishMux wired together (elaboration through firtool proves there is
  * no combinational loop; simulation proves a younger ALU result killed by the branch's
  * same-cycle RecoveryEvent is never published while the branch's own result survives).
  */
object BranchPublishSpecTests {

  val p = BackendParams()
  val D = p.robDepth
  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)
  def u32(i: Long): Long = i & 0xFFFFFFFFL
  def code(e: chisel3.EnumType): Int = e.litValue.toInt
  def code(u: UInt): Int = u.litValue.toInt
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  val BM = RecoveryKind.BranchMispredict
  val AR = RecoveryKind.ArchRedirect
  val B  = BranchControl
  val CT = CfiType

  def mustAssert[M <: Module](t: SpecTest, gen: => M, label: String, expect: String)(body: M => Unit): TCheck = {
    var reachedEnd = false
    try {
      t.sim(gen) { dut => body(dut); dut.clock.step(3); reachedEnd = true; Nil }
      TCheck(false, label, "simulation ended normally: the design assertion did not fire")
    } catch {
      case e: NotImplementedError => throw e
      case e: Throwable =>
        val fired = chisel3.simulator.CachedSimulator.lastSimulationLog.linesIterator.filter(_.contains("Assertion failed")).toSeq
        val ok = !reachedEnd && fired.exists(_.contains(expect))
        TCheck(ok, label, if (ok) "" else s"expected '$expect'; got: ${fired.headOption.getOrElse(e.getClass.getSimpleName)}")
    }
  }

  // ==== BranchUnit ================================================================================

  case class Br(s: Int, ctrl: Int, cfi: Int, src1: Long = 0, src2: Long = 0, imm: Long = 16, pc: Long = 0x1000,
      prd: Int = 0, hasDest: Boolean = false, ckpt: Int = 1, predTaken: Boolean = false, predTarget: Long = 0,
      ftq: Int = 3, slot: Int = 2)
  case class BObs(reqReady: Boolean, resV: Boolean, resF: Boolean, res: Map[String, Long],
      rsvV: Boolean, rsvF: Boolean, rsv: Map[String, Long], relV: Boolean, relF: Boolean, rel: Map[String, Long])

  class BDrv(val dut: BranchUnit) {
    val io = dut.io
    var resultReady, resolutionReady, releaseReady = true
    /** A RecoveryController stand-in: a fired BranchResolution produces its own event in the
      * same cycle (zero latency), unless an explicit event is given. */
    var selfEvent = false
    def cycle(b: Option[Br] = None, event: Option[(UInt, Int)] = None): BObs = {
      val r = io.branchUnitReqIn
      r.valid.poke(b.nonEmpty.B)
      b.foreach { x =>
        r.bits.robTag.wrap.poke(tagOf(x.s)._1.B); r.bits.robTag.idx.poke(tagOf(x.s)._2.U)
        r.bits.fuType.poke(FuType.Branch); r.bits.op.poke(BranchOp.encode(x.ctrl, x.cfi).U)
        r.bits.src1.poke(u32(x.src1).U); r.bits.src2.poke(u32(x.src2).U); r.bits.imm.poke(u32(x.imm).U)
        r.bits.pc.poke(u32(x.pc).U); r.bits.prd.poke(x.prd.U); r.bits.hasDest.poke(x.hasDest.B)
        r.bits.checkpointId.id.poke(x.ckpt.U)
        r.bits.prediction.predictedTaken.poke(x.predTaken.B); r.bits.prediction.predictedTarget.poke(u32(x.predTarget).U)
        r.bits.prediction.ftqIdx.poke(x.ftq.U); r.bits.prediction.slot.poke(x.slot.U); r.bits.prediction.blockEnd.poke(false.B)
      }
      io.branchResultOut.ready.poke(resultReady.B)
      io.branchResolutionOut.ready.poke(resolutionReady.B)
      io.checkpointReleaseOut.ready.poke(releaseReady.B)
      val e = io.recoveryEventIn
      def pokeEv(k: UInt, s: Int): Unit = { e.valid.poke(true.B); e.kind.poke(k); e.robTag.wrap.poke(tagOf(s)._1.B); e.robTag.idx.poke(tagOf(s)._2.U) }
      e.valid.poke(false.B)
      event.foreach { case (k, s) => pokeEv(k, s) }
      val rsvV = io.branchResolutionOut.valid.peek().litToBoolean
      val rb = io.branchResolutionOut.bits
      if (event.isEmpty && selfEvent && rsvV && resolutionReady)
        pokeEv(BM, if (rb.robTag.wrap.peek().litToBoolean) rb.robTag.idx.peek().litValue.toInt + D else rb.robTag.idx.peek().litValue.toInt)
      def b2l(x: Bool) = if (x.peek().litToBoolean) 1L else 0L
      val o = io.branchResultOut.bits
      val res = Map("s" -> o.robTag.idx.peek().litValue.toLong, "data" -> o.data.peek().litValue.toLong,
        "wen" -> b2l(o.wen), "prd" -> o.prd.peek().litValue.toLong, "exc" -> b2l(o.exception.valid),
        "cause" -> o.exception.cause.peek().litValue.toLong, "tval" -> o.exception.tval.peek().litValue.toLong,
        "taken" -> b2l(o.cfiOutcome.taken), "target" -> o.cfiOutcome.target.peek().litValue.toLong,
        "cfi" -> o.cfiOutcome.cfiType.peek().litValue.toLong, "slot" -> o.cfiOutcome.slot.peek().litValue.toLong)
      val rsv = Map("s" -> rb.robTag.idx.peek().litValue.toLong, "ckpt" -> rb.checkpointId.id.peek().litValue.toLong,
        "redirect" -> rb.redirectTarget.peek().litValue.toLong, "cause" -> rb.cause.peek().litValue.toLong,
        "ftq" -> rb.ftqIdx.peek().litValue.toLong, "pc" -> rb.pc.peek().litValue.toLong,
        "taken" -> b2l(rb.outcome.taken), "target" -> rb.outcome.target.peek().litValue.toLong)
      val rl = io.checkpointReleaseOut.bits
      val rel = Map("s" -> rl.robTag.idx.peek().litValue.toLong, "ckpt" -> rl.checkpointId.id.peek().litValue.toLong)
      val resV = io.branchResultOut.valid.peek().litToBoolean
      val relV = io.checkpointReleaseOut.valid.peek().litToBoolean
      val rr = r.ready.peek().litToBoolean
      val ob = BObs(rr, resV, resV && resultReady, res, rsvV, rsvV && resolutionReady, rsv, relV, relV && releaseReady, rel)
      dut.clock.step()
      ob
    }
    def run(n: Int): Seq[BObs] = (0 until n).map(_ => cycle())
    /** Request ready with `b` presented, without advancing the clock. */
    def readyFor(b: Option[Br]): Boolean = {
      val r = io.branchUnitReqIn
      r.valid.poke(b.nonEmpty.B)
      b.foreach { x => r.bits.robTag.idx.poke((x.s % D).U); r.bits.op.poke(BranchOp.encode(x.ctrl, x.cfi).U)
        r.bits.src1.poke(u32(x.src1).U); r.bits.src2.poke(u32(x.src2).U) }
      io.branchResultOut.ready.poke(resultReady.B); io.checkpointReleaseOut.ready.poke(releaseReady.B)
      io.branchResolutionOut.ready.poke(resolutionReady.B); io.recoveryEventIn.valid.poke(false.B)
      val v = r.ready.peek().litToBoolean
      r.valid.poke(false.B)
      v
    }
    /** Present one branch, then collect its outputs over n cycles. */
    def exec(b: Br, n: Int = 3): Seq[BObs] = cycle(Some(b)) +: run(n)
  }

  def withB(t: SpecTest)(body: BDrv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new BranchUnit(p)) { dut => val d = new BDrv(dut); d.cycle(); body(d) }

  val RC = RecoveryCause

  val buResolve = new SpecTest("bu.resolve", Seq("funcBranchResolve")) {
    def run(): Seq[TCheck] = withB(this) { d =>
      def res(os: Seq[BObs]) = os.find(_.resV).map(_.res)
      val beqT = res(d.exec(Br(1, code(B.BEQ), code(CT.Branch), 5, 5, imm = 0x40, pc = 0x1000, predTaken = true, predTarget = 0x1040)))
      val beqN = res(d.exec(Br(2, code(B.BEQ), code(CT.Branch), 5, 6)))
      val bne  = res(d.exec(Br(3, code(B.BNE), code(CT.Branch), 5, 6, imm = 8, predTaken = true, predTarget = 0x1008)))
      val blt  = res(d.exec(Br(4, code(B.BLT), code(CT.Branch), u32(-1), 1, imm = 8, predTaken = true, predTarget = 0x1008)))
      val bgeu = res(d.exec(Br(5, code(B.BGEU), code(CT.Branch), u32(-1), 1, imm = 8, predTaken = true, predTarget = 0x1008)))
      val jal  = res(d.exec(Br(6, code(B.JAL), code(CT.Jal), imm = 0x100, pc = 0x2000, prd = 33, hasDest = true, predTaken = true, predTarget = 0x2100)))
      val jalr = res(d.exec(Br(7, code(B.JALR), code(CT.Jalr), src1 = 0x3001, imm = 4, pc = 0x2000, prd = 34, hasDest = true, predTaken = true, predTarget = 0x3004)))
      val misOs = d.exec(Br(8, code(B.JALR), code(CT.Jalr), src1 = 0x3002, imm = 0, pc = 0x2000, predTaken = true, predTarget = 0x3000))
      val mis = res(misOs)
      Seq(
        chk(beqT.exists(r => r("taken") == 1 && r("target") == 0x1040 && r("exc") == 0), "BEQ equal: taken to pc + imm", s"$beqT"),
        chk(beqN.exists(_("taken") == 0), "BEQ unequal: not taken", s"$beqN"),
        chk(bne.exists(_("taken") == 1) && blt.exists(_("taken") == 1) && bgeu.exists(_("taken") == 1),
          "BNE, signed BLT (-1 < 1), unsigned BGEU (0xffffffff >= 1)", s"$bne $blt $bgeu"),
        chk(jal.exists(r => r("taken") == 1 && r("target") == 0x2100 && r("data") == 0x2004 && r("wen") == 1 && r("prd") == 33),
          "JAL: always taken, link value pc + 4 written to its prd", s"$jal"),
        chk(jalr.exists(r => r("target") == 0x3004 && r("data") == 0x2004), "JALR: target (src1 + imm) with bit 0 cleared", s"$jalr"),
        chk(mis.exists(r => r("exc") == 1 && r("cause") == 0 && r("tval") == 0x3002),
          "a taken target that is not 4-byte aligned raises instruction-address-misaligned (tval = target)", s"$mis"),
        chk(!misOs.exists(_.rsvV) && misOs.count(_.relV) == 1, "a faulting branch releases its checkpoint instead of recovering", s"$misOs")
      )
    }
  }

  val buMispredict = new SpecTest("bu.mispredict", Seq("funcMispredictDetect")) {
    def run(): Seq[TCheck] = withB(this) { d =>
      def ctl(os: Seq[BObs]) = (os.find(_.rsvV).map(_.rsv), os.count(_.relV))
      val dir  = ctl(d.exec(Br(1, code(B.BEQ), code(CT.Branch), 1, 2, imm = 0x40, pc = 0x1000, predTaken = true, predTarget = 0x1040)))
      val tgt  = ctl(d.exec(Br(2, code(B.JALR), code(CT.Jalr), src1 = 0x5000, pc = 0x1000, predTaken = true, predTarget = 0x4000, imm = 0)))
      val unJ  = ctl(d.exec(Br(3, code(B.JAL), code(CT.Jal), imm = 0x80, pc = 0x1000)))
      val unB  = ctl(d.exec(Br(4, code(B.BEQ), code(CT.Branch), 7, 7, imm = 0x20, pc = 0x1000)))
      val okNT = ctl(d.exec(Br(5, code(B.BNE), code(CT.Branch), 7, 7)))
      val okT  = ctl(d.exec(Br(6, code(B.BEQ), code(CT.Branch), 7, 7, imm = 0x20, pc = 0x1000, predTaken = true, predTarget = 0x1020)))
      Seq(
        chk(dir._1.exists(r => r("cause") == code(RC.DirectionMispredict) && r("redirect") == 0x1004 && r("taken") == 0) && dir._2 == 0,
          "predicted taken but not taken: DirectionMispredict, redirect pc + 4, no release", s"$dir"),
        chk(tgt._1.exists(r => r("cause") == code(RC.TargetMispredict) && r("redirect") == 0x5000) && tgt._2 == 0,
          "both taken with a different target: TargetMispredict, redirect to the resolved target", s"$tgt"),
        chk(unJ._1.exists(r => r("cause") == code(RC.UnpredictedCfi) && r("redirect") == 0x1080),
          "a taken jump that was not the predicted exit: UnpredictedCfi", s"$unJ"),
        chk(unB._1.exists(r => r("cause") == code(RC.DirectionMispredict) && r("redirect") == 0x1020),
          "a taken conditional branch predicted not taken: DirectionMispredict", s"$unB"),
        chk(okNT._1.isEmpty && okNT._2 == 1, "a not-taken, unpredicted branch is correct: release only", s"$okNT"),
        chk(okT._1.isEmpty && okT._2 == 1, "a correctly predicted taken branch: release only", s"$okT")
      )
    }
  }

  def mispredictBr(s: Int, ckpt: Int = 2) = Br(s, code(B.BEQ), code(CT.Branch), 1, 2, imm = 0x40, pc = 0x1000,
    predTaken = true, predTarget = 0x1040, ckpt = ckpt)
  def correctBr(s: Int, ckpt: Int = 2) = Br(s, code(B.BEQ), code(CT.Branch), 1, 1, imm = 0x40, pc = 0x1000,
    predTaken = true, predTarget = 0x1040, ckpt = ckpt)

  val buChannels = new SpecTest("bu.channels", Seq("funcBranchRecoveryRequest")) {
    def run(): Seq[TCheck] = withB(this) { d =>
      // Resolution fires while the result is backpressured; the result fires later.
      d.resultReady = false
      val a = d.exec(mispredictBr(1), 3)
      d.resultReady = true
      val a2 = d.run(2)
      // Result and resolution in the same cycle, with the branch's own event.
      d.selfEvent = true
      val b = d.exec(mispredictBr(2), 2)
      d.selfEvent = false
      // A release fires while the result is backpressured, and vice versa.
      d.resultReady = false
      val c = d.exec(correctBr(3), 2)
      d.resultReady = true; d.releaseReady = false
      val c2 = d.run(1)
      d.releaseReady = true
      val c3 = d.run(1)
      d.releaseReady = false
      val e = d.exec(correctBr(4), 2)
      d.releaseReady = true
      val e2 = d.run(2)
      // An older ArchRedirect kills the pending result and the unfired release.
      d.resultReady = false; d.releaseReady = false
      d.cycle(Some(correctBr(6)))
      val k = d.cycle(event = Some((AR, 5)))
      d.resultReady = true; d.releaseReady = true
      val k2 = d.run(3)
      // Request ready depends only on held state, not on the presented branch.
      d.resultReady = false
      d.cycle(Some(correctBr(8)))
      val ind = d.readyFor(None) == d.readyFor(Some(mispredictBr(9))) && d.readyFor(None) == d.readyFor(Some(correctBr(10)))
      val heldBlocks = !d.readyFor(Some(correctBr(10)))
      d.resultReady = true; d.run(2)
      Seq(
        chk(a.exists(o => o.rsvF && !o.resF) && a.count(_.rsvF) == 1 && a.forall(!_.resF) && a2.exists(_.resF),
          "BranchResolution fires while BranchResult is backpressured; the result publishes afterwards", s"$a $a2"),
        chk(b.exists(o => o.rsvF && o.resF), "BranchResolution and BranchResult can fire in the same cycle (own event)", s"$b"),
        chk(b.count(_.resF) == 1, "the branch's own RecoveryEvent does not kill its result", s"$b"),
        chk(c.exists(o => o.relF && !o.resF) && c2.exists(_.resF) && c3.forall(!_.relV),
          "CheckpointRelease fires independently of the result (and only once)", s"$c $c2 $c3"),
        chk(e.exists(o => o.resF && !o.relF) && e2.exists(_.relF), "the result fires while the release is backpressured", s"$e $e2"),
        chk(!k.resV && k2.forall(o => !o.resV && !o.relV), "an older ArchRedirect discards the pending result and the unfired release", s"$k $k2"),
        chk(Seq(a, b, c, e).forall(os => !os.exists(o => o.rsvF && o.relF)), "no branch emits both control side effects", ""),
        chk(ind && heldBlocks, "request ready depends only on held state (a held undrained result blocks), not on the presented branch", "")
      )
    }
  }

  /** Random branches, readiness, own-event feedback, and older ArchRedirects against a
    * reference: control XOR per surviving branch, recovery only on a live non-faulting
    * mispredict, exactly one result per surviving branch. */
  def buRandom(d: BDrv, seed: Long, steps: Int): Seq[TCheck] = {
    val rnd = new scala.util.Random(seed)
    case class Inf(s: Int, mis: Boolean, fault: Boolean, var ctl: Int = 0, var res: Int = 0, var killed: Boolean = false)
    val all = ArrayBuffer.empty[Inf]
    var s = 0
    var bad = Seq.empty[String]
    for (step <- 0 until steps) {
      d.resultReady = rnd.nextInt(3) > 0; d.releaseReady = rnd.nextInt(3) > 0; d.resolutionReady = true
      d.selfEvent = true
      val ev = if (all.nonEmpty && rnd.nextInt(30) == 0) Some((AR, math.max(0, s - 1))) else None
      val kind = rnd.nextInt(3)
      val br = kind match {
        case 0 => mispredictBr(s % 16)
        case 1 => correctBr(s % 16)
        case _ => Br(s % 16, code(B.JALR), code(CT.Jalr), src1 = 0x3002, imm = 0, predTaken = true, predTarget = 0x3000)
      }
      val o = d.cycle(Some(br), ev)
      val live = all.filterNot(_.killed)
      if (o.rsvF) live.find(x => x.s % 16 == o.rsv("s")).foreach { x => x.ctl += 1; if (!x.mis || x.fault) bad :+= s"step $step: resolution for a non-mispredict $x" }
      if (o.relF) live.find(x => x.s % 16 == o.rel("s")).foreach { x => x.ctl += 1; if (x.mis && !x.fault) bad :+= s"step $step: release for a mispredict $x" }
      if (o.resF) live.find(x => x.s % 16 == o.res("s")).foreach(_.res += 1)
      ev.foreach { _ => all.foreach(x => if (x.ctl == 0 || x.res == 0) x.killed = true) }
      // A branch presented in the cycle an older branch's resolution fires is younger than the
      // recovery point: its own-event kill drops it (wrong path).
      if (o.reqReady && ev.isEmpty && !o.rsvF) { all += Inf(s, kind == 0, kind == 2); s += 1 }
      all --= all.filter(x => x.killed || (x.ctl == 1 && x.res == 1))
    }
    d.resultReady = true; d.releaseReady = true
    (0 until 4).foreach { _ => val o = d.cycle()
      if (o.rsvF) all.find(x => x.s % 16 == o.rsv("s")).foreach(_.ctl += 1)
      if (o.relF) all.find(x => x.s % 16 == o.rel("s")).foreach(_.ctl += 1)
      if (o.resF) all.find(x => x.s % 16 == o.res("s")).foreach(_.res += 1) }
    val open = all.filterNot(x => x.killed || (x.ctl == 1 && x.res == 1))
    Seq(
      chk(bad.isEmpty, s"control tokens match the resolution kind (seed $seed)", bad.take(3).mkString("; ")),
      chk(open.isEmpty, s"every surviving branch has exactly one control token and one result (seed $seed)", s"$open")
    )
  }

  val buRecoveryOnly = new SpecTest("bu.recoveryOnlyOnMispredict", Seq("propRecoveryOnlyOnMispredict")) {
    def run(): Seq[TCheck] = withB(this)(d => buRandom(d, 83L, 400))
  }
  val buCompletesOnce = new SpecTest("bu.completesOnce", Seq("propBranchCompletesOnce")) {
    def run(): Seq[TCheck] = withB(this)(d => buRandom(d, 89L, 400))
  }
  val buControlOnce = new SpecTest("bu.controlOnce", Seq("propBranchControlOnce")) {
    def run(): Seq[TCheck] = withB(this)(d => buRandom(d, 97L, 400))
  }

  // ==== PublishMux ==================================================================================

  val inputs = Seq("alu", "mul", "div", "branch", "csr", "mem") // csr: native FuResult (ADR-019D E-1)
  case class Cand(s: Int, prd: Int = 40, wen: Boolean = true, data: Long = 0, exc: Boolean = false, hx: Boolean = false)
  case class PObs(granted: Seq[String], prfV: Boolean, prf: (Int, Long), wk: Option[Int], robV: Boolean, rob: Map[String, Long])

  class PDrv(val dut: PublishMux) {
    val io = dut.io
    var prfReady, robReady = true
    private def port(n: String) = n match {
      case "alu" => io.aluResultIn; case "mul" => io.multiplierResultIn; case "div" => io.dividerResultIn
      case "branch" => io.branchResultIn; case "csr" => io.csrResultIn
    }
    def cycle(c: Map[String, Cand]): PObs = {
      for (n <- inputs) {
        val v = c.get(n)
        if (n == "mem") {
          val m = io.memResultIn
          m.valid.poke(v.nonEmpty.B)
          v.foreach { x => m.bits.robTag.wrap.poke(tagOf(x.s)._1.B); m.bits.robTag.idx.poke(tagOf(x.s)._2.U)
            m.bits.prd.poke(x.prd.U); m.bits.wen.poke(x.wen.B); m.bits.data.poke(x.data.U); m.bits.headExecute.poke(x.hx.B)
            m.bits.exception.valid.poke(x.exc.B); m.bits.exception.cause.poke(5.U) }
        } else {
          val f = port(n)
          f.valid.poke(v.nonEmpty.B)
          v.foreach { x => f.bits.robTag.wrap.poke(tagOf(x.s)._1.B); f.bits.robTag.idx.poke(tagOf(x.s)._2.U)
            f.bits.prd.poke(x.prd.U); f.bits.wen.poke(x.wen.B); f.bits.data.poke(x.data.U)
            f.bits.exception.valid.poke(x.exc.B); f.bits.exception.cause.poke(2.U)
            f.bits.cfiOutcome.taken.poke((n == "branch").B) }
        }
      }
      io.physicalRegWriteOut.ready.poke(prfReady.B)
      io.robCompletionOut.ready.poke(robReady.B)
      val granted = inputs.filter { n =>
        val (v, r) = if (n == "mem") (io.memResultIn.valid, io.memResultIn.ready) else (port(n).valid, port(n).ready)
        v.peek().litToBoolean && r.peek().litToBoolean }
      val pw = io.physicalRegWriteOut
      val w  = io.wakeupBroadcastOut
      val rc = io.robCompletionOut
      def b2l(x: Bool) = if (x.peek().litToBoolean) 1L else 0L
      val o = PObs(granted, pw.valid.peek().litToBoolean && prfReady,
        (pw.bits.prd.peek().litValue.toInt, pw.bits.data.peek().litValue.toLong),
        if (w.valid.peek().litToBoolean) Some(w.prd.peek().litValue.toInt) else None,
        rc.valid.peek().litToBoolean && robReady,
        Map("s" -> rc.bits.robTag.idx.peek().litValue.toLong, "exc" -> b2l(rc.bits.exception.valid),
          "hx" -> b2l(rc.bits.headExecute), "taken" -> b2l(rc.bits.cfiOutcome.taken)))
      dut.clock.step()
      o
    }
  }

  def withP(t: SpecTest)(body: PDrv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new PublishMux(p)) { dut => val d = new PDrv(dut); body(d) }

  val pmArbitrate = new SpecTest("pm.arbitrate", Seq("funcPublishArbitrate")) {
    def run(): Seq[TCheck] = withP(this) { d =>
      var pend = Map("alu" -> Cand(5, 35, data = 0x55), "mul" -> Cand(2, 32, data = 0x22), "div" -> Cand(7, 37, data = 0x77),
        "branch" -> Cand(3, 33, data = 0x33), "mem" -> Cand(4, 34, data = 0x44), "csr" -> Cand(6, 36, data = 0x66))
      var order = Seq.empty[Long]
      var losersHeld = true
      var guard = 0
      while (pend.nonEmpty && guard < 16) {
        guard += 1
        val o = d.cycle(pend)
        if (o.granted.size != 1) losersHeld = false
        order :+= o.rob("s")
        pend = pend -- o.granted
      }
      Seq(
        chk(order == Seq(2, 3, 4, 5, 6, 7), "each cycle the oldest valid candidate is granted (the CSR FuResult included, ADR-019D E-1)", s"$order"),
        chk(losersHeld, "exactly one candidate is taken per cycle; losers stay valid on their edges", "")
      )
    }
  }

  val pmFanout = new SpecTest("pm.fanout", Seq("funcPublishFanout")) {
    def run(): Seq[TCheck] = withP(this) { d =>
      val a = d.cycle(Map("alu" -> Cand(1, 41, data = 0xabc)))
      val n = d.cycle(Map("branch" -> Cand(2, 0, wen = false)))
      val m = d.cycle(Map("mem" -> Cand(3, 0, wen = false, hx = true)))
      d.robReady = false
      val r = d.cycle(Map("alu" -> Cand(4, 42, data = 1)))
      d.robReady = true; d.prfReady = false
      val w = d.cycle(Map("alu" -> Cand(5, 43, data = 2)))
      val nw = d.cycle(Map("mem" -> Cand(6, 0, wen = false, exc = true)))
      d.prfReady = true
      Seq(
        chk(a.granted == Seq("alu") && a.prfV && a.prf == ((41, 0xabcL)) && a.wk.contains(41) && a.robV && a.rob("s") == 1,
          "a granted result writes the PRF, wakes its prd, and completes its ROB entry in one cycle", s"$a"),
        chk(n.robV && !n.prfV && n.wk.isEmpty && n.rob("taken") == 1, "wen = 0: ROB completion only (no PRF write, no wakeup)", s"$n"),
        chk(m.robV && m.rob("hx") == 1 && !m.prfV, "a headExecute memory completion passes to the ROB", s"$m"),
        chk(r.granted.isEmpty && !r.prfV && r.wk.isEmpty, "the grant is withheld while the ROB completion is not ready", s"$r"),
        chk(w.granted.isEmpty && !w.robV && w.wk.isEmpty, "the grant is withheld while a needed PRF write is not ready", s"$w"),
        chk(nw.granted == Seq("mem") && nw.robV && nw.rob("exc") == 1, "a result without a PRF write does not wait for the PRF", s"$nw")
      )
    }
  }

  val pmSingleDrain = new SpecTest("pm.singleDrain", Seq("propSingleDrain")) {
    def run(): Seq[TCheck] = withP(this) { d =>
      val rnd = new scala.util.Random(101)
      var bad = Seq.empty[String]
      var pubs = 0
      for (step <- 0 until 400) {
        val c = inputs.filter(_ => rnd.nextBoolean()).map(n => n -> Cand(rnd.nextInt(14), 32 + rnd.nextInt(16),
          wen = rnd.nextInt(4) > 0, data = rnd.nextInt(1000))).toMap
        d.prfReady = rnd.nextInt(6) > 0; d.robReady = rnd.nextInt(6) > 0
        val o = d.cycle(c)
        if (o.granted.size > 1) bad :+= s"step $step: ${o.granted}"
        if (o.granted.size == 1) {
          pubs += 1
          val x = c(o.granted.head)
          if (!o.robV || o.rob("s") != x.s) bad :+= s"step $step: completion $o for $x"
          if (x.wen && !(o.prfV && o.prf._1 == x.prd && o.wk.contains(x.prd))) bad :+= s"step $step: write/wakeup $o for $x"
          if (!x.wen && (o.prfV || o.wk.nonEmpty)) bad :+= s"step $step: spurious write $o"
          val oldest = c.values.map(_.s).min
          if (x.s != oldest) bad :+= s"step $step: granted s${x.s} not oldest $oldest"
        } else if (o.robV || o.prfV || o.wk.nonEmpty) bad :+= s"step $step: output without a grant $o"
      }
      Seq(
        chk(bad.isEmpty, "at most one PRF write, wakeup, and completion per cycle, all for the granted oldest uop", bad.take(3).mkString("; ")),
        chk(pubs > 150, "the stream exercised publications", s"$pubs")
      )
    }
  }

  // ==== C-2 integration (ADR-019C) ==================================================================

  /** BranchUnit + AluUnit + RecoveryController + PublishMux, wired like BackendTop. */
  class C2Harness extends Module {
    val io = IO(new Bundle {
      val bru  = Flipped(Decoupled(new IssuedUop(p)))
      val alu  = Flipped(Decoupled(new IssuedUop(p)))
      val holdResults = Input(Bool()) // test knob: PublishMux ROB side not ready
      val event = Output(new RecoveryEvent(p))
      val robC  = Valid(new RobCompletion(p))
      val prfW  = Valid(new PhysicalRegWrite(p))
    })
    val bu = Module(new BranchUnit(p)); val al = Module(new AluUnit(p))
    val rc = Module(new RecoveryController(p)); val pm = Module(new PublishMux(p))
    bu.io.branchUnitReqIn <> io.bru; al.io.aluReqIn <> io.alu
    rc.io.branchResolutionIn <> bu.io.branchResolutionOut
    rc.io.archRedirectIn.valid := false.B; rc.io.archRedirectIn.bits := 0.U.asTypeOf(rc.io.archRedirectIn.bits)
    bu.io.checkpointReleaseOut.ready := true.B
    Seq(bu.io.recoveryEventIn, al.io.recoveryEventIn).foreach(_ := rc.io.recoveryEventOut)
    pm.io.branchResultIn <> bu.io.branchResultOut; pm.io.aluResultIn <> al.io.aluResultOut
    Seq(pm.io.multiplierResultIn, pm.io.dividerResultIn).foreach { x => x.valid := false.B; x.bits := 0.U.asTypeOf(x.bits) }
    pm.io.csrResultIn.valid := false.B; pm.io.csrResultIn.bits := 0.U.asTypeOf(pm.io.csrResultIn.bits)
    pm.io.memResultIn.valid := false.B; pm.io.memResultIn.bits := 0.U.asTypeOf(pm.io.memResultIn.bits)
    pm.io.physicalRegWriteOut.ready := true.B
    pm.io.robCompletionOut.ready := !io.holdResults
    io.event := rc.io.recoveryEventOut
    io.robC.valid := pm.io.robCompletionOut.fire; io.robC.bits := pm.io.robCompletionOut.bits
    io.prfW.valid := pm.io.physicalRegWriteOut.fire; io.prfW.bits := pm.io.physicalRegWriteOut.bits
  }

  val c2Integration = new SpecTest("pm.c2Integration", Seq("funcBranchRecoveryRequest", "funcPublishArbitrate")) {
    def run(): Seq[TCheck] = sim(new C2Harness) { dut =>
      def pokeUop(port: DecoupledIO[IssuedUop], s: Int, fu: UInt, op: Int, src1: Long, src2: Long, imm: Long, prd: Int,
          predT: Boolean, predTarget: Long): Unit = {
        port.valid.poke(true.B)
        port.bits.robTag.wrap.poke(false.B); port.bits.robTag.idx.poke(s.U); port.bits.fuType.poke(fu)
        port.bits.op.poke(op.U); port.bits.src1.poke(src1.U); port.bits.src2.poke(src2.U); port.bits.imm.poke(imm.U)
        port.bits.pc.poke(0x1000.U); port.bits.prd.poke(prd.U); port.bits.hasDest.poke(true.B)
        port.bits.checkpointId.id.poke(1.U)
        port.bits.prediction.predictedTaken.poke(predT.B); port.bits.prediction.predictedTarget.poke(predTarget.U)
        port.bits.prediction.ftqIdx.poke(0.U); port.bits.prediction.slot.poke(0.U); port.bits.prediction.blockEnd.poke(false.B)
      }
      dut.io.bru.valid.poke(false.B); dut.io.alu.valid.poke(false.B)
      // Both results pending: hold the ROB side so nothing publishes yet.
      dut.io.holdResults.poke(true.B)
      pokeUop(dut.io.alu, 6, FuType.Alu, AluOp.encode(code(AluControl.ADD)), 1, 2, 0, 40, false, 0)
      dut.clock.step(); dut.io.alu.valid.poke(false.B)
      // The pending ALU result is held under backpressure before the branch executes.
      // The mispredicting branch (s = 5, older than the ALU uop s = 6).
      pokeUop(dut.io.bru, 5, FuType.Branch, BranchOp.encode(code(BranchControl.BEQ), code(CfiType.Branch)), 1, 2, 0x40, 41,
        true, 0x1040)
      dut.clock.step(); dut.io.bru.valid.poke(false.B)
      // This cycle the BranchUnit fires its resolution: the event kills the younger ALU result.
      val ev = dut.io.event.valid.peek().litToBoolean
      val evTag = dut.io.event.robTag.idx.peek().litValue.toInt
      dut.clock.step()
      dut.io.holdResults.poke(false.B)
      var pubs = Seq.empty[(Int, Boolean)]
      for (_ <- 0 until 6) {
        if (dut.io.robC.valid.peek().litToBoolean)
          pubs :+= ((dut.io.robC.bits.robTag.idx.peek().litValue.toInt, dut.io.prfW.valid.peek().litToBoolean))
        dut.clock.step()
      }
      Seq(
        chk(ev && evTag == 5, "the BranchResolution produced a same-cycle RecoveryEvent while both results were pending", s"$ev $evTag"),
        chk(!pubs.exists(_._1 == 6), "the killed younger ALU result is never published (no PRF write, wakeup, or completion)", s"$pubs"),
        chk(pubs.exists(_._1 == 5), "the recovering branch's own result survives and publishes", s"$pubs"),
        chk(true, "the wired BranchUnit/RecoveryController/PublishMux loop elaborated through firtool (no combinational cycle)", "")
      )
    }
  }

  val all: Seq[SpecTest] = Seq(buResolve, buMispredict, buChannels, buRecoveryOnly, buCompletesOnce, buControlOnce,
    pmArbitrate, pmFanout, pmSingleDrain, c2Integration)
}
