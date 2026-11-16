package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.core.design.modules.DataCache
import udacore.core.design.shared.{
  CacheParams,
  CoreContractParams,
  CoreParams,
  DataMemOp,
  LoadStatus,
  MaintOp,
  TranslationStatus
}

/** Directed L1 checks for the DataCache physical and translated read paths. */
object DataCacheSpecTests {
  val sramStructure        =
    new SpecTest("dc.sramStructure", Seq("funcDCacheSramStorage")) {
      def run(): Seq[TCheck] = {
        val chirrtl = circt.stage.ChiselStage.emitCHIRRTL(new DataCache(cp))
        Seq(
          TCheck(
            chirrtl.contains("inst lines of CacheLineSram"),
            "the cache line-data array is the synchronous SRAM vertex",
            "DataCache must instantiate CacheLineSram as lines instead of an asynchronous Mem"
          )
        )
      }
    }
  val cp                   = CoreParams()
  val lineBytes            = cp.contract.dcache.blockBytes
  val lineWords            = lineBytes / (cp.dataWidth / 8)
  def op(x: UInt): Int     = x.litValue.toInt
  def word(pa: Long): Long = ((pa >> 2) * 1103515245L + 12345L) & 0xffffffffL

  case class L(idx: Int, gen: Int, va: Long, size: Int)
  case class T(id: Int, status: Int, pa: Long, cacheable: Boolean)
  case class M(
      opcode: Int,
      source: Int,
      pa: Long,
      size: Int,
      data: Long,
      mask: Int,
      last: Boolean
  )
  case class D(source: Int, data: Long, last: Boolean, denied: Boolean)
  case class R(idx: Int, gen: Int, status: Int, pa: Long, data: Long)
  case class P(data: Long, fault: Boolean)

  class Drv(val dut: DataCache) {
    val io                                       = dut.io
    var cyc                                      = 0
    var load: Option[L]                          = None
    var tr: Option[T]                            = None
    var ptw: Option[Long]                        = None
    var uncLoad: Option[(Int, Int, Long, Int)]   = None
    var uncStore: Option[(Int, Long, Long, Int)] = None
    var store: Option[(Long, Long, Int)]         = None
    var clean                                    = false
    var memReady                                 = true
    var memPermit                                = true
    var loadRespReady                            = true
    var deny: Long => Boolean                    = _ => false
    val memQ                                     = Queue[D]()
    val memReqs                                  = ArrayBuffer[M]()
    val loadResps                                = ArrayBuffer[R]()
    val ptwResps                                 = ArrayBuffer[P]()
    val uncLoadResps                             = ArrayBuffer[(Int, Int, Long, Boolean)]()
    val uncStoreResps                            = ArrayBuffer[(Int, Boolean)]()
    var loadFire                                 = false
    var trFire                                   = false
    var ptwFire                                  = false
    var uncLoadFire                              = false
    var uncStoreFire                             = false
    var storeFire                                = false
    var cleanFire                                = false
    var storeResponses                           = 0
    var cleanResponses                           = 0
    var loadOffered                              = false

    def cycle(): Unit                                   = {
      val lq        = io.dCacheLoadReqIn
      lq.valid.poke(load.nonEmpty.B)
      load.foreach { x =>
        lq.bits.lqIdx.poke(x.idx.U); lq.bits.lqGen.poke(x.gen.U)
        lq.bits.vaddr.poke(x.va.U); lq.bits.size.poke(x.size.U)
      }
      val tq        = io.dCacheTranslationIn
      tq.valid.poke(tr.nonEmpty.B)
      tr.foreach { x =>
        tq.bits.reqId.poke(x.id.U); tq.bits.status.poke(x.status.U)
        tq.bits.paddr.poke(x.pa.U); tq.bits.cacheable.poke(x.cacheable.B)
      }
      io.ptwMemReqIn.valid.poke(ptw.nonEmpty.B)
      ptw.foreach(x => io.ptwMemReqIn.bits.paddr.poke(x.U))
      io.uncachedLoadReqIn.valid.poke(uncLoad.nonEmpty.B)
      uncLoad.foreach { case (idx, gen, pa, size) =>
        val b = io.uncachedLoadReqIn.bits
        b.lqIdx.poke(idx.U); b.lqGen.poke(gen.U); b.paddr.poke(pa.U);
        b.size.poke(size.U)
      }
      io.uncachedStoreReqIn.valid.poke(uncStore.nonEmpty.B)
      uncStore.foreach { case (idx, pa, data, mask) =>
        val b = io.uncachedStoreReqIn.bits
        b.sqIdx.poke(idx.U); b.paddr.poke(pa.U); b.data.poke(data.U);
        b.mask.poke(mask.U)
      }
      io.storeDrainReqIn.valid.poke(store.nonEmpty.B)
      store.foreach { case (pa, data, mask) =>
        val b = io.storeDrainReqIn.bits
        b.paddr.poke(pa.U); b.data.poke(data.U); b.mask.poke(mask.U)
      }
      io.dCacheCleanReqIn.valid.poke(clean.B)
      io.dCacheCleanReqIn.bits.op.poke(MaintOp.DCacheCleanAll)
      io.dCacheLoadRespOut.ready.poke(loadRespReady.B)
      io.ptwMemRespOut.ready.poke(true.B)
      io.uncachedLoadRespOut.ready.poke(true.B)
      io.uncachedStoreRespOut.ready.poke(true.B)
      io.storeDrainRespOut.ready.poke(true.B)
      io.dCacheCleanRespOut.ready.poke(true.B)
      io.dataMemReqOut.ready.poke(memReady.B)
      val beatValid = memPermit && memQ.nonEmpty
      io.dataMemRespIn.valid.poke(beatValid.B)
      memQ.headOption.foreach { x =>
        val b = io.dataMemRespIn.bits
        b.source.poke(x.source.U); b.data.poke(x.data.U)
        b.last.poke(x.last.B); b.denied.poke(x.denied.B)
      }

      loadFire = load.nonEmpty && lq.ready.peek().litToBoolean
      trFire = tr.nonEmpty && tq.ready.peek().litToBoolean
      ptwFire = ptw.nonEmpty && io.ptwMemReqIn.ready.peek().litToBoolean
      uncLoadFire =
        uncLoad.nonEmpty && io.uncachedLoadReqIn.ready.peek().litToBoolean
      uncStoreFire =
        uncStore.nonEmpty && io.uncachedStoreReqIn.ready.peek().litToBoolean
      storeFire = store.nonEmpty && io.storeDrainReqIn.ready.peek().litToBoolean
      cleanFire = clean && io.dCacheCleanReqIn.ready.peek().litToBoolean

      val lm = io.dCacheLoadRespOut
      loadOffered = lm.valid.peek().litToBoolean
      if (loadOffered && loadRespReady) {
        val b = lm.bits
        loadResps += R(
          b.lqIdx.peek().litValue.toInt,
          b.lqGen.peek().litValue.toInt,
          b.status.peek().litValue.toInt,
          b.paddr.peek().litValue.toLong,
          b.data.peek().litValue.toLong
        )
      }
      val pm = io.ptwMemRespOut
      if (pm.valid.peek().litToBoolean) {
        val p     = pm.bits.pte
        val flags = Seq(p.d, p.a, p.g, p.u, p.x, p.w, p.r, p.v)
          .foldLeft(0L)((bits, bit) =>
            (bits << 1) | (if (bit.peek().litToBoolean) 1L else 0L)
          )
        val raw   = (p.ppn1.peek().litValue.toLong << 20) |
          (p.ppn0.peek().litValue.toLong << 10) |
          (p.rsw.peek().litValue.toLong << 8) | flags
        ptwResps += P(raw, pm.bits.accessFault.peek().litToBoolean)
      }
      val ul = io.uncachedLoadRespOut
      if (ul.valid.peek().litToBoolean) {
        val b = ul.bits
        uncLoadResps += ((
          b.lqIdx.peek().litValue.toInt,
          b.lqGen.peek().litValue.toInt,
          b.data.peek().litValue.toLong,
          b.accessFault.peek().litToBoolean
        ))
      }
      val us = io.uncachedStoreRespOut
      if (us.valid.peek().litToBoolean)
        uncStoreResps += ((
          us.bits.sqIdx.peek().litValue.toInt,
          us.bits.accessFault.peek().litToBoolean
        ))
      if (io.storeDrainRespOut.valid.peek().litToBoolean) storeResponses += 1
      if (io.dCacheCleanRespOut.valid.peek().litToBoolean) cleanResponses += 1

      val mr = io.dataMemReqOut
      if (mr.valid.peek().litToBoolean && memReady) {
        val b = mr.bits
        val m = M(
          b.op.peek().litValue.toInt,
          b.source.peek().litValue.toInt,
          b.paddr.peek().litValue.toLong,
          b.size.peek().litValue.toInt,
          b.data.peek().litValue.toLong,
          b.mask.peek().litValue.toInt,
          b.last.peek().litToBoolean
        )
        memReqs += m
        if (m.opcode == op(DataMemOp.GetLine)) {
          (0 until lineWords).foreach(i =>
            memQ.enqueue(
              D(m.source, word(m.pa + 4L * i), i == lineWords - 1, deny(m.pa))
            )
          )
        } else if (m.opcode == op(DataMemOp.GetUncached)) {
          memQ.enqueue(D(m.source, word(m.pa & ~3L), true, deny(m.pa)))
        } else if (
          m.opcode == op(DataMemOp.PutUncached) ||
          (m.opcode == op(DataMemOp.PutLine) && m.last)
        ) {
          memQ.enqueue(D(m.source, 0, true, deny(m.pa)))
        }
      }
      if (beatValid && io.dataMemRespIn.ready.peek().litToBoolean)
        memQ.dequeue()
      dut.clock.step(); cyc += 1
    }
    def run(n: Int): Unit                               = (0 until n).foreach(_ => cycle())
    def until(done: => Boolean, limit: Int = 300): Unit = {
      var k = 0
      while (!done && k < limit) { cycle(); k += 1 }
    }
    def sendLoad(x: L, t: T): Unit                      = {
      load = Some(x); loadFire = false; until(loadFire); load = None
      tr = Some(t); trFire = false; until(trFire); tr = None
    }
    def sendPtw(pa: Long): Unit                         = {
      ptw = Some(pa); ptwFire = false; until(ptwFire); ptw = None
    }
  }

  def chk(ok: Boolean, label: String, detail: => String): TCheck  =
    TCheck(ok, label, if (ok) "" else detail)
  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new DataCache(cp)) { dut => body(new Drv(dut)) }
  def rid(idx: Int, gen: Int): Int                                = (idx << cp.lsq.generationWidth) | gen

  val loadFill = new SpecTest(
    "dc.loadFill",
    Seq(
      "funcDCacheViptLookup",
      "funcDCacheMshr",
      "propDCacheLoadAnswerExactlyOnce"
    )
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val va            = 0x40002040L; val pa = 0x2040L
      d.memPermit = false
      d.sendLoad(
        L(0, 0, va, 2),
        T(rid(0, 0), op(TranslationStatus.Hit), pa, true)
      )
      d.run(4)
      val noEarlyAnswer = d.loadResps.isEmpty
      d.memPermit = true
      d.until(d.loadResps.size == 1)
      d.sendLoad(
        L(1, 1, va + 4, 2),
        T(rid(1, 1), op(TranslationStatus.Hit), pa + 4, true)
      )
      d.until(d.loadResps.size == 2); d.run(3)
      Seq(
        chk(
          noEarlyAnswer,
          "a cacheable miss waits for fill data",
          s"${d.loadResps}"
        ),
        chk(
          d.memReqs.count(_.opcode == op(DataMemOp.GetLine)) == 1 &&
            d.memReqs.headOption.exists(m =>
              m.pa == (pa & ~(lineBytes - 1L)) && m.size == 6
            ),
          "a cold load fills one aligned line and a second load hits",
          s"${d.memReqs}"
        ),
        chk(
          d.loadResps.toSeq == Seq(
            R(0, 0, op(LoadStatus.Data), pa, word(pa)),
            R(1, 1, op(LoadStatus.Data), pa + 4, word(pa + 4))
          ),
          "each load gets its own tagged naturally aligned word exactly once",
          s"${d.loadResps}"
        )
      )
    }
  }

  val ptwPhysical =
    new SpecTest("dc.ptwPhysical", Seq("funcDCachePhysicalRead")) {
      def run(): Seq[TCheck] = withDrv(this) { d =>
        val cached   = 0x2040L; val device = 0xf0000040L;
        val unmapped = 0x100000040L
        d.sendPtw(cached); d.until(d.ptwResps.size == 1)
        d.sendPtw(cached); d.until(d.ptwResps.size == 2)
        d.sendPtw(device); d.until(d.ptwResps.size == 3)
        d.sendPtw(device); d.until(d.ptwResps.size == 4)
        d.sendPtw(unmapped); d.until(d.ptwResps.size == 5)
        Seq(
          chk(
            d.ptwResps.take(4).toSeq == Seq(
              P(word(cached), false),
              P(word(cached), false),
              P(word(device), false),
              P(word(device), false)
            ) &&
              d.ptwResps.size == 5 && d.ptwResps.last.fault,
            "PTE reads use physical cached, uncached, and unmapped paths",
            s"${d.ptwResps}"
          ),
          chk(
            d.memReqs.map(m => (m.opcode, m.pa, m.size)).toSeq == Seq(
              (op(DataMemOp.GetLine), cached & ~(lineBytes - 1L), 6),
              (op(DataMemOp.GetUncached), device, 2),
              (op(DataMemOp.GetUncached), device, 2)
            ),
            "a cache hit avoids a second fill, while readable device PTE reads never install",
            s"${d.memReqs}"
          )
        )
      }
    }

  val portPriority =
    new SpecTest("dc.portPriority", Seq("funcDCachePortArbitrate")) {
      def run(): Seq[TCheck] = withDrv(this) { d =>
        val pa           = 0x2040L
        d.sendLoad(
          L(0, 0, pa, 2),
          T(rid(0, 0), op(TranslationStatus.Hit), pa, true)
        )
        d.until(d.loadResps.size == 1)
        d.load = Some(L(1, 0, pa, 2)); d.cycle()
        val loadAccepted = d.loadFire
        d.load = None
        d.tr = Some(T(rid(1, 0), op(TranslationStatus.Hit), pa, true))
        d.store = Some((pa, 0xdeadbeefL, 15))
        d.ptw = Some(pa + 4)
        d.cycle()
        val first        = (d.ptwFire, d.storeFire, d.trFire)
        d.ptw = None
        d.cycle()
        val second       = (d.storeFire, d.trFire)
        d.store = None
        d.cycle()
        val third        = d.trFire
        d.tr = None
        d.until(d.loadResps.size == 2)
        Seq(
          chk(
            loadAccepted && first == ((true, false, false)) &&
              second == ((true, false)) && third,
            "a pending lookup waits behind a simultaneous PTE read and store drain",
            s"load=$loadAccepted first=$first second=$second third=$third"
          ),
          chk(
            d.ptwResps.size == 1 && d.storeResponses == 1 &&
              d.loadResps.lastOption
                .contains(R(1, 0, op(LoadStatus.Data), pa, 0xdeadbeefL)),
            "each request completes and the load sees the prior committed store",
            s"ptw=${d.ptwResps} store=${d.storeResponses} load=${d.loadResps}"
          )
        )
      }
    }

  val uncached = new SpecTest("dc.uncached", Seq("funcDCacheUncached")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa           = 0xf0000040L
      d.memPermit = false
      d.uncLoad = Some((2, 1, pa, 2)); d.until(d.uncLoadFire); d.uncLoad = None
      d.run(4)
      val noEarlyLoad  = d.uncLoadResps.isEmpty
      d.memPermit = true
      d.until(d.uncLoadResps.size == 1)
      d.uncLoad = Some((2, 2, pa, 2)); d.uncLoadFire = false
      d.until(d.uncLoadFire); d.uncLoad = None
      d.until(d.uncLoadResps.size == 2)
      d.memPermit = false
      d.uncStore = Some((3, pa, 0x12345678L, 15)); d.until(d.uncStoreFire);
      d.uncStore = None
      d.run(4)
      val noEarlyStore = d.uncStoreResps.isEmpty
      d.memPermit = true
      d.until(d.uncStoreResps.size == 1)
      d.deny = _ => true
      d.uncLoad = Some((4, 0, pa, 2)); d.uncLoadFire = false
      d.until(d.uncLoadFire); d.uncLoad = None
      d.until(d.uncLoadResps.size == 3)
      d.uncStore = Some((5, pa, 0x55667788L, 15)); d.uncStoreFire = false
      d.until(d.uncStoreFire); d.uncStore = None
      d.until(d.uncStoreResps.size == 2)
      Seq(
        chk(
          noEarlyLoad && noEarlyStore,
          "uncached answers wait for their bus acknowledgement",
          s"loadEarly=$noEarlyLoad storeEarly=$noEarlyStore"
        ),
        chk(
          d.memReqs.map(_.opcode).toSeq == Seq(
            op(DataMemOp.GetUncached),
            op(DataMemOp.GetUncached),
            op(DataMemOp.PutUncached),
            op(DataMemOp.GetUncached),
            op(DataMemOp.PutUncached)
          ) && d.memReqs.forall(_.pa == pa),
          "each uncached access bypasses the array as an exact physical bus access",
          s"${d.memReqs}"
        ),
        chk(
          d.uncLoadResps.toSeq == Seq(
            (2, 1, word(pa), false),
            (2, 2, word(pa), false),
            (4, 0, word(pa), true)
          ) &&
            d.uncStoreResps.toSeq == Seq((3, false), (5, true)),
          "uncached acknowledgements retain LSQ identities and denied access faults",
          s"load=${d.uncLoadResps} store=${d.uncStoreResps}"
        )
      )
    }
  }

  val twoMshrs =
    new SpecTest("dc.twoMshrs", Seq("funcDCacheMshr", "funcDCacheViptLookup")) {
      def run(): Seq[TCheck] = withDrv(this) { d =>
        val resident    = 0x3000L
        d.sendLoad(
          L(0, 0, resident, 2),
          T(rid(0, 0), op(TranslationStatus.Hit), resident, true)
        )
        d.until(d.loadResps.size == 1)
        d.memPermit = false
        val miss0       = 0x4000L; val miss1 = 0x4040L
        d.sendLoad(
          L(1, 0, miss0, 2),
          T(rid(1, 0), op(TranslationStatus.Hit), miss0, true)
        )
        d.sendLoad(
          L(2, 0, miss1, 2),
          T(rid(2, 0), op(TranslationStatus.Hit), miss1, true)
        )
        d.run(5)
        val getsBeforeD =
          d.memReqs.filter(_.opcode == op(DataMemOp.GetLine)).toSeq
        d.sendLoad(
          L(3, 0, resident, 2),
          T(rid(3, 0), op(TranslationStatus.Hit), resident, true)
        )
        d.until(d.loadResps.size == 2)
        val hitBeforeD  = d.loadResps.lastOption.contains(
          R(3, 0, op(LoadStatus.Data), resident, word(resident))
        )
        d.memPermit = true
        d.until(d.loadResps.size == 4)
        Seq(
          chk(
            getsBeforeD.size == 3 && getsBeforeD
              .takeRight(2)
              .map(_.source)
              .distinct
              .size == 2,
            "two different lines use two MSHRs before either D response transfers",
            s"${getsBeforeD}"
          ),
          chk(
            hitBeforeD && d.loadResps.toSet == Set(
              R(0, 0, op(LoadStatus.Data), resident, word(resident)),
              R(1, 0, op(LoadStatus.Data), miss0, word(miss0)),
              R(2, 0, op(LoadStatus.Data), miss1, word(miss1)),
              R(3, 0, op(LoadStatus.Data), resident, word(resident))
            ),
            "a resident hit completes while two fills are outstanding",
            s"${d.loadResps}"
          )
        )
      }
    }

  val sameSetMshrs = new SpecTest(
    "dc.sameSetMshrs",
    Seq("funcDCacheMshr", "funcDCacheViptLookup")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa0          = 0x10000L; val pa1 = 0x11000L
      d.memPermit = false
      d.sendLoad(
        L(0, 0, pa0, 2),
        T(rid(0, 0), op(TranslationStatus.Hit), pa0, true)
      )
      d.sendLoad(
        L(1, 0, pa1, 2),
        T(rid(1, 0), op(TranslationStatus.Hit), pa1, true)
      )
      d.run(4)
      val simultaneous = d.memReqs.count(_.opcode == op(DataMemOp.GetLine)) == 2
      d.memPermit = true
      d.until(d.loadResps.size == 2)
      d.sendLoad(
        L(2, 0, pa0, 2),
        T(rid(2, 0), op(TranslationStatus.Hit), pa0, true)
      )
      d.until(d.loadResps.size == 3)
      d.sendLoad(
        L(3, 0, pa1, 2),
        T(rid(3, 0), op(TranslationStatus.Hit), pa1, true)
      )
      d.until(d.loadResps.size == 4)
      Seq(
        chk(
          simultaneous && d.memReqs.count(
            _.opcode == op(DataMemOp.GetLine)
          ) == 2,
          "two same-set fills reserve different ways and both remain resident",
          s"requests=${d.memReqs} responses=${d.loadResps}"
        ),
        chk(
          d.loadResps.toSet == Set(
            R(0, 0, op(LoadStatus.Data), pa0, word(pa0)),
            R(1, 0, op(LoadStatus.Data), pa1, word(pa1)),
            R(2, 0, op(LoadStatus.Data), pa0, word(pa0)),
            R(3, 0, op(LoadStatus.Data), pa1, word(pa1))
          ),
          "every same-set load keeps its physical line identity",
          s"${d.loadResps}"
        )
      )
    }
  }

  val interleavedFills =
    new SpecTest("dc.interleavedFills", Seq("funcDCacheMshr")) {
      def run(): Seq[TCheck] = withDrv(this) { d =>
        val pa0     = 0x30040L; val pa1 = 0x40080L
        d.memPermit = false
        d.sendLoad(
          L(0, 0, pa0, 2),
          T(rid(0, 0), op(TranslationStatus.Hit), pa0, true)
        )
        d.sendLoad(
          L(1, 0, pa1, 2),
          T(rid(1, 0), op(TranslationStatus.Hit), pa1, true)
        )
        d.run(4)
        val beats   = d.memQ.dequeueAll(_ => true).toSeq
        val source0 = beats.filter(_.source == 0)
        val source1 = beats.filter(_.source == 1)
        (0 until lineWords).foreach { i =>
          if (i < source0.size) d.memQ.enqueue(source0(i))
          if (i < source1.size) d.memQ.enqueue(source1(i))
        }
        d.memPermit = true
        d.until(d.loadResps.size == 2)
        Seq(
          chk(
            source0.size == lineWords && source1.size == lineWords,
            "two fills have independent source IDs and full response bursts",
            s"${beats}"
          ),
          chk(
            d.loadResps.toSet == Set(
              R(0, 0, op(LoadStatus.Data), pa0, word(pa0)),
              R(1, 0, op(LoadStatus.Data), pa1, word(pa1))
            ),
            "interleaved D beats assemble the correct line for each source",
            s"${d.loadResps}"
          )
        )
      }
    }

  val ptwJoinsFill = new SpecTest(
    "dc.ptwJoinsFill",
    Seq("funcDCachePhysicalRead", "funcDCacheMshr")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa              = 0x50040L
      d.memPermit = false
      d.sendLoad(
        L(0, 0, pa, 2),
        T(rid(0, 0), op(TranslationStatus.Hit), pa, true)
      )
      d.sendPtw(pa + 4)
      d.run(4)
      val requestsBeforeD = d.memReqs.toSeq
      d.memPermit = true
      d.until(d.loadResps.size == 1 && d.ptwResps.size == 1)
      Seq(
        chk(
          requestsBeforeD.count(_.opcode == op(DataMemOp.GetLine)) == 1,
          "a physical PTE read joins an existing cacheable line fill",
          s"${requestsBeforeD}"
        ),
        chk(
          d.loadResps.toSeq == Seq(
            R(0, 0, op(LoadStatus.Data), pa, word(pa))
          ) &&
            d.ptwResps.toSeq == Seq(P(word(pa + 4), false)),
          "the joined load and PTE keep distinct response identities",
          s"load=${d.loadResps} ptw=${d.ptwResps}"
        )
      )
    }
  }

  val targetLimit = new SpecTest(
    "dc.targetLimit",
    Seq("funcDCacheMshr", "propDCacheLoadAnswerExactlyOnce")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa     = 0x5040L
      d.memPermit = false
      (0 until 3).foreach { i =>
        d.sendLoad(
          L(i, 0, pa + 4L * i, 2),
          T(rid(i, 0), op(TranslationStatus.Hit), pa + 4L * i, true)
        )
      }
      d.until(d.loadResps.nonEmpty)
      val replay = d.loadResps.size == 1 && d.loadResps.head.idx == 2 &&
        d.loadResps.head.gen == 0 && d.loadResps.head.status == op(
          LoadStatus.Replay
        ) &&
        d.loadResps.head.pa == pa + 8
      val oneGet = d.memReqs.count(_.opcode == op(DataMemOp.GetLine)) == 1
      d.memPermit = true
      d.until(d.loadResps.size == 3)
      Seq(
        chk(
          replay && oneGet,
          "a third load target for one outstanding line replays without another fill",
          s"responses=${d.loadResps} requests=${d.memReqs}"
        ),
        chk(
          d.loadResps.size == 3 && d.loadResps.drop(1).toSet == Set(
            R(0, 0, op(LoadStatus.Data), pa, word(pa)),
            R(1, 0, op(LoadStatus.Data), pa + 4, word(pa + 4))
          ),
          "each accepted target is answered once after the shared fill",
          s"${d.loadResps}"
        )
      )
    }
  }

  val storeVisibility = new SpecTest(
    "dc.storeVisibility",
    Seq(
      "funcDCacheStoreWrite",
      "funcDCacheMshr",
      "propDrainResponseMakesStoreVisible"
    )
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa                 = 0x6080L; val data = 0xaabbccddL; val mask = 5
      d.memPermit = false
      d.sendLoad(
        L(0, 0, pa, 2),
        T(rid(0, 0), op(TranslationStatus.Hit), pa, true)
      )
      d.store = Some((pa, data, mask)); d.storeFire = false
      d.until(d.storeFire); d.store = None
      d.until(d.storeResponses == 1)
      val responseBeforeFill = d.storeResponses == 1 && d.loadResps.isEmpty
      d.memPermit = true
      d.until(d.loadResps.size == 1)
      val byteMask           = 0x00ff00ffL
      val merged             = (word(pa) & ~byteMask) | (data & byteMask)
      Seq(
        chk(
          responseBeforeFill,
          "the committed store drain may complete before the line fill",
          s"store=${d.storeResponses} loads=${d.loadResps}"
        ),
        chk(
          d.loadResps.toSeq == Seq(R(0, 0, op(LoadStatus.Data), pa, merged)),
          "a later load answer includes every byte of the already completed partial store",
          s"${d.loadResps}, expected=$merged"
        )
      )
    }
  }

  val heldLoadVisibility = new SpecTest(
    "dc.heldLoadVisibility",
    Seq("propDrainResponseMakesStoreVisible")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa                  = 0x6800L; val data = 0xaabbccddL; val mask = 5
      d.loadRespReady = false
      d.sendLoad(
        L(0, 0, pa, 2),
        T(rid(0, 0), op(TranslationStatus.Hit), pa, true)
      )
      d.until(d.loadOffered)
      val held                = d.loadOffered && d.loadResps.isEmpty
      d.store = Some((pa, data, mask)); d.storeFire = false
      d.until(d.storeFire); d.store = None
      d.until(d.storeResponses == 1)
      val drainBeforeTransfer = d.storeResponses == 1 && d.loadResps.isEmpty
      d.loadRespReady = true
      d.until(d.loadResps.size == 1)
      val byteMask            = 0x00ff00ffL
      val merged              = (word(pa) & ~byteMask) | (data & byteMask)
      Seq(
        chk(
          held && drainBeforeTransfer,
          "a store drain completes while an older load answer is held by backpressure",
          s"held=$held drainBeforeTransfer=$drainBeforeTransfer"
        ),
        chk(
          d.loadResps.toSeq == Seq(R(0, 0, op(LoadStatus.Data), pa, merged)),
          "the later load transfer includes store bytes after StoreDrainResp",
          s"${d.loadResps}, expected=$merged"
        )
      )
    }
  }

  val sramReadStoreOrdering = new SpecTest(
    "dc.sramReadStoreOrdering",
    Seq("funcDCacheSramStorage", "propDrainResponseMakesStoreVisible")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa                 = 0x6c00L
      d.sendLoad(
        L(0, 0, pa, 2),
        T(rid(0, 0), op(TranslationStatus.Hit), pa, true)
      )
      d.until(d.loadResps.size == 1)
      d.loadRespReady = false
      d.sendLoad(
        L(1, 1, pa, 2),
        T(rid(1, 1), op(TranslationStatus.Hit), pa, true)
      )
      // Offer the store immediately after the hit's SRAM read is launched, before its
      // delayed data has necessarily reached the ordinary load-response holder.
      d.store = Some((pa, 0xaabbccddL, 5)); d.storeFire = false
      d.until(d.storeFire); d.store = None
      d.until(d.storeResponses == 1)
      val completedWhileHeld = d.storeResponses == 1 && d.loadResps.size == 1
      d.loadRespReady = true
      d.until(d.loadResps.size == 2)
      val merged             = (word(pa) & ~0x00ff00ffL) | 0x00bb00ddL
      Seq(
        chk(
          completedWhileHeld,
          "an overlapping store completes while the SRAM load answer waits for its consumer",
          s"store=${d.storeResponses} loads=${d.loadResps}"
        ),
        chk(
          d.loadResps.lastOption
            .contains(R(1, 1, op(LoadStatus.Data), pa, merged)),
          "a load crossing the SRAM pipeline includes all already-completed store bytes",
          s"loads=${d.loadResps}, expected=$merged"
        )
      )
    }
  }

  val completedFillStoreVisibility = new SpecTest(
    "dc.completedFillStoreVisibility",
    Seq("funcDCacheSramStorage", "propDrainResponseMakesStoreVisible")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa                 = 0x6d00L
      d.loadRespReady = false
      d.memPermit = false
      d.sendLoad(
        L(0, 0, pa, 2),
        T(rid(0, 0), op(TranslationStatus.Hit), pa, true)
      )
      d.sendLoad(
        L(1, 1, pa + 4, 2),
        T(rid(1, 1), op(TranslationStatus.Hit), pa + 4, true)
      )
      d.memPermit = true
      d.run(lineWords + 12)
      val firstHeld          = d.loadOffered && d.loadResps.isEmpty
      d.store = Some((pa + 4, 0xaabbccddL, 5)); d.storeFire = false
      d.until(d.storeFire); d.store = None
      d.until(d.storeResponses == 1)
      val drainBeforeTargets = d.storeResponses == 1 && d.loadResps.isEmpty
      d.loadRespReady = true
      d.until(d.loadResps.size == 2)
      val merged             = (word(pa + 4) & ~0x00ff00ffL) | 0x00bb00ddL
      Seq(
        chk(
          firstHeld && drainBeforeTargets,
          "a completed line still has an unanswered target when the partial store completes",
          s"held=$firstHeld drainBeforeTargets=$drainBeforeTargets"
        ),
        chk(
          d.loadResps.toSeq == Seq(
            R(0, 0, op(LoadStatus.Data), pa, word(pa)),
            R(1, 1, op(LoadStatus.Data), pa + 4, merged)
          ),
          "completed MSHR targets include committed bytes even after the fill has installed",
          s"responses=${d.loadResps}, expected second word=$merged"
        )
      )
    }
  }

  val cleanAll = new SpecTest(
    "dc.cleanAll",
    Seq("funcDCacheStoreWrite", "funcDCacheCleanAll", "funcDCacheWriteback")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa                = 0x7004L; val base = pa & ~(lineBytes - 1L)
      d.store = Some((pa, 0xdeadbeefL, 15)); d.storeFire = false
      d.until(d.storeFire); d.store = None
      d.until(d.storeResponses == 1)
      d.clean = true; d.cleanFire = false; d.until(d.cleanFire); d.clean = false
      d.until(d.cleanResponses == 1)
      val firstWrites       =
        d.memReqs.filter(_.opcode == op(DataMemOp.PutLine)).toSeq
      d.clean = true; d.cleanFire = false; d.until(d.cleanFire); d.clean = false
      d.until(d.cleanResponses == 2)
      d.run(3)
      val writesAfterSecond = d.memReqs.count(_.opcode == op(DataMemOp.PutLine))
      Seq(
        chk(
          firstWrites.size == lineWords && firstWrites.zipWithIndex.forall {
            case (m, i) =>
              m.source == cp.contract.dcache.mshrs && m.pa == base && m.size == 6 &&
              m.last == (i == lineWords - 1) && m.data == (if (i == 1)
                                                             0xdeadbeefL
                                                           else
                                                             word(
                                                               base + 4L * i
                                                             ))
          },
          "clean-all writes each dirty line beat before completion",
          s"${firstWrites}"
        ),
        chk(
          d.cleanResponses == 2 && writesAfterSecond == lineWords,
          "a second clean leaves the valid line clean and issues no second writeback",
          s"responses=${d.cleanResponses} writeBeats=$writesAfterSecond"
        )
      )
    }
  }

  val writebackHazard = new SpecTest(
    "dc.writebackHazard",
    Seq("funcDCacheWriteback", "funcDCacheViptLookup")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val lines       = Seq(0x20000L, 0x21000L, 0x22000L, 0x23000L)
      lines.zipWithIndex.foreach { case (pa, i) =>
        d.store = Some((pa, 0x11110000L + i, 15)); d.storeFire = false
        d.until(d.storeFire); d.store = None
        d.until(d.storeResponses == i + 1); d.run(30)
      }
      d.memPermit = false
      val fifth       = 0x24000L
      d.sendLoad(
        L(5, 0, fifth, 2),
        T(rid(5, 0), op(TranslationStatus.Hit), fifth, true)
      )
      d.until(d.memReqs.count(_.opcode == op(DataMemOp.PutLine)) == lineWords)
      val writeback   = d.memReqs.filter(_.opcode == op(DataMemOp.PutLine)).toSeq
      val victim      = writeback.headOption.map(_.pa).getOrElse(0L)
      d.load = Some(L(6, 0, victim, 2)); d.loadFire = false
      d.until(d.loadFire); d.load = None
      d.tr = Some(T(rid(6, 0), op(TranslationStatus.Hit), victim, true))
      d.trFire = false
      var transferred = false
      (0 until 5).foreach { _ => d.cycle(); transferred ||= d.trFire }
      Seq(
        chk(
          writeback.size == lineWords && writeback.headOption.exists(m =>
            lines.contains(m.pa) && m.source == cp.contract.dcache.mshrs
          ),
          "a dirty victim starts a complete line writeback on the reserved source",
          s"${writeback}"
        ),
        chk(
          !transferred && d.memReqs.count(
            _.opcode == op(DataMemOp.GetLine)
          ) == 4,
          "the evicted physical line cannot be refetched before its writeback acknowledgement",
          s"translationTransferred=$transferred requests=${d.memReqs}"
        )
      )
    }
  }

  val faults = new SpecTest(
    "dc.faults",
    Seq("funcDCacheViptLookup", "funcDCacheMshr", "funcDCachePhysicalRead")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val va         = 0x40008040L; val pa = 0x8040L; val device = 0xf0000080L
      d.sendLoad(
        L(0, 0, va, 2),
        T(rid(0, 0), op(TranslationStatus.PageFault), 0, false)
      )
      d.until(d.loadResps.size == 1)
      d.sendLoad(
        L(1, 0, va, 2),
        T(rid(1, 0), op(TranslationStatus.AccessFault), 0, false)
      )
      d.until(d.loadResps.size == 2)
      val noFaultBus = d.memReqs.isEmpty
      d.deny = x => x == (pa & ~(lineBytes - 1L)) || x == device
      d.sendLoad(
        L(2, 0, va, 2),
        T(rid(2, 0), op(TranslationStatus.Hit), pa, true)
      )
      d.until(d.loadResps.size == 3)
      d.deny = x => x == device
      d.sendLoad(
        L(3, 0, va, 2),
        T(rid(3, 0), op(TranslationStatus.Hit), pa, true)
      )
      d.until(d.loadResps.size == 4)
      d.sendPtw(device); d.until(d.ptwResps.size == 1)
      Seq(
        chk(
          noFaultBus && d.loadResps.take(2).map(_.status).toSeq ==
            Seq(op(LoadStatus.PageFault), op(LoadStatus.AccessFault)),
          "translation faults pass through without an array or bus access",
          s"responses=${d.loadResps} requests=${d.memReqs}"
        ),
        chk(
          d.loadResps(2).status == op(LoadStatus.AccessFault) &&
            d.loadResps(3) == R(3, 0, op(LoadStatus.Data), pa, word(pa)) &&
            d.memReqs.count(_.opcode == op(DataMemOp.GetLine)) == 2,
          "a denied fill installs nothing, so a later load fetches the line again",
          s"responses=${d.loadResps} requests=${d.memReqs}"
        ),
        chk(
          d.ptwResps.size == 1 && d.ptwResps.head.fault,
          "a denied device PTE read reports an access fault",
          s"${d.ptwResps}"
        )
      )
    }
  }

  val smallerGeometry =
    new SpecTest("dc.smallerGeometry", Seq("funcDCacheViptLookup")) {
      def run(): Seq[TCheck] = {
        val alt = CoreParams(contract =
          CoreContractParams(dcache =
            CacheParams(
              sets = 32,
              ways = 4,
              blockBytes = 64,
              mshrs = 2,
              targetsPerMshr = 2
            )
          )
        )
        sim(new DataCache(alt)) { dut =>
          val d   = new Drv(dut)
          val pa0 = 0x10040L; val pa1 = 0x10840L
          Seq(pa0, pa1, pa0, pa1).zipWithIndex.foreach { case (pa, i) =>
            d.sendLoad(
              L(i, 0, pa, 2),
              T(rid(i, 0), op(TranslationStatus.Hit), pa, true)
            )
            d.until(d.loadResps.size == i + 1)
          }
          Seq(
            chk(
              d.memReqs.count(_.opcode == op(DataMemOp.GetLine)) == 2,
              "a smaller legal set count keeps page-offset bits above the set in the physical tag",
              s"${d.memReqs}"
            ),
            chk(
              d.loadResps.toSeq == Seq(pa0, pa1, pa0, pa1).zipWithIndex.map {
                case (pa, i) => R(i, 0, op(LoadStatus.Data), pa, word(pa))
              },
              "both physical tags remain distinct across two hits",
              s"${d.loadResps}"
            )
          )
        }
      }
    }

  val all: Seq[SpecTest] = Seq(
    sramStructure,
    loadFill,
    ptwPhysical,
    portPriority,
    uncached,
    twoMshrs,
    sameSetMshrs,
    interleavedFills,
    ptwJoinsFill,
    targetLimit,
    storeVisibility,
    heldLoadVisibility,
    sramReadStoreOrdering,
    completedFillStoreVisibility,
    cleanAll,
    writebackHazard,
    faults,
    smallerGeometry
  )
}
