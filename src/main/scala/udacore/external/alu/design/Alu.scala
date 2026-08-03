package udacore.external.alu.design

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.common.ControlSignal._
import udacore.external.alu.spec.AluSpecs._

@LocalSpec(intfAluIO)
class AluIO(dataWidth: Int) extends Bundle {
  val srcA  = Input(UInt(dataWidth.W))
  val srcB  = Input(UInt(dataWidth.W))
  val ctrl  = Input(AluControl())
  val result = Output(UInt(dataWidth.W))
}

@LocalSpec(paramAlu)
case class AluParams(dataWidth: Int)

@LocalSpec(contAlu)
class Alu(params: AluParams) extends Module {
  val io = IO(new AluIO(params.dataWidth))

  import udacore.common.ControlSignal.AluControl._

  private val width = params.dataWidth

  val isSub             = io.ctrl.asUInt(3)
  val isCompareUnsigned = io.ctrl.asUInt(0)
  val isCompareInversed = io.ctrl.asUInt(1)
  val isCompareEqual    = !io.ctrl.asUInt(3)

  val bInv        = Mux(isSub, ~io.srcB, io.srcB).asUInt
  val adderResult = io.srcA + bInv + isSub
  val aXorB       = io.srcA ^ io.srcB

  val lt =
    Mux(
      io.srcA(width - 1) === io.srcB(width - 1),
      adderResult(width - 1),
      Mux(isCompareUnsigned, io.srcB(width - 1), io.srcA(width - 1))
    )

  val isCompareOp = io.ctrl === EQ || io.ctrl === NE ||
    io.ctrl === LT || io.ctrl === LTU ||
    io.ctrl === GE || io.ctrl === GEU

  val compareFlagRaw =
    isCompareInversed ^ Mux(isCompareEqual, aXorB === 0.U, lt)
  val compareFlag  = Mux(isCompareOp, compareFlagRaw, false.B)
  val compareValue = Mux(compareFlag, 1.U(width.W), 0.U(width.W))

  val shamt   = io.srcB(4, 0)
  val shinRaw = io.srcA
  val shin = Mux(io.ctrl === SRL || io.ctrl === SRA, shinRaw, Reverse(shinRaw))
  val shoutRight = (Cat(isSub & shin(width - 1), shin).asSInt >> shamt)(width - 1, 0)
  val shoutLeft  = Reverse(shoutRight)
  val shout =
    Mux(io.ctrl === SRL || io.ctrl === SRA, shoutRight, 0.U(width.W)) |
      Mux(io.ctrl === SLL, shoutLeft, 0.U(width.W))

  val logic = Mux(io.ctrl === XOR || io.ctrl === OR, aXorB, 0.U(width.W)) |
    Mux(io.ctrl === OR || io.ctrl === AND, io.srcA & io.srcB, 0.U(width.W))

  val slt = Mux(io.ctrl === SLT || io.ctrl === SLTU, lt, 0.U(width.W))

  val arithmeticResult = Mux(
    io.ctrl === ADD || io.ctrl === SUB,
    adderResult,
    shout | logic | slt
  )

  @LocalSpec(funcComputeAlu)
  val compute = {
    io.result := Mux(isCompareOp, compareValue, arithmeticResult)
  }
}
