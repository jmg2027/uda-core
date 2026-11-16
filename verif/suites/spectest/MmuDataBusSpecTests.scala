package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.{ArrayBuffer, Map, Queue}
import udacore.backend.design.shared.TranslationContext
import udacore.common.tilelink.TLBundle
import udacore.core.design.shared._

/** The real DTLB, PTW, DataCache and DataBusAdapter on one physical PTE path.
  */
class MmuDataBusHarness(val cp: CoreParams) extends Module {
  val mmu                              = Module(new MmuHarness(cp))
  val data                             = Module(new DCacheBusHarness(cp))
  private def port[T <: Data](x: T): T = IO(chiselTypeOf(x))
  val dReq                             = port(mmu.dReq)
  val dOut                             = port(mmu.dLoad)
  val refill                           = port(mmu.dRefill)
  val ctx                              = IO(Input(new TranslationContext))
  val bus                              = IO(new TLBundle(cp.dataBusParams))

  mmu.iReq.valid   := false.B
  mmu.iReq.bits    := 0.U.asTypeOf(mmu.iReq.bits)
  mmu.iOut.ready   := true.B
  mmu.dReq <> dReq
  dOut <> mmu.dLoad
  mmu.dStore.ready := true.B
  refill <> mmu.dRefill
  mmu.ctx          := ctx
  mmu.sfence.valid := false.B
  mmu.sfence.bits  := 0.U.asTypeOf(mmu.sfence.bits)
  data.io.ptwReq <> mmu.memReq
  mmu.memResp <> data.io.ptwResp

  data.io.loadReq.valid      := false.B
  data.io.loadReq.bits       := 0.U.asTypeOf(data.io.loadReq.bits)
  data.io.translation.valid  := false.B
  data.io.translation.bits   := 0.U.asTypeOf(data.io.translation.bits)
  data.io.storeReq.valid     := false.B
  data.io.storeReq.bits      := 0.U.asTypeOf(data.io.storeReq.bits)
  data.io.cleanReq.valid     := false.B
  data.io.cleanReq.bits      := 0.U.asTypeOf(data.io.cleanReq.bits)
  data.io.uncLoadReq.valid   := false.B
  data.io.uncLoadReq.bits    := 0.U.asTypeOf(data.io.uncLoadReq.bits)
  data.io.uncStoreReq.valid  := false.B
  data.io.uncStoreReq.bits   := 0.U.asTypeOf(data.io.uncStoreReq.bits)
  data.io.loadResp.ready     := true.B
  data.io.storeResp.ready    := true.B
  data.io.cleanResp.ready    := true.B
  data.io.uncLoadResp.ready  := true.B
  data.io.uncStoreResp.ready := true.B
  bus.a <> data.io.bus.a
  data.io.bus.d <> bus.d
}

object MmuDataBusSpecTests {
  import PageTableWalkerSpecTests.{l0Addr, l1Addr, leaf, ptr}
  val cp        = CoreParams()
  val beatBytes = cp.dataWidth / 8
  val root      = 0x100L
  val va        = 0x00400010L
  val vpn       = va >>> 12
  case class Tr(id: Int, status: Int, pa: Long, cacheable: Boolean)
  case class Beat(op: Int, size: Int, source: Int, data: Long, denied: Boolean)
  case class Req(op: Int, size: Int, source: Int, address: Long)

  class Drv(val dut: MmuDataBusHarness) {
    val io                           = dut
    val mem                          = Map[Long, Long]().withDefaultValue(0L)
    val answers                      = Queue[Beat]()
    val requests                     = ArrayBuffer[Req]()
    val translations                 = ArrayBuffer[Tr]()
    val refills                      = ArrayBuffer[Int]()
    var deny: Long => Boolean        = _ => false
    var request: Option[(Int, Long)] = None
    var requestFire                  = false
    var cycles                       = 0

    def cycle(): Unit                                      = {
      io.dReq.valid.poke(request.nonEmpty.B)
      request.foreach { case (id, address) =>
        io.dReq.bits.reqId.poke(id.U)
        io.dReq.bits.vaddr.poke(address.U)
        io.dReq.bits.access.poke(AccessType.Load)
      }
      io.dOut.ready.poke(true.B)
      io.refill.ready.poke(true.B)
      io.ctx.satpMode.poke(true.B)
      io.ctx.asid.poke(1.U)
      io.ctx.rootPpn.poke(root.U)
      io.ctx.priv.poke(1.U)
      io.ctx.dataPriv.poke(1.U)
      io.ctx.sum.poke(false.B)
      io.ctx.mxr.poke(false.B)
      io.bus.a.ready.poke(true.B)
      val dValid = answers.nonEmpty
      io.bus.d.valid.poke(dValid.B)
      answers.headOption.foreach { x =>
        val b = io.bus.d.bits
        b.opcode.poke(x.op.U); b.param.poke(0.U); b.size.poke(x.size.U)
        b.source.poke(x.source.U); b.sink.poke(0.U); b.data.poke(x.data.U)
        b.denied.poke(x.denied.B); b.corrupt.poke(false.B)
      }

      requestFire = request.nonEmpty && io.dReq.ready.peek().litToBoolean
      if (io.dOut.valid.peek().litToBoolean) {
        val b = io.dOut.bits
        translations += Tr(
          b.reqId.peek().litValue.toInt,
          b.status.peek().litValue.toInt,
          b.paddr.peek().litValue.toLong,
          b.cacheable.peek().litToBoolean
        )
      }
      if (io.refill.valid.peek().litToBoolean)
        refills += io.refill.bits.status.peek().litValue.toInt
      if (io.bus.a.valid.peek().litToBoolean) {
        val b = io.bus.a.bits
        val r = Req(
          b.opcode.peek().litValue.toInt,
          b.size.peek().litValue.toInt,
          b.source.peek().litValue.toInt,
          b.address.peek().litValue.toLong
        )
        requests += r
        if (r.op == 4) {
          val beats = math.max(1, (1 << r.size) / beatBytes)
          (0 until beats).foreach { i =>
            val address = r.address + i.toLong * beatBytes
            answers.enqueue(
              Beat(1, r.size, r.source, mem(address), deny(address))
            )
          }
        }
      }
      if (dValid && io.bus.d.ready.peek().litToBoolean) answers.dequeue()
      dut.clock.step(); cycles += 1
    }
    def until(done: => Boolean, limit: Int = 500): Boolean = {
      var k = 0
      while (!done && k < limit) { cycle(); k += 1 }
      done
    }
    def translate(id: Int, address: Long): Option[Tr]      = {
      val start = translations.size
      request = Some((id, address)); requestFire = false
      until(requestFire); request = None
      until(translations.size > start)
      translations.lift(start)
    }
  }

  def chk(ok: Boolean, label: String, detail: => String): TCheck  =
    TCheck(ok, label, if (ok) "" else detail)
  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new MmuDataBusHarness(cp)) { dut =>
      val d = new Drv(dut); d.cycle(); body(d)
    }

  val twoLevel = new SpecTest(
    "mmubus.twoLevel",
    Seq("funcSv32Walk", "funcDCachePhysicalRead", "funcDataBusTransaction")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val l1   = l1Addr(root, vpn)
      val l0   = l0Addr(0x200, vpn)
      d.mem(l1) = ptr(0x200).word
      d.mem(l0) = leaf(0x300).word
      val miss = d.translate(0, va)
      d.until(d.refills.size == 1)
      val hit  = d.translate(4, va + 4)
      Seq(
        chk(
          miss.contains(Tr(0, 1, 0, false)) &&
            hit.contains(Tr(4, 0, 0x300014L, true)),
          "the DTLB retries into the two-level translation fetched over the data bus",
          s"miss=$miss hit=$hit refill=${d.refills}"
        ),
        chk(
          d.refills.toSeq == Seq(0) &&
            d.requests.toSeq == Seq(
              Req(4, 6, 0, l1 & ~63L),
              Req(4, 6, 0, l0 & ~63L)
            ),
          "the PTW fetches both PTE lines through DataCache and DataBusAdapter",
          s"refill=${d.refills} A=${d.requests}"
        )
      )
    }
  }

  val denied = new SpecTest(
    "mmubus.denied",
    Seq(
      "funcPtwPhysicalAccess",
      "propWalkFaultTyping",
      "funcDCachePhysicalRead"
    )
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val l1    = l1Addr(root, vpn)
      d.mem(l1) = ptr(0x200).word
      d.deny = _ == l1
      val miss  = d.translate(0, va)
      d.until(d.refills.size == 1)
      val fault = d.translate(4, va + 4)
      Seq(
        chk(
          miss.contains(Tr(0, 1, 0, false)) &&
            d.refills.toSeq == Seq(2) && fault.contains(Tr(4, 3, 0, false)),
          "a denied PTE fill is recorded as a DTLB access fault after refill",
          s"miss=$miss refill=${d.refills} fault=$fault"
        ),
        chk(
          d.requests.toSeq == Seq(Req(4, 6, 0, l1 & ~63L)),
          "a denied level-1 PTE does not trigger a level-0 bus read",
          s"${d.requests}"
        )
      )
    }
  }

  val all: Seq[SpecTest] = Seq(twoLevel, denied)
}
