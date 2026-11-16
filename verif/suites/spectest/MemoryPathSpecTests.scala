package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Map, Queue}
import udacore.backend.design.modules.StoreBuffer
import udacore.backend.design.shared.{BackendParams, RobTag, TranslationContext}
import udacore.common.tilelink.TLBundle
import udacore.core.design.modules.PageTableWalker
import udacore.core.design.shared._

/** Connected LSQ, DTLB, PTW, StoreBuffer, DataCache and DataBusAdapter. */
class MemoryPathHarness(val bp: BackendParams, val cp: CoreParams) extends Module {
  val lsq = Module(new LsqDtlbHarness(bp, cp))
  val ptw = Module(new PageTableWalker(cp))
  val store = Module(new StoreBuffer(bp))
  val data = Module(new DCacheBusHarness(cp))
  private def port[T <: Data](x: T): T = IO(chiselTypeOf(x))
  val alloc = port(lsq.lsqAllocIn)
  val address = port(lsq.memAddressIn)
  val result = port(lsq.memResultOut)
  val commit = port(lsq.storeCommitIn)
  val grant = port(lsq.headMemGrantIn)
  val rob = port(lsq.robStatusIn)
  val recovery = port(lsq.recoveryEventIn)
  val sfence = port(ptw.io.sfenceVmaIn)
  val drainEnabled = IO(Input(Bool()))
  val ctx = IO(Input(new TranslationContext))
  val bus = IO(new TLBundle(cp.dataBusParams))
  val mon = IO(Output(new Bundle {
    val loadReq = Bool()
    val storeDrain = Bool()
    val storeDrainResp = Bool()
    val forward = Bool()
    val forwardHitMask = UInt(4.W)
    val bufferEmpty = Bool()
    val ptwRead = Bool()
    val refill = Bool()
    val refillStatus = UInt(WalkStatus.width.W)
  }))

  lsq.lsqAllocIn <> alloc
  lsq.memAddressIn <> address
  result <> lsq.memResultOut
  lsq.storeCommitIn <> commit
  lsq.headMemGrantIn <> grant
  lsq.robStatusIn := rob
  lsq.recoveryEventIn := recovery
  lsq.ctx := ctx
  ptw.io.itlbWalkReqIn.valid := false.B
  ptw.io.itlbWalkReqIn.bits := 0.U.asTypeOf(ptw.io.itlbWalkReqIn.bits)
  ptw.io.itlbWalkRespOut.ready := true.B
  ptw.io.dtlbWalkReqIn <> lsq.walkReq
  lsq.walkResp <> ptw.io.dtlbWalkRespOut
  ptw.io.itlbFlushOut.ready := true.B
  lsq.flush <> ptw.io.dtlbFlushOut
  ptw.io.sfenceVmaIn <> sfence

  data.io.loadReq <> lsq.dCacheLoadReqOut
  lsq.dCacheLoadRespIn <> data.io.loadResp
  data.io.translation <> lsq.dCacheTranslationOut
  data.io.uncLoadReq <> lsq.uncachedLoadReqOut
  lsq.uncachedLoadRespIn <> data.io.uncLoadResp
  data.io.uncStoreReq <> lsq.uncachedStoreReqOut
  lsq.uncachedStoreRespIn <> data.io.uncStoreResp
  store.io.committedStoreIn <> lsq.committedStoreOut
  store.io.storeForwardQueryIn <> lsq.storeForwardQueryOut
  lsq.storeForwardDataIn <> store.io.storeForwardDataOut
  lsq.storeBufferEmptyIn := store.io.storeBufferEmptyOut
  data.io.storeReq.valid := store.io.storeDrainReqOut.valid && drainEnabled
  data.io.storeReq.bits := store.io.storeDrainReqOut.bits
  store.io.storeDrainReqOut.ready := data.io.storeReq.ready && drainEnabled
  store.io.storeDrainRespIn <> data.io.storeResp
  store.io.storeBufferDrainReqIn.valid := false.B
  store.io.storeBufferDrainReqIn.bits := 0.U.asTypeOf(store.io.storeBufferDrainReqIn.bits)
  store.io.storeBufferDrainRespOut.ready := true.B
  data.io.ptwReq <> ptw.io.ptwMemReqOut
  ptw.io.ptwMemRespIn <> data.io.ptwResp
  data.io.cleanReq.valid := false.B
  data.io.cleanReq.bits := 0.U.asTypeOf(data.io.cleanReq.bits)
  data.io.cleanResp.ready := true.B
  bus.a <> data.io.bus.a
  data.io.bus.d <> bus.d

  mon.loadReq := lsq.dCacheLoadReqOut.fire
  mon.storeDrain := store.io.storeDrainReqOut.fire
  mon.storeDrainResp := data.io.storeResp.fire
  mon.forward := lsq.storeForwardQueryOut.fire
  mon.forwardHitMask := store.io.storeForwardDataOut.bits.hitMask
  mon.bufferEmpty := store.io.storeBufferEmptyOut
  mon.ptwRead := ptw.io.ptwMemReqOut.fire
  mon.refill := lsq.mon.noticeFire
  mon.refillStatus := lsq.mon.noticeStatus
}

object MemoryPathSpecTests {
  import LoadStoreQueueSpecTests.{Alloc, Agu, misaligned, tagOf}
  import PageTableWalkerSpecTests.{l0Addr, l1Addr, leaf, ptr}
  val bp = BackendParams()
  val cp = CoreParams()
  val beatBytes = cp.dataWidth / 8
  val lineWords = cp.contract.dcache.blockBytes / beatBytes
  case class Result(tag: Int, data: Long, exception: Option[Int])
  case class A(op: Int, size: Int, source: Int, address: Long, mask: Int, data: Long)
  case class D(op: Int, size: Int, source: Int, data: Long)

  class Drv(val dut: MemoryPathHarness) {
    val io = dut
    val mem = Map[Long, Long]().withDefaultValue(0L)
    val dq = Queue[D]()
    val as = ArrayBuffer[A]()
    val results = ArrayBuffer[Result]()
    val forwardMasks = ArrayBuffer[Int]()
    val refills = ArrayBuffer[Int]()
    var loadRequests = 0
    var ptwReads = 0
    var drains = 0
    var drainResponses = 0
    var alloc: Option[Alloc] = None
    var agu: Option[Agu] = None
    var commit: Option[Int] = None
    var grant: Option[Int] = None
    var dPermit = true
    var drainEnabled = true
    var satp = false
    var rootPpn = 0L
    var asid = 0
    var priv = 3
    var allocFire = false
    var aguFire = false
    var commitFire = false
    var cycles = 0
    private var putBeats = 0

    private def tag(t: RobTag, s: Int): Unit = {
      t.wrap.poke(tagOf(s)._1.B); t.idx.poke(tagOf(s)._2.U)
    }
    def cycle(): Unit = {
      io.alloc.valid.poke(alloc.nonEmpty.B)
      alloc.foreach { x =>
        val a = io.alloc.bits
        tag(a.robTag, x.s); a.isLoad.poke(x.isLoad.B); a.isStore.poke((!x.isLoad).B)
        a.size.poke(x.size.U); a.signed.poke(x.signed.B); a.prd.poke(x.prd.U)
      }
      io.address.valid.poke(agu.nonEmpty.B)
      agu.foreach { x =>
        val m = io.address.bits
        tag(m.robTag, x.s); m.vaddr.poke(x.vaddr.U); m.storeData.poke(x.data.U)
        m.misaligned.poke(misaligned(x.size, x.vaddr).B)
      }
      io.commit.valid.poke(commit.nonEmpty.B)
      commit.foreach(s => tag(io.commit.bits.robTag, s))
      io.grant.valid.poke(grant.nonEmpty.B)
      grant.foreach(s => tag(io.grant.bits.robTag, s))
      io.rob.empty.poke(false.B)
      tag(io.rob.headTag, 0)
      io.recovery.valid.poke(false.B)
      io.recovery.kind.poke(0.U)
      tag(io.recovery.robTag, 0)
      io.recovery.checkpointId.id.poke(0.U)
      io.recovery.target.poke(0.U)
      io.recovery.cause.poke(0.U)
      io.sfence.valid.poke(false.B)
      io.sfence.bits.vaddr.poke(0.U)
      io.sfence.bits.vaddrValid.poke(false.B)
      io.sfence.bits.asid.poke(0.U)
      io.sfence.bits.asidValid.poke(false.B)
      io.ctx.satpMode.poke(satp.B)
      io.ctx.asid.poke(asid.U)
      io.ctx.rootPpn.poke(rootPpn.U)
      io.ctx.priv.poke(priv.U)
      io.ctx.dataPriv.poke(priv.U)
      io.ctx.sum.poke(false.B)
      io.ctx.mxr.poke(false.B)
      io.result.ready.poke(true.B)
      io.drainEnabled.poke(drainEnabled.B)
      io.bus.a.ready.poke(true.B)
      val dValid = dPermit && dq.nonEmpty
      io.bus.d.valid.poke(dValid.B)
      dq.headOption.foreach { x =>
        val b = io.bus.d.bits
        b.opcode.poke(x.op.U); b.param.poke(0.U); b.size.poke(x.size.U)
        b.source.poke(x.source.U); b.sink.poke(0.U); b.data.poke(x.data.U)
        b.denied.poke(false.B); b.corrupt.poke(false.B)
      }

      allocFire = alloc.nonEmpty && io.alloc.ready.peek().litToBoolean
      aguFire = agu.nonEmpty && io.address.ready.peek().litToBoolean
      commitFire = commit.nonEmpty && io.commit.ready.peek().litToBoolean
      if (io.result.valid.peek().litToBoolean) {
        val b = io.result.bits
        results += Result(b.robTag.idx.peek().litValue.toInt, b.data.peek().litValue.toLong,
          if (b.exception.valid.peek().litToBoolean) Some(b.exception.cause.peek().litValue.toInt) else None)
      }
      if (io.mon.loadReq.peek().litToBoolean) loadRequests += 1
      if (io.mon.ptwRead.peek().litToBoolean) ptwReads += 1
      if (io.mon.refill.peek().litToBoolean)
        refills += io.mon.refillStatus.peek().litValue.toInt
      if (io.mon.storeDrain.peek().litToBoolean) drains += 1
      if (io.mon.storeDrainResp.peek().litToBoolean) drainResponses += 1
      if (io.mon.forward.peek().litToBoolean)
        forwardMasks += io.mon.forwardHitMask.peek().litValue.toInt
      if (io.bus.a.valid.peek().litToBoolean) {
        val b = io.bus.a.bits
        val a = A(b.opcode.peek().litValue.toInt, b.size.peek().litValue.toInt,
          b.source.peek().litValue.toInt, b.address.peek().litValue.toLong,
          b.mask.peek().litValue.toInt, b.data.peek().litValue.toLong)
        as += a
        if (a.op == 4) {
          val beats = math.max(1, (1 << a.size) / beatBytes)
          (0 until beats).foreach(i => dq.enqueue(D(1, a.size, a.source,
            mem((a.address + beatBytes.toLong * i) >>> 2))))
        } else if (a.op == 0) {
          mem((a.address >>> 2) + putBeats) = a.data
          putBeats += 1
          if (putBeats == lineWords) { dq.enqueue(D(0, a.size, a.source, 0)); putBeats = 0 }
        } else if (a.op == 1) {
          val old = mem(a.address >>> 2)
          val merged = (0 until beatBytes).foldLeft(old) { (w, i) =>
            if ((a.mask & (1 << i)) == 0) w
            else (w & ~(0xffL << (8 * i))) | (a.data & (0xffL << (8 * i)))
          }
          mem(a.address >>> 2) = merged
          dq.enqueue(D(0, a.size, a.source, 0))
        }
      }
      if (dValid && io.bus.d.ready.peek().litToBoolean) dq.dequeue()
      dut.clock.step(); cycles += 1
    }
    def until(done: => Boolean, limit: Int = 400): Boolean = {
      var k = 0
      while (!done && k < limit) { cycle(); k += 1 }
      done
    }
    def allocate(x: Alloc): Boolean = {
      alloc = Some(x); allocFire = false; val ok = until(allocFire, 30); alloc = None; ok
    }
    def address(x: Agu): Boolean = {
      agu = Some(x); aguFire = false; val ok = until(aguFire, 30); agu = None; ok
    }
    def storeCommit(s: Int): Boolean = {
      commit = Some(s); commitFire = false; val ok = until(commitFire, 30); commit = None; ok
    }
    def load(s: Int, pa: Long): Unit = { allocate(Alloc(s, isLoad = true)); address(Agu(s, pa)) }
    def storeOp(s: Int, pa: Long, data: Long): Unit = {
      allocate(Alloc(s, isLoad = false)); address(Agu(s, pa, data))
    }
    def done(s: Int): Option[Result] = results.find(_.tag == s)
  }

  def chk(ok: Boolean, label: String, detail: => String): TCheck =
    TCheck(ok, label, if (ok) "" else detail)
  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new MemoryPathHarness(bp, cp)) { dut => val d = new Drv(dut); d.cycle(); body(d) }

  val loadStoreLoad = new SpecTest("memorypath.loadStoreLoad",
    Seq("funcDtlbTranslate", "funcDCacheViptLookup", "funcCommitOrderDrain")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa = 0x1040L
      d.mem(pa >>> 2) = 0x11223344L
      d.load(0, pa); d.until(d.done(0).nonEmpty)
      d.storeOp(1, pa, 0xaabbccddL); d.until(d.done(1).nonEmpty)
      val committed = d.storeCommit(1)
      d.until(d.drainResponses == 1 && d.io.mon.bufferEmpty.peek().litToBoolean)
      d.load(2, pa); d.until(d.done(2).nonEmpty)
      Seq(
        chk(d.done(0).contains(Result(0, 0x11223344L, None)) &&
          d.done(2).contains(Result(2, 0xaabbccddL, None)),
          "loads on both sides of a committed store see their respective data",
          s"${d.results}"),
        chk(committed && d.drains == 1 && d.drainResponses == 1 &&
          d.as.toSeq == Seq(A(4, 6, 0, pa & ~63L, 15, 0)) && d.loadRequests == 2,
          "the real StoreBuffer drains once into the filled DataCache line",
          s"commit=$committed drain=${d.drains}/${d.drainResponses} A=${d.as} loads=${d.loadRequests}")
      )
    }
  }

  val coldStoreOverlay = new SpecTest("memorypath.coldStoreOverlay",
    Seq("funcDCacheStoreWrite", "propDrainResponseMakesStoreVisible", "funcCommittedForward")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa = 0x2080L
      d.mem(pa >>> 2) = 0x11111111L
      d.storeOp(0, pa, 0xdeadbeefL); d.until(d.done(0).nonEmpty)
      d.dPermit = false
      val committed = d.storeCommit(0)
      d.until(d.as.nonEmpty)
      val fillPending = d.as.nonEmpty && d.dq.nonEmpty && d.drainResponses == 1
      d.load(1, pa); d.until(d.loadRequests == 1)
      d.dPermit = true
      d.until(d.done(1).nonEmpty && d.io.mon.bufferEmpty.peek().litToBoolean)
      Seq(
        chk(committed && fillPending && d.drains == 1 && d.drainResponses == 1,
          "the committed cold store is accepted while its line fill remains pending",
          s"commit=$committed pending=$fillPending drains=${d.drains}/${d.drainResponses}"),
        chk(d.done(1).contains(Result(1, 0xdeadbeefL, None)) &&
          d.as.toSeq == Seq(A(4, 6, 0, pa & ~63L, 15, 0)),
          "the younger load sees store bytes without a second line fetch",
          s"result=${d.done(1)} A=${d.as}")
      )
    }
  }

  val sv32Load = new SpecTest("memorypath.sv32Load",
    Seq("funcDtlbMissNonBlocking", "funcSv32Walk", "funcDCachePhysicalRead")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val va = 0x00400020L
      val pa = 0x00300020L
      val l1 = l1Addr(0x100, va >>> 12)
      val l0 = l0Addr(0x200, va >>> 12)
      d.satp = true; d.rootPpn = 0x100; d.asid = 1; d.priv = 1
      d.mem(l1 >>> 2) = ptr(0x200).word
      d.mem(l0 >>> 2) = leaf(0x300).word
      d.mem(pa >>> 2) = 0x89abcdefL
      d.load(0, va); d.until(d.done(0).nonEmpty)
      Seq(
        chk(d.done(0).contains(Result(0, 0x89abcdefL, None)) &&
          d.refills.toSeq == Seq(0) && d.loadRequests == 2,
          "the LSQ retries a DTLB miss after the real two-level PTW refill",
          s"result=${d.done(0)} refill=${d.refills} loads=${d.loadRequests}"),
        chk(d.ptwReads == 2 && d.as.filter(_.op == 4).map(_.address).toSeq ==
          Seq(l1 & ~63L, l0 & ~63L, pa & ~63L),
          "both PTE lines and the translated data line arrive over the same TileLink path",
          s"reads=${d.ptwReads} A=${d.as}")
      )
    }
  }

  val sv32Store = new SpecTest("memorypath.sv32Store",
    Seq("funcDtlbTranslate", "funcSv32Walk", "funcCommitOrderDrain", "funcDCacheStoreWrite")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val va = 0x00400024L
      val pa = 0x00300024L
      val l1 = l1Addr(0x100, va >>> 12)
      val l0 = l0Addr(0x200, va >>> 12)
      d.satp = true; d.rootPpn = 0x100; d.asid = 1; d.priv = 1
      d.mem(l1 >>> 2) = ptr(0x200).word
      d.mem(l0 >>> 2) = leaf(0x300).word
      d.mem(pa >>> 2) = 0x11111111L
      d.storeOp(0, va, 0xdeadbeefL); d.until(d.done(0).nonEmpty)
      val committed = d.storeCommit(0)
      d.until(d.drainResponses == 1 && d.io.mon.bufferEmpty.peek().litToBoolean)
      d.load(1, va); d.until(d.done(1).nonEmpty)
      Seq(
        chk(d.done(0).exists(_.exception.isEmpty) && committed &&
          d.drains == 1 && d.drainResponses == 1 &&
          d.done(1).contains(Result(1, 0xdeadbeefL, None)),
          "a translated store commits through StoreBuffer and a load reads its data",
          s"store=${d.done(0)} commit=$committed drain=${d.drains}/${d.drainResponses} load=${d.done(1)}"),
        chk(d.refills.toSeq == Seq(0) && d.ptwReads == 2 &&
          d.as.filter(_.op == 4).map(_.address).toSeq ==
            Seq(l1 & ~63L, l0 & ~63L, pa & ~63L),
          "the store miss walks once and its data fill reuses the mapped physical page",
          s"refill=${d.refills} reads=${d.ptwReads} A=${d.as}")
      )
    }
  }

  val bufferedForward = new SpecTest("memorypath.bufferedForward",
    Seq("funcCommittedForward", "funcStoreToLoadForward", "funcCommitOrderDrain")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val pa = 0x10c0L
      d.mem(pa >>> 2) = 0x11223344L
      d.load(0, pa); d.until(d.done(0).nonEmpty)
      d.storeOp(1, pa, 0xaabbccddL); d.until(d.done(1).nonEmpty)
      d.drainEnabled = false
      val committed = d.storeCommit(1)
      d.until(!d.io.mon.bufferEmpty.peek().litToBoolean)
      d.load(2, pa); d.until(d.done(2).nonEmpty)
      val held = d.done(2).contains(Result(2, 0xaabbccddL, None)) &&
        d.forwardMasks.contains(15) && d.drains == 0 &&
        !d.io.mon.bufferEmpty.peek().litToBoolean
      d.drainEnabled = true
      d.until(d.drainResponses == 1 && d.io.mon.bufferEmpty.peek().litToBoolean)
      d.load(3, pa); d.until(d.done(3).nonEmpty)
      Seq(
        chk(committed && held,
          "the younger load receives committed bytes from StoreBuffer while drain is backpressured",
          s"commit=$committed held=$held results=${d.results} masks=${d.forwardMasks} drains=${d.drains}"),
        chk(d.done(3).contains(Result(3, 0xaabbccddL, None)) &&
          d.drains == 1 && d.drainResponses == 1 &&
          d.as.toSeq == Seq(A(4, 6, 0, pa & ~63L, 15, 0)),
          "after drain, the cache returns the same bytes without another bus fetch",
          s"result=${d.done(3)} drains=${d.drains}/${d.drainResponses} A=${d.as}")
      )
    }
  }

  val sv32PageFault = new SpecTest("memorypath.sv32PageFault",
    Seq("funcSv32Walk", "funcDtlbTranslate", "funcDCacheViptLookup")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val va = 0x00400028L
      val l1 = l1Addr(0x100, va >>> 12)
      d.satp = true; d.rootPpn = 0x100; d.asid = 1; d.priv = 1
      d.load(0, va)
      d.until(d.results.exists(r => r.tag == 0 && r.exception.contains(13)))
      Seq(
        chk(d.results.exists(r => r.tag == 0 && r.exception.contains(13)) &&
          d.refills.toSeq == Seq(1) && d.loadRequests == 2,
          "an invalid level-1 PTE reaches the LSQ as a load page fault after retry",
          s"results=${d.results} refill=${d.refills} loads=${d.loadRequests}"),
        chk(d.ptwReads == 1 && d.as.filter(_.op == 4).map(_.address).toSeq == Seq(l1 & ~63L),
          "the faulting walk reads only the root PTE line and never fetches data",
          s"reads=${d.ptwReads} A=${d.as}")
      )
    }
  }

  val all: Seq[SpecTest] = Seq(loadStoreLoad, coldStoreOverlay, sv32Load, sv32Store,
    bufferedForward, sv32PageFault)
}
