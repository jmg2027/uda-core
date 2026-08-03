package udacore.backend.design.modules

import chisel3._
import chisel3.util.BitPat
import chisel3.util._
import chisel3.util.experimental.decode.{
  DecodeField,
  DecodePattern,
  DecodeTable
}
import chisel3.experimental.BundleLiterals._
import udacore.common._
import udacore.common.enums._
import udacore.common.ControlSignal._
import udacore.common.isa._
import udacore.backend.design.shared._

// AGENT: DO NOT TOUCH CORE LOGIC
/** Result of decoding an instruction. */
class Decoded(val params: BackendParams) extends BackendBundle {
  val imm = new Bundle {
    val i = SInt(xLen.W)
    val u = SInt(xLen.W)
    val j = SInt(xLen.W)
    val b = SInt(xLen.W)
    val s = SInt(xLen.W)
  }

  val rd  = UInt(regIdWidth.W)
  val rs1 = UInt(regIdWidth.W)
  val rs2 = UInt(regIdWidth.W)

  val illegal       = IllegalInstruction()
  val operandSelect = new Bundle {
    val a = OperandType()
    val b = OperandType()
  }

  val aluCtrl = AluControl()

  val csrCtrl = CSRControl()

  val isWriteback = IsWriteback()

  val branchCtrl = BranchControl()

  val storeCtrl = StoreControl()
  val loadCtrl  = LoadControl()

  val ecall  = Ecall()
  val ebreak = Ebreak()
  val mret   = MRet()

  // for debugger
  val dret = DRet()

  val fence       = Fence()
  val flushICache = FlushICache()
  val wfi         = WFI()

  val divCtrl        = DividerControl()
  val multiplierCtrl = MultiplierControl()

  val bitALUCtrl = BitAluControl()
}

/** Instruction decoder generating control signals. */
class DecodeCore(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    val inst  = Input(UInt(iLen.W))
    val out = Output(new Decoded(params))
  })

  val d = io.out

  val decodeMapping = Seq(
    IllegalField       -> d.illegal,
    OpAField           -> d.operandSelect.a,
    OpBField           -> d.operandSelect.b,
    AluField           -> d.aluCtrl,
    CSRControlField    -> d.csrCtrl,
    IsWritebackField   -> d.isWriteback,
    BranchControlField -> d.branchCtrl,
    StoreControlField  -> d.storeCtrl,
    LoadControlField   -> d.loadCtrl,
    ECallField         -> d.ecall,
    EBreakField        -> d.ebreak,
    MRetField          -> d.mret,
    // for debugger
    DRetField          -> d.dret,
    FenceField         -> d.fence,
    FlushICacheField   -> d.flushICache,
    WFIField           -> d.wfi,
    DividerField       -> d.divCtrl,
    MultiplierField    -> d.multiplierCtrl,
    BitAluField        -> d.bitALUCtrl
  )

  var instTable =
    RV32IDecode.table ++
      (if (enableMulDiv) RV32MDecode.table else Nil)

  if (enableBitAlu) instTable = instTable ++ RV32BDecode.table

//  val decodeTable = new DecodeTable(instTable, decodeMapping.unzip._1)
  val decodeTable =
    new DecodeTableWithDefault(instTable, decodeMapping.unzip._1)
  val decodedInst = decodeTable.decode(io.inst)

  // Set Output
  decodeMapping.map { case (a, b) =>
    b := decodedInst(a)
  }

  val instValue = InstParser(io.inst)
  d.rd  := instValue.rd
  d.rs1 := instValue.rs1
  d.rs2 := instValue.rs2

  d.imm.i := instValue.iimm.asSInt
  d.imm.s := instValue.simm.asSInt
  d.imm.b := instValue.bimm.asSInt
  d.imm.u := instValue.uimm.asSInt
  d.imm.j := instValue.jimm.asSInt
}

case class InstParser(data: UInt) {
  def rd  = data(11, 7)
  def rs1 = data(19, 15)
  def rs2 = data(24, 20)

  def iimm = data(31, 20)
  def simm = data(31, 25) ## data(11, 7)
  def bimm = data(31) ## data(7) ## data(30, 25) ## data(11, 8) ## 0.U(1.W)
  def uimm = data(31, 12) ## 0.U(12.W)
  def jimm = data(31) ## data(19, 12) ## data(20) ## data(30, 21) ## 0.U(1.W)
}
