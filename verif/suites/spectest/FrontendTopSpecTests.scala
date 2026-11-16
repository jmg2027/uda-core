package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.shared.{RecoveryCause, RecoveryKind}
import udacore.frontend.design.top.FrontendTop
import verif.spectest.FrontendTestKit._

/** Connected prediction, fetch, and packet delivery through the real FrontendTop. */
object FrontendTopSpecTests {
  case class Request(cycle: Int, addr: Long, id: Int)
  case class Slot(pc: Long, inst: Long, ftqIdx: Int, slot: Int, end: Boolean, fault: Int)

  class Drv(val dut: FrontendTop) {
    val io = dut.io
    var cycleNo = 0
    var boot: Option[Long] = None
    var event: Option[Event] = None
    var packetReady = true
    var responseLatency = 2
    val tlb = ArrayBuffer[Request]()
    val cache = ArrayBuffer[Request]()
    val slots = ArrayBuffer[Slot]()
    val packets = ArrayBuffer[Seq[Slot]]()
    val held = ArrayBuffer[Seq[Slot]]()
    private val pending = ArrayBuffer[(Int, Int, Long)]()
    var bootFire = false

    def word(pa: Long): Long = ((pa >>> 2) * 1103515245L + 12345L) & 0xffffffffL
    def peekSlot(i: Int): Slot = {
      val x = io.fetchPacketOut.bits.insts(i)
      Slot(l(x.pc), l(x.inst), l(x.ftqIdx).toInt, l(x.slot).toInt,
        b(x.blockEnd), l(x.fault).toInt)
    }
    def cycle(): Unit = {
      io.bootAddrIn.valid.poke(boot.nonEmpty.B)
      boot.foreach(x => io.bootAddrIn.bits.poke(x.U))
      pokeEvent(io.recoveryEventIn, event)
      io.ftqCommitIn.valid.poke(false.B)
      io.ftqCommitIn.bits.ftqIdx.poke(0.U)
      pokeOutcome(io.ftqCommitIn.bits.exit, Outcome())
      io.iTlbReqOut.ready.poke(true.B)
      io.iCacheReqOut.ready.poke(true.B)
      io.fetchPacketOut.ready.poke(packetReady.B)
      val due = pending.headOption.filter(_._1 <= cycleNo)
      io.iCacheRespIn.valid.poke(due.nonEmpty.B)
      due.foreach { case (_, id, base) =>
        io.iCacheRespIn.bits.reqId.poke(id.U)
        io.iCacheRespIn.bits.fault.poke(0.U)
        io.iCacheRespIn.bits.data.zipWithIndex.foreach { case (w, i) =>
          w.poke(word(base + 4L * i).U)
        }
      }

      bootFire = boot.nonEmpty && b(io.bootAddrIn.ready)
      if (b(io.iTlbReqOut.valid)) {
        val x = io.iTlbReqOut.bits
        tlb += Request(cycleNo, l(x.vaddr), l(x.reqId).toInt)
      }
      if (b(io.iCacheReqOut.valid)) {
        val x = io.iCacheReqOut.bits
        val r = Request(cycleNo, l(x.vaddr), l(x.reqId).toInt)
        cache += r
        pending += ((cycleNo + responseLatency, r.id, r.addr))
      }
      if (due.nonEmpty && b(io.iCacheRespIn.ready)) pending.remove(0)
      if (b(io.fetchPacketOut.valid)) {
        val packet = (0 until fp.decodeWidth).filter(i => b(io.fetchPacketOut.bits.valid(i))).map(peekSlot)
        if (packetReady) { packets += packet; slots ++= packet }
        else held += packet
      }
      dut.clock.step(); cycleNo += 1; event = None
    }
    def until(done: => Boolean, limit: Int = 120): Boolean = {
      var k = 0
      while (!done && k < limit) { cycle(); k += 1 }
      done
    }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new FrontendTop(fp)) { dut => val d = new Drv(dut); d.cycle(); body(d) }

  val bootFlow = new SpecTest("frontendTop.bootFlow", Seq("contFrontendTop")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.boot = Some(0x1008L); d.cycle(); val booted = d.bootFire; d.boot = None
      d.until(d.slots.size >= 6)
      val first = d.slots.take(6).toSeq
      val expectedPc = Seq(0x1008L, 0x100cL, 0x1010L, 0x1014L, 0x1018L, 0x101cL)
      Seq(
        chk(booted && d.tlb.take(2).map(_.addr).toSeq == Seq(0x1008L, 0x1010L) &&
          d.cache.take(2).map(_.addr).toSeq == Seq(0x1000L, 0x1010L) &&
          d.tlb.take(2).map(_.cycle) == d.cache.take(2).map(_.cycle),
          "boot PC reaches atomic ITLB and aligned I-cache lookups in FTQ order",
          s"boot=$booted tlb=${d.tlb.take(2)} cache=${d.cache.take(2)}"),
        chk(first.map(_.pc) == expectedPc && first.map(_.inst) == expectedPc.map(d.word) &&
          first.head.slot == 2 && first(1).end && first(5).end &&
          first.forall(_.fault == 0),
          "the fetch buffer delivers mid-block slots then the next full block in order",
          s"$first")
      )
    }
  }

  val backpressure = new SpecTest("frontendTop.backpressure", Seq("contFrontendTop")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.packetReady = false
      d.boot = Some(0x2000L); d.cycle(); d.boot = None
      d.until(d.held.size >= 3)
      val stable = d.held.takeRight(3).toSeq
      d.packetReady = true
      d.until(d.slots.size >= 4)
      Seq(
        chk(stable.size == 3 && stable.distinct.size == 1 && d.packets.nonEmpty,
          "a blocked fetch packet stays stable until DecodeUnit accepts it", s"$stable"),
        chk(d.slots.take(4).map(_.pc).toSeq == Seq(0x2000L, 0x2004L, 0x2008L, 0x200cL),
          "packet backpressure preserves the four instruction PCs", s"${d.slots.take(4)}")
      )
    }
  }

  val recovery = new SpecTest("frontendTop.recovery", Seq("contFrontendTop")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.boot = Some(0x2000L); d.cycle(); d.boot = None
      d.until(d.slots.size >= 2)
      val before = d.slots.size
      d.event = Some(Event(RecoveryKind.ArchRedirect, 0x3004L, 0,
        Outcome(), RecoveryCause.Trap))
      d.cycle()
      d.until(d.slots.size >= before + 3)
      val after = d.slots.drop(before).toSeq
      Seq(
        chk(after.take(3).map(_.pc) == Seq(0x3004L, 0x3008L, 0x300cL) &&
          after.take(3).map(_.inst) == Seq(0x3004L, 0x3008L, 0x300cL).map(d.word),
          "RecoveryEvent discards queued wrong-path fetches and resumes at its mid-block target",
          s"before=$before after=$after"),
        chk(d.tlb.exists(_.addr == 0x3004L) && d.cache.exists(_.addr == 0x3000L),
          "the redirected fetch reaches both external lookup ports", s"tlb=${d.tlb} cache=${d.cache}")
      )
    }
  }

  val all: Seq[SpecTest] = Seq(bootFlow, backpressure, recovery)
}
