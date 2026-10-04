package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.shared.{RecoveryCause, RecoveryKind}
import udacore.frontend.design.modules.FetchBuffer
import verif.spectest.FrontendTestKit._

/** L1 SpecTests for the ADR-019 FetchBuffer (ADR-018; FetchBufferSpecs, ADR-019G E-6).
  *
  * The driver plays the FetchUnit (FetchBlock producer), the DecodeUnit (FetchPacket consumer) and
  * the RecoveryEvent broadcast. Expected instructions are expanded from each accepted block by a
  * test-side model (valid slots in slot order, pc = basePc + 4 * slot, blockEnd on the last valid
  * slot, prediction on the last valid slot, fault on every delivered slot).
  */
object FetchBufferSpecTests {
  val W  = fp.fetchWidth
  val DW = fp.decodeWidth
  val N  = fp.tuning.fetchBufferEntries
  val FtqMod = 2 * fp.ftqDepth

  case class Blk(ftqIdx: Int, basePc: Long, valid: Seq[Boolean], exitTaken: Boolean = false, exitTarget: Long = 0,
      fault: Int = 0, insts: Seq[Long] = Nil) {
    def word(s: Int): Long = if (insts.nonEmpty) insts(s) else 0x13L + (ftqIdx.toLong << 20) + (s.toLong << 12) // distinct words
    def nValid: Int = valid.count(identity)
  }
  case class FI(inst: Long, pc: Long, ftqIdx: Int, slot: Int, blockEnd: Boolean, predictedTaken: Boolean,
      predictedTarget: Long, fault: Int)

  /** Test-side model of funcFetchBufferEnqueue. */
  def expand(b: Blk): Seq[FI] = {
    val slots = (0 until W).filter(b.valid)
    slots.map { s =>
      val last = s == slots.last
      val pt   = b.exitTaken && last
      FI(b.word(s), b.basePc + 4L * s, b.ftqIdx, s, last, pt, if (pt) b.exitTarget else 0L, b.fault)
    }
  }
  /** Contiguous slots lo..hi. */
  def slots(lo: Int, hi: Int): Seq[Boolean] = (0 until W).map(s => s >= lo && s <= hi)

  class Drv(val dut: FetchBuffer) {
    val io  = dut.io
    var cyc = 0
    var block: Option[Blk] = None
    var event: Option[Event] = None
    var outReady = true
    val accepted = ArrayBuffer[(Int, Blk)]()
    val emitted  = ArrayBuffer[(Int, FI)]()
    val packets  = ArrayBuffer[(Int, Seq[FI])]() // transferred packets
    val offered  = ArrayBuffer[(Int, Seq[Boolean], Seq[FI])]() // every cycle: lane valids and payloads
    var blockFire, packetFire, packetValid = false

    def peekFI(i: Int): FI = {
      val x = io.fetchPacketOut.bits.insts(i)
      FI(l(x.inst), l(x.pc), l(x.ftqIdx).toInt, l(x.slot).toInt, b(x.blockEnd), b(x.predictedTaken), l(x.predictedTarget),
        l(x.fault).toInt)
    }
    def cycle(): Unit = {
      val bi = io.fetchBlockIn
      bi.valid.poke(block.nonEmpty.B)
      block.foreach { k =>
        bi.bits.ftqIdx.poke(k.ftqIdx.U); bi.bits.basePc.poke(k.basePc.U)
        (0 until W).foreach { s => bi.bits.insts(s).poke(k.word(s).U); bi.bits.slotValid(s).poke(k.valid(s).B) }
        bi.bits.exitTaken.poke(k.exitTaken.B); bi.bits.exitTarget.poke(k.exitTarget.U); bi.bits.fault.poke(k.fault.U)
      }
      pokeEvent(io.recoveryEventIn, event)
      io.fetchPacketOut.ready.poke(outReady.B)
      blockFire   = block.nonEmpty && b(bi.ready)
      packetValid = b(io.fetchPacketOut.valid)
      val lanes = (0 until DW).map(i => b(io.fetchPacketOut.bits.valid(i)))
      val pay   = (0 until DW).map(peekFI)
      offered += ((cyc, if (packetValid) lanes else Seq.fill(DW)(false), pay))
      packetFire = packetValid && outReady
      if (blockFire) accepted += ((cyc, block.get))
      if (packetFire) {
        val got = (0 until DW).filter(lanes).map(pay)
        packets += ((cyc, got)); got.foreach(f => emitted += ((cyc, f)))
      }
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    def push(k: Blk, limit: Int = 20): Boolean = {
      block = Some(k); var n = 0; cycle(); while (!blockFire && n < limit) { cycle(); n += 1 }; block = None; blockFire
    }
    def emittedFI: Seq[FI] = emitted.map(_._2).toSeq
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new FetchBuffer(fp)) { dut => val d = new Drv(dut); d.cycle(); body(d) }

  def flush(): Event = Event(RecoveryKind.BranchMispredict, 0x9000, 0, Outcome(BRANCH, 0, taken = true, 0x9000))

  val basic = new SpecTest("fb.basic", Seq("funcFetchBufferEnqueue", "funcFetchPacketDequeue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val k = Blk(3, 0x1000, slots(0, 3))
      d.outReady = false; d.push(k); d.outReady = true; d.run(4)
      val ps = d.packets.map(_._2.size).toSeq
      Seq(
        chk(ps == Seq(2, 2), "one 4-slot block leaves as two full 2-wide packets", s"${d.packets}"),
        chk(d.emittedFI == expand(k), "pc = basePc + 4 * slot, slot order, blockEnd only on slot 3", s"${d.emittedFI}"))
    }
  }

  val midBlock = new SpecTest("fb.midBlock", Seq("funcFetchBufferEnqueue", "funcFetchPacketDequeue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val k1 = Blk(4, 0x1000, slots(2, 3)); val k2 = Blk(5, 0x1010, slots(0, 3))
      d.outReady = false; d.push(k1); d.push(k2); d.outReady = true; d.run(5)
      val pk = d.packets.map(_._2).toSeq
      Seq(
        chk(pk.headOption.exists(p => p.map(f => (f.pc, f.slot, f.blockEnd)) == Seq((0x1008L, 2, false), (0x100cL, 3, true))),
          "slots 2 and 3 fill two adjacent FIFO positions: first packet 0x1008 (slot 2), 0x100c (slot 3, blockEnd)", s"$pk"),
        chk(d.emittedFI == expand(k1) ++ expand(k2) && pk.map(_.size) == Seq(2, 2, 2),
          "the next block follows directly (no hole left by the absent slots 0 and 1)", s"$pk"))
    }
  }

  val takenExit = new SpecTest("fb.takenExit", Seq("funcFetchBufferEnqueue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val k = Blk(6, 0x2000, slots(1, 2), exitTaken = true, exitTarget = 0x5000)
      d.push(k); d.run(3)
      val e = d.emittedFI
      Seq(chk(e.map(f => (f.pc, f.slot, f.blockEnd, f.predictedTaken, f.predictedTarget)) ==
          Seq((0x2004L, 1, false, false, 0L), (0x2008L, 2, true, true, 0x5000L)),
        "only slots 1 and 2 are delivered; slot 2 is blockEnd and carries predictedTaken with its target", s"$e"))
    }
  }

  val faultT = new SpecTest("fb.fault", Seq("funcFetchBufferEnqueue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val k1 = Blk(7, 0x3000, slots(2, 2), fault = 1)  // page fault at startSlot 2
      val k2 = Blk(8, 0x3010, slots(1, 1), fault = 2)  // access fault at startSlot 1
      d.push(k1); d.push(k2); d.run(4)
      val e = d.emittedFI
      Seq(chk(e.map(f => (f.pc, f.slot, f.blockEnd, f.fault)) == Seq((0x3008L, 2, true, 1), (0x3014L, 1, true, 2)),
        "a fault block delivers its single startSlot instruction at its real slot and PC, blockEnd, fault unchanged", s"$e"))
    }
  }

  val crossBlock = new SpecTest("fb.crossBlock", Seq("funcFetchPacketDequeue", "propFetchBufferProgramOrder")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val a = Blk(9, 0x4000, slots(0, 2)); val c = Blk(10, 0x4010, slots(0, 3))
      d.outReady = false; d.push(a); d.push(c); d.outReady = true; d.run(5)
      val pk = d.packets.map(_._2).toSeq
      Seq(
        chk(pk.lift(1).exists(p => p.size == 2 && p(0).ftqIdx == 9 && p(0).blockEnd && p(1).ftqIdx == 10 && p(1).slot == 0),
          "the second packet holds block N's final instruction (blockEnd) and block N+1's first", s"$pk"),
        chk(d.emittedFI == expand(a) ++ expand(c), "no instruction dropped or duplicated across the boundary", s"${d.emittedFI}"))
    }
  }

  val backpressure = new SpecTest("fb.backpressure", Seq("funcFetchPacketDequeue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val k = Blk(11, 0x5000, slots(0, 3), exitTaken = true, exitTarget = 0x7770)
      d.outReady = false; d.push(k); d.run(1)
      val o0 = d.offered.size; d.run(6)
      val held = d.offered.drop(o0).map(x => (x._2, x._3)).toSeq
      d.outReady = true; d.run(4)
      Seq(
        chk(held.size == 6 && held.distinct.size == 1 && held.head._1 == Seq(true, true),
          "under backpressure the lane valids and every payload bit stay stable", s"${held.distinct}"),
        chk(d.emittedFI == expand(k), "after release each instruction leaves exactly once", s"${d.emittedFI}"))
    }
  }

  val capacity = new SpecTest("fb.capacity", Seq("funcFetchBufferEnqueue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.outReady = false
      val a = Blk(12, 0x6000, slots(0, 3)); val c = Blk(13, 0x6010, slots(0, 0)) // 5 occupied, 3 free
      val e3 = Blk(14, 0x6020, slots(0, 2))                                   // needs 3
      d.push(a); d.push(c)
      val acc3 = d.push(e3)                                                 // 8 occupied
      val f = Blk(15, 0x6030, slots(1, 3))
      val g = Blk(16, 0x6040, slots(0, 1))
      d.outReady = true; d.cycle(); d.outReady = false                       // one packet out: 2 free
      val n0 = d.accepted.size
      val rej = !d.push(f, 5)                                               // 3 valid slots, 2 free: whole block refused
      val stillSix = d.accepted.size == n0
      d.outReady = true; d.cycle(); d.outReady = false                       // 4 free
      val acc = d.push(f, 5)
      d.outReady = true; d.push(g); d.run(8)
      Seq(
        chk(acc3, "5 occupied, 3 free: a 3-slot block is accepted", ""),
        chk(rej && stillSix, "2 free: a 3-slot block is refused as a whole (no partial enqueue)", s"${d.accepted}"),
        chk(acc && d.accepted.count(_._2 == f) == 1, "after a drain it is accepted exactly once", s"${d.accepted}"),
        chk(d.emittedFI == Seq(a, c, e3, f, g).flatMap(expand), "the output is exactly the accepted slots in order", s"${d.emittedFI}"))
    }
  }

  /** Software FIFO model: irregular legal blocks, several wraps, irregular output readiness. */
  def modelRun(d: Drv, blocks: Int, seed0: Long, recoverEvery: Int, faultRate: Int): (Seq[String], Int, Int) = {
    var seed = seed0
    def rnd(n: Int): Int = { seed = seed * 6364136223846793005L + 1442695040888963407L; ((seed >>> 33) % n).toInt }
    var ftq = 0; var pending: Option[Blk] = None; var made = 0; var sinceEv = 0; var events = 0
    val expected = ArrayBuffer[FI](); var cmpFrom = 0
    var errs = List.empty[String]
    def check(): Unit = {
      val got = d.emitted.drop(cmpFrom).map(_._2).toSeq
      if (got != expected.toSeq.take(got.size)) errs ::= s"at cycle ${d.cyc}: got ${got.size} emitted, first mismatch " +
        got.zip(expected).find(p => p._1 != p._2).toString
    }
    while (made < blocks || pending.nonEmpty) {
      if (pending.isEmpty && made < blocks) {
        val isFault = rnd(faultRate) == 0
        val lo = rnd(W); val hi = if (isFault) lo else lo + rnd(W - lo)
        pending = Some(Blk(ftq, 0x100000L + 16L * made, slots(lo, hi), exitTaken = !isFault && rnd(3) == 0,
          exitTarget = 0x200000L + 4L * made, fault = if (isFault) 1 + rnd(2) else 0))
        made += 1
      }
      d.block = pending
      d.outReady = rnd(3) != 0
      val ev = recoverEvery > 0 && sinceEv >= recoverEvery && rnd(4) == 0
      d.event = if (ev) Some(flush()) else None
      val before = d.emitted.size
      d.cycle(); d.event = None
      if (ev) {
        if (d.blockFire || d.emitted.size != before) errs ::= s"transfer in the event cycle ${d.cyc - 1}"
        check(); expected.clear(); cmpFrom = d.emitted.size; sinceEv = 0; events += 1
        pending = None; ftq = (ftq + 7) % FtqMod // the next block after a recovery has some new FTQ identity
      } else {
        if (d.blockFire) { expected ++= expand(pending.get); pending = None; ftq = (ftq + 1) % FtqMod }
        sinceEv += 1
      }
    }
    d.block = None; d.outReady = true; d.run(N + 4); check()
    val tail = d.emitted.drop(cmpFrom).map(_._2).toSeq
    if (tail != expected.toSeq) errs ::= s"final: emitted ${tail.size} expected ${expected.size}"
    (errs.reverse, d.emitted.size, events)
  }

  val wrap = new SpecTest("fb.wrap", Seq("funcFetchBufferEnqueue", "funcFetchPacketDequeue", "propFetchBufferProgramOrder")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val (errs, n, _) = modelRun(d, 60, 7L, 0, 1000000)
      Seq(
        chk(errs.isEmpty, "irregular 1..4-slot blocks through repeated FIFO wraps equal the software queue", errs.take(3).mkString("; ")),
        chk(n >= 5 * N, s"several wraps of the $N-entry FIFO", s"$n emitted"))
    }
  }

  val recovery = new SpecTest("fb.recovery", Seq("funcFetchBufferRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.outReady = false
      d.push(Blk(17, 0x7000, slots(0, 3))); d.push(Blk(18, 0x7010, slots(0, 1))); d.run(2)
      val heldBefore = d.packetValid
      d.outReady = true; d.event = Some(flush()); d.cycle(); d.event = None
      val inEvent = d.packetValid || d.packetFire
      d.run(3); val nothingAfter = d.emitted.isEmpty && !d.packetValid
      val k = Blk(2, 0x9000, slots(1, 3))
      d.push(k); d.run(4)
      Seq(
        chk(heldBefore, "a packet was presented and held before the event", ""),
        chk(!inEvent, "FetchPacketOut.valid is low in the event cycle: the held wrong-path packet never transfers", ""),
        chk(nothingAfter, "occupancy is zero after the event: nothing buffered survives", s"${d.emitted}"),
        chk(d.emittedFI == expand(k), "the first block accepted after the recovery is the new oldest instruction", s"${d.emittedFI}"))
    }
  }

  val recoveryInput = new SpecTest("fb.recoveryInput", Seq("funcFetchBufferRecovery", "funcFetchBufferEnqueue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.block = Some(Blk(19, 0x8000, slots(0, 3))); d.event = Some(flush()); d.cycle(); d.block = None
      val refused = !d.blockFire
      d.cycle(); d.event = None                  // back-to-back events while empty are harmless
      d.run(3)
      val k = Blk(20, 0x8010, slots(0, 1)); d.push(k); d.run(3)
      Seq(
        chk(refused, "a FetchBlock valid in the RecoveryEvent cycle is not accepted", ""),
        chk(d.emittedFI == expand(k), "only the block accepted after the events is delivered", s"${d.emittedFI}"))
    }
  }

  /** Conservative capacity (documented): a FetchBlock is accepted against the free entries at the start
    * of the cycle; space a same-cycle packet transfer frees is usable from the next cycle. Enqueue and
    * dequeue in the same cycle around the pointer wrap keep order and occupancy exact. */
  val simultaneous = new SpecTest("fb.simultaneous", Seq("funcFetchBufferEnqueue", "funcFetchPacketDequeue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.outReady = false
      val bs = Seq(Blk(0, 0xa000, slots(0, 3)), Blk(1, 0xa010, slots(0, 2))) // 7 occupied, head 0, tail 7
      bs.foreach(d.push(_))
      val need2 = Blk(2, 0xa020, slots(2, 3))
      d.outReady = true; d.block = Some(need2); d.cycle()
      val sameCycle = d.blockFire; val deq1 = d.packetFire
      d.cycle(); val nextCycle = d.blockFire; d.block = None      // wraps the tail (7 -> 1)
      val c = Blk(3, 0xa030, slots(0, 3))
      val simul = ArrayBuffer[Boolean]()
      d.block = Some(c); (0 until 6).foreach { _ => d.cycle(); simul += (d.blockFire && d.packetFire); if (d.blockFire) d.block = None }
      d.block = None; d.run(8)
      Seq(
        chk(deq1 && !sameCycle, "with 1 free entry a 2-slot block is not accepted in the cycle a packet frees 2 (conservative)", ""),
        chk(nextCycle, "it is accepted in the next cycle, wrapping the tail", ""),
        chk(simul.contains(true), "an enqueue and a dequeue transfer in the same cycle", s"$simul"),
        chk(d.emittedFI == (bs :+ need2 :+ c).flatMap(expand), "order and occupancy stay exact across the wrap", s"${d.emittedFI}"))
    }
  }

  val random = new SpecTest("fb.random", Seq("funcFetchBufferEnqueue", "funcFetchPacketDequeue", "funcFetchBufferRecovery",
      "propFetchBufferProgramOrder")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val (errs, n, ev) = modelRun(d, 2500, 99L, 40, 12)
      Seq(
        chk(errs.isEmpty, "random legal blocks, backpressure and periodic RecoveryEvents: exact sequence equality per epoch",
          errs.take(3).mkString("; ")),
        chk(n >= 3000 && ev >= 20, "coverage: thousands of instructions and many recoveries", s"$n emitted, $ev events"))
    }
  }

  /** Negative: a design assertion must fire; `tag` must appear in its message. */
  def expectAssert(t: SpecTest, tag: String, label: String)(body: Drv => Unit): Seq[TCheck] = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.run(4); reachedEnd = true; Nil }
      Seq(TCheck(false, label, "simulation ended normally"))
    } catch {
      case e: NotImplementedError => throw e
      case _: Throwable =>
        val fired = chisel3.simulator.CachedSimulator.lastSimulationLog.linesIterator.filter(_.contains("Assertion failed")).toSeq
        Seq(chk(!reachedEnd && fired.exists(_.contains(tag)), label, fired.headOption.getOrElse("no assertion")))
    }
  }

  val malformed = new SpecTest("fb.malformed", Seq("funcFetchBufferEnqueue")) {
    def run(): Seq[TCheck] =
      expectAssert(this, "FetchBlockSlots", "a FetchBlock with a hole in its valid slots ({0, 2}) fires the slot-shape assertion") { d =>
        d.push(Blk(21, 0xb000, Seq(true, false, true, false)))
      } ++ expectAssert(this, "FetchBlockSlots", "a fault block with two valid slots fires the slot-shape assertion") { d =>
        d.push(Blk(22, 0xb010, slots(1, 2), fault = 1))
      }
  }

  val orderAssert = new SpecTest("fb.orderAssert", Seq("propFetchBufferProgramOrder")) {
    def run(): Seq[TCheck] =
      expectAssert(this, "FetchBufferProgramOrder", "a skipped FTQ identity between blocks fires FetchBufferProgramOrder") { d =>
        d.push(Blk(1, 0xc000, slots(0, 1))); d.push(Blk(3, 0xc010, slots(0, 1)))
      }
  }

  /** A held one-lane packet keeps its lane count when more instructions arrive behind it. */
  val backpressureGrow = new SpecTest("fb.backpressureGrow", Seq("funcFetchPacketDequeue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val a = Blk(23, 0xd000, slots(3, 3)); val c = Blk(24, 0xd010, slots(0, 3))
      d.outReady = false; d.push(a); d.run(1)
      val o0 = d.offered.size
      d.push(c); d.run(3)
      val held = d.offered.drop(o0).map(x => (x._2, x._3.head)).toSeq
      d.outReady = true; d.run(5)
      Seq(
        chk(held.nonEmpty && held.forall(_ == held.head) && held.head._1 == Seq(true, false),
          "a presented one-lane packet keeps valid 10 and its payload while a new block is enqueued behind it", s"${held.distinct}"),
        chk(d.packets.map(_._2.size).toSeq == Seq(1, 2, 2) && d.emittedFI == expand(a) ++ expand(c),
          "it transfers as presented; the new block follows in the next packets", s"${d.packets}"))
    }
  }

  val all: Seq[SpecTest] = Seq(basic, midBlock, takenExit, faultT, crossBlock, backpressure, capacity, wrap, recovery,
    recoveryInput, simultaneous, random, malformed, orderAssert, backpressureGrow)
}
