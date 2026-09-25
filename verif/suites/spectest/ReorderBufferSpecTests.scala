package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.modules.ReorderBuffer
import udacore.backend.design.shared.{BackendParams, CfiType, FuType, RecoveryKind, RobOrder, RobTag, SysOp}

/** L1 SpecTests for the ADR-019 ReorderBuffer (ADR-018 Spec-TDD; frozen spec 242feaf).
  *
  * Scope: funcRobAllocate, funcRobComplete, funcRobHeadOffer, funcRobRecovery,
  * propRobRetireInOrder, propOlderSurvivesRecovery, propRobCompletionTargetsLive, and the
  * shared order function funcRobOlder. The test plays RenameUnit (allocations at the tail),
  * PublishMux (completions), CommitUnit (RobHeadOut.ready), and the RecoveryController.
  *
  * Uops are named by a program-order sequence number s; the ROB tag of s is
  * {wrap = (s / RobDepth) odd, idx = s mod RobDepth}. After a RecoveryEvent at s the next
  * allocation is s + 1, exactly as RenameUnit's allocation pointer.
  *
  * PROPERTY tests pair a positive randomized history (the @LocalSpec assertions stay
  * silent and the observed retire stream matches the reference) with negative runs that
  * must stop on the named assertion (RenameUnitSpecTests.mustAssert protocol).
  */
object ReorderBufferSpecTests {

  val p = BackendParams()
  val D = p.robDepth

  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)

  /** One allocation as the test offers it. */
  case class E(
      pc: Long = 0x1000,
      insn: Long = 0x13,
      rd: Int = 0,
      newPrd: Int = 0,
      oldPrd: Int = 0,
      fu: UInt = FuType.Alu,
      op: Int = 0,
      cfi: Boolean = false,
      ckpt: Int = 0,
      load: Boolean = false,
      store: Boolean = false,
      serialize: Boolean = false,
      predFault: Boolean = false,
      exc: Option[Int] = None,
      ftqIdx: Int = 0,
      blockEnd: Boolean = false
  )

  /** The observed head (RobHeadOut payload). */
  case class H(
      tag: (Boolean, Int),
      done: Boolean,
      headExecute: Boolean,
      exc: Option[Int],
      pc: Long,
      insn: Long,
      archRd: Int,
      hasDest: Boolean,
      newPrd: Int,
      oldPrd: Int,
      isCfi: Boolean,
      ckpt: Int,
      cfiType: Int,
      cfiTaken: Boolean,
      cfiTarget: Long,
      ftqIdx: Int,
      blockEnd: Boolean,
      isLoad: Boolean,
      isStore: Boolean,
      serialize: Boolean,
      sysOp: Int,
      predFault: Boolean
  )

  class Drv(val dut: ReorderBuffer) {
    val io = dut.io

    def idle(): Unit = {
      io.robAllocIn.valid.poke(false.B)
      io.robCompletionIn.valid.poke(false.B)
      io.robHeadOut.ready.poke(false.B)
      io.recoveryEventIn.valid.poke(false.B)
    }

    private def pokeTag(t: RobTag, s: Int): Unit = {
      t.wrap.poke(tagOf(s)._1.B)
      t.idx.poke(tagOf(s)._2.U)
    }

    private def pokeAlloc(s: Int, e: E): Unit = {
      val a = io.robAllocIn.bits
      io.robAllocIn.valid.poke(true.B)
      pokeTag(a.robTag, s)
      a.uop.pc.poke(e.pc.U)
      a.uop.insn.poke(e.insn.U)
      a.uop.fuType.poke(e.fu)
      a.uop.op.poke(e.op.U)
      a.uop.rd.poke(e.rd.U)
      a.uop.rs1.poke(0.U)
      a.uop.rs2.poke(0.U)
      a.uop.imm.poke(0.U)
      a.uop.isCfi.poke(e.cfi.B)
      a.uop.isLoad.poke(e.load.B)
      a.uop.isStore.poke(e.store.B)
      a.uop.serialize.poke(e.serialize.B)
      a.uop.prediction.predictedTaken.poke(false.B)
      a.uop.prediction.predictedTarget.poke(0.U)
      a.uop.prediction.ftqIdx.poke(e.ftqIdx.U)
      a.uop.prediction.slot.poke(0.U)
      a.uop.prediction.blockEnd.poke(e.blockEnd.B)
      a.uop.predictionFault.poke(e.predFault.B)
      a.uop.exception.valid.poke(e.exc.nonEmpty.B)
      a.uop.exception.cause.poke(e.exc.getOrElse(0).U)
      a.uop.exception.tval.poke(e.insn.U)
      a.prs1.poke(0.U)
      a.prs2.poke(0.U)
      a.prs1Ready.poke(true.B)
      a.prs2Ready.poke(true.B)
      a.hasDest.poke((e.rd != 0).B)
      a.newPrd.poke(e.newPrd.U)
      a.oldPrd.poke(e.oldPrd.U)
      a.checkpointId.id.poke(e.ckpt.U)
    }

    /** Offer allocation s for one cycle; returns whether it transferred. */
    def alloc(s: Int, e: E = E()): Boolean = {
      pokeAlloc(s, e)
      val ok = io.robAllocIn.ready.peek().litToBoolean
      dut.clock.step()
      idle()
      ok
    }

    private def pokeCompletion(s: Int, exc: Option[Int], headExecute: Boolean, cfi: Option[(Int, Boolean, Long)]): Unit = {
      val c = io.robCompletionIn.bits
      io.robCompletionIn.valid.poke(true.B)
      pokeTag(c.robTag, s)
      c.exception.valid.poke(exc.nonEmpty.B)
      c.exception.cause.poke(exc.getOrElse(0).U)
      c.exception.tval.poke(0xbad0.U)
      c.headExecute.poke(headExecute.B)
      val (ty, taken, target) = cfi.getOrElse((0, false, 0L))
      c.cfiOutcome.cfiType.poke(ty.U)
      c.cfiOutcome.slot.poke(2.U)
      c.cfiOutcome.taken.poke(taken.B)
      c.cfiOutcome.target.poke(target.U)
    }

    def complete(s: Int, exc: Option[Int] = None, headExecute: Boolean = false,
        cfi: Option[(Int, Boolean, Long)] = None): Unit = {
      pokeCompletion(s, exc, headExecute, cfi)
      dut.clock.step()
      idle()
    }

    def head(): Option[H] =
      if (!io.robHeadOut.valid.peek().litToBoolean) None
      else {
        val b = io.robHeadOut.bits
        val e = b.entry
        def bool(x: Bool) = x.peek().litToBoolean
        def int(x: UInt)  = x.peek().litValue.toInt
        def lng(x: UInt)  = x.peek().litValue.toLong
        Some(H(
          (bool(b.robTag.wrap), int(b.robTag.idx)),
          bool(e.done), bool(e.headExecute),
          if (bool(e.exception.valid)) Some(int(e.exception.cause)) else None,
          lng(e.pc), lng(e.insn), int(e.archRd), bool(e.hasDest), int(e.newPrd), int(e.oldPrd),
          bool(e.isCfi), int(e.checkpointId.id), int(e.cfiOutcome.cfiType), bool(e.cfiOutcome.taken),
          lng(e.cfiOutcome.target), int(e.ftqIdx), bool(e.blockEnd), bool(e.isLoad), bool(e.isStore),
          bool(e.serialize), int(e.sysOp), bool(e.predictionFault)
        ))
      }

    /** CommitUnit accepts the presented head for one cycle; returns what transferred. */
    def retire(): Option[H] = {
      io.robHeadOut.ready.poke(true.B)
      val h = head()
      dut.clock.step()
      idle()
      h
    }

    def status(): (Boolean, (Boolean, Int)) =
      (io.robStatusOut.empty.peek().litToBoolean,
        (io.robStatusOut.headTag.wrap.peek().litToBoolean, io.robStatusOut.headTag.idx.peek().litValue.toInt))

    def allocReady(): Boolean = io.robAllocIn.ready.peek().litToBoolean

    private def pokeEvent(kind: UInt, s: Int): Unit = {
      val e = io.recoveryEventIn
      e.valid.poke(true.B)
      e.kind.poke(kind)
      pokeTag(e.robTag, s)
      e.checkpointId.id.poke(0.U)
      e.target.poke(0x2000.U)
    }

    /** BranchMispredict at s, optionally with a same-cycle completion of `withCompletion`. */
    def mispredict(s: Int, withCompletion: Option[Int] = None): Unit = {
      pokeEvent(RecoveryKind.BranchMispredict, s)
      withCompletion.foreach(c => pokeCompletion(c, None, false, None))
      dut.clock.step()
      idle()
    }

    def archRedirect(s: Int): Unit = {
      pokeEvent(RecoveryKind.ArchRedirect, s)
      dut.clock.step()
      idle()
    }
  }

  private def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] = t.sim(new ReorderBuffer(p)) { dut =>
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

  // ---- funcRobAllocate ----------------------------------------------------------

  val allocate = new SpecTest("rob.allocate", Seq("funcRobAllocate")) {
    def run(): Seq[TCheck] = {
      val main = withDrv(this) { d =>
        val st0 = d.status()
        val e0 = E(pc = 0x100, insn = 0x00300193, rd = 3, newPrd = 32, oldPrd = 3, ftqIdx = 5)
        val e1 = E(pc = 0x104, insn = 0x0ff0000f, fu = FuType.System, op = 2, serialize = true)
        val e2 = E(pc = 0x108, insn = 0x00112023, fu = FuType.Mem, store = true, ftqIdx = 6)
        val e3 = E(pc = 0x10c, insn = 0x0080006f, rd = 1, newPrd = 33, oldPrd = 1, fu = FuType.Branch,
          cfi = true, ckpt = 2, ftqIdx = 6, blockEnd = true, predFault = false)
        val e4 = E(pc = 0x110, insn = 0xffffffffL, exc = Some(2), ftqIdx = 7, predFault = true)
        val acc = Seq(d.alloc(0, e0))
        val st1 = d.status()
        val h0none = d.head()
        val acc2 = Seq(d.alloc(1, e1), d.alloc(2, e2), d.alloc(3, e3), d.alloc(4, e4))
        d.complete(0)
        val h0 = d.retire()
        val h1 = d.retire() // FENCE: done at allocation
        val h2wait = d.head()
        d.complete(2)
        val h2 = d.retire()
        d.complete(3, cfi = Some((2, true, 0x400L)))
        val h3 = d.retire()
        val h4 = d.head() // decode exception: done at allocation
        // Fill: one live entry (4); RobDepth - 1 more fit, then ready is low.
        val fills = (5 until 5 + D).map(s => d.alloc(s))
        Seq(
          chk(st0._1 && st0._2 == tagOf(0), "reset: empty, head = tail = tag 0", s"$st0"),
          chk(acc.forall(identity) && acc2.forall(identity), "allocations at the tail are accepted", s"$acc $acc2"),
          chk(!st1._1, "the entry is live (RobStatus.empty low) in the cycle after its allocation", s"$st1"),
          chk(h0none.isEmpty, "an allocated uop that needs execution starts not done", s"$h0none"),
          chk(h0.exists(h => h.tag == tagOf(0) && h.pc == 0x100 && h.insn == 0x00300193 && h.archRd == 3 &&
            h.hasDest && h.newPrd == 32 && h.oldPrd == 3 && h.ftqIdx == 5 && !h.blockEnd && h.exc.isEmpty &&
            !h.isCfi && !h.isLoad && !h.isStore && !h.serialize && h.sysOp == 0 && !h.predFault),
            "pc, insn, archRd, hasDest, newPrd, oldPrd, ftqIdx, blockEnd are written from the allocation", s"$h0"),
          chk(h1.exists(h => h.done && h.serialize && h.sysOp == SysOp.Fence.litValue.toInt),
            "a FENCE (no execution) is done at allocation with serialize and sysOp", s"$h1"),
          chk(h2wait.isEmpty && h2.exists(h => h.isStore && !h.isLoad),
            "isStore recorded; a store waits for its completion", s"$h2wait $h2"),
          chk(h3.exists(h => h.isCfi && h.ckpt == 2 && h.blockEnd && h.archRd == 1 && h.newPrd == 33),
            "isCfi, checkpointId, blockEnd recorded", s"$h3"),
          chk(h4.exists(h => h.done && h.exc.contains(2) && h.predFault && h.ftqIdx == 7),
            "a decode-time exception is done at allocation and keeps its cause; predictionFault recorded", s"$h4"),
          chk(fills.take(D - 1).forall(identity) && !fills.last,
            "ready is low exactly when all RobDepth entries are live", s"${fills.map(if (_) 1 else 0).mkString}")
        )
      }
      main :+ mustAssert(this, "an allocation whose robTag is not the tail stops the run",
        "RobAllocate: allocation robTag is not the tail") { d =>
        d.alloc(0); d.alloc(2)
      }
    }
  }

  // ---- funcRobComplete -------------------------------------------------------------

  val complete = new SpecTest("rob.complete", Seq("funcRobComplete")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      (0 to 2).foreach(s => d.alloc(s))
      d.complete(2)
      d.complete(0)
      val h0 = d.retire()
      val h1wait = d.head()
      d.complete(1, exc = Some(5))
      val h1 = d.head()
      d.retire()               // trap hand-off
      d.archRedirect(1)        // window empty, next tag is 2
      d.alloc(2, E(fu = FuType.Mem, load = true))
      d.complete(2, headExecute = true)
      val hx = d.head()
      d.complete(2)
      val hxDone = d.head()
      d.retire()
      d.alloc(3, E(fu = FuType.Branch, cfi = true, ckpt = 1))
      d.complete(3, cfi = Some((CfiType.Jal.litValue.toInt, true, 0x480L)))
      val hc = d.retire()
      Seq(
        chk(h0.exists(h => h.tag == tagOf(0) && h.done), "completions are accepted out of order", s"$h0"),
        chk(h1wait.isEmpty, "an entry is not done until its own completion (2 done does not help 1)", s"$h1wait"),
        chk(h1.exists(h => h.tag == tagOf(1) && h.done && h.exc.contains(5)),
          "a completion records its exception", s"$h1"),
        chk(hx.exists(h => h.headExecute && !h.done && h.isLoad),
          "a headExecute completion marks headExecute and does not set done", s"$hx"),
        chk(hxDone.exists(h => h.done && !h.headExecute),
          "the later completion (headExecute = 0) sets done and clears headExecute", s"$hxDone"),
        chk(hc.exists(h => h.cfiType == CfiType.Jal.litValue.toInt && h.cfiTaken && h.cfiTarget == 0x480L),
          "a control-flow completion records its cfiOutcome", s"$hc")
      )
    }
  }

  // ---- funcRobHeadOffer --------------------------------------------------------------

  val headOffer = new SpecTest("rob.headOffer", Seq("funcRobHeadOffer")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.alloc(0); d.alloc(1)
      d.complete(0); d.complete(1)
      val held = (0 until 3).map { _ => val h = d.head(); d.dut.clock.step(); h }
      val r0 = d.retire()
      val h1 = d.head()
      val r1 = d.retire()
      d.alloc(2); d.alloc(3)
      d.complete(2, exc = Some(13)); d.complete(3)
      val trap = d.retire()
      val lockedHeads = (0 until 3).map(_ => d.retire())
      val stLocked = d.status()
      d.archRedirect(2)
      val stAfter = d.status()
      val acc4 = d.alloc(3) // the next tag after the redirect is 2 + 1
      val hAfter = d.head()
      Seq(
        chk(held.forall(_.exists(_.tag == tagOf(0))), "valid presents the head; without ready it is not dequeued", s"$held"),
        chk(r0.exists(_.tag == tagOf(0)) && h1.exists(_.tag == tagOf(1)) && r1.exists(_.tag == tagOf(1)),
          "the transfer of a done, exception-free head is its retirement: the head advances by one", s"$r0 $h1 $r1"),
        chk(trap.exists(h => h.tag == tagOf(2) && h.exc.contains(13)), "a trapping head is presented and transferred", s"$trap"),
        chk(lockedHeads.forall(_.isEmpty), "after the trap hand-off the ROB offers nothing (headLocked)", s"$lockedHeads"),
        chk(!stLocked._1 && stLocked._2 == tagOf(2), "the trap hand-off does not advance the head", s"$stLocked"),
        chk(stAfter._1 && stAfter._2 == tagOf(3), "the ArchRedirect naming that robTag empties the window (head = e.robTag + 1)", s"$stAfter"),
        chk(acc4 && hAfter.isEmpty, "allocation resumes at e.robTag + 1 with the lock released", s"acc=$acc4 head=$hAfter")
      )
    }
  }

  // ---- funcRobRecovery ------------------------------------------------------------------

  val recovery = new SpecTest("rob.recovery", Seq("funcRobRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.alloc(0, E(fu = FuType.Div, rd = 5))        // older long-latency uop
      d.alloc(1)
      d.alloc(2, E(fu = FuType.Branch, cfi = true)) // recovering branch, blockEnd = 0
      d.alloc(3)
      d.alloc(4, E(fu = FuType.Branch, cfi = true))
      d.complete(1); d.complete(3)
      d.mispredict(2, withCompletion = Some(4)) // same-cycle completion of a killed uop is ignored
      val accTail = d.alloc(3)                   // tail = e.robTag + 1
      val accNext = d.alloc(4)
      d.complete(0)
      val r0 = d.retire(); val r1 = d.retire()
      val wait2 = d.head()
      d.complete(2)
      val r2 = d.retire()
      d.complete(3)
      d.retire()
      val stale4 = d.head() // re-allocated 4 must not be done from the killed completion
      d.complete(4)
      d.retire()
      val st = d.status()
      // ArchRedirect: every entry invalid, head = tail = e.robTag + 1.
      d.alloc(5); d.alloc(6); d.complete(6)
      d.archRedirect(5)
      val stA = d.status()
      val hA = d.head()
      val accA = d.alloc(6)
      Seq(
        chk(accTail && accNext, "BranchMispredict sets the tail to e.robTag + 1", s"$accTail $accNext"),
        chk(r0.exists(_.tag == tagOf(0)) && r1.exists(_.tag == tagOf(1)),
          "older entries survive (the older DIV completes and retires)", s"$r0 $r1"),
        chk(wait2.isEmpty && r2.exists(h => h.tag == tagOf(2) && h.blockEnd),
          "the recovering branch survives and gets blockEnd", s"$wait2 $r2"),
        chk(stale4.isEmpty, "a killed uop's same-cycle completion does not reach the re-allocated tag", s"$stale4"),
        chk(st._1 && st._2 == tagOf(5), "younger entries were invalidated (the window drains at e.robTag + 1 + 2)", s"$st"),
        chk(stA._1 && stA._2 == tagOf(6) && hA.isEmpty, "ArchRedirect invalidates every entry: head = tail = e.robTag + 1", s"$stA $hA"),
        chk(accA, "allocation continues at e.robTag + 1 after ArchRedirect", s"$accA")
      )
    }
  }

  // ---- funcRobOlder ---------------------------------------------------------------------

  private class OrderWrap extends Module {
    val io = IO(new Bundle {
      val a     = Input(new RobTag(p))
      val b     = Input(new RobTag(p))
      val older = Output(Bool())
    })
    io.older := RobOrder.robOlder(io.a, io.b)
  }

  val order = new SpecTest("rob.order", Seq("funcRobOlder")) {
    def run(): Seq[TCheck] = sim(new OrderWrap) { dut =>
      var bad = Seq.empty[String]
      var n   = 0
      // Every pair of live tags: both within one window of at most RobDepth allocations.
      for (a <- 0 until 2 * D; off <- -(D - 1) until D) {
        val b = a + off
        if (b >= 0) {
          dut.io.a.wrap.poke(tagOf(a)._1.B); dut.io.a.idx.poke(tagOf(a)._2.U)
          dut.io.b.wrap.poke(tagOf(b)._1.B); dut.io.b.idx.poke(tagOf(b)._2.U)
          val got = dut.io.older.peek().litToBoolean
          if (got != (a < b)) bad :+= s"robOlder(s$a, s$b) = $got"
          n += 1
        }
      }
      Seq(
        chk(bad.isEmpty, s"robOlder(a, b) iff a was allocated before b, for all $n live pairs", bad.take(4).mkString("; ")),
        chk(n > 0, "the pair space is non-empty", "")
      )
    }
  }

  // ---- Randomized reference history ------------------------------------------------------

  /** A legal history of allocations, out-of-order completions (with two-phase headExecute
    * memory uops and rare exceptions), commit-side transfers, trap hand-offs followed by
    * their ArchRedirect, branch mispredicts at unresolved CFIs, and interrupt redirects.
    * Checks every cycle that RobStatus, the presented head, and the retire stream match the
    * reference window. */
  private def randomRun(d: Drv, seed: Long, steps: Int): Seq[TCheck] = {
    case class W(s: Int, cfi: Boolean, mem: Boolean, var done: Boolean, var hx: Boolean, var exc: Boolean)
    val rnd     = new scala.util.Random(seed)
    val win     = scala.collection.mutable.ArrayBuffer.empty[W]
    var tail    = 0
    var locked  = Option.empty[Int]
    var retired = Seq.empty[Int]
    var bad     = Seq.empty[String]
    var traps, mispredicts, interrupts, hxs = 0
    def expectHead: Option[W] = win.headOption.filter(w => locked.isEmpty && (w.done || w.hx))
    for (step <- 0 until steps) {
      val (empty, headTag) = d.status()
      if (empty != win.isEmpty) bad :+= s"step $step: empty=$empty window=${win.map(_.s)}"
      win.headOption.foreach(w => if (headTag != tagOf(w.s)) bad :+= s"step $step: headTag $headTag != s${w.s}")
      val h = d.head()
      if (h.map(_.tag) != expectHead.map(w => tagOf(w.s))) bad :+= s"step $step: head $h expected ${expectHead.map(_.s)}"
      val k = rnd.nextInt(100)
      if (locked.nonEmpty) {
        d.archRedirect(locked.get); tail = locked.get + 1; win.clear(); locked = None
      } else if (k < 35 && win.size < D) {
        val kind = rnd.nextInt(20)
        val cfi  = kind < 4
        val mem  = kind >= 4 && kind < 8
        val pre  = kind == 8 // decode exception: done at allocation
        val sys  = kind == 9 // FENCE: done at allocation
        val e = E(fu = if (cfi) FuType.Branch else if (mem) FuType.Mem else if (sys) FuType.System else FuType.Alu,
          op = if (sys) 2 else 0, cfi = cfi, load = mem, exc = if (pre) Some(2) else None, serialize = sys)
        if (!d.alloc(tail, e)) bad :+= s"step $step: allocation s$tail refused with ${win.size} live"
        win += W(tail, cfi, mem, pre || sys, false, pre)
        tail += 1
      } else if (k < 70) {
        val open = win.filter(!_.done)
        if (open.nonEmpty) {
          val w = open(rnd.nextInt(open.size))
          if (w.mem && !w.hx && rnd.nextBoolean()) { d.complete(w.s, headExecute = true); w.hx = true; hxs += 1 }
          else {
            val exc = rnd.nextInt(25) == 0
            d.complete(w.s, exc = if (exc) Some(4) else None)
            w.done = true; w.hx = false; w.exc = exc
          }
        }
      } else if (k < 88) {
        expectHead.filter(_.done) match {
          case Some(w) =>
            val got = d.retire()
            if (!got.exists(_.tag == tagOf(w.s))) bad :+= s"step $step: transferred $got expected s${w.s}"
            if (w.exc) { locked = Some(w.s); traps += 1 }
            else { retired :+= w.s; win.remove(0) }
          case None => d.dut.clock.step()
        }
      } else if (k < 99) {
        val br = win.filter(w => w.cfi && !w.done)
        if (br.nonEmpty) {
          val b = br(rnd.nextInt(br.size))
          d.mispredict(b.s)
          win.remove(win.indexWhere(_.s == b.s) + 1, win.size - win.indexWhere(_.s == b.s) - 1)
          tail = b.s + 1
          mispredicts += 1
        }
      } else if (win.nonEmpty) {
        d.archRedirect(win.head.s); tail = win.head.s + 1; win.clear(); interrupts += 1
      }
    }
    val inOrder = retired.zip(retired.drop(1)).forall { case (a, b) => a < b } && retired.distinct.size == retired.size
    Seq(
      chk(bad.isEmpty, s"RobStatus, the presented head, and every transfer match the reference (seed $seed)", bad.take(4).mkString("; ")),
      chk(inOrder, s"retired entries leave once each, in robTag order (seed $seed)", retired.take(20).mkString(",")),
      chk(retired.size > steps / 10 && traps > 0 && mispredicts > 3 && interrupts > 0 && hxs > 0,
        s"history exercised retire/trap/mispredict/interrupt/headExecute (seed $seed)",
        s"retired=${retired.size} traps=$traps mispredicts=$mispredicts interrupts=$interrupts hx=$hxs")
    )
  }

  // ---- propRobRetireInOrder --------------------------------------------------------------

  val retireInOrder = new SpecTest("rob.retireInOrder", Seq("propRobRetireInOrder")) {
    def run(): Seq[TCheck] =
      withDrv(this)(d => randomRun(d, 3L, 600)) ++
        withDrv(this)(d => randomRun(d, 11L, 600)) :+
        mustAssert(this, "transferring a headExecute head that is not done stops the run",
          "RobRetireInOrder: transfer of a head that is not done") { d =>
          d.alloc(0, E(fu = FuType.Mem, load = true))
          d.complete(0, headExecute = true)
          d.retire()
        }
  }

  // ---- propOlderSurvivesRecovery ---------------------------------------------------------

  val olderSurvives = new SpecTest("rob.olderSurvives", Seq("propOlderSurvivesRecovery")) {
    def run(): Seq[TCheck] = {
      val directed = withDrv(this) { d =>
        // ADR-019 directed case: an older DIV, a younger done ALU uop with an exception
        // recorded, then a mispredicted branch younger than both.
        d.alloc(0, E(fu = FuType.Div, rd = 7))
        d.alloc(1)
        d.complete(1, exc = Some(6))
        d.alloc(2, E(fu = FuType.Branch, cfi = true))
        d.complete(2, cfi = Some((1, true, 0x800L)))
        d.alloc(3); d.alloc(4)
        d.mispredict(2)
        val wait0 = d.head()
        (0 until 5).foreach(_ => d.dut.clock.step())
        d.complete(0)
        val r0 = d.retire()
        val h1 = d.head()
        Seq(
          chk(wait0.isEmpty && r0.exists(h => h.tag == tagOf(0) && h.archRd == 7 && h.exc.isEmpty),
            "an older not-done DIV survives, completes later, and retires", s"$wait0 $r0"),
          chk(h1.exists(h => h.tag == tagOf(1) && h.done && h.exc.contains(6)),
            "an older done entry keeps its done and exception state", s"$h1")
        )
      }
      val branchState = withDrv(this) { d =>
        d.alloc(0, E(fu = FuType.Branch, cfi = true))
        d.complete(0, cfi = Some((1, true, 0x800L)))
        d.alloc(1)
        d.mispredict(0)
        val h = d.head()
        Seq(chk(h.exists(h => h.tag == tagOf(0) && h.done && h.cfiTaken && h.cfiTarget == 0x800L),
          "the recovering branch keeps its done and outcome state", s"$h"))
      }
      directed ++ branchState ++ withDrv(this)(d => randomRun(d, 5L, 400)) :+
        mustAssert(this, "a BranchMispredict naming a robTag that is not live stops the run",
          "OlderSurvivesRecovery: BranchMispredict names a robTag that is not live") { d =>
          d.alloc(0); d.alloc(1)
          d.mispredict(5)
        }
    }
  }

  // ---- propRobCompletionTargetsLive ----------------------------------------------------------

  val completionLive = new SpecTest("rob.completionLive", Seq("propRobCompletionTargetsLive")) {
    def run(): Seq[TCheck] =
      withDrv(this)(d => randomRun(d, 17L, 400)) ++ Seq(
        mustAssert(this, "a second completion of a done entry stops the run",
          "RobCompletionTargetsLive: completion names an entry that is not live and not done") { d =>
          d.alloc(0); d.alloc(1)
          d.complete(1); d.complete(1)
        },
        mustAssert(this, "a completion of a uop killed by an earlier recovery stops the run",
          "RobCompletionTargetsLive: completion names an entry that is not live and not done") { d =>
          d.alloc(0, E(fu = FuType.Branch, cfi = true)); d.alloc(1); d.alloc(2)
          d.mispredict(0)
          d.complete(2)
        },
        mustAssert(this, "a completion of a never-allocated tag stops the run",
          "RobCompletionTargetsLive: completion names an entry that is not live and not done") { d =>
          d.alloc(0)
          d.complete(9)
        }
      )
  }

  val all: Seq[SpecTest] =
    Seq(allocate, complete, headOffer, recovery, order, retireInOrder, olderSurvives, completionLive)
}
