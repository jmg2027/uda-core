package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.core.design.modules.InstructionCache
import udacore.core.design.shared.CoreParams

/** L1 SpecTests for the ADR-019 InstructionCache (InstructionCacheSpecs, ADR-019 D-19.4/D-19.12).
  *
  * The driver plays the FetchUnit (ICacheReq stream), the InstructionTlb (one Translation per
  * request, in order, offered a programmable number of cycles after the request is accepted), the
  * InstBusAdapter (InstMemReq consumer, InstMemResp beats from a backing memory with programmable
  * gaps and denied beats) and the CommitUnit (invalidate tokens).
  */
object InstructionCacheSpecTests {
  val cp = CoreParams()
  val geo = cp.contract.icache
  val LineBytes = geo.blockBytes
  val Sets = geo.sets
  val Ways = geo.ways
  val FW = cp.fetch.fetchWidth

  // Translation status and fetch-fault codes (MemoryBundles / FetchFault).
  val HIT = 0; val MISS = 1; val PF = 2; val AF = 3
  val FNone = 0; val FPage = 1; val FAccess = 2

  case class Tr(reqId: Int, status: Int = HIT, paddr: Long = 0, cacheable: Boolean = true)
  case class Resp(reqId: Int, data: Seq[Long], fault: Int)
  case class MemReq(paddr: Long, size: Int)

  def word(pa: Long): Long = ((pa >> 2) * 2654435761L + 0x9e3779b9L) & 0xffffffffL
  def block(pa: Long): Seq[Long] = (0 until FW).map(i => word((pa & ~15L) + 4L * i))

  class Drv(val dut: InstructionCache) {
    val io = dut.io
    var cyc = 0
    val reqQ   = Queue[(Long, Tr)]()           // (vaddr, translation to answer with)
    val transQ = Queue[(Int, Tr)]()            // (first cycle offered, translation)
    var tDelay: Int => Int = _ => 1            // cycles after acceptance (>= 1)
    var respReady: Int => Boolean = _ => true
    var inv: Option[Int] = None
    var memReqReady = true
    var beatStall: Int => Boolean = _ => false
    var denyLine: Long => Boolean = _ => false // deny beat 1 of a transfer at this aligned paddr
    val beatQ  = Queue[(Long, Boolean)]()      // (data, denied)
    var accepted = 0
    val memReqs = ArrayBuffer[(Int, MemReq)]()
    val resps   = ArrayBuffer[(Int, Resp)]()
    val offered = ArrayBuffer[(Int, Resp)]()
    val reqFires, transFires, invFires, lastBeatFires = ArrayBuffer[Int]()
    var reqFire, transFire, invFire, respFire, lastBeatFire = false
    var beatsLeft = 0

    def peekResp(): Resp = {
      val b = io.iCacheRespOut.bits
      Resp(b.reqId.peek().litValue.toInt, (0 until FW).map(i => b.data(i).peek().litValue.toLong), b.fault.peek().litValue.toInt)
    }
    def cycle(): Unit = {
      val rq = io.iCacheReqIn
      rq.valid.poke(reqQ.nonEmpty.B)
      reqQ.headOption.foreach { case (va, t) => rq.bits.vaddr.poke(va.U); rq.bits.reqId.poke(t.reqId.U) }
      val tq = io.iCacheTranslationIn
      val tv = transQ.nonEmpty && transQ.head._1 <= cyc
      tq.valid.poke(tv.B)
      transQ.headOption.foreach { case (_, t) =>
        tq.bits.reqId.poke(t.reqId.U); tq.bits.status.poke(t.status.U); tq.bits.paddr.poke(t.paddr.U)
        tq.bits.cacheable.poke(t.cacheable.B)
      }
      io.iCacheInvalidateIn.valid.poke(inv.nonEmpty.B); inv.foreach(op => io.iCacheInvalidateIn.bits.op.poke(op.U))
      val rr = respReady(cyc)
      io.iCacheRespOut.ready.poke(rr.B)
      io.instMemReqOut.ready.poke(memReqReady.B)
      val bv = beatQ.nonEmpty && !beatStall(cyc)
      io.instMemRespIn.valid.poke(bv.B)
      beatQ.headOption.foreach { case (dat, den) =>
        io.instMemRespIn.bits.data.poke(dat.U); io.instMemRespIn.bits.denied.poke(den.B)
        io.instMemRespIn.bits.last.poke((beatsLeft == 1).B)
      }

      reqFire   = reqQ.nonEmpty && rq.ready.peek().litToBoolean
      transFire = tv && tq.ready.peek().litToBoolean
      invFire   = inv.nonEmpty && io.iCacheInvalidateIn.ready.peek().litToBoolean
      val respV = io.iCacheRespOut.valid.peek().litToBoolean
      if (respV) offered += ((cyc, peekResp()))
      respFire  = respV && rr
      if (respFire) resps += ((cyc, peekResp()))
      val mv = io.instMemReqOut.valid.peek().litToBoolean
      if (mv && memReqReady) {
        val m = MemReq(io.instMemReqOut.bits.paddr.peek().litValue.toLong, io.instMemReqOut.bits.size.peek().litValue.toInt)
        memReqs += ((cyc, m))
        val n = (1 << m.size) / 4
        (0 until n).foreach(i => beatQ.enqueue((word(m.paddr + 4L * i), denyLine(m.paddr) && i == 1)))
        if (beatsLeft == 0) beatsLeft = n
      }
      val beatFire = bv && io.instMemRespIn.ready.peek().litToBoolean
      lastBeatFire = beatFire && beatsLeft == 1
      if (beatFire) { beatQ.dequeue(); beatsLeft -= 1; if (lastBeatFire) lastBeatFires += cyc }
      if (reqFire) { val (_, t) = reqQ.dequeue(); transQ.enqueue((cyc + tDelay(accepted), t)); accepted += 1; reqFires += cyc }
      if (transFire) { transQ.dequeue(); transFires += cyc }
      if (invFire) invFires += cyc
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    /** Run until `n` responses in total have transferred (bounded). */
    def until(n: Int, limit: Int = 400): Unit = { var k = 0; while (resps.size < n && k < limit) { cycle(); k += 1 } }
    def access(va: Long, pa: Long, id: Int, cacheable: Boolean = true, status: Int = HIT): Unit =
      reqQ.enqueue((va, Tr(id, status, pa, cacheable)))
    def invalidate(op: Int = 0): Unit = { inv = Some(op); var k = 0; cycle(); while (!invFire && k < 50) { cycle(); k += 1 }; inv = None }
    def respsOf: Seq[Resp] = resps.map(_._2).toSeq
    def reqsOf: Seq[MemReq] = memReqs.map(_._2).toSeq
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new InstructionCache(cp)) { dut => val d = new Drv(dut); body(d) }

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  def hitResp(id: Int, pa: Long): Resp = Resp(id, block(pa), FNone)
  def fill(pa: Long): MemReq = MemReq(pa & ~(LineBytes - 1L), 6)
  def unc(pa: Long): MemReq = MemReq(pa & ~15L, 4)

  // VA/PA pairs keep the 4 KiB page offset (VIPT: index bits va[11:6] = pa[11:6]).
  val VA = 0x00401040L; val PA = 0x2a0005040L

  val coldMiss = new SpecTest("ic.coldMiss", Seq("funcICacheViptLookup", "funcICacheMissFill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.access(VA, PA, 1); d.until(1)
      d.access(VA + 4, PA + 4, 2); d.until(2); d.run(3)
      Seq(
        chk(d.reqsOf == Seq(fill(PA)), "a cold miss requests the 64-byte aligned line once (size 6)", s"${d.reqsOf}"),
        chk(d.respsOf == Seq(hitResp(1, PA), hitResp(2, PA)),
          "the fill answers the requested 16-byte block; the second access (mid-block address) hits the same block, no bus request",
          s"${d.respsOf}"))
    }
  }

  val fourBlocks = new SpecTest("ic.fourBlocks", Seq("funcICacheViptLookup")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.access(VA + 0x30, PA + 0x30, 0); d.until(1)
      (0 until 4).foreach(b => d.access(VA + 16L * b + 8, PA + 16L * b + 8, 1 + b)); d.until(5); d.run(2)
      Seq(
        chk(d.reqsOf == Seq(fill(PA)), "one fill covers the line", s"${d.reqsOf}"),
        chk(d.respsOf == hitResp(0, PA + 0x30) +: (0 until 4).map(b => hitResp(1 + b, PA + 16L * b)),
          "each of the four 16-byte blocks (selected by va[5:4], mid-block addresses) returns its own aligned block", s"${d.respsOf}"))
    }
  }

  val vipt = new SpecTest("ic.vipt", Seq("funcICacheViptLookup")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val va2 = 0x00c01040L   // same index, different VA
      val pa2 = 0x1b0009040L  // same index, different physical tag
      d.access(VA, PA, 1); d.until(1)
      d.access(va2, PA, 2); d.until(2)           // synonym: same PA -> hit
      d.access(VA, pa2, 3); d.until(3)           // homonym: same VA, other PA -> miss
      d.access(va2, pa2, 4); d.until(4); d.run(2)
      Seq(
        chk(d.reqsOf == Seq(fill(PA), fill(pa2)),
          "the tag is physical: another VA of the same PA hits, the same VA with another PA misses", s"${d.reqsOf}"),
        chk(d.respsOf == Seq(hitResp(1, PA), hitResp(2, PA), hitResp(3, pa2), hitResp(4, pa2)), "each answer carries its PA's block",
          s"${d.respsOf}"))
    }
  }

  /** Tree pseudo-LRU (test-side model): b0 picks the pair (0: ways 0/1, 1: ways 2/3), b1/b2 the way. */
  class Plru { var b0, b1, b2 = false
    def victim: Int = if (b0) (if (b2) 3 else 2) else (if (b1) 1 else 0)
    def touch(w: Int): Unit = { b0 = w < 2; if (w < 2) b1 = w == 0 else b2 = w == 2 }
  }

  val plru = new SpecTest("ic.plru", Seq("funcICacheMissFill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      def pa(k: Int): Long = 0x100000040L + (k.toLong << 12) // same set 1, distinct tags
      def va(k: Int): Long = 0x00010040L + (k.toLong << 12)
      var id = 0
      def acc(k: Int): Unit = { d.access(va(k), pa(k), id % 16); id += 1; d.until(id) }
      (0 until 4).foreach(acc)       // fill ways 0..3 (invalid ways first)
      acc(0); acc(2)                 // touch 0 then 2
      val n0 = d.memReqs.size
      acc(4)                         // victim by the model
      val m = new Plru; Seq(0, 1, 2, 3, 0, 2).foreach(m.touch)
      val victim = m.victim          // way 1 holds tag 1
      acc(1); val refill1 = d.memReqs.size - n0
      acc(0); acc(2); acc(3); d.run(2)
      Seq(
        chk(n0 == 4 && victim == 1, "four cold misses fill the four ways; the model victim is way 1", s"$n0 $victim"),
        chk(refill1 == 2, "the fifth line evicts the pseudo-LRU way (tag 1), so tag 1 misses again", s"${d.reqsOf}"),
        chk(d.memReqs.size == n0 + 2 + 1, "the other tags stay resident except the one tag 1's refill evicts", s"${d.reqsOf}"),
        chk(d.respsOf.zipWithIndex.forall { case (r, i) => r.fault == FNone && r.reqId == i % 16 }, "all answers in order", s"${d.respsOf}"))
    }
  }

  val faults = new SpecTest("ic.faults", Seq("funcICacheViptLookup")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.access(VA, PA, 5, status = PF); d.access(VA + 0x10, PA + 0x10, 6, status = AF); d.until(2); d.run(4)
      Seq(chk(d.respsOf.map(r => (r.reqId, r.fault)) == Seq((5, FPage), (6, FAccess)) && d.memReqs.isEmpty,
        "translation PageFault / AccessFault answer InstPageFault / InstAccessFault with no bus request", s"${d.respsOf} ${d.reqsOf}"))
    }
  }

  val uncached = new SpecTest("ic.uncached", Seq("funcICacheUncachedFetch", "propICacheReadOnly")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.access(VA + 0x28, PA + 0x28, 1, cacheable = false); d.until(1)
      d.access(VA + 0x28, PA + 0x28, 2, cacheable = false); d.until(2)
      d.access(VA + 0x28, PA + 0x28, 3); d.until(3); d.run(2)
      Seq(
        chk(d.reqsOf == Seq(unc(PA + 0x28), unc(PA + 0x28), fill(PA)),
          "an uncached fetch Gets only the 16-byte block (size 4) each time and installs nothing: a later cacheable access still fills",
          s"${d.reqsOf}"),
        chk(d.respsOf == Seq(hitResp(1, PA + 0x20), hitResp(2, PA + 0x20), hitResp(3, PA + 0x20)), "the answers are the block data",
          s"${d.respsOf}"))
    }
  }

  val denied = new SpecTest("ic.denied", Seq("funcICacheMissFill", "funcICacheUncachedFetch")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.denyLine = pa => pa == (PA & ~63L) || pa == ((PA + 0x10) & ~15L)
      d.access(VA, PA, 7); d.until(1)
      d.access(VA + 0x10, PA + 0x10, 8, cacheable = false); d.until(2)
      d.denyLine = _ => false
      d.access(VA, PA, 9); d.until(3); d.run(2)
      Seq(
        chk(d.respsOf.take(2).map(r => (r.reqId, r.fault)) == Seq((7, FAccess), (8, FAccess)),
          "a denied fill beat and a denied uncached beat both answer InstAccessFault with the original reqId", s"${d.respsOf}"),
        chk(d.reqsOf == Seq(fill(PA), unc(PA + 0x10), fill(PA)) && d.respsOf.lift(2).contains(hitResp(9, PA)),
          "the denied fill installed nothing: the next access fills again", s"${d.reqsOf}"))
    }
  }

  val pairing = new SpecTest("ic.pairing", Seq("funcICacheViptLookup")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.access(VA, PA, 1); d.until(1)
      d.tDelay = k => 1 + (k * 3) % 5
      (0 until 6).foreach(i => d.access(VA + 16L * (i % 4), PA + 16L * (i % 4), 2 + i)); d.until(7); d.run(2)
      Seq(chk(d.respsOf.drop(1) == (0 until 6).map(i => hitResp(2 + i, PA + 16L * (i % 4))),
        "translations arriving 1..5 cycles after their requests pair in order with the right request", s"${d.respsOf}"))
    }
  }

  val steadyHits = new SpecTest("ic.steadyHits", Seq("funcICacheViptLookup")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.access(VA, PA, 0); d.until(1); d.run(2)
      val c0 = d.cyc
      (0 until 12).foreach(i => d.access(VA + 16L * (i % 4), PA + 16L * (i % 4), (1 + i) % 16)); d.until(13); d.run(2)
      val acc = d.reqFires.filter(_ >= c0); val tr = d.transFires.filter(_ >= c0); val rc = d.resps.drop(1).map(_._1)
      def consecutive(s: Seq[Int]) = s.zip(s.drop(1)).forall { case (a, b) => b == a + 1 }
      Seq(
        chk(acc.size == 12 && consecutive(acc.toSeq), "hits: one ICacheReq accepted every cycle", s"$acc"),
        chk(tr.size == 12 && consecutive(tr.toSeq), "one Translation consumed every cycle", s"$tr"),
        chk(consecutive(rc.toSeq) && rc.size == 12, "one hit answer every cycle (no pairing bubble)", s"$rc"))
    }
  }

  val respBackpressure = new SpecTest("ic.respBackpressure", Seq("propICacheResponseOrder")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.respReady = c => c < 3 || c > 60 // fill answer held
      d.access(VA, PA, 1); d.access(VA + 0x10, PA + 0x10, 2); d.run(40)
      val heldFill = d.offered.map(_._2).distinct.toSeq
      d.respReady = c => c == 45 || c > 80 // the fill answer leaves at 45; the hit answer is then held
      d.run(30)
      val heldHit = d.offered.map(_._2).distinct.toSeq
      d.respReady = _ => true; d.until(2); d.run(2)
      Seq(
        chk(heldFill == Seq(hitResp(1, PA)), "a fill answer is offered bit for bit stable while ready is low", s"$heldFill"),
        chk(heldHit == Seq(hitResp(1, PA), hitResp(2, PA + 0x10)), "then the next (hit) answer too; nothing overtakes it", s"$heldHit"),
        chk(d.respsOf == Seq(hitResp(1, PA), hitResp(2, PA + 0x10)), "each leaves once, in request order", s"${d.respsOf}"))
    }
  }

  val invalidate = new SpecTest("ic.invalidate", Seq("funcICacheInvalidate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.access(VA, PA, 1); d.until(1)
      d.access(VA, PA, 2); d.until(2)
      d.invalidate()
      d.access(VA, PA, 3); d.until(3); d.run(2)
      Seq(chk(d.reqsOf == Seq(fill(PA), fill(PA)) && d.respsOf.lift(2).contains(hitResp(3, PA)),
        "after an invalidate-all the populated line misses and refills", s"${d.reqsOf}"))
    }
  }

  val invalidateDuringFill = new SpecTest("ic.invalidateDuringFill", Seq("funcICacheInvalidate", "funcICacheMissFill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.beatStall = c => c % 2 == 0
      d.access(VA, PA, 1)
      while (d.beatQ.size != 10 && d.cyc < 100) d.cycle()     // mid-fill
      d.invalidate()
      d.until(1)
      d.beatStall = _ => false
      d.access(VA, PA, 2); d.until(2); d.run(2)
      Seq(
        chk(d.respsOf.headOption.contains(hitResp(1, PA)), "the in-flight fill completes and still answers its request", s"${d.respsOf}"),
        chk(d.reqsOf == Seq(fill(PA), fill(PA)), "but it installs nothing: the next access refills", s"${d.reqsOf}"))
    }
  }

  val invalidateLastBeat = new SpecTest("ic.invalidateLastBeat", Seq("funcICacheInvalidate", "funcICacheMissFill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.beatStall = c => d.beatsLeft == 1 && d.inv.isEmpty // hold the final beat until the invalidate is up
      d.access(VA, PA, 1)
      while (d.beatsLeft != 1 && d.cyc < 100) d.cycle()
      d.run(2)
      d.inv = Some(0); d.cycle(); val together = d.invFire && d.lastBeatFire; d.inv = None
      d.beatStall = _ => false
      d.until(1)
      d.access(VA, PA, 2); d.until(2); d.run(2)
      Seq(
        chk(together, "the invalidate and the final fill beat transfer in the same cycle", s"${d.invFires} ${d.lastBeatFires}"),
        chk(d.reqsOf == Seq(fill(PA), fill(PA)), "the invalidate wins: the line is not installed", s"${d.reqsOf}"))
    }
  }

  /** The cache sees no RecoveryEvent: a fill whose fetch the frontend discards still installs. */
  val wrongPath = new SpecTest("ic.wrongPath", Seq("funcICacheMissFill")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.access(VA, PA, 3); d.until(1)          // the frontend drops this answer (stale generation)
      d.access(VA + 0x20, PA + 0x20, 4); d.until(2); d.run(2)
      Seq(chk(d.reqsOf == Seq(fill(PA)) && d.respsOf.lift(1).contains(hitResp(4, PA + 0x20)),
        "the wrong-path fill stays installed and later serves a hit", s"${d.reqsOf}"))
    }
  }

  /** Reference model: random pages (cacheable or not), random translation faults, denied lines,
    * random response backpressure, translation delays and invalidates, against a software cache. */
  val random = new SpecTest("ic.random", Seq("funcICacheViptLookup", "funcICacheMissFill", "funcICacheUncachedFetch",
      "funcICacheInvalidate", "propICacheResponseOrder", "propICacheReadOnly")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      var seed = 4242L
      def rnd(n: Int): Int = { seed = seed * 6364136223846793005L + 1442695040888963407L; ((seed >>> 33) % n).toInt }
      val pages = (0 until 12).map(p => (0x00100000L + (p.toLong << 12), 0x300000000L + ((p * 37 % 64).toLong << 12), p % 4 != 3))
      val deniedLines = Set(0x300000000L + ((5 * 37 % 64).toLong << 12) + 0x80L)
      d.denyLine = pa => deniedLines(pa & ~63L)
      d.respReady = c => (c / 7) % 5 != 3
      d.tDelay = k => 1 + (k * 7) % 3
      val expect = ArrayBuffer[Resp](); val expReqs = ArrayBuffer[MemReq]()
      // Software cache (same geometry and victim policy as the contract).
      val tags = Array.fill(Sets, Ways)(-1L); val plru = Array.fill(Sets)(new Plru)
      var n = 0; var quiesceFailed = 0
      while (n < 1500) {
        if (rnd(40) == 0) {
          var k = 0; while ((d.reqQ.nonEmpty || d.resps.size < n) && k < 5000) { d.cycle(); k += 1 } // quiesce: no fill in flight
          if (d.reqQ.nonEmpty || d.resps.size < n) quiesceFailed += 1
          d.invalidate(); for (s <- 0 until Sets; w <- 0 until Ways) tags(s)(w) = -1L
        }
        val (vb, pb, cach) = pages(rnd(pages.size))
        val off = rnd(4096 / 4) * 4L
        val va = vb + off; val pa = pb + off; val id = n % 16
        val st = rnd(50) match { case 0 => PF; case 1 => AF; case _ => HIT }
        d.access(va, pa, id, cach, st)
        val set = ((pa >> 6) & (Sets - 1)).toInt; val tag = pa >> 12
        if (st == PF) expect += Resp(id, Seq.fill(FW)(0L), FPage)
        else if (st == AF) expect += Resp(id, Seq.fill(FW)(0L), FAccess)
        else if (!cach) { expReqs += unc(pa); expect += (if (deniedLines(pa & ~63L)) Resp(id, Seq.fill(FW)(0L), FAccess) else hitResp(id, pa)) }
        else {
          val w = tags(set).indexOf(tag)
          if (w >= 0) { plru(set).touch(w); expect += hitResp(id, pa) }
          else {
            expReqs += fill(pa)
            if (deniedLines(pa & ~63L)) expect += Resp(id, Seq.fill(FW)(0L), FAccess)
            else {
              val inv = tags(set).indexOf(-1L); val v = if (inv >= 0) inv else plru(set).victim
              tags(set)(v) = tag; plru(set).touch(v); expect += hitResp(id, pa)
            }
          }
        }
        n += 1
        var k = 0; while (d.reqQ.size > 2 && k < 500) { d.cycle(); k += 1 }
      }
      d.until(n, 20000); d.run(4)
      val got = d.respsOf
      def norm(r: Resp) = if (r.fault != FNone) r.copy(data = Nil) else r
      val bad = got.map(norm).zip(expect.map(norm)).indexWhere { case (a, b) => a != b }
      Seq(
        chk(quiesceFailed == 0, "every invalidate was issued with no fill in flight (the model's assumption)", s"$quiesceFailed"),
        chk(got.size == n && bad < 0, s"all $n answers equal the software cache and backing memory, in request order",
          s"${got.size} answers; first mismatch at $bad: ${got.lift(bad)} vs ${expect.lift(bad)}"),
        chk(d.reqsOf == expReqs.toSeq, "the bus requests equal the model's fills and uncached fetches (hits cost no traffic)",
          { val i = d.reqsOf.zip(expReqs).indexWhere(p => p._1 != p._2)
            s"${d.memReqs.size} vs ${expReqs.size}; first diff at $i: got ${d.reqsOf.slice(i - 2, i + 3)} exp ${expReqs.slice(i - 2, i + 3)}" }))
    }
  }

  def expectAssert(t: SpecTest, tag: String, label: String)(body: Drv => Unit): Seq[TCheck] = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.run(12); reachedEnd = true; Nil }
      Seq(TCheck(false, label, "simulation ended normally"))
    } catch {
      case e: NotImplementedError => throw e
      case _: Throwable =>
        val fired = chisel3.simulator.CachedSimulator.lastSimulationLog.linesIterator.filter(_.contains("Assertion failed")).toSeq
        Seq(chk(!reachedEnd && fired.exists(_.contains(tag)), label, fired.headOption.getOrElse("no assertion")))
    }
  }

  val negative = new SpecTest("ic.negative", Seq("funcICacheViptLookup", "funcICacheInvalidate")) {
    def run(): Seq[TCheck] =
      expectAssert(this, "ICacheTranslation", "a Translation whose reqId differs from the paired request fires the pairing assertion") { d =>
        d.reqQ.enqueue((VA, Tr(3, HIT, PA))); d.cycle(); d.transQ.clear(); d.transQ.enqueue((0, Tr(4, HIT, PA)))
      } ++ expectAssert(this, "ICacheTranslation", "a Translation with status Miss fires the ITLB-edge assertion") { d =>
        d.access(VA, PA, 1, status = MISS)
      } ++ expectAssert(this, "ICacheInvalidate", "a maintenance token other than ICacheInvalidateAll fires the assertion") { d =>
        d.inv = Some(1); d.run(2)
      }
  }

  val all: Seq[SpecTest] = Seq(coldMiss, fourBlocks, vipt, plru, faults, uncached, denied, pairing, steadyHits,
    respBackpressure, invalidate, invalidateDuringFill, invalidateLastBeat, wrongPath, random, negative)
}
