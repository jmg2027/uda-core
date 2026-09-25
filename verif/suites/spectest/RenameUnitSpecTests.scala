package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.modules.RenameUnit
import udacore.backend.design.shared.{BackendParams, FuType, RecoveryKind}

/** L1 SpecTests for the ADR-019 RenameUnit (ADR-018 Spec-TDD; frozen spec 242feaf).
  *
  * Scope: funcRenameMap, funcFreeListAllocate, funcBranchCheckpoint,
  * funcBranchRecoveryRestore, propPhysRegConservation, propCheckpointReleasedOnce.
  * The vertex is driven alone: the test plays DecodeUnit (packets), the ROB/RS/LSQ
  * (allocation readiness), CommitUnit (RenameCommit), BranchUnit (CheckpointRelease),
  * and the RecoveryController (RecoveryEvent). Every expectation is read from the
  * allocation token only - sRAT and free-list state are observed through prs/newPrd/
  * oldPrd/checkpointId of later uops, never through internal probes.
  *
  * Reference state after reset (v0: 48 PRF entries, 4 checkpoints): sRAT = rRAT =
  * identity (xN -> pN, x0 -> p0), free list = p32..p47 in order, all prds ready.
  *
  * The PROPERTY tests have two halves: a positive run (the @LocalSpec assertion must
  * stay silent over a long legal history) and negative runs that feed one illegal
  * input and require the assertion to stop the simulation. A negative run that ends
  * normally means the assertion is missing or inactive - a FAIL, never a pass.
  */
object RenameUnitSpecTests {

  val p = BackendParams()

  /** One uop as the test offers it (the other DecodedUop fields are don't-care for rename). */
  case class U(
      rd: Int = 0,
      rs1: Int = 0,
      rs2: Int = 0,
      cfi: Boolean = false,
      load: Boolean = false,
      store: Boolean = false,
      serialize: Boolean = false,
      insn: Long = 0x13L
  )

  /** One observed allocation token (RobAllocOut payload). */
  case class A(
      wrap: Boolean,
      idx: Int,
      prs1: Int,
      prs2: Int,
      hasDest: Boolean,
      newPrd: Int,
      oldPrd: Int,
      ckpt: Int
  ) {
    def tag: (Boolean, Int) = (wrap, idx)
  }

  /** Test-side driver around the DUT ports. Every method leaves all inputs idle on return. */
  class Drv(val dut: RenameUnit) {
    val io = dut.io

    def idle(): Unit = {
      io.decodedPacketIn.valid.poke(false.B)
      for (l <- 0 until p.decodeWidth) io.decodedPacketIn.bits.lanes(l).valid.poke(false.B)
      io.robAllocOut.ready.poke(true.B)
      io.rsAllocOut.ready.poke(true.B)
      io.lsqAllocOut.ready.poke(true.B)
      io.renameCommitIn.valid.poke(false.B)
      io.checkpointReleaseIn.valid.poke(false.B)
      io.wakeupBroadcastIn.valid.poke(false.B)
      io.robStatusIn.empty.poke(true.B)
      io.recoveryEventIn.valid.poke(false.B)
    }

    private def pokeLane(l: Int, u: U): Unit = {
      val b = io.decodedPacketIn.bits.lanes(l)
      b.valid.poke(true.B)
      b.uop.pc.poke((0x1000 + 4 * l).U)
      b.uop.insn.poke(u.insn.U)
      val fu = if (u.cfi) FuType.Branch else if (u.load || u.store) FuType.Mem else FuType.Alu
      b.uop.fuType.poke(fu)
      b.uop.op.poke(0.U)
      b.uop.rd.poke(u.rd.U)
      b.uop.rs1.poke(u.rs1.U)
      b.uop.rs2.poke(u.rs2.U)
      b.uop.imm.poke(0.U)
      b.uop.isCfi.poke(u.cfi.B)
      b.uop.isLoad.poke(u.load.B)
      b.uop.isStore.poke(u.store.B)
      b.uop.serialize.poke(u.serialize.B)
      b.uop.predictionFault.poke(false.B)
      b.uop.exception.valid.poke(false.B)
    }

    /** Read the RobAllocOut token of this cycle (valid && ready = fired). */
    def sample(): Option[A] = {
      val r = io.robAllocOut
      if (r.valid.peek().litToBoolean && r.ready.peek().litToBoolean) {
        val b = r.bits
        Some(A(
          b.robTag.wrap.peek().litToBoolean,
          b.robTag.idx.peek().litValue.toInt,
          b.prs1.peek().litValue.toInt,
          b.prs2.peek().litValue.toInt,
          b.hasDest.peek().litToBoolean,
          b.newPrd.peek().litValue.toInt,
          b.oldPrd.peek().litValue.toInt,
          b.checkpointId.id.peek().litValue.toInt
        ))
      } else None
    }

    /** Offer one packet (1..decodeWidth lanes) and step until it is consumed or
      * `maxCycles` pass. Returns the allocations in order and whether the packet was
      * consumed. */
    def offer(us: Seq[U], maxCycles: Int = 8): (Seq[A], Boolean) = {
      require(us.nonEmpty && us.size <= p.decodeWidth)
      io.decodedPacketIn.valid.poke(true.B)
      for (l <- 0 until p.decodeWidth)
        if (l < us.size) pokeLane(l, us(l)) else io.decodedPacketIn.bits.lanes(l).valid.poke(false.B)
      var out      = Seq.empty[A]
      var consumed = false
      var n        = 0
      while (!consumed && n < maxCycles) {
        out ++= sample()
        consumed = io.decodedPacketIn.ready.peek().litToBoolean
        dut.clock.step()
        n += 1
      }
      idle()
      (out, consumed)
    }

    /** Rename a sequence one uop per packet; stops at the first uop that is not accepted. */
    def renameAll(us: Seq[U]): Seq[A] = {
      var out = Seq.empty[A]
      var ok  = true
      for (u <- us if ok) {
        val (a, c) = offer(Seq(u))
        out ++= a
        ok = c
      }
      out
    }

    def commit(archRd: Int, newPrd: Int, oldPrd: Int, hasDest: Boolean = true): Unit = {
      io.renameCommitIn.valid.poke(true.B)
      io.renameCommitIn.bits.archRd.poke(archRd.U)
      io.renameCommitIn.bits.newPrd.poke(newPrd.U)
      io.renameCommitIn.bits.oldPrd.poke(oldPrd.U)
      io.renameCommitIn.bits.hasDest.poke(hasDest.B)
      dut.clock.step()
      idle()
    }

    def commitOf(a: A, archRd: Int): Unit = commit(archRd, a.newPrd, a.oldPrd, a.hasDest)

    def release(ckpt: Int, tag: (Boolean, Int)): Unit = {
      io.checkpointReleaseIn.valid.poke(true.B)
      io.checkpointReleaseIn.bits.checkpointId.id.poke(ckpt.U)
      io.checkpointReleaseIn.bits.robTag.wrap.poke(tag._1.B)
      io.checkpointReleaseIn.bits.robTag.idx.poke(tag._2.U)
      dut.clock.step()
      idle()
    }

    private def event(kind: UInt, ckpt: Int, tag: (Boolean, Int)): Unit = {
      val e = io.recoveryEventIn
      e.valid.poke(true.B)
      e.kind.poke(kind)
      e.robTag.wrap.poke(tag._1.B)
      e.robTag.idx.poke(tag._2.U)
      e.checkpointId.id.poke(ckpt.U)
      e.target.poke(0x2000.U)
    }

    /** BranchMispredict at the branch `b`, with a younger uop offered in the event cycle.
      * Returns whether that offered uop was (wrongly) renamed in the event cycle. */
    def mispredict(b: A, offered: Option[U] = None): Boolean = {
      event(RecoveryKind.BranchMispredict, b.ckpt, b.tag)
      offered.foreach { u => io.decodedPacketIn.valid.poke(true.B); pokeLane(0, u) }
      val renamed = sample().nonEmpty
      dut.clock.step()
      idle()
      renamed
    }

    def archRedirect(tag: (Boolean, Int)): Unit = {
      event(RecoveryKind.ArchRedirect, 0, tag)
      dut.clock.step()
      idle()
    }
  }

  /** Run `body` against a fresh RenameUnit; the driver starts idle. */
  private def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] = t.sim(new RenameUnit(p)) { dut =>
    val d = new Drv(dut); d.idle(); dut.clock.step(); body(d)
  }

  /** Negative run: the illegal step must stop the simulation through a design assertion.
    * NotImplementedError (spec shell) propagates, so the test stays PENDING before RTL. */
  private def mustAssert(t: SpecTest, label: String, expect: String)(body: Drv => Unit): TCheck = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.dut.clock.step(3); reachedEnd = true; Nil }
      TCheck(false, label, "simulation ended normally: the design assertion did not fire")
    } catch {
      case e: NotImplementedError => throw e
      case e: Throwable =>
        // Attribute the stop: the simulation log must carry the expected assertion text.
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

  // ---- funcRenameMap ----------------------------------------------------------

  val renameMap = new SpecTest("rename.map", Seq("funcRenameMap")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val a0 = d.renameAll(Seq(U(rd = 0, rs1 = 5, rs2 = 7))).head
      val a1 = d.renameAll(Seq(U(rd = 0, rs1 = 0, rs2 = 31))).head
      val a2 = d.renameAll(Seq(U(rd = 3, rs1 = 3, rs2 = 0))).head
      val a3 = d.renameAll(Seq(U(rd = 0, rs1 = 3, rs2 = 3))).head
      val a4 = d.renameAll(Seq(U(rd = 3, rs1 = 3, rs2 = 4))).head
      // Same-group dependency: lane 1 reads lane 0's destination.
      val (g, gc) = d.offer(Seq(U(rd = 4, rs1 = 3), U(rd = 5, rs1 = 4, rs2 = 3)))
      val a7 = d.renameAll(Seq(U(rd = 0, rs1 = 5, rs2 = 4))).head
      Seq(
        chk(a0.prs1 == 5 && a0.prs2 == 7, "reset sRAT is identity: prs = sRAT[rs]", s"$a0"),
        chk(!a0.hasDest, "rd == x0 means no destination (hasDest = 0)", s"$a0"),
        chk(a1.prs1 == 0 && a1.prs2 == 31, "x0 maps to p0", s"$a1"),
        chk(a2.hasDest && a2.newPrd == 32 && a2.oldPrd == 3,
          "hasDest: newPrd from the free list, oldPrd = sRAT[rd]", s"$a2"),
        chk(a2.prs1 == 3, "a uop reads its sources before its own destination update", s"$a2"),
        chk(a3.prs1 == 32 && a3.prs2 == 32, "sRAT[rd] := newPrd is seen by the next uop", s"$a3"),
        chk(a4.newPrd == 33 && a4.oldPrd == 32 && a4.prs1 == 32,
          "a repeated write to rd returns the previous mapping as oldPrd", s"$a4"),
        chk(gc && g.size == 2, "a two-lane packet is consumed after both lanes rename", s"consumed=$gc $g"),
        chk(g.size == 2 && g(1).prs1 == g(0).newPrd && g(1).prs2 == 33,
          "within a rename group a later lane sees an earlier lane's destination", s"$g"),
        chk(a7.prs1 == g.lift(1).map(_.newPrd).getOrElse(-1) && a7.prs2 == g.lift(0).map(_.newPrd).getOrElse(-1),
          "group destinations persist in the sRAT", s"$a7 after $g")
      )
    }
  }

  // ---- funcFreeListAllocate ---------------------------------------------------

  val freeList = new SpecTest("rename.freelist", Seq("funcFreeListAllocate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // 16 destinations exhaust the free list (48 - 32 architectural mappings).
      val regs  = (0 until 16).map(i => 1 + (i % 4))
      val as    = d.renameAll(regs.map(r => U(rd = r)))
      val (blk, blkC) = d.offer(Seq(U(rd = 9)), maxCycles = 4)
      // A destination-less uop still renames with the free list empty.
      val (nd, ndC) = d.offer(Seq(U(rd = 0, rs1 = 9)))
      // Commit the oldest uop: its oldPrd (p1) is pushed at the tail and becomes allocatable.
      d.commitOf(as.head, regs.head)
      val (after, afterC) = d.offer(Seq(U(rd = 9)))
      // A destination-less commit does not advance the architectural head.
      d.commit(0, 0, 0, hasDest = false)
      d.commitOf(as(1), regs(1))
      val (after2, _) = d.offer(Seq(U(rd = 10)))
      Seq(
        chk(as.map(_.newPrd) == (32 until 48), "allocation pops the speculative head in FIFO order", s"${as.map(_.newPrd)}"),
        chk(!as.exists(_.newPrd == 0) && !after.exists(_.newPrd == 0), "p0 is never allocated", ""),
        chk(!blkC && blk.isEmpty, "an empty free list backpressures a destination uop", s"consumed=$blkC $blk"),
        chk(ndC && nd.size == 1 && !nd.head.hasDest, "a uop without a destination needs no free entry", s"$nd"),
        chk(afterC && after.map(_.newPrd) == Seq(regs.head),
          "commit pushes oldPrd at the tail; it is the next allocation once the list wrapped", s"$after"),
        chk(after2.map(_.newPrd) == Seq(regs(1)),
          "commits free in program order; a hasDest = 0 commit pushes nothing", s"$after2")
      )
    }
  }

  // ---- funcBranchCheckpoint ---------------------------------------------------

  val checkpoint = new SpecTest("rename.checkpoint", Seq("funcBranchCheckpoint")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val cfis = d.renameAll(Seq.fill(p.checkpointCount)(U(cfi = true, rs1 = 1)))
      val (blk, blkC) = d.offer(Seq(U(cfi = true)), maxCycles = 4)
      val (plain, plainC) = d.offer(Seq(U(rd = 2)))
      // Commit never frees checkpoints: retire the four branches (no destinations).
      cfis.foreach(_ => d.commit(0, 0, 0, hasDest = false))
      val (stillBlk, stillC) = d.offer(Seq(U(cfi = true)), maxCycles = 4)
      // CheckpointRelease (completion without recovery) frees exactly that slot.
      d.release(cfis(2).ckpt, cfis(2).tag)
      val (reuse, reuseC) = d.offer(Seq(U(cfi = true)))
      Seq(
        chk(cfis.size == p.checkpointCount && cfis.map(_.ckpt).distinct.size == p.checkpointCount,
          "each control-flow uop allocates a distinct free checkpoint", s"${cfis.map(_.ckpt)}"),
        chk(!blkC && blk.isEmpty, "with no free checkpoint a control-flow uop waits", s"$blk"),
        chk(plainC && plain.size == 1, "a non-CFI uop needs no checkpoint", s"$plain"),
        chk(!stillC && stillBlk.isEmpty, "commit never frees a checkpoint", s"$stillBlk"),
        chk(reuseC && reuse.map(_.ckpt) == Seq(cfis(2).ckpt),
          "CheckpointRelease frees the named checkpoint for reuse", s"$reuse")
      )
    }
  }

  val checkpointSnapshot = new SpecTest("rename.checkpoint.snapshot", Seq("funcBranchCheckpoint", "funcBranchRecoveryRestore")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // A linking CFI (jal x1): the snapshot is taken after its own destination update.
      val link  = d.renameAll(Seq(U(cfi = true, rd = 1))).head
      val young = d.renameAll(Seq(U(rd = 1), U(rd = 1)))
      d.mispredict(link)
      val probe = d.renameAll(Seq(U(rd = 6, rs1 = 1))).head
      Seq(
        chk(link.hasDest && link.newPrd == 32, "the link destination renames like any destination", s"$link"),
        chk(probe.prs1 == link.newPrd, "the snapshot includes the CFI's own link destination", s"probe=$probe link=$link young=$young"),
        chk(probe.newPrd == 33, "the snapshot head is the one after the CFI's own pop", s"$probe")
      )
    }
  }

  // ---- funcBranchRecoveryRestore ------------------------------------------------

  val branchRecovery = new SpecTest("rename.branchRecovery", Seq("funcBranchRecoveryRestore")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val z  = d.renameAll(Seq(U(cfi = true))).head             // older branch, survives
      val a  = d.renameAll(Seq(U(rd = 5))).head                  // x5 -> pA
      val b  = d.renameAll(Seq(U(cfi = true))).head              // recovering branch
      val c  = d.renameAll(Seq(U(rd = 5), U(rd = 6), U(rd = 5))) // wrong path
      val e  = d.renameAll(Seq(U(cfi = true))).head              // younger branch, killed
      val renamedInEvent = d.mispredict(b, offered = Some(U(rd = 7)))
      val r  = d.renameAll(Seq(U(rd = 7, rs1 = 5, rs2 = 6))).head
      // Checkpoints: z survives; b (own) and e (killed) are free -> 3 more CFIs fit, the 4th waits.
      val more = d.renameAll(Seq.fill(3)(U(cfi = true)))
      val (blk, blkC) = d.offer(Seq(U(cfi = true)), maxCycles = 4)
      val nextIdx = (b.idx + 1) % p.robDepth
      Seq(
        chk(!renamedInEvent, "no uop is renamed in the event cycle", ""),
        chk(r.prs1 == a.newPrd && r.prs2 == 6, "sRAT := checkpoint sRAT (wrong-path mappings discarded)", s"$r a=$a c=$c"),
        chk(r.newPrd == c.head.newPrd,
          "speculative head := checkpoint head (wrong-path prds reclaimed in order)", s"$r c=$c"),
        chk(r.idx == nextIdx && r.wrap == (b.wrap ^ (nextIdx == 0)), "the next robTag is e.robTag + 1", s"$r b=$b"),
        chk(more.size == 3 && !more.exists(_.ckpt == z.ckpt),
          "the older branch keeps its checkpoint; the recovering and killed ones are free",
          s"z=${z.ckpt} e=${e.ckpt} more=${more.map(_.ckpt)}"),
        chk(!blkC && blk.isEmpty, "exactly the freed checkpoints became available", s"$blk")
      )
    }
  }

  // ---- propPhysRegConservation ------------------------------------------------

  /** Randomized legal history: renames, in-order commits, releases, branch and
    * architectural recoveries. The test keeps a shadow rRAT and the in-flight window
    * from the observed tokens and checks, for every allocation, that the new prd is
    * in no other partition set; the RTL assertion checks the full partition every cycle. */
  private def conservationRun(d: Drv, seed: Long, steps: Int): Seq[TCheck] = {
    val rnd      = new scala.util.Random(seed)
    val rrat     = Array.tabulate(32)(i => i)
    case class F(a: A, rd: Int, cfi: Boolean, var holds: Boolean)
    val window   = scala.collection.mutable.ArrayBuffer.empty[F]
    var bad      = Seq.empty[String]
    var allocs   = 0
    var branchEv = 0
    var archEv   = 0
    def inUse: Set[Int] = rrat.toSet ++ window.filter(_.a.hasDest).map(_.a.newPrd)
    for (_ <- 0 until steps) {
      val k = rnd.nextInt(100)
      if (k < 50 && window.size < p.robDepth) {
        val rd  = if (rnd.nextInt(4) == 0) 0 else 1 + rnd.nextInt(4) // few registers: repeated writes
        val cfi = rnd.nextInt(4) == 0
        val used = inUse
        val (as, _) = d.offer(Seq(U(rd = rd, rs1 = 1 + rnd.nextInt(4), cfi = cfi)), maxCycles = 1)
        as.foreach { a =>
          allocs += 1
          if (a.hasDest && used.contains(a.newPrd)) bad :+= s"double allocation of p${a.newPrd}"
          if (a.hasDest && a.oldPrd != (window.reverseIterator.find(f => f.rd == rd && f.a.hasDest)
                .map(_.a.newPrd).getOrElse(rrat(rd)))) bad :+= s"oldPrd mismatch $a"
          window += F(a, rd, cfi, cfi)
        }
      } else if (k < 75 && window.nonEmpty) {
        val h = window.head
        if (h.holds) { d.release(h.a.ckpt, h.a.tag); h.holds = false }
        d.commitOf(h.a, h.rd)
        if (h.a.hasDest) rrat(h.rd) = h.a.newPrd
        window.remove(0)
      } else if (k < 85) {
        val held = window.indices.filter(window(_).holds)
        if (held.nonEmpty) {
          val i = held(rnd.nextInt(held.size))
          d.release(window(i).a.ckpt, window(i).a.tag); window(i).holds = false
        }
      } else if (k < 96) {
        val held = window.indices.filter(window(_).holds)
        if (held.nonEmpty) {
          val i = held(rnd.nextInt(held.size))
          d.mispredict(window(i).a)
          window(i).holds = false
          window.remove(i + 1, window.size - i - 1)
          branchEv += 1
        }
      } else if (window.nonEmpty) {
        d.archRedirect(window.head.a.tag)
        window.clear()
        archEv += 1
      }
    }
    // Drain: resolve and commit everything, then the free list must hold exactly the
    // 16 prds outside the rRAT image and p0.
    while (window.nonEmpty) {
      val h = window.head
      if (h.holds) d.release(h.a.ckpt, h.a.tag)
      d.commitOf(h.a, h.rd)
      if (h.a.hasDest) rrat(h.rd) = h.a.newPrd
      window.remove(0)
    }
    val tail = (0 until 16).flatMap(_ => d.offer(Seq(U(rd = 20)), maxCycles = 1)._1)
    val (over, overC) = d.offer(Seq(U(rd = 20)), maxCycles = 3)
    val expectFree = (0 until p.prfEntries).toSet -- rrat.toSet
    Seq(
      chk(bad.isEmpty, s"no prd is allocated while in another partition set (seed $seed)", bad.take(4).mkString("; ")),
      chk(tail.map(_.newPrd).toSet == expectFree && tail.size == 16,
        s"after drain the free list is exactly PRF - {p0} - rRAT image (no leak; seed $seed)",
        s"free=${tail.map(_.newPrd).sorted} expected=${expectFree.toSeq.sorted}"),
      chk(!overC && over.isEmpty, s"no prd appears in the free list twice (seed $seed)", s"$over"),
      chk(allocs > steps / 4 && branchEv > 3 && archEv > 0,
        s"history exercised renames/branch/arch recovery (seed $seed)", s"allocs=$allocs branch=$branchEv arch=$archEv")
    )
  }

  val conservation = new SpecTest("rename.conservation", Seq("propPhysRegConservation")) {
    def run(): Seq[TCheck] =
      withDrv(this)(d => conservationRun(d, 1L, 400)) ++
        withDrv(this)(d => conservationRun(d, 7L, 400)) ++
        Seq(
          mustAssert(this, "a commit whose oldPrd is not the rRAT mapping (leak/double free) stops the run",
            "PhysRegConservation: commit oldPrd is not the rRAT mapping") { d =>
            val a = d.renameAll(Seq(U(rd = 3))).head
            d.commit(3, a.newPrd, 4) // oldPrd should be p3: p3 leaks, p4 is doubled
          },
          mustAssert(this, "a commit whose newPrd is not the oldest allocation stops the run",
            "PhysRegConservation: commit newPrd is not the oldest allocation") { d =>
            d.renameAll(Seq(U(rd = 3), U(rd = 4)))
            d.commit(3, 33, 3) // the oldest allocation is p32
          }
        )
  }

  // ---- propCheckpointReleasedOnce ---------------------------------------------

  val checkpointOnce = new SpecTest("rename.checkpointOnce", Seq("propCheckpointReleasedOnce")) {
    def run(): Seq[TCheck] = {
      val positive = withDrv(this) { d =>
        // Release, mispredict-free, and kill-free each free a checkpoint exactly once:
        // afterwards all four slots are allocatable again, and no fifth.
        val x = d.renameAll(Seq(U(cfi = true))).head
        val y = d.renameAll(Seq(U(cfi = true))).head
        val z = d.renameAll(Seq(U(cfi = true))).head
        d.release(x.ckpt, x.tag)
        d.mispredict(y) // frees y (own) and z (killed)
        val again = d.renameAll(Seq.fill(p.checkpointCount)(U(cfi = true)))
        val (blk, blkC) = d.offer(Seq(U(cfi = true)), maxCycles = 4)
        Seq(
          chk(again.map(_.ckpt).distinct.size == p.checkpointCount,
            "release / own mispredict / kill each free exactly one checkpoint", s"${again.map(_.ckpt)} z=$z"),
          chk(!blkC && blk.isEmpty, "no checkpoint is freed twice (the pool never exceeds its size)", s"$blk")
        )
      }
      positive ++ Seq(
        mustAssert(this, "releasing an already released checkpoint stops the run",
          "CheckpointReleasedOnce: release of a free checkpoint") { d =>
          val a = d.renameAll(Seq(U(cfi = true))).head
          d.release(a.ckpt, a.tag)
          d.release(a.ckpt, a.tag)
        },
        mustAssert(this, "releasing a checkpoint for a uop that does not own it stops the run",
          "CheckpointReleasedOnce: release by a non-owner") { d =>
          val a = d.renameAll(Seq(U(cfi = true), U())).last
          val c = d.renameAll(Seq(U(cfi = true))).head
          d.release(c.ckpt, a.tag)
        },
        mustAssert(this, "a BranchMispredict naming a free checkpoint stops the run",
          "CheckpointReleasedOnce: BranchMispredict names a free checkpoint") { d =>
          val a = d.renameAll(Seq(U(cfi = true))).head
          d.release(a.ckpt, a.tag)
          d.mispredict(a)
        }
      )
    }
  }

  val all: Seq[SpecTest] =
    Seq(renameMap, freeList, checkpoint, checkpointSnapshot, branchRecovery, conservation, checkpointOnce)
}
