package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.core.design.modules.CacheLineSram

/** Observe SRAM transfers at rising edges, not in the macro's output-invalidating half-cycle. */
class CacheSramTestProbe extends CacheLineSram(512, 2) {
  val observedCount = IO(Output(UInt(16.W)))
  val observedData = IO(Output(Vec(2, UInt(32.W))))
  val count = RegInit(0.U(16.W))
  val captured = Reg(Vec(2, UInt(32.W)))
  when(io.readResp.fire) { count := count + 1.U; captured := io.readResp.bits }
  observedCount := count
  observedData := captured
}

object CacheLineSramSpecTests {
  val storage = new SpecTest("sram.storage", Seq("funcSramRead", "funcSramWrite", "funcSramPhysicalBanks")) {
    def run(): Seq[TCheck] = sim(new CacheSramTestProbe) { dut =>
      val io = dut.io
      io.readReq.valid.poke(false.B)
      io.readResp.ready.poke(false.B)
      io.writeReq.valid.poke(false.B)

      def write(row: Int, a: Long, b: Long, ma: Int = 15, mb: Int = 15): Unit = {
        io.writeReq.bits.row.poke(row.U)
        io.writeReq.bits.data(0).poke(a.U); io.writeReq.bits.data(1).poke(b.U)
        io.writeReq.bits.mask(0).poke(ma.U); io.writeReq.bits.mask(1).poke(mb.U)
        io.writeReq.valid.poke(true.B)
        require(io.writeReq.ready.peek().litToBoolean)
        dut.clock.step()
        io.writeReq.valid.poke(false.B)
      }
      def request(row: Int): Unit = {
        io.readReq.bits.poke(row.U); io.readReq.valid.poke(true.B)
        require(io.readReq.ready.peek().litToBoolean)
        dut.clock.step()
        io.readReq.valid.poke(false.B)
        // The simulator stops at rising edges; allow the intervening falling-edge read
        // to reach the next sampling edge before inspecting a backpressured response.
        dut.clock.step()
      }
      def answer: Seq[Long] = io.readResp.bits.map(_.peek().litValue.toLong).toSeq
      def drain(): Unit = {
        io.readResp.ready.poke(true.B); dut.clock.step(); io.readResp.ready.poke(false.B)
      }
      write(0, 0x12345678L, 0x89abcdefL)
      write(255, 0x55L, 0x66L)
      write(256, 0x77L, 0x88L)
      write(511, 0x99L, 0xaaL)
      request(0)
      var stable = io.readResp.valid.peek().litToBoolean && answer == Seq(0x12345678L, 0x89abcdefL)
      (0 until 5).foreach { _ => dut.clock.step(); stable &&=
        io.readResp.valid.peek().litToBoolean && answer == Seq(0x12345678L, 0x89abcdefL) }
      drain()
      val boundaries = Seq((255, Seq(0x55L, 0x66L)), (256, Seq(0x77L, 0x88L)), (511, Seq(0x99L, 0xaaL)))
        .forall { case (row, expected) => request(row); val ok = answer == expected; drain(); ok }
      write(0, 0xffL, 0xee000000L, 1, 8)
      request(0)
      val bytes = answer == Seq(0x123456ffL, 0xeeabcdefL)
      drain()

      io.readReq.valid.poke(true.B); io.readReq.bits.poke(0.U)
      io.writeReq.valid.poke(true.B); io.writeReq.bits.row.poke(0.U)
      io.writeReq.bits.data(0).poke(0x11.U); io.writeReq.bits.data(1).poke(0x22.U)
      io.writeReq.bits.mask.foreach(_.poke(15.U))
      val serialized = io.writeReq.ready.peek().litToBoolean && !io.readReq.ready.peek().litToBoolean
      dut.clock.step(); io.writeReq.valid.poke(false.B)
      require(io.readReq.ready.peek().litToBoolean)
      dut.clock.step(); io.readReq.valid.poke(false.B)
      dut.clock.step()
      val collision = serialized && answer == Seq(0x11L, 0x22L)
      drain()

      io.readReq.valid.poke(true.B); io.readReq.bits.poke(256.U)
      io.writeReq.valid.poke(true.B); io.writeReq.bits.row.poke(255.U)
      io.writeReq.bits.data(0).poke(0x33.U); io.writeReq.bits.data(1).poke(0x44.U)
      val parallel = io.readReq.ready.peek().litToBoolean && io.writeReq.ready.peek().litToBoolean
      dut.clock.step(); io.readReq.valid.poke(false.B); io.writeReq.valid.poke(false.B)
      dut.clock.step()
      val independent = parallel && answer == Seq(0x77L, 0x88L)
      drain()

      val before = dut.observedCount.peek().litValue
      io.readResp.ready.poke(true.B)
      io.readReq.valid.poke(true.B); io.readReq.bits.poke(0.U)
      dut.clock.step()
      io.readReq.bits.poke(255.U); dut.clock.step()
      val first = dut.observedData.map(_.peek().litValue.toLong).toSeq
      io.readReq.bits.poke(511.U); dut.clock.step()
      val second = dut.observedData.map(_.peek().litValue.toLong).toSeq
      io.readReq.valid.poke(false.B); dut.clock.step()
      val third = dut.observedData.map(_.peek().litValue.toLong).toSeq
      val throughput = dut.observedCount.peek().litValue == before + 3 &&
        first == Seq(0x11L, 0x22L) && second == Seq(0x33L, 0x44L) && third == Seq(0x99L, 0xaaL)
      Seq(TCheck(stable, "blocked SRAM output survives clock edges with stable bytes"),
        TCheck(boundaries, "word banks and physical page boundaries retain their row identity"),
        TCheck(bytes, "SRAM byte masks preserve every unselected byte"),
        TCheck(collision, "same-row write backpressures the read and the retry sees new bytes"),
        TCheck(independent, "different-row read and write can transfer together"),
        TCheck(throughput, "rising-edge consumers capture back-to-back reads at one per cycle"))
    }
  }
  val all: Seq[SpecTest] = Seq(storage)
}
