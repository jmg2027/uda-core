package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.common.system.csr.{Counter, Csr, Shadow}

/** L1 SpecTests for the CSR decorator library (common/system/csr): the write
  * proposal pipeline, legalize totality, the counter decorator, and the shadow
  * (windowed storage) decorator, driven through a small testbed module.
  */

private class CsrScratchTb extends Bundle {
  val hi = UInt(28.W)
  val lo = UInt(4.W)
}

private class CsrWinTb extends Bundle {
  val lo = UInt(4.W)
}

private class CsrParentTb extends Bundle {
  val hi  = UInt(28.W)
  val win = new CsrWinTb
}

private class CsrTestBed extends Module {
  val io = IO(new Bundle {
    val wen        = Input(Bool())
    val wdata      = Input(UInt(32.W))
    val rdata      = Output(UInt(32.W))
    val inhibit    = Input(Bool())
    val count      = Output(UInt(32.W))
    val shadowWen  = Input(Bool())
    val shadowW    = Input(UInt(4.W))
    val parentRead = Output(UInt(32.W))
    val shadowRead = Output(UInt(4.W))
  })

  // Plain CSR with a WARL legalize: the low nibble is hard-wired to zero.
  private val scratch = Csr(
    field = new CsrScratchTb,
    default = 0.U,
    legalize = (x: CsrScratchTb) => { val v = Wire(new CsrScratchTb); v := x; v.lo := 0.U; v }
  ).named("tbScratch")
  when(io.wen) { scratch.write(io.wdata) }
  io.rdata := scratch.reg.asUInt

  // Self-incrementing counter with an inhibit line.
  private val cnt = Counter(dataWidth = 32, inc = 1.U, inhibit = io.inhibit).named("tbCounter")
  io.count := cnt.reg.data

  // Parent + Shadow window: the shadow's storage IS the parent's win subfield
  // (no standalone register), so shadow writes land in the parent.
  private val parent = Csr(field = new CsrParentTb, default = 0.U).named("tbParent")
  private val shadow = Shadow[CsrWinTb, CsrParentTb](parent, (p: CsrParentTb) => p.win)
  when(io.shadowWen) { shadow.write(io.shadowW) }
  io.parentRead := parent.reg.asUInt
  io.shadowRead := shadow.reg.asUInt
}

object CsrSpecTests {
  val library = new SpecTest("csr.library",
      Seq("funcCsrWriteProtocol", "propCsrLegalizeTotal", "funcCsrCounter", "funcCsrShadow")) {
    def run(): Seq[TCheck] = sim(new CsrTestBed) { dut =>
      val out = scala.collection.mutable.ArrayBuffer[TCheck]()
      def step() = dut.clock.step()
      dut.io.wen.poke(false.B); dut.io.shadowWen.poke(false.B); dut.io.inhibit.poke(true.B)
      step()

      // funcCsrWriteProtocol + propCsrLegalizeTotal: write all-ones; the stored value
      // must be the LEGALIZED one (low nibble zeroed), visible on the next cycle.
      dut.io.wdata.poke("hFFFFFFFF".U)
      dut.io.wen.poke(true.B); step(); dut.io.wen.poke(false.B); step()
      val got = dut.io.rdata.peek().litValue.toLong & 0xFFFFFFFFL
      out += TCheck(got == 0xFFFFFFF0L, "write-then-legalized-read", f"got=0x$got%08x exp=0xfffffff0")

      // funcCsrCounter: inhibited counter holds; released counter increments by 1/cycle.
      val c0 = dut.io.count.peek().litValue.toLong
      step(); step()
      val cHeld = dut.io.count.peek().litValue.toLong
      out += TCheck(cHeld == c0, "counter-inhibit-holds", s"c0=$c0 held=$cHeld")
      dut.io.inhibit.poke(false.B); step(); step(); step(); dut.io.inhibit.poke(true.B); step()
      val c3 = dut.io.count.peek().litValue.toLong
      out += TCheck(c3 == c0 + 3, "counter-increments", s"c0=$c0 after3=$c3")

      // funcCsrShadow: a shadow write lands in the parent's windowed subfield only.
      dut.io.shadowW.poke(0xA.U)
      dut.io.shadowWen.poke(true.B); step(); dut.io.shadowWen.poke(false.B); step()
      val pr = dut.io.parentRead.peek().litValue.toLong & 0xFFFFFFFFL
      val sr = dut.io.shadowRead.peek().litValue.toLong & 0xFL
      out += TCheck(sr == 0xAL, "shadow-read-window", f"shadow=0x$sr%x exp=0xa")
      out += TCheck(pr == 0xAL, "shadow-writes-parent-subfield-only", f"parent=0x$pr%08x exp=0x0000000a")
      out.toSeq
    }
  }

  val all: Seq[SpecTest] = Seq(library)
}
