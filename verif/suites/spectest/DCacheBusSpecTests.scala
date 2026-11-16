package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Queue}
import udacore.common.tilelink.TLBundle
import udacore.core.design.modules.{DataBusAdapter, DataCache}
import udacore.core.design.shared._

/** Real DataCache and DataBusAdapter connected through their specified
  * ready/valid edges.
  */
class DCacheBusHarness(val cp: CoreParams) extends Module {
  val io      = IO(new Bundle {
    val loadReq      = Flipped(
      Decoupled(
        new DCacheLoadReq(
          cp.lsq.lqIdxWidth,
          cp.lsq.generationWidth,
          cp.vAddrWidth
        )
      )
    )
    val translation  =
      Flipped(Decoupled(new Translation(cp.lsq.reqIdWidth, cp.pAddrWidth)))
    val loadResp     = Decoupled(
      new DCacheLoadResp(
        cp.lsq.lqIdxWidth,
        cp.lsq.generationWidth,
        cp.pAddrWidth,
        cp.dataWidth
      )
    )
    val storeReq     =
      Flipped(Decoupled(new StoreDrainReq(cp.pAddrWidth, cp.dataWidth)))
    val storeResp    = Decoupled(new StoreDrainResp)
    val ptwReq       = Flipped(Decoupled(new PtwMemReq(cp.pAddrWidth)))
    val ptwResp      = Decoupled(new PtwMemResp)
    val cleanReq     = Flipped(Decoupled(new CacheMaintenance))
    val cleanResp    = Decoupled(new CacheMaintenance)
    val uncLoadReq   = Flipped(
      Decoupled(
        new UncachedLoadReq(
          cp.lsq.lqIdxWidth,
          cp.lsq.generationWidth,
          cp.pAddrWidth
        )
      )
    )
    val uncLoadResp  = Decoupled(
      new UncachedLoadResp(
        cp.lsq.lqIdxWidth,
        cp.lsq.generationWidth,
        cp.dataWidth
      )
    )
    val uncStoreReq  = Flipped(
      Decoupled(
        new UncachedStoreReq(cp.lsq.sqIdxWidth, cp.pAddrWidth, cp.dataWidth)
      )
    )
    val uncStoreResp = Decoupled(new UncachedStoreResp(cp.lsq.sqIdxWidth))
    val bus          = new TLBundle(cp.dataBusParams)
  })
  val cache   = Module(new DataCache(cp))
  val adapter = Module(new DataBusAdapter(cp))
  cache.io.dCacheLoadReqIn <> io.loadReq
  cache.io.dCacheTranslationIn <> io.translation
  io.loadResp <> cache.io.dCacheLoadRespOut
  cache.io.storeDrainReqIn <> io.storeReq
  io.storeResp <> cache.io.storeDrainRespOut
  cache.io.ptwMemReqIn <> io.ptwReq
  io.ptwResp <> cache.io.ptwMemRespOut
  cache.io.dCacheCleanReqIn <> io.cleanReq
  io.cleanResp <> cache.io.dCacheCleanRespOut
  cache.io.uncachedLoadReqIn <> io.uncLoadReq
  io.uncLoadResp <> cache.io.uncachedLoadRespOut
  cache.io.uncachedStoreReqIn <> io.uncStoreReq
  io.uncStoreResp <> cache.io.uncachedStoreRespOut
  adapter.io.dataMemReqIn <> cache.io.dataMemReqOut
  cache.io.dataMemRespIn <> adapter.io.dataMemRespOut
  io.bus.a <> adapter.io.dataBus.a
  adapter.io.dataBus.d <> io.bus.d
}

/** Connected data-link checks with a TileLink memory that honors multibeat A
  * and D.
  */
object DCacheBusSpecTests {
  val cp                           = CoreParams()
  val beatBytes                    = cp.dataWidth / 8
  val lineWords                    = cp.contract.dcache.blockBytes / beatBytes
  def word(pa: Long): Long         = ((pa >> 2) * 1103515245L + 12345L) & 0xffffffffL
  def rid(idx: Int, gen: Int): Int = (idx << cp.lsq.generationWidth) | gen

  case class L(
      idx: Int,
      gen: Int,
      va: Long,
      size: Int,
      pa: Long,
      cacheable: Boolean = true
  )
  case class A(
      op: Int,
      size: Int,
      source: Int,
      address: Long,
      mask: Int,
      data: Long
  )
  case class D(
      op: Int,
      size: Int,
      source: Int,
      data: Long,
      denied: Boolean = false
  )
  case class R(idx: Int, gen: Int, status: Int, pa: Long, data: Long)

  class Drv(val dut: DCacheBusHarness) {
    val io                                       = dut.io
    var cyc                                      = 0
    var load: Option[L]                          = None
    var translation: Option[L]                   = None
    var store: Option[(Long, Long, Int)]         = None
    var ptw: Option[Long]                        = None
    var clean                                    = false
    var uncLoad: Option[(Int, Int, Long, Int)]   = None
    var uncStore: Option[(Int, Long, Long, Int)] = None
    var aReady                                   = true
    var dPermit                                  = true
    var deny: Long => Boolean                    = _ => false
    val dq                                       = Queue[D]()
    val as                                       = ArrayBuffer[A]()
    val rs                                       = ArrayBuffer[R]()
    val ptwRs                                    = ArrayBuffer[(Long, Boolean)]()
    val uncLoadRs                                = ArrayBuffer[(Int, Int, Long, Boolean)]()
    val uncStoreRs                               = ArrayBuffer[(Int, Boolean)]()
    var storeResponses                           = 0
    var cleanResponses                           = 0
    var loadFire                                 = false
    var transFire                                = false
    var storeFire                                = false
    var ptwFire                                  = false
    var cleanFire                                = false
    var uncLoadFire                              = false
    var uncStoreFire                             = false
    var dFires                                   = 0
    private var putBeats                         = 0

    def cycle(): Unit                                    = {
      io.loadReq.valid.poke(load.nonEmpty.B)
      load.foreach { x =>
        io.loadReq.bits.lqIdx.poke(x.idx.U); io.loadReq.bits.lqGen.poke(x.gen.U)
        io.loadReq.bits.vaddr.poke(x.va.U); io.loadReq.bits.size.poke(x.size.U)
      }
      io.translation.valid.poke(translation.nonEmpty.B)
      translation.foreach { x =>
        io.translation.bits.reqId.poke(rid(x.idx, x.gen).U)
        io.translation.bits.status.poke(TranslationStatus.Hit)
        io.translation.bits.paddr.poke(x.pa.U)
        io.translation.bits.cacheable.poke(x.cacheable.B)
      }
      io.storeReq.valid.poke(store.nonEmpty.B)
      store.foreach { case (pa, data, mask) =>
        io.storeReq.bits.paddr.poke(pa.U); io.storeReq.bits.data.poke(data.U)
        io.storeReq.bits.mask.poke(mask.U)
      }
      io.ptwReq.valid.poke(ptw.nonEmpty.B)
      ptw.foreach(x => io.ptwReq.bits.paddr.poke(x.U))
      io.cleanReq.valid.poke(clean.B)
      io.cleanReq.bits.op.poke(MaintOp.DCacheCleanAll)
      io.uncLoadReq.valid.poke(uncLoad.nonEmpty.B)
      uncLoad.foreach { case (idx, gen, pa, size) =>
        io.uncLoadReq.bits.lqIdx.poke(idx.U);
        io.uncLoadReq.bits.lqGen.poke(gen.U)
        io.uncLoadReq.bits.paddr.poke(pa.U);
        io.uncLoadReq.bits.size.poke(size.U)
      }
      io.uncStoreReq.valid.poke(uncStore.nonEmpty.B)
      uncStore.foreach { case (idx, pa, data, mask) =>
        io.uncStoreReq.bits.sqIdx.poke(idx.U);
        io.uncStoreReq.bits.paddr.poke(pa.U)
        io.uncStoreReq.bits.data.poke(data.U);
        io.uncStoreReq.bits.mask.poke(mask.U)
      }
      io.loadResp.ready.poke(true.B)
      io.storeResp.ready.poke(true.B)
      io.ptwResp.ready.poke(true.B)
      io.cleanResp.ready.poke(true.B)
      io.uncLoadResp.ready.poke(true.B)
      io.uncStoreResp.ready.poke(true.B)
      io.bus.a.ready.poke(aReady.B)
      val dv = dPermit && dq.nonEmpty
      io.bus.d.valid.poke(dv.B)
      dq.headOption.foreach { x =>
        val b = io.bus.d.bits
        b.opcode.poke(x.op.U); b.param.poke(0.U); b.size.poke(x.size.U)
        b.source.poke(x.source.U); b.sink.poke(0.U); b.data.poke(x.data.U)
        b.denied.poke(x.denied.B); b.corrupt.poke(false.B)
      }

      loadFire = load.nonEmpty && io.loadReq.ready.peek().litToBoolean
      transFire =
        translation.nonEmpty && io.translation.ready.peek().litToBoolean
      storeFire = store.nonEmpty && io.storeReq.ready.peek().litToBoolean
      ptwFire = ptw.nonEmpty && io.ptwReq.ready.peek().litToBoolean
      cleanFire = clean && io.cleanReq.ready.peek().litToBoolean
      uncLoadFire = uncLoad.nonEmpty && io.uncLoadReq.ready.peek().litToBoolean
      uncStoreFire =
        uncStore.nonEmpty && io.uncStoreReq.ready.peek().litToBoolean
      if (io.loadResp.valid.peek().litToBoolean) {
        val b = io.loadResp.bits
        rs += R(
          b.lqIdx.peek().litValue.toInt,
          b.lqGen.peek().litValue.toInt,
          b.status.peek().litValue.toInt,
          b.paddr.peek().litValue.toLong,
          b.data.peek().litValue.toLong
        )
      }
      if (io.ptwResp.valid.peek().litToBoolean) {
        val p     = io.ptwResp.bits.pte
        val flags = Seq(p.d, p.a, p.g, p.u, p.x, p.w, p.r, p.v)
          .foldLeft(0L)((bits, bit) =>
            (bits << 1) | (if (bit.peek().litToBoolean) 1L else 0L)
          )
        val raw   = (p.ppn1.peek().litValue.toLong << 20) |
          (p.ppn0.peek().litValue.toLong << 10) |
          (p.rsw.peek().litValue.toLong << 8) | flags
        ptwRs += ((raw, io.ptwResp.bits.accessFault.peek().litToBoolean))
      }
      if (io.uncLoadResp.valid.peek().litToBoolean) {
        val b = io.uncLoadResp.bits
        uncLoadRs += ((
          b.lqIdx.peek().litValue.toInt,
          b.lqGen.peek().litValue.toInt,
          b.data.peek().litValue.toLong,
          b.accessFault.peek().litToBoolean
        ))
      }
      if (io.uncStoreResp.valid.peek().litToBoolean)
        uncStoreRs += ((
          io.uncStoreResp.bits.sqIdx.peek().litValue.toInt,
          io.uncStoreResp.bits.accessFault.peek().litToBoolean
        ))
      if (io.storeResp.valid.peek().litToBoolean) storeResponses += 1
      if (io.cleanResp.valid.peek().litToBoolean) cleanResponses += 1

      if (io.bus.a.valid.peek().litToBoolean && aReady) {
        val b = io.bus.a.bits
        val a = A(
          b.opcode.peek().litValue.toInt,
          b.size.peek().litValue.toInt,
          b.source.peek().litValue.toInt,
          b.address.peek().litValue.toLong,
          b.mask.peek().litValue.toInt,
          b.data.peek().litValue.toLong
        )
        as += a
        if (a.op == 4) {
          val beats = math.max(1, (1 << a.size) / beatBytes)
          (0 until beats).foreach(i =>
            dq.enqueue(
              D(
                1,
                a.size,
                a.source,
                word((a.address & ~3L) + beatBytes.toLong * i),
                deny(a.address)
              )
            )
          )
        } else if (a.op == 0) {
          putBeats += 1
          if (putBeats == lineWords) {
            dq.enqueue(D(0, a.size, a.source, 0, deny(a.address))); putBeats = 0
          }
        } else if (a.op == 1) {
          dq.enqueue(D(0, a.size, a.source, 0, deny(a.address)))
        }
      }
      if (dv && io.bus.d.ready.peek().litToBoolean) {
        dq.dequeue(); dFires += 1
      }
      dut.clock.step(); cyc += 1
    }
    def until(done: => Boolean, limit: Int = 400): Unit  = {
      var k = 0
      while (!done && k < limit) { cycle(); k += 1 }
    }
    def run(n: Int): Unit                                = (0 until n).foreach(_ => cycle())
    def sendLoad(x: L): Unit                             = {
      load = Some(x); loadFire = false; until(loadFire); load = None
      translation = Some(x); transFire = false; until(transFire);
      translation = None
    }
    def sendStore(pa: Long, data: Long, mask: Int): Unit = {
      store = Some((pa, data, mask)); storeFire = false; until(storeFire);
      store = None
    }
    def sendClean(): Unit                                = {
      clean = true; cleanFire = false; until(cleanFire); clean = false
    }
    def sendPtw(pa: Long): Unit                          = {
      ptw = Some(pa); ptwFire = false; until(ptwFire); ptw = None
    }
  }

  def chk(ok: Boolean, label: String, detail: => String): TCheck  =
    TCheck(ok, label, if (ok) "" else detail)
  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new DCacheBusHarness(cp)) { dut => body(new Drv(dut)) }

  val fill =
    new SpecTest("dbus.fill", Seq("funcDCacheMshr", "funcDataBusTransaction")) {
      def run(): Seq[TCheck] = withDrv(this) { d =>
        val pa = 0x2040L
        d.sendLoad(L(0, 0, pa, 2, pa)); d.until(d.rs.size == 1)
        d.sendLoad(L(1, 0, pa + 4, 2, pa + 4)); d.until(d.rs.size == 2)
        Seq(
          chk(
            d.as.toSeq == Seq(
              A(4, 6, 0, pa & ~63L, 15, 0)
            ) && d.dFires == lineWords,
            "one TileLink Get fills the line through the connected adapter",
            s"A=${d.as} D=${d.dFires}"
          ),
          chk(
            d.rs.toSeq == Seq(
              R(0, 0, 0, pa, word(pa)),
              R(1, 0, 0, pa + 4, word(pa + 4))
            ),
            "the filled line serves the second load without another A request",
            s"${d.rs}"
          )
        )
      }
    }

  val writebackAndUncached = new SpecTest(
    "dbus.writebackAndUncached",
    Seq("funcDCacheCleanAll", "funcDCacheUncached", "funcDataBusTransaction")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa   = 0x3004L; val device = 0xf0000040L
      d.sendStore(pa, 0xdeadbeefL, 15); d.until(d.storeResponses == 1)
      d.sendClean(); d.until(d.cleanResponses == 1)
      d.uncLoad = Some((2, 1, device, 2)); d.uncLoadFire = false
      d.until(d.uncLoadFire); d.uncLoad = None
      d.until(d.uncLoadRs.size == 1)
      d.uncStore = Some((3, device, 0x12345678L, 15)); d.uncStoreFire = false
      d.until(d.uncStoreFire); d.uncStore = None
      d.until(d.uncStoreRs.size == 1)
      val puts = d.as.filter(_.op == 0).toSeq
      Seq(
        chk(
          d.as.headOption.contains(A(4, 6, 0, pa & ~63L, 15, 0)) &&
            puts.size == lineWords && puts.forall(a =>
              a.source == 2 && a.size == 6 &&
                a.address == (pa & ~63L) && a.mask == 15
            ) && puts(1).data == 0xdeadbeefL,
          "the dirty line becomes a contiguous TileLink PutFullData burst",
          s"${d.as}"
        ),
        chk(
          d.as.takeRight(2).toSeq == Seq(
            A(4, 2, 3, device, 15, 0),
            A(1, 2, 3, device, 15, 0x12345678L)
          ) &&
            d.uncLoadRs.toSeq == Seq((2, 1, word(device), false)) &&
            d.uncStoreRs.toSeq == Seq((3, false)),
          "uncached Get and PutPartialData use source 3 and return their acknowledgements",
          s"A=${d.as.takeRight(2)} load=${d.uncLoadRs} store=${d.uncStoreRs}"
        )
      )
    }
  }

  val ptwPhysical = new SpecTest(
    "dbus.ptwPhysical",
    Seq("funcDCachePhysicalRead", "funcDataBusTransaction")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val device = 0xf0000080L
      d.deny = _ == device
      d.sendPtw(device); d.until(d.ptwRs.size == 1)
      Seq(
        chk(
          d.as.toSeq == Seq(A(4, 2, 3, device, 15, 0)),
          "a readable device PTE uses one exact physical TileLink Get",
          s"${d.as}"
        ),
        chk(
          d.ptwRs.size == 1 && d.ptwRs.head._2,
          "a denied TileLink AccessAckData reaches the PTW port as accessFault",
          s"${d.ptwRs}"
        )
      )
    }
  }

  val all: Seq[SpecTest] = Seq(fill, writebackAndUncached, ptwPhysical)
}
