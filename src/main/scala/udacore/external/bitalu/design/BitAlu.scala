package udacore.external.bitalu.design

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.common.ControlSignal._
import udacore.common.util.bit.BitSliceUtil._
import udacore.external.bitalu.spec.BitAluSpecs._

@LocalSpec(intfBitAluIO)
class BitAluIO(dataWidth: Int) extends Bundle {
  val start = Input(Bool())
  val srcA  = Input(UInt(dataWidth.W))
  val srcB  = Input(UInt(dataWidth.W))
  val ctrl  = Input(BitAluControl())
  val result = Output(UInt(dataWidth.W))
  val done   = Output(Bool())
}

@LocalSpec(paramBitAlu)
case class BitAluParams(
    dataWidth: Int,
    enableZba: Boolean = true,
    enableZbb: Boolean = true,
    enableZbs: Boolean = true,
    enableZbc: Boolean = true
)

@LocalSpec(contBitAlu)
class BitAlu(params: BitAluParams) extends Module {
  val io = IO(new BitAluIO(params.dataWidth))

  private val width = params.dataWidth
  private val config = params

  def clmul(x: UInt, y: UInt): UInt = {
    val partials = Wire(Vec(width, UInt((width * 2).W)))
    for (i <- 0 until width) {
      partials(i) := Mux(y(i), x.asUInt << i, 0.U)
    }

    def reduceTree(vec: Seq[UInt]): UInt = vec match {
      case Seq()  => 0.U
      case Seq(a) => a
      case _      =>
        val paired = vec
          .grouped(2)
          .map {
            case Seq(a, b) => a ^ b
            case Seq(a)    => a
          }
          .toSeq
        reduceTree(paired)
    }

    reduceTree(partials).asUInt
  }

  val result = WireDefault(0.U(width.W))
  val valid  = WireDefault(false.B)

  when(io.start) {
    switch(io.ctrl) {
      is(BitAluControl.BCLR) {
        if (config.enableZbs) result := io.srcA & (~(1.U << io.srcB(4, 0)).asUInt).asUInt
      }
      is(BitAluControl.BSET) {
        if (config.enableZbs) result := io.srcA | (1.U << io.srcB(4, 0)).asUInt
      }
      is(BitAluControl.BINV) {
        if (config.enableZbs) result := io.srcA ^ (1.U << io.srcB(4, 0)).asUInt
      }
      is(BitAluControl.BEXT) {
        if (config.enableZbs) result := (io.srcA >> io.srcB(4, 0))(0)
      }

      is(BitAluControl.BCLRI) {
        if (config.enableZbs) result := io.srcA & (~(1.U << io.srcB(4, 0)).asUInt).asUInt
      }
      is(BitAluControl.BSETI) {
        if (config.enableZbs) result := io.srcA | (1.U << io.srcB(4, 0)).asUInt
      }
      is(BitAluControl.BINVI) {
        if (config.enableZbs) result := io.srcA ^ (1.U << io.srcB(4, 0)).asUInt
      }
      is(BitAluControl.BEXTI) {
        if (config.enableZbs) result := (io.srcA >> io.srcB(4, 0))(0)
      }

      is(BitAluControl.CLZ) {
        if (config.enableZbb) result := PriorityEncoder(Reverse(io.srcA))
      }
      is(BitAluControl.CPOP) {
        if (config.enableZbb) result := PopCount(io.srcA)
      }
      is(BitAluControl.CTZ) {
        if (config.enableZbb) result := PriorityEncoder(io.srcA)
      }

      is(BitAluControl.MIN) {
        if (config.enableZbb) result := Mux(io.srcA.asSInt < io.srcB.asSInt, io.srcA, io.srcB)
      }
      is(BitAluControl.MINU) {
        if (config.enableZbb) result := Mux(io.srcA < io.srcB, io.srcA, io.srcB)
      }
      is(BitAluControl.MAX) {
        if (config.enableZbb) result := Mux(io.srcA.asSInt > io.srcB.asSInt, io.srcA, io.srcB)
      }
      is(BitAluControl.MAXU) {
        if (config.enableZbb) result := Mux(io.srcA > io.srcB, io.srcA, io.srcB)
      }

      is(BitAluControl.ZEXT_H) {
        if (config.enableZbb) result := Cat(0.U(16.W), io.srcA(15, 0))
      }
      is(BitAluControl.SEXT_B) {
        if (config.enableZbb) result := Cat(Fill(24, io.srcA(7)), io.srcA(7, 0))
      }
      is(BitAluControl.SEXT_H) {
        if (config.enableZbb) result := Cat(Fill(16, io.srcA(15)), io.srcA(15, 0))
      }

      is(BitAluControl.ANDN) {
        if (config.enableZbb) result := io.srcA & (~io.srcB).asUInt
      }
      is(BitAluControl.ORN) {
        if (config.enableZbb) result := io.srcA | (~io.srcB).asUInt
      }
      is(BitAluControl.XNOR) {
        if (config.enableZbb) result := ~(io.srcA ^ io.srcB).asUInt
      }

      is(BitAluControl.ROL) {
        if (config.enableZbb)
          result := (io.srcA << io.srcB(4, 0)).asUInt | (io.srcA >> (width.U - io.srcB(4, 0))).asUInt
      }
      is(BitAluControl.ROR) {
        if (config.enableZbb)
          result := (io.srcA >> io.srcB(4, 0)).asUInt | (io.srcA << (width.U - io.srcB(4, 0))).asUInt
      }
      is(BitAluControl.RORI) {
        if (config.enableZbb)
          result := (io.srcA >> io.srcB(4, 0)).asUInt | (io.srcA << (width.U - io.srcB(4, 0))).asUInt
      }

      is(BitAluControl.REV8) {
        if (config.enableZbb) result := Cat(split(io.srcA, 8).map(b => Reverse(b)))
      }
      is(BitAluControl.ORC_B) {
        if (config.enableZbb) result := Cat(split(io.srcA, 8).map(b => Fill(8, b.orR)))
      }

      is(BitAluControl.SH1ADD) {
        if (config.enableZba) result := (io.srcA << 1).asUInt + io.srcB
      }
      is(BitAluControl.SH2ADD) {
        if (config.enableZba) result := (io.srcA << 2).asUInt + io.srcB
      }
      is(BitAluControl.SH3ADD) {
        if (config.enableZba) result := (io.srcA << 3).asUInt + io.srcB
      }

      is(BitAluControl.CLMUL) {
        if (config.enableZbc) result := clmul(io.srcA, io.srcB)(width - 1, 0)
      }
      is(BitAluControl.CLMULH) {
        if (config.enableZbc) result := clmul(io.srcA, io.srcB)(2 * width - 1, width)
      }
      is(BitAluControl.CLMULR) {
        if (config.enableZbc) result := clmul(io.srcA, Reverse(io.srcB))(width - 1, 0)
      }
    }
    valid := true.B
  }

  @LocalSpec(funcExecuteBitAlu)
  val execute = {
    io.result := result
    io.done   := valid
  }
}
