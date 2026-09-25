package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.modules.LoadStoreQueue
import udacore.backend.design.shared.{BackendParams, RecoveryKind}
import udacore.core.design.shared.{AccessType, LoadStatus, TranslationStatus}

/** L1 SpecTests for the ADR-019 LoadStoreQueue (ADR-018; spec 242feaf + ADR-019A..E).
  *
  * The driver plays the RenameUnit (LsqAllocIn), the AGU (MemAddressIn), the DataTlb (store
  * translations, load translation status through the D-cache answer, walk refills), the VIPT
  * DataCache (load answers after a latency, optionally out of order), the StoreBuffer (it holds
  * committed stores, answers a forwarding query combinationally in the query cycle, and drains
  * them into memory later), the uncached bus port, the CommitUnit (StoreCommit, HeadMemGrant,
  * RobStatus), and the RecoveryController. Memory uops are named by program-order sequence
  * numbers s (robTag = {s / robDepth odd, s mod robDepth}). Words are lane-aligned.
  */
object LoadStoreQueueSpecTests {

  val p = BackendParams()
  val D = p.robDepth
  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)

  // Causes (RISC-V).
  val LoadMisaligned = 4; val LoadAccess = 5; val StoreMisaligned = 6; val StoreAccess = 7
  val LoadPage = 13; val StorePage = 15

  def maskOf(size: Int, addr: Long): Int = (size match { case 0 => 1; case 1 => 3; case _ => 0xf }) << (addr & 3).toInt
  def misaligned(size: Int, addr: Long): Boolean = (size == 1 && (addr & 1) != 0) || (size == 2 && (addr & 3) != 0)
  def laneData(data: Long, addr: Long): Long = (data << (8 * (addr & 3))) & 0xffffffffL
  def merge(word: Long, data: Long, mask: Int): Long =
    (0 until 4).foldLeft(word) { (w, b) => if ((mask >> b & 1) == 1) (w & ~(0xffL << 8 * b)) | (data & (0xffL << 8 * b)) else w } & 0xffffffffL
  def extract(word: Long, addr: Long, size: Int, signed: Boolean): Long = {
    val bits = 8 << size
    val raw  = (word >>> (8 * (addr & 3))) & ((1L << bits) - 1)
    val v    = if (signed && ((raw >> (bits - 1)) & 1) == 1) raw | (~((1L << bits) - 1)) else raw
    v & 0xffffffffL
  }

  case class Alloc(s: Int, isLoad: Boolean, size: Int = 2, signed: Boolean = false, prd: Int = 40)
  case class Agu(s: Int, vaddr: Long, data: Long = 0, size: Int = 2)
  case class Res(cyc: Int, s: Int, prd: Int, wen: Boolean, data: Long, hx: Boolean, exc: Option[Int], tval: Long)
  /** A virtual page: ppn, cacheable, fault ("pf" / "af"), present in the DTLB. */
  case class Page(ppn: Long, cacheable: Boolean = true, fault: Option[String] = None, var present: Boolean = true,
      var walking: Boolean = false)
  case class DcReq(due: Int, lqIdx: Int, gen: Int, vaddr: Long, size: Int, status: UInt, paddr: Long)
  case class TlbReq(due: Int, reqId: Long, status: UInt, paddr: Long, cacheable: Boolean)

  class Drv(val dut: LoadStoreQueue, seed: Int = 1) {
    val io  = dut.io
    val rnd = new scala.util.Random(seed)
    var cyc = 0
    // Environment knobs.
    var dtlbReady, dcReady, memResReady, sbReady, ucLdReady, ucStReady = true
    var dcLatency = 2; var tlbLatency = 1; var walkLatency = 4; var sbDrainDelay = 2; var ucLatency = 2
    var dcOutOfOrder = false
    var replayNext = 0          // the next n Data answers become Replay
    var sbPartial = false       // the StoreBuffer reports partial for any overlap
    var ucLoadFault, ucStoreFault = false
    var sbHold = false          // the StoreBuffer does not drain
    // Page table and memory (word-addressed by paddr >> 2).
    val pages = mutable.Map[Long, Page](0x10L -> Page(0x10))
    val mem   = mutable.Map[Long, Long]().withDefault(w => ((w * 0x9e3779b1L) ^ 0x5a5a5a5aL) & 0xffffffffL)
    val sb    = mutable.Queue[(Long, Long, Int)]() // (paddr, lane data, mask)
    private var sbCountdown = -1
    val dcq  = ArrayBuffer[DcReq]()
    val tlbq = ArrayBuffer[TlbReq]()
    val refq = ArrayBuffer[(Int, Long)]()
    val ucLdq = ArrayBuffer[(Int, Int, Int, Long, Boolean)]()
    val ucStq = ArrayBuffer[(Int, Int, Boolean)]()
    // Per-cycle inputs.
    var alloc: Option[Alloc] = None
    var agu: Option[Agu] = None
    var commit: Option[Int] = None
    var grant: Option[Int] = None
    var event: Option[(UInt, Int)] = None
    var robHead: Option[Int] = None // None = ROB empty
    var robNext = 0                 // headTag while empty
    // Records.
    val results   = ArrayBuffer[Res]()
    val dcReqs    = ArrayBuffer[(Int, Int, Long)]()   // (cyc, lqIdx, vaddr)
    val tlbStores = ArrayBuffer[(Int, Long)]()        // (cyc, vaddr)
    val sbPushes  = ArrayBuffer[(Int, Long, Long, Int)]()
    val ucLoads   = ArrayBuffer[(Int, Long)]()
    val ucStores  = ArrayBuffer[(Int, Long, Long, Int)]()
    val fwdQueries = ArrayBuffer[(Int, Long, Int)]()
    var allocFire, aguFire, commitFire, grantFire = false
    private var near = 0

    def paddrOf(vaddr: Long): Option[(Page, Long)] = pages.get(vaddr >>> 12).map(pg => (pg, (pg.ppn << 12) | (vaddr & 0xfff)))
    private def seqOf(t: (Boolean, Int)): Int = (near - D until near + D).find(s => s >= 0 && tagOf(s) == t).getOrElse(-1)
    private def pokeTag(t: udacore.backend.design.shared.RobTag, s: Int): Unit = {
      t.wrap.poke(tagOf(s)._1.B); t.idx.poke(tagOf(s)._2.U)
    }
    private def b(x: Bool) = x.peek().litToBoolean
    private def l(x: UInt) = x.peek().litValue.toLong

    /** The StoreBuffer forwarding answer from its current contents (youngest store per byte). */
    private def sbAnswer(paddr: Long, mask: Int): (Long, Int, Boolean) = {
      var data = 0L; var hit = 0
      for ((a, d, m) <- sb if (a >>> 2) == (paddr >>> 2)) {
        val ov = m & mask
        data = merge(data, d, ov); hit |= ov
      }
      (data, hit, sbPartial && hit != 0)
    }

    def cycle(): Unit = {
      alloc.foreach(a => near = math.max(near, a.s))
      val a = io.lsqAllocIn
      a.valid.poke(alloc.nonEmpty.B)
      alloc.foreach { x => pokeTag(a.bits.robTag, x.s); a.bits.isLoad.poke(x.isLoad.B); a.bits.isStore.poke((!x.isLoad).B)
        a.bits.size.poke(x.size.U); a.bits.signed.poke(x.signed.B); a.bits.prd.poke(x.prd.U) }
      val m = io.memAddressIn
      m.valid.poke(agu.nonEmpty.B)
      agu.foreach { x => pokeTag(m.bits.robTag, x.s); m.bits.vaddr.poke(x.vaddr.U); m.bits.storeData.poke(x.data.U)
        m.bits.misaligned.poke(misaligned(x.size, x.vaddr).B) }
      io.dtlbReqOut.ready.poke(dtlbReady.B); io.dCacheLoadReqOut.ready.poke(dcReady.B)
      // Due answers.
      val tlbDue = tlbq.find(_.due <= cyc)
      val t = io.dtlbStoreRespIn
      t.valid.poke(tlbDue.nonEmpty.B)
      tlbDue.foreach { x => t.bits.reqId.poke(x.reqId.U); t.bits.status.poke(x.status); t.bits.paddr.poke(x.paddr.U)
        t.bits.cacheable.poke(x.cacheable.B) }
      val refDue = refq.find(_._1 <= cyc)
      io.dtlbRefillIn.valid.poke(refDue.nonEmpty.B)
      io.dtlbRefillIn.bits.status.poke(0.U)
      refDue.foreach { r => io.dtlbRefillIn.bits.vpn.poke(r._2.U) }
      val dues = dcq.filter(_.due <= cyc)
      val dcDue = if (dues.isEmpty) None else Some(if (dcOutOfOrder) dues(rnd.nextInt(dues.size)) else dues.head)
      val dr = io.dCacheLoadRespIn
      dr.valid.poke(dcDue.nonEmpty.B)
      var dcStatusNow: Option[UInt] = None
      dcDue.foreach { x =>
        val st = if (x.status == LoadStatus.Data && replayNext > 0) LoadStatus.Replay else x.status
        dcStatusNow = Some(st)
        dr.bits.lqIdx.poke(x.lqIdx.U); dr.bits.lqGen.poke(x.gen.U); dr.bits.status.poke(st); dr.bits.paddr.poke(x.paddr.U)
        dr.bits.data.poke(mem(x.paddr >>> 2).U)
      }
      val ucLdDue = ucLdq.find(_._1 <= cyc)
      io.uncachedLoadRespIn.valid.poke(ucLdDue.nonEmpty.B)
      ucLdDue.foreach { x => io.uncachedLoadRespIn.bits.lqIdx.poke(x._2.U); io.uncachedLoadRespIn.bits.lqGen.poke(x._3.U)
        io.uncachedLoadRespIn.bits.data.poke(x._4.U); io.uncachedLoadRespIn.bits.accessFault.poke(x._5.B) }
      val ucStDue = ucStq.find(_._1 <= cyc)
      io.uncachedStoreRespIn.valid.poke(ucStDue.nonEmpty.B)
      ucStDue.foreach { x => io.uncachedStoreRespIn.bits.sqIdx.poke(x._2.U); io.uncachedStoreRespIn.bits.accessFault.poke(x._3.B) }
      io.uncachedLoadReqOut.ready.poke(ucLdReady.B); io.uncachedStoreReqOut.ready.poke(ucStReady.B)
      io.storeBufferEmptyIn.poke(sb.isEmpty.B)
      io.robStatusIn.empty.poke(robHead.isEmpty.B)
      pokeTag(io.robStatusIn.headTag, robHead.getOrElse(robNext))
      io.headMemGrantIn.valid.poke(grant.nonEmpty.B); grant.foreach(s => pokeTag(io.headMemGrantIn.bits.robTag, s))
      io.storeCommitIn.valid.poke(commit.nonEmpty.B); commit.foreach(s => pokeTag(io.storeCommitIn.bits.robTag, s))
      io.committedStoreOut.ready.poke(sbReady.B)
      val e = io.recoveryEventIn
      e.valid.poke(event.nonEmpty.B)
      e.checkpointId.id.poke(0.U); e.target.poke(0.U); e.cause.poke(0.U)
      event.foreach { case (k, s) => e.kind.poke(k); pokeTag(e.robTag, s) }
      io.memResultOut.ready.poke(memResReady.B)
      // StoreBuffer forwarding: answered combinationally in the query cycle.
      io.storeForwardQueryOut.ready.poke(true.B)
      val qv = b(io.storeForwardQueryOut.valid)
      io.storeForwardDataIn.valid.poke(qv.B)
      if (qv) {
        val qa = l(io.storeForwardQueryOut.bits.paddr); val qm = l(io.storeForwardQueryOut.bits.mask).toInt
        val (d, h, part) = sbAnswer(qa, qm)
        io.storeForwardDataIn.bits.data.poke(d.U); io.storeForwardDataIn.bits.hitMask.poke(h.U)
        io.storeForwardDataIn.bits.partial.poke(part.B)
        if (b(io.storeForwardDataIn.ready)) fwdQueries += ((cyc, qa, qm))
      }

      // ---- Observe transfers --------------------------------------------------------------
      allocFire  = alloc.nonEmpty && b(a.ready)
      aguFire    = agu.nonEmpty && b(m.ready)
      commitFire = commit.nonEmpty && b(io.storeCommitIn.ready)
      grantFire  = grant.nonEmpty && b(io.headMemGrantIn.ready)
      val dq = io.dtlbReqOut
      val dtlbFire = b(dq.valid) && dtlbReady
      val dcFire   = b(io.dCacheLoadReqOut.valid) && dcReady
      if (dtlbFire) {
        val va = l(dq.bits.vaddr); val acc = l(dq.bits.access)
        val pg = paddrOf(va)
        val (st, pa, cach) = pg match {
          case None => (TranslationStatus.AccessFault, 0L, false)
          case Some((pgx, pa)) =>
            if (pgx.fault.contains("pf")) (TranslationStatus.PageFault, 0L, false)
            else if (pgx.fault.contains("af")) (TranslationStatus.AccessFault, 0L, false)
            else if (!pgx.present) {
              if (!pgx.walking) { pgx.walking = true; refq += ((cyc + walkLatency, va >>> 12)) }
              (TranslationStatus.Miss, 0L, false)
            } else (TranslationStatus.Hit, pa, pgx.cacheable)
        }
        if (acc == AccessType.Store.litValue.toLong) {
          tlbStores += ((cyc, va))
          tlbq += TlbReq(cyc + tlbLatency, l(dq.bits.reqId), st, pa, cach)
        } else {
          val lsSt = if (st == TranslationStatus.Hit) (if (cach) LoadStatus.Data else LoadStatus.Uncacheable)
                     else if (st == TranslationStatus.Miss) LoadStatus.TlbMiss
                     else if (st == TranslationStatus.PageFault) LoadStatus.PageFault else LoadStatus.AccessFault
          if (dcFire) {
            val c = io.dCacheLoadReqOut.bits
            dcReqs += ((cyc, l(c.lqIdx).toInt, l(c.vaddr)))
            dcq += DcReq(cyc + dcLatency, l(c.lqIdx).toInt, l(c.lqGen).toInt, l(c.vaddr), l(c.size).toInt, lsSt, pa)
          }
        }
      }
      require(dtlbFire == dcFire || (dtlbFire && l(dq.bits.access) == AccessType.Store.litValue.toLong),
        s"cycle $cyc: a load DtlbReq and its DCacheLoadReq did not fire together")
      if (tlbDue.nonEmpty && b(t.ready)) tlbq -= tlbDue.get
      if (refDue.nonEmpty && b(io.dtlbRefillIn.ready)) {
        refq -= refDue.get; pages.get(refDue.get._2).foreach { pg => pg.present = true; pg.walking = false }
      }
      if (dcDue.nonEmpty && b(dr.ready)) {
        dcq -= dcDue.get
        if (dcStatusNow.contains(LoadStatus.Replay) && dcDue.get.status == LoadStatus.Data) replayNext -= 1
      }
      if (ucLdDue.nonEmpty && b(io.uncachedLoadRespIn.ready)) ucLdq -= ucLdDue.get
      if (ucStDue.nonEmpty && b(io.uncachedStoreRespIn.ready)) ucStq -= ucStDue.get
      val r = io.memResultOut
      if (b(r.valid) && memResReady) {
        val rs = seqOf((b(r.bits.robTag.wrap), l(r.bits.robTag.idx).toInt))
        results += Res(cyc, rs, l(r.bits.prd).toInt, b(r.bits.wen), l(r.bits.data), b(r.bits.headExecute),
          if (b(r.bits.exception.valid)) Some(l(r.bits.exception.cause).toInt) else None, l(r.bits.exception.tval))
      }
      val cs = io.committedStoreOut
      if (b(cs.valid) && sbReady) {
        val entry = (l(cs.bits.paddr), l(cs.bits.data), l(cs.bits.mask).toInt)
        sbPushes += ((cyc, entry._1, entry._2, entry._3))
        sb.enqueue(entry)
      }
      val ul = io.uncachedLoadReqOut
      if (b(ul.valid) && ucLdReady) {
        val pa = l(ul.bits.paddr)
        ucLoads += ((cyc, pa))
        ucLdq += ((cyc + ucLatency, l(ul.bits.lqIdx).toInt, l(ul.bits.lqGen).toInt, mem(pa >>> 2), ucLoadFault))
      }
      val us = io.uncachedStoreReqOut
      if (b(us.valid) && ucStReady) {
        val pa = l(us.bits.paddr); val d = l(us.bits.data); val mk = l(us.bits.mask).toInt
        ucStores += ((cyc, pa, d, mk))
        if (!ucStoreFault) mem(pa >>> 2) = merge(mem(pa >>> 2), d, mk)
        ucStq += ((cyc + ucLatency, l(us.bits.sqIdx).toInt, ucStoreFault))
      }
      dut.clock.step()
      cyc += 1
      // The StoreBuffer drains its oldest store after a delay (end of cycle: this cycle's reads
      // and forwarding answers saw it still buffered).
      if (sb.nonEmpty && !sbHold) {
        if (sbCountdown < 0) sbCountdown = sbDrainDelay
        if (sbCountdown == 0) { val (pa, d, mk) = sb.dequeue(); mem(pa >>> 2) = merge(mem(pa >>> 2), d, mk); sbCountdown = -1 }
        else sbCountdown -= 1
      }
    }

    def run(n: Int): Unit = (0 until n).foreach(_ => cycle())
    /** Hold `alloc` until it transfers. */
    def allocate(x: Alloc): Unit = { alloc = Some(x); var k = 0; cycle(); while (!allocFire && k < 20) { cycle(); k += 1 }; alloc = None }
    def address(x: Agu): Unit = { agu = Some(x); var k = 0; cycle(); while (!aguFire && k < 20) { cycle(); k += 1 }; agu = None }
    def storeCommit(s: Int): Boolean = { commit = Some(s); var k = 0; cycle(); while (!commitFire && k < 20) { cycle(); k += 1 }; commit = None; commitFire }
    def headGrant(s: Int): Unit = { grant = Some(s); cycle(); grant = None }
    def kill(kind: UInt, s: Int): Unit = { event = Some((kind, s)); cycle(); event = None }
    def resultsFor(s: Int): Seq[Res] = results.filter(_.s == s).toSeq
    def done(s: Int): Option[Res] = results.find(r => r.s == s && !r.hx)
    def until(n: Int)(p: => Boolean): Boolean = { var k = 0; while (!p && k < n) { cycle(); k += 1 }; p }
  }

  def withDrv(t: SpecTest, seed: Int = 1)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new LoadStoreQueue(p)) { dut => val d = new Drv(dut, seed); d.cycle(); body(d) }

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  def hx(v: Long): String = f"0x$v%x"
  val BM = RecoveryKind.BranchMispredict; val AR = RecoveryKind.ArchRedirect
  val RAM = 0x10000L // page 0x10, identity-mapped, cacheable

  def mustAssert(t: SpecTest, label: String, expect: String)(body: Drv => Unit): TCheck = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.run(3); reachedEnd = true; Nil }
      TCheck(false, label, "simulation ended normally: the design assertion did not fire")
    } catch {
      case e: NotImplementedError => throw e
      case e: Throwable =>
        val log   = chisel3.simulator.CachedSimulator.lastSimulationLog
        val fired = log.linesIterator.filter(_.contains("Assertion failed")).toSeq
        val ok    = !reachedEnd && fired.exists(_.contains(expect))
        val why   = if (fired.isEmpty) s"no assertion in the simulation log (${e.getClass.getSimpleName}: ${e.getMessage})"
                    else fired.head.trim.take(200)
        TCheck(ok, label, if (ok) "" else s"expected '$expect'; got: $why")
    }
  }

  // ---- funcLsqAllocate ------------------------------------------------------------------------------

  val allocate = new SpecTest("lsq.allocate", Seq("funcLsqAllocate")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.robHead = Some(0)
      val nL = p.tuning.loadQueueDepth; val nS = p.tuning.storeQueueDepth
      (0 until nL).foreach(i => d.allocate(Alloc(i, isLoad = true)))
      d.alloc = Some(Alloc(nL, isLoad = true)); d.cycle(); val fullLoad = d.allocFire
      d.alloc = Some(Alloc(nL, isLoad = false)); d.cycle(); val storeOk = d.allocFire
      (1 until nS).foreach(i => d.allocate(Alloc(nL + i, isLoad = false)))
      d.alloc = Some(Alloc(nL + nS, isLoad = false)); d.cycle(); val fullStore = d.allocFire
      d.alloc = None
      Seq(
        chk(!fullLoad, s"LsqAllocIn.ready is low for a load when the LQ holds $nL entries", ""),
        chk(storeOk && !fullStore, "stores allocate into the SQ independently; ready is low when the SQ is full", s"$storeOk $fullStore")
      )
    }
  }

  // ---- funcAddressCapture -----------------------------------------------------------------------------

  val addressCapture = new SpecTest("lsq.addressCapture", Seq("funcAddressCapture")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.robHead = Some(0)
      d.allocate(Alloc(0, isLoad = false)); d.allocate(Alloc(1, isLoad = true, size = 1)); d.allocate(Alloc(2, isLoad = false, size = 2))
      d.address(Agu(0, RAM + 8, 0x11223344)); d.run(3)
      d.address(Agu(1, RAM + 3, size = 1)); d.address(Agu(2, RAM + 6, 1, size = 2)); d.run(4)
      Seq(
        chk(d.tlbStores.exists(_._2 == RAM + 8), "a store address issues a translation-only DtlbReq{Store}", s"${d.tlbStores}"),
        chk(d.done(1).exists(r => r.exc.contains(LoadMisaligned) && r.tval == RAM + 3 && !r.wen),
          "a misaligned load completes with the load-misaligned cause (tval = vaddr) and no write", s"${d.resultsFor(1)}"),
        chk(d.done(2).exists(r => r.exc.contains(StoreMisaligned) && r.tval == RAM + 6),
          "a misaligned store completes with the store-misaligned cause", s"${d.resultsFor(2)}"),
        chk(!d.tlbStores.exists(_._2 == RAM + 6) && !d.dcReqs.exists(_._3 == RAM + 3),
          "a misaligned access issues no translation or cache lookup", s"${d.tlbStores} ${d.dcReqs}")
      )
    }
  }

  // ---- funcTranslationWait --------------------------------------------------------------------------------

  val translationWait = new SpecTest("lsq.translationWait", Seq("funcTranslationWait")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.pages(0x11) = Page(0x21, present = false); d.pages(0x30) = Page(0x30, fault = Some("pf")); d.pages(0x31) = Page(0x31, fault = Some("af"))
      d.walkLatency = 12; d.robHead = Some(0)
      d.allocate(Alloc(0, isLoad = true)); d.allocate(Alloc(1, isLoad = true))
      d.address(Agu(0, 0x11000L + 4)); d.address(Agu(1, RAM + 4))
      d.until(10)(d.done(1).nonEmpty)
      val otherFirst = d.done(1).nonEmpty && d.done(0).isEmpty
      d.until(30)(d.done(0).nonEmpty)
      val missDone = d.done(0)
      // A store to a missing page re-translates after the refill.
      d.pages(0x12) = Page(0x22, present = false)
      d.allocate(Alloc(2, isLoad = false)); d.address(Agu(2, 0x12000L, 7))
      d.until(40)(d.done(2).nonEmpty)
      val stTries = d.tlbStores.count(_._2 == 0x12000L)
      // Faults of each access type.
      d.allocate(Alloc(3, isLoad = true)); d.allocate(Alloc(4, isLoad = false)); d.allocate(Alloc(5, isLoad = true)); d.allocate(Alloc(6, isLoad = false))
      d.address(Agu(3, 0x30000L)); d.address(Agu(4, 0x30004L)); d.address(Agu(5, 0x31000L)); d.address(Agu(6, 0x31004L))
      d.run(12)
      Seq(
        chk(otherFirst, "a DTLB miss blocks only its own load; an independent load completes meanwhile", s"${d.results}"),
        chk(missDone.exists(r => r.wen && r.data == d.mem((0x21004L) >>> 2)) && d.dcReqs.count(_._3 == 0x11004L) >= 2,
          "after the refill the missing load re-issues and completes with the data at its translated paddr", s"$missDone ${d.dcReqs}"),
        chk(stTries >= 2 && d.done(2).exists(_.exc.isEmpty), "a store translation miss re-issues after the refill and completes", s"$stTries ${d.resultsFor(2)}"),
        chk(d.done(3).exists(_.exc.contains(LoadPage)) && d.done(4).exists(_.exc.contains(StorePage)) &&
          d.done(5).exists(_.exc.contains(LoadAccess)) && d.done(6).exists(_.exc.contains(StoreAccess)),
          "PageFault / AccessFault answers fault the entry with the cause of its access type", s"${d.results.filter(_.s >= 3)}")
      )
    }
  }

  /** The walk finishes while the lookup (or store translation) that reported the miss is still
    * in flight: the refill notice arrives before the Miss answer and must not be lost. */
  val refillRace = new SpecTest("lsq.refillRace", Seq("funcTranslationWait")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.pages(0x11) = Page(0x21, present = false); d.pages(0x12) = Page(0x22, present = false)
      d.robHead = Some(0); d.dcLatency = 8; d.tlbLatency = 8; d.walkLatency = 2
      d.allocate(Alloc(0, isLoad = true)); d.allocate(Alloc(1, isLoad = false))
      d.address(Agu(0, 0x11008L)); d.address(Agu(1, 0x12008L, 5))
      d.until(60)(d.done(0).nonEmpty && d.done(1).nonEmpty)
      Seq(
        chk(d.done(0).exists(_.data == d.mem(0x21008L >>> 2)), "a load whose refill beat its TlbMiss answer re-issues and completes", s"${d.results}"),
        chk(d.done(1).exists(_.exc.isEmpty), "a store whose refill beat its Miss answer re-translates and completes", s"${d.results}")
      )
    }
  }

  /** A stale D-cache answer for a killed load must not complete the load that reuses its slot. */
  val staleAnswer = new SpecTest("lsq.staleAnswer", Seq("funcLsqRecovery")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.robHead = Some(0); d.dcLatency = 10
      d.mem((RAM + 0x60) >>> 2) = 0x11111111L; d.mem((RAM + 0x64) >>> 2) = 0x22222222L
      d.allocate(Alloc(1, isLoad = true, prd = 41)); d.address(Agu(1, RAM + 0x60))
      d.until(5)(d.dcReqs.nonEmpty)
      d.kill(BM, 0)
      d.dcLatency = 20
      d.allocate(Alloc(1, isLoad = true, prd = 42)); d.address(Agu(1, RAM + 0x64))
      d.until(40)(d.done(1).nonEmpty)
      d.run(4)
      Seq(chk(d.results.filter(_.s == 1).map(r => (r.prd, r.data)) == Seq((42, 0x22222222L)),
        "the reused slot completes with its own data; the killed load's late answer is dropped (generation)", s"${d.results}"))
    } :+ mustAssert(this, "an allocation offered in a RecoveryEvent cycle asserts", "LsqAllocate: an allocation was offered in a RecoveryEvent cycle") { d =>
      d.robHead = Some(0); d.alloc = Some(Alloc(3, isLoad = true)); d.event = Some((BM, 2)); d.cycle()
    }
  }

  // ---- funcConservativeDisambig / propNoPassUnresolvedStore ------------------------------------------------

  val disambig = new SpecTest("lsq.disambig", Seq("funcConservativeDisambig", "propNoPassUnresolvedStore")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.robHead = Some(0)
      d.allocate(Alloc(0, isLoad = false)); d.allocate(Alloc(1, isLoad = true)); d.allocate(Alloc(2, isLoad = false))
      d.address(Agu(1, RAM + 0x40)); d.run(6)
      val blocked = d.dcReqs.isEmpty
      d.address(Agu(0, RAM + 0x80, 5)); d.until(10)(d.dcReqs.nonEmpty)
      val afterResolve = d.dcReqs.headOption
      // A younger unresolved store (s=2) never blocks; a faulted older store does not block.
      d.pages(0x30) = Page(0x30, fault = Some("pf"))
      d.address(Agu(2, RAM + 0x84, 1))
      d.allocate(Alloc(3, isLoad = false)); d.allocate(Alloc(4, isLoad = true))
      d.address(Agu(3, 0x30000L, 1)); d.address(Agu(4, RAM + 0x44)); d.until(10)(d.dcReqs.exists(_._3 == RAM + 0x44))
      Seq(
        chk(blocked, "a load does not issue while an older store lacks its physical address", s"${d.dcReqs}"),
        chk(afterResolve.exists(_._3 == RAM + 0x40) && d.tlbStores.exists(_._2 == RAM + 0x80),
          "the load issues once the older store's address is translated (a younger unresolved store does not block)", s"$afterResolve"),
        chk(d.dcReqs.exists(_._3 == RAM + 0x44), "a faulted older store does not block a younger load", s"${d.dcReqs}")
      )
    }
  }

  // ---- funcLoadIssue -------------------------------------------------------------------------------------------

  val loadIssue = new SpecTest("lsq.loadIssue", Seq("funcLoadIssue")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.robHead = Some(0); d.dcReady = false
      (0 until 3).foreach(i => d.allocate(Alloc(i, isLoad = true)))
      d.address(Agu(2, RAM + 0x28)); d.address(Agu(1, RAM + 0x24)); d.address(Agu(0, RAM + 0x20))
      d.run(3)
      val noneWhileBusy = d.dcReqs.isEmpty
      d.dcReady = true; d.run(4)
      Seq(
        chk(noneWhileBusy, "with the D-cache not ready neither the DtlbReq nor the D-cache lookup fires (paired issue)", s"${d.dcReqs}"),
        chk(d.dcReqs.map(_._3).take(3) == Seq(RAM + 0x20, RAM + 0x24, RAM + 0x28), "the oldest ready load issues first", s"${d.dcReqs}")
      )
    }
  }

  // ---- funcStoreToLoadForward / propPhysicalOrderingAuthority ------------------------------------------------

  val forward = new SpecTest("lsq.forward", Seq("funcStoreToLoadForward", "propPhysicalOrderingAuthority")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.pages(0x12) = Page(0x10) // a synonym: VA page 0x12 maps to the same PA page as 0x10
      d.robHead = Some(0); d.sbHold = true
      val w0 = d.mem((RAM + 0x10) >>> 2)
      // Two older stores to one word (the younger wins), a younger store (never forwarded), then loads.
      d.allocate(Alloc(0, isLoad = false, size = 2)); d.allocate(Alloc(1, isLoad = false, size = 0))
      d.allocate(Alloc(2, isLoad = true, size = 2)); d.allocate(Alloc(3, isLoad = false, size = 2))
      d.allocate(Alloc(4, isLoad = true, size = 1, signed = true))
      d.address(Agu(0, RAM + 0x10, 0xaabbccddL)); d.address(Agu(1, 0x12000L + 0x11, 0x5e, size = 0))
      d.address(Agu(3, RAM + 0x10, 0x81020304L)); d.address(Agu(2, 0x12000L + 0x10)); d.address(Agu(4, RAM + 0x12, size = 1))
      d.until(20)(d.done(2).nonEmpty && d.done(4).nonEmpty)
      val full = d.done(2); val half = d.done(4)
      // Committed stores in the StoreBuffer are forwarded; the SQ still wins over the StoreBuffer.
      d.robHead = Some(0); d.storeCommit(0); d.robHead = Some(1); d.storeCommit(1); d.robHead = Some(2)
      d.allocate(Alloc(5, isLoad = true, size = 2)); d.address(Agu(5, RAM + 0x10))
      d.until(20)(d.done(5).nonEmpty)
      val fromSb = d.done(5)
      // A load whose bytes are partly in the SQ and partly in the cache merges both.
      d.allocate(Alloc(6, isLoad = false, size = 0)); d.allocate(Alloc(7, isLoad = true, size = 2))
      d.address(Agu(6, RAM + 0x21, 0x77, size = 0)); d.address(Agu(7, RAM + 0x20))
      d.until(20)(d.done(7).nonEmpty)
      val mixed = d.done(7)
      // StoreBuffer partial on bytes the SQ does not cover: commit s2..s5 so s3 moves to the
      // StoreBuffer too; the load waits for the drain and re-issues.
      d.robHead = Some(3); d.storeCommit(3); d.robHead = Some(8)
      d.sbPartial = true
      val reqsBefore = d.dcReqs.count(_._3 == RAM + 0x10)
      d.allocate(Alloc(8, isLoad = true, size = 2)); d.address(Agu(8, RAM + 0x10))
      d.run(8)
      val waited = d.done(8).isEmpty && d.dcReqs.count(_._3 == RAM + 0x10) == reqsBefore + 1
      d.sbPartial = false; d.sbHold = false
      d.until(40)(d.done(8).nonEmpty)
      val drained = d.done(8)
      val expFull = 0xaabb5eddL
      Seq(
        chk(full.exists(r => r.wen && r.data == expFull), s"each byte takes the youngest older SQ store (synonym VA, same PA; the younger s=3 is ignored): ${hx(expFull)}", s"$full"),
        chk(half.exists(_.data == 0xffff8102L), "a signed half load forwards and sign-extends the youngest older SQ bytes", s"$half"),
        chk(fromSb.exists(_.data == 0x81020304L), "the SQ store (s=3) wins over the committed StoreBuffer bytes", s"$fromSb"),
        chk(mixed.exists(_.data == merge(d.mem((RAM + 0x20) >>> 2), laneData(0x77, RAM + 0x21), 0x2)), "SQ bytes merge with the cache word",
          s"$mixed"),
        chk(waited && drained.exists(_.data == 0x81020304L) && d.dcReqs.count(_._3 == RAM + 0x10) >= 3,
          "a StoreBuffer partial answer makes the load wait (no re-issue) until the drain, then re-issue", s"$waited $drained ${d.dcReqs}"),
        chk(d.fwdQueries.nonEmpty && d.fwdQueries.forall(q => (q._2 >>> 12) == 0x10L), "the StoreBuffer is queried by physical address", s"${d.fwdQueries}")
      )
    }
  }

  // ---- funcLoadComplete ----------------------------------------------------------------------------------------

  val loadComplete = new SpecTest("lsq.loadComplete", Seq("funcLoadComplete")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.robHead = Some(0)
      val w = 0x80ff7f01L
      d.mem((RAM + 0x30) >>> 2) = w
      val cases = Seq((0, 0, true), (1, 0, true), (2, 0, false), (3, 0, true), (0, 1, true), (2, 1, false), (0, 1, false), (0, 2, false))
      cases.zipWithIndex.foreach { case ((off, size, sg), i) =>
        d.robHead = Some(i)
        d.allocate(Alloc(i, isLoad = true, size = size, signed = sg, prd = 33 + i)); d.address(Agu(i, RAM + 0x30 + off, size = size))
        d.until(10)(d.done(i).nonEmpty)
      }
      d.replayNext = 1; d.robHead = Some(8)
      d.allocate(Alloc(8, isLoad = true)); d.address(Agu(8, RAM + 0x30)); d.until(20)(d.done(8).nonEmpty)
      val checks = cases.zipWithIndex.map { case ((off, size, sg), i) =>
        val exp = extract(w, off, size, sg)
        chk(d.done(i).exists(r => r.wen && r.prd == 33 + i && r.data == exp), s"load size $size offset $off signed $sg -> ${hx(exp)}", s"${d.done(i)}")
      }
      checks :+ chk(d.done(8).exists(_.data == w) && d.dcReqs.count(_._3 == RAM + 0x30) >= cases.count(_._1 == 0) + 2,
        "a Replay answer returns the load to Ready; it re-issues and completes", s"${d.done(8)}")
    }
  }

  // ---- funcStoreComplete ------------------------------------------------------------------------------------------

  val storeComplete = new SpecTest("lsq.storeComplete", Seq("funcStoreComplete")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.pages(0x20) = Page(0x40, cacheable = false)
      d.robHead = Some(0)
      d.allocate(Alloc(0, isLoad = false)); d.allocate(Alloc(1, isLoad = false))
      d.address(Agu(0, RAM + 4, 9)); d.address(Agu(1, 0x20000L, 9)); d.run(6)
      Seq(
        chk(d.resultsFor(0).exists(r => !r.hx && !r.wen && r.exc.isEmpty), "a cacheable store with paddr and data completes (no register write)",
          s"${d.resultsFor(0)}"),
        chk(d.resultsFor(1).size == 1 && d.resultsFor(1).head.hx && d.ucStores.isEmpty,
          "an uncacheable store reports headExecute (not done) and performs nothing", s"${d.resultsFor(1)}"),
        chk(d.sbPushes.isEmpty, "no store leaves the SQ before its commit", s"${d.sbPushes}")
      )
    }
  }

  // ---- funcUncacheableAtHead / propUncachedPerformedOnce ------------------------------------------------------------

  val uncached = new SpecTest("lsq.uncached", Seq("funcUncacheableAtHead", "propUncachedPerformedOnce")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.pages(0x20) = Page(0x40, cacheable = false)
      d.mem(0x40010L >>> 2) = 0x12345678L
      d.robHead = Some(0)
      // A committed cacheable store still in the StoreBuffer delays the uncached load after its grant.
      d.sbHold = true
      d.allocate(Alloc(0, isLoad = false)); d.address(Agu(0, RAM, 1)); d.until(6)(d.done(0).nonEmpty); d.storeCommit(0)
      d.robHead = Some(1)
      d.allocate(Alloc(1, isLoad = true, size = 1, signed = true)); d.address(Agu(1, 0x20012L, size = 1))
      d.until(10)(d.resultsFor(1).nonEmpty)
      val hx1 = d.resultsFor(1)
      d.run(4)
      val noneBeforeGrant = d.ucLoads.isEmpty
      d.headGrant(1); d.run(4)
      val waitsForSb = d.ucLoads.isEmpty
      d.sbHold = false; d.until(20)(d.done(1).nonEmpty)
      val ld = d.done(1)
      // Uncached store: grant, one bus write, done; StoreCommit releases it without the StoreBuffer.
      d.robHead = Some(2)
      d.allocate(Alloc(2, isLoad = false, size = 0)); d.address(Agu(2, 0x20021L, 0xab, size = 0))
      d.until(10)(d.resultsFor(2).nonEmpty)
      d.headGrant(2); d.until(10)(d.done(2).nonEmpty)
      val pushesBefore = d.sbPushes.size
      val released = d.storeCommit(2)
      // Access faults.
      d.robHead = Some(3); d.ucLoadFault = true
      d.allocate(Alloc(3, isLoad = true)); d.address(Agu(3, 0x20030L)); d.until(10)(d.resultsFor(3).nonEmpty)
      d.headGrant(3); d.until(10)(d.done(3).nonEmpty)
      d.robHead = Some(4); d.ucStoreFault = true
      d.allocate(Alloc(4, isLoad = false)); d.address(Agu(4, 0x20034L, 1)); d.until(10)(d.resultsFor(4).nonEmpty)
      d.headGrant(4); d.until(10)(d.done(4).nonEmpty); d.run(4)
      Seq(
        chk(hx1.size == 1 && hx1.head.hx && noneBeforeGrant, "an Uncacheable answer reports headExecute; no bus access before HeadMemGrant", s"$hx1 ${d.ucLoads}"),
        chk(waitsForSb, "after the grant the load waits for StoreBufferEmpty", s"${d.ucLoads}"),
        chk(d.ucLoads.count(_._2 == 0x40012L) == 1 && ld.exists(r => r.wen && r.data == 0x1234L),
          "exactly one uncached load, completed with the extended value", s"${d.ucLoads} $ld"),
        chk(d.ucStores.count(_._2 == 0x40021L) == 1 && d.ucStores.find(_._2 == 0x40021L).exists(x => x._3 == 0xab00L && x._4 == 2) &&
          d.done(2).exists(_.exc.isEmpty), "one uncached store {paddr, lane data, mask} after the grant, then done", s"${d.ucStores}"),
        chk(released && d.sbPushes.size == pushesBefore, "StoreCommit releases a performed uncached store without CommittedStoreOut", s"$released"),
        chk(d.done(3).exists(r => r.exc.contains(LoadAccess) && r.tval == 0x20030L && !r.wen) &&
          d.done(4).exists(r => r.exc.contains(StoreAccess) && r.tval == 0x20034L),
          "a denied uncached access completes with the access-fault cause and tval = vaddr", s"${d.done(3)} ${d.done(4)}"),
        chk(d.ucLoads.size == 2 && d.ucStores.size == 2, "every uncached access reaches the bus exactly once", s"${d.ucLoads} ${d.ucStores}")
      )
    }
  }

  // ---- funcStoreCommitHandoff / propNoWrongPathStoreVisible ----------------------------------------------------------

  val storeCommit = new SpecTest("lsq.storeCommit", Seq("funcStoreCommitHandoff", "propNoWrongPathStoreVisible")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.robHead = Some(0)
      d.allocate(Alloc(0, isLoad = false, size = 1)); d.allocate(Alloc(1, isLoad = false))
      d.address(Agu(0, RAM + 0x42, 0xbeef, size = 1)); d.address(Agu(1, RAM + 0x44, 7)); d.until(10)(d.done(1).nonEmpty)
      val before = d.sbPushes.size
      d.sbReady = false; d.commit = Some(0); d.run(3); val stalled = !d.commitFire; d.commit = None; d.sbReady = true
      val ok = d.storeCommit(0)
      val push = d.sbPushes.lastOption
      // A killed store never reaches the StoreBuffer.
      d.kill(BM, 0)
      d.robHead = Some(1); d.robNext = 1; d.run(3)
      Seq(
        chk(before == 0 && stalled, "no CommittedStoreOut before StoreCommit; StoreCommit waits for the StoreBuffer", s"$before $stalled"),
        chk(ok && push.exists(x => x._2 == RAM + 0x42 && x._3 == 0xbeef0000L && x._4 == 0xc),
          "StoreCommit hands the SQ head {paddr, lane data, mask} to the StoreBuffer in the same cycle", s"$push"),
        chk(d.sbPushes.size == 1, "a store killed by a RecoveryEvent never reaches the StoreBuffer", s"${d.sbPushes}")
      )
    } :+ mustAssert(this, "a StoreCommit that does not name the SQ head asserts", "StoreCommitHandoff: StoreCommit does not name the SQ head") { d =>
      d.robHead = Some(0)
      d.allocate(Alloc(0, isLoad = false)); d.allocate(Alloc(1, isLoad = false))
      d.address(Agu(0, RAM, 1)); d.address(Agu(1, RAM + 4, 1)); d.run(4)
      d.commit = Some(1); d.run(2)
    }
  }

  // ---- funcLsqRecovery / propLsqRecoveryKeepsOlder / propWrongPathLoadNoResult ----------------------------------------

  val recovery = new SpecTest("lsq.recovery", Seq("funcLsqRecovery", "propLsqRecoveryKeepsOlder", "propWrongPathLoadNoResult")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.robHead = Some(0); d.dcLatency = 6
      // s0 store, s1 load (issued, slow answer), s2 = the branch (not in the LSQ), s3 load, s4 store.
      d.allocate(Alloc(0, isLoad = false)); d.allocate(Alloc(1, isLoad = true, prd = 41))
      d.allocate(Alloc(3, isLoad = true, prd = 43)); d.allocate(Alloc(4, isLoad = false))
      d.address(Agu(0, RAM + 0x50, 3)); d.address(Agu(1, RAM + 0x54)); d.address(Agu(3, RAM + 0x58)); d.address(Agu(4, RAM + 0x50, 9))
      d.until(6)(d.dcReqs.exists(_._3 == RAM + 0x58))
      d.kill(BM, 2)
      d.run(10)
      // The tails rewound: new uops at s=3,4 reuse the slots and complete with their own values.
      d.allocate(Alloc(3, isLoad = false)); d.allocate(Alloc(4, isLoad = true, prd = 44))
      d.address(Agu(3, RAM + 0x54, 0x55)); d.address(Agu(4, RAM + 0x54))
      d.until(20)(d.results.exists(r => r.s == 4 && r.prd == 44))
      val newLoad = d.results.find(r => r.s == 4 && r.prd == 44)
      // Commit the survivors in order.
      val c0 = d.storeCommit(0)
      Seq(
        chk(d.done(1).exists(r => r.prd == 41 && r.data == d.mem((RAM + 0x54) >>> 2)), "the older load survives the mispredict and completes", s"${d.done(1)}"),
        chk(!d.results.exists(r => r.s == 3 && r.prd == 43), "a killed load never produces a result, even after its D-cache answer arrives",
          s"${d.results}"),
        chk(newLoad.exists(_.data == 0x55L), "after the rewind the reused slots serve the new uops (store-to-load forwarding at s=3 -> s=4)", s"$newLoad"),
        chk(c0 && d.sbPushes.exists(_._3 == 3L), "the older store survives and commits", s"${d.sbPushes}"),
        chk(!d.sbPushes.exists(_._3 == 9L), "the killed store never becomes visible", s"${d.sbPushes}")
      )
    }
  }

  // ---- The reference model: random program vs sequential semantics ------------------------------------------------

  sealed trait Kind
  case object LoadOp extends Kind; case object StoreOp extends Kind; case object BranchOp extends Kind
  case class Op(s: Int, kind: Kind, vaddr: Long = 0, size: Int = 2, signed: Boolean = false, data: Long = 0, prd: Int = 0) {
    var allocated = false; var addrSent = -1; var hxSeen = false; var granted = false
    var result: Option[Res] = None
  }

  val lastTrace = ArrayBuffer[String]()
  def modelRun(d: Drv, seed: Int, target: Int): Seq[TCheck] = {
    val rnd = new scala.util.Random(seed)
    d.pages(0x11) = Page(0x21, present = false); d.pages(0x12) = Page(0x21) // synonyms of one PA page
    d.pages(0x20) = Page(0x40, cacheable = false); d.pages(0x30) = Page(0x30, fault = Some("pf"))
    d.dcOutOfOrder = true
    val golden = mutable.Map[Long, Long]().withDefault(w => d.mem(w))
    val live = ArrayBuffer[Op]()
    var nextS = 0
    var committed = 0; var loadsChecked = 0; var traps = 0; var mispredicts = 0
    var bad = Seq.empty[String]
    val perfCount = mutable.Map[Int, Int]().withDefaultValue(0)
    def pick(): Long = {
      val page = rnd.nextInt(20) match { case 0 => 0x30L; case 1 | 2 => 0x20L; case 3 | 4 | 5 => 0x11L; case 6 | 7 => 0x12L; case _ => 0x10L }
      (page << 12) | (rnd.nextInt(4) * 4 + rnd.nextInt(4))
    }
    def alignFor(va: Long, size: Int): Long = if (rnd.nextInt(30) == 0) va else va & ~((1L << size) - 1)
    def newOp(): Op = {
      val s = nextS; nextS += 1
      rnd.nextInt(10) match {
        case 0 | 1 => Op(s, BranchOp)
        case 2 | 3 | 4 | 5 => val sz = rnd.nextInt(3); Op(s, LoadOp, alignFor(pick(), sz), sz, rnd.nextBoolean(), prd = 32 + rnd.nextInt(16))
        case _ => val sz = rnd.nextInt(3); Op(s, StoreOp, alignFor(pick(), sz), sz, data = rnd.nextLong() & 0xffffffffL)
      }
    }
    var pending: Option[Op] = None
    val trace = lastTrace; trace.clear()
    var cycles = 0
    // After `target` retirements the window drains: no new uops, no mispredicts, until empty.
    while ((committed < target || live.nonEmpty || pending.nonEmpty) && cycles < target * 40) {
      cycles += 1
      val draining = committed >= target
      d.memResReady = rnd.nextInt(5) > 0; d.dcReady = rnd.nextInt(4) > 0; d.dtlbReady = rnd.nextInt(4) > 0
      d.sbReady = rnd.nextInt(4) > 0; d.sbDrainDelay = rnd.nextInt(4); d.dcLatency = 1 + rnd.nextInt(4)
      d.replayNext = if (rnd.nextInt(25) == 0) 1 else d.replayNext
      val head = live.headOption
      d.robHead = head.map(_.s); d.robNext = nextS
      d.commit = None; d.grant = None; d.event = None; d.alloc = None; d.agu = None
      // CommitUnit model.
      var commitNow: Option[Op] = None
      var trapNow = false
      head.foreach { h =>
        h.kind match {
          case BranchOp => commitNow = Some(h)
          case _ => h.result match {
            case Some(r) if r.exc.nonEmpty => trapNow = true
            case Some(_) => if (h.kind == StoreOp) d.commit = Some(h.s); commitNow = Some(h)
            case None => if (h.hxSeen && !h.granted) { d.grant = Some(h.s); h.granted = true }
          }
        }
      }
      // Recovery.
      if (trapNow) d.event = Some((AR, head.get.s))
      else if (!draining && rnd.nextInt(30) == 0) {
        val brs = live.filter(o => o.kind == BranchOp && o.allocated && !commitNow.contains(o))
        if (brs.nonEmpty) d.event = Some((BM, brs(rnd.nextInt(brs.size)).s))
      }
      // Allocation (never in an event cycle) and AGU.
      if (d.event.isEmpty) {
        if (pending.isEmpty && live.size < 12 && !draining) pending = Some(newOp())
        pending.foreach { o =>
          if (o.kind == BranchOp) { o.allocated = true; live += o; pending = None }
          else d.alloc = Some(Alloc(o.s, o.kind == LoadOp, o.size, o.signed, o.prd))
        }
        val cand = live.filter(o => o.allocated && o.kind != BranchOp && o.addrSent < 0)
        if (cand.nonEmpty && rnd.nextBoolean()) {
          val o = cand(rnd.nextInt(cand.size)); d.agu = Some(Agu(o.s, o.vaddr, o.data, o.size))
        }
      }
      val resBefore = d.results.size; val ucBefore = d.ucLoads.size + d.ucStores.size
      d.cycle()
      trace += s"cyc ${d.cyc} alloc=${d.alloc.map(_.s)}/${d.allocFire} agu=${d.agu.map(_.s)}/${d.aguFire} ev=${d.event.map(x => (x._1.litValue, x._2))} commit=${commitNow.map(_.s)} fire=${d.commitFire} live=${live.map(o => (o.s, o.kind.toString.take(1))).mkString(",")} res=${d.results.drop(resBefore).map(r => (r.s, r.hx))}"
      if (d.allocFire) { val o = pending.get; o.allocated = true; live += o; pending = None }
      if (d.aguFire) live.find(_.s == d.agu.get.s).foreach(_.addrSent = d.cyc)
      d.results.drop(resBefore).foreach { r =>
        live.find(o => o.s == r.s && o.kind != BranchOp) match {
          case None => bad :+= s"cycle ${r.cyc}: result for a dead uop s${r.s}"
          case Some(o) =>
            if (o.addrSent < 0) bad :+= s"cycle ${r.cyc}: result for s${o.s} before its address (wrong-path result)"
            if (r.hx) { if (o.hxSeen) bad :+= s"s${o.s}: second headExecute"; o.hxSeen = true }
            else { if (o.result.nonEmpty) bad :+= s"s${o.s}: second completion"; o.result = Some(r) }
        }
      }
      if ((d.ucLoads.size + d.ucStores.size) > ucBefore) head.foreach { h =>
        perfCount(h.s) += 1
        if (!h.granted) bad :+= s"s${h.s}: uncached access before its grant"
      }
      // The retirement of this cycle (it precedes, and never conflicts with, a same-cycle event).
      commitNow.foreach { h =>
        val fired = h.kind != StoreOp || d.commitFire
        if (fired) {
          h.kind match {
            case LoadOp =>
              val pa = d.paddrOf(h.vaddr).get._2
              val exp = extract(golden(pa >>> 2), pa, h.size, h.signed)
              val got = h.result.get
              if (!got.wen || got.data != exp || got.prd != h.prd) bad :+= f"s${h.s}: load ${h.vaddr}%x -> ${got.data}%x expected $exp%x"
              loadsChecked += 1
            case StoreOp =>
              val pa = d.paddrOf(h.vaddr).get._2
              golden(pa >>> 2) = merge(golden(pa >>> 2), laneData(h.data, pa), maskOf(h.size, pa))
            case BranchOp =>
          }
          live -= h; committed += 1
        }
      }
      d.event.foreach { case (k, s) =>
        if (k == AR) { live.clear(); traps += 1 }
        else { live --= live.filter(o => o.s > s); mispredicts += 1 }
        nextS = s + 1
        pending = None
      }
    }
    // Let the StoreBuffer drain, then every committed store is in memory and nothing else is.
    d.commit = None; d.grant = None; d.event = None; d.alloc = None; d.agu = None
    d.robHead = None; d.robNext = nextS; d.sbReady = true; d.memResReady = true
    d.until(200)(d.sb.isEmpty)
    val memBad = golden.keys.filter(w => d.mem(w) != golden(w)).take(3).map(w => f"word ${w << 2}%x: ${d.mem(w)}%x vs ${golden(w)}%x")
    Seq(
      chk(committed >= target, s"the model program commits $target uops (no deadlock)", s"committed $committed in $cycles cycles"),
      chk(bad.isEmpty, "every load returns its sequential value; no wrong-path, duplicate, or ungranted result/access", bad.take(4).mkString("; ")),
      chk(memBad.isEmpty, "memory holds exactly the committed stores (no wrong-path store visible)", memBad.mkString("; ")),
      chk(perfCount.values.forall(_ == 1), "each uncached uop reaches the bus exactly once", s"${perfCount.filter(_._2 != 1)}"),
      chk(loadsChecked > target / 4 && traps > 0 && mispredicts > 0, "the stream exercised loads, traps, and mispredicts",
        s"loads $loadsChecked traps $traps mispredicts $mispredicts")
    )
  }

  val model = new SpecTest("lsq.model", Seq("funcLoadIssue", "funcStoreToLoadForward", "funcLsqRecovery",
      "propPhysicalOrderingAuthority", "propNoWrongPathStoreVisible", "propWrongPathLoadNoResult", "propUncachedPerformedOnce")) {
    def run(): Seq[TCheck] = Seq(11, 23).flatMap(seed => withDrv(this, seed)(d => try modelRun(d, seed, 300) catch { case e: Throwable => lastTrace.takeRight(60).foreach(println); throw e }))
  }

  val all: Seq[SpecTest] = Seq(allocate, addressCapture, translationWait, refillRace, staleAnswer, disambig, loadIssue, forward, loadComplete,
    storeComplete, uncached, storeCommit, recovery, model)
}
