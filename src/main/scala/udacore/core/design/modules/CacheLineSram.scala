package udacore.core.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.core.spec.modules.CacheLineSramSpecs._

@LocalSpec(bndCacheLineWrite)
class CacheLineWrite(rows: Int, words: Int, wordBits: Int) extends Bundle {
  val row = UInt(math.max(1, log2Ceil(rows)).W)
  val data = Vec(words, UInt(wordBits.W))
  val mask = Vec(words, UInt((wordBits / 8).W))
}

/** Clocked line storage backed by physical 1RW1R word macros (ADR-020).
  * SRAM/holder state is cache-local and recovery-exempt; requests are never canceled here.
  */
@LocalSpec(contCacheLineSram)
class CacheLineSram(val rows: Int, val words: Int, val wordBits: Int = 32) extends Module {
  require(rows > 0 && words > 0 && wordBits == 32)
  val io = IO(new Bundle {
    @LocalSpec(intfSramReadReq)
    val readReq = Flipped(Decoupled(UInt(math.max(1, log2Ceil(rows)).W)))
    @LocalSpec(intfSramReadResp)
    val readResp = Decoupled(Vec(words, UInt(wordBits.W)))
    @LocalSpec(intfSramWriteReq)
    val writeReq = Flipped(Decoupled(new CacheLineWrite(rows, words, wordBits)))
  })
  private val pages = (rows + 255) / 256
  private val pageWidth = math.max(1, log2Ceil(pages))
  val pending = RegInit(false.B)
  val heldValid = RegInit(false.B)
  val held = Reg(Vec(words, UInt(wordBits.W)))
  val readPage = Reg(UInt(pageWidth.W))
  private def pageOf(row: UInt): UInt = if (pages == 1) 0.U(pageWidth.W) else row >> 8
  private val collision = io.writeReq.valid && io.writeReq.bits.row === io.readReq.bits

  @LocalSpec(funcSramPhysicalBanks)
  private val banks = Seq.tabulate(words, pages) { (_, _) => Module(new Sky130CacheSram) }

  @LocalSpec(funcSramRead)
  val read: Unit = {
    io.readResp.valid := pending || heldValid
    val raw = VecInit((0 until words).map(w =>
      Mux1H((0 until pages).map(p => (readPage === p.U) -> banks(w)(p).io.dout1))))
    io.readResp.bits := Mux(heldValid, held, raw)
    io.readReq.ready := !reset.asBool && (!io.readResp.valid || io.readResp.ready) && !collision
    pending := io.readReq.fire
    when(io.readReq.fire) {
      assert(io.readReq.bits < rows.U, "SramRead: row is outside the logical array")
      readPage := pageOf(io.readReq.bits)
    }
    when(io.readResp.fire) { heldValid := false.B }
    when(pending && !io.readResp.ready) { held := raw; heldValid := true.B }
    for (w <- 0 until words; p <- 0 until pages) {
      val b = banks(w)(p).io
      b.clk1 := clock
      b.csb1 := !(io.readReq.fire && pageOf(io.readReq.bits) === p.U)
      b.addr1 := io.readReq.bits.pad(8)(7, 0)
    }
    assert(!(pending && heldValid), "SramRead: both response owners are live")
  }

  @LocalSpec(funcSramWrite)
  val write: Unit = {
    io.writeReq.ready := !reset.asBool
    when(io.writeReq.valid) {
      assert(io.writeReq.bits.row < rows.U, "SramWrite: row is outside the logical array")
    }
    for (w <- 0 until words; p <- 0 until pages) {
      val b = banks(w)(p).io
      b.clk0 := clock
      b.csb0 := !(io.writeReq.fire && pageOf(io.writeReq.bits.row) === p.U &&
        io.writeReq.bits.mask(w).orR)
      b.web0 := false.B
      b.addr0 := io.writeReq.bits.row.pad(8)(7, 0)
      b.din0 := io.writeReq.bits.data(w)
      b.wmask0 := io.writeReq.bits.mask(w)
    }
  }
}

/** Functional view of the pinned OpenRAM leaf; physical synthesis resolves its Liberty cell.
  * The edge sequence and invalid-output semantics match the upstream model. Its arbitrary
  * #DELAY/#T_HOLD values are omitted; NBA invalidation preserves same-edge consumer capture.
  * Provenance: verif/sta/sram/manifest.json, VLSIDA/sky130_sram_macros commit 965df150.
  */
@LocalSpec(rawCacheSramMacro)
private class Sky130CacheSram extends BlackBox with HasBlackBoxInline {
  override def desiredName = "sky130_sram_1kbyte_1rw1r_32x256_8"
  val io = IO(new Bundle {
    val clk0 = Input(Clock())
    val csb0 = Input(Bool())
    val web0 = Input(Bool())
    val wmask0 = Input(UInt(4.W))
    val addr0 = Input(UInt(8.W))
    val din0 = Input(UInt(32.W))
    val dout0 = Output(UInt(32.W))
    val clk1 = Input(Clock())
    val csb1 = Input(Bool())
    val addr1 = Input(UInt(8.W))
    val dout1 = Output(UInt(32.W))
  })
  setInline("sky130_cache_sram_functional.sv", """
`ifndef SYNTHESIS
module sky130_sram_1kbyte_1rw1r_32x256_8 (
  input clk0, csb0, web0,
  input [3:0] wmask0,
  input [7:0] addr0,
  input [31:0] din0,
  output reg [31:0] dout0,
  input clk1, csb1,
  input [7:0] addr1,
  output reg [31:0] dout1
);
  reg [31:0] mem [0:255];
  reg csb0_reg, web0_reg, csb1_reg;
  reg [3:0] wmask0_reg;
  reg [7:0] addr0_reg, addr1_reg;
  reg [31:0] din0_reg;
  integer i;
  always @(posedge clk0 or negedge clk0) begin
    if (clk0) begin
      csb0_reg <= csb0;
      web0_reg <= web0;
      wmask0_reg <= wmask0;
      addr0_reg <= addr0;
      din0_reg <= din0;
      dout0 <= 32'bx;
    end else begin
      if (!csb0_reg && !web0_reg)
        for (i = 0; i < 4; i = i + 1)
          if (wmask0_reg[i]) mem[addr0_reg][i*8 +: 8] <= din0_reg[i*8 +: 8];
      if (!csb0_reg && web0_reg) dout0 <= mem[addr0_reg];
    end
  end
  always @(posedge clk1 or negedge clk1) begin
    if (clk1) begin
      csb1_reg <= csb1;
      addr1_reg <= addr1;
      dout1 <= 32'bx;
    end else if (!csb1_reg) dout1 <= mem[addr1_reg];
  end
endmodule
`endif
""")
}
