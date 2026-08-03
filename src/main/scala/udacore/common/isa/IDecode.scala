package udacore.common.isa

import chisel3._
import chisel3.util._

import chisel3.util.experimental.decode.{
  DecodeField,
  DecodePattern,
  BoolDecodeField,
  DecodeTable,
  TruthTable
}
import udacore.common.enums._
import udacore.common.ControlSignal._

/** Definitions of Decoder Base classes
  */

class InstProperty(val v: Data) {
  var chain: List[InstProperty] = List(this)
  var isCompose                 = false

  def ++(prop: InstProperty) = {
    this.chain = this.chain ++ prop.chain
    this
  }

  def this(prop: InstProperty) = {
    this(0.U)
    this ++ prop
    this.isCompose = true
  }

  def toList = chain.filter(!_.isCompose)
}

case class InstPattern(val pat: BitPat, val prop: InstProperty)
    extends DecodePattern {
  def this(code: String, prop: InstProperty) = this(BitPat(code), prop)
  def bitPat: BitPat = pat
}

trait DecodeFieldWithDefault[T <: DecodePattern, D <: Data]
    extends DecodeField[T, D] {
  def default: BitPat
}

trait BoolDecodeFieldWithDefault[T <: DecodePattern]
    extends DecodeFieldWithDefault[T, Bool] {
  def chiselType: Bool = Bool()

  def y: BitPat = BitPat.Y(1)

  def n: BitPat = BitPat.N(1)
}

abstract class InstEnumField(e: SnitchEnum)
    extends DecodeFieldWithDefault[InstPattern, EnumType] {
  override def default = BitPat(e.default.litOption.get.U(e.getWidth.W))
  // def default = BitPat(e.default.litOption.get.U(e.getWidth.W))
  def typeChecker: (InstProperty => Boolean)

  override def chiselType: EnumType     = e()
  override def genTable(p: InstPattern) = {
    val ret = p.prop.toList
      .filter(typeChecker)
      .map(_.v.asInstanceOf[EnumType])
      .headOption
      .getOrElse(e.default)
    BitPat(ret.litOption.get.U(e.getWidth.W))
  }
}

abstract class InstBoolField(_default: Bool = false.B)
    extends BoolDecodeFieldWithDefault[InstPattern] {
  override def default = BitPat(_default) // Every default value is 0
  def typeChecker: (InstProperty => Boolean)

  override def genTable(p: InstPattern) = {
    val ret = p.prop.toList
      .filter(typeChecker)
      .map(_.v.asInstanceOf[UInt])
      .headOption
      .getOrElse(false.B)
    BitPat(ret)
  }
}

// Custom extension for decode
class DecodeTableWithDefault[
    I <: DecodePattern,
    D <: Data,
    T <: DecodeFieldWithDefault[I, _ <: Data]
](patterns: Seq[I], fields: Seq[T])
    extends DecodeTable[I](patterns, fields) {
  val _table   = patterns.map { op =>
    op.bitPat -> fields.reverse.map(field => field.genTable(op)).reduce(_ ## _)
  }
  val _default =
    fields.reverse.map(field => field.default).reduce(_ ## _)

  // override val table: TruthTable = TruthTable(_table, _default)
  override val table: TruthTable = TruthTable(_table, _default)
}

/** Definitions of Decoder Properties and Fields
  */

case class IllegalProperty(op: IllegalInstruction.Type) extends InstProperty(op)
object IllegalField                                     extends InstEnumField(IllegalInstruction) {
  override def name        = "illegal"
  override def default     = BitPat(
    IllegalInstruction.illegal.litOption.get.U(IllegalInstruction.getWidth.W)
  )
  override def typeChecker = _.isInstanceOf[IllegalProperty]
}

case class OpAProperty(op: OperandType.Type) extends InstProperty(op)
object OpAField                              extends InstEnumField(OperandType) {
  override def name        = "op a"
  override def typeChecker = _.isInstanceOf[OpAProperty]
}

case class OpBProperty(op: OperandType.Type) extends InstProperty(op)
object OpBField                              extends InstEnumField(OperandType) {
  override def name        = "op b"
  override def typeChecker = _.isInstanceOf[OpBProperty]
}

case class AluProperty(op: AluControl.Type) extends InstProperty(op)
object AluField                             extends InstEnumField(AluControl) {
  override def name        = "alu"
  override def typeChecker = _.isInstanceOf[AluProperty]
}

case class CSRControlProperty(op: CSRControl.Type) extends InstProperty(op)
object CSRControlField                             extends InstEnumField(CSRControl) {
  override def name        = "csr instruction"
  override def typeChecker = _.isInstanceOf[CSRControlProperty]
}

case class IsWritebackProperty(op: IsWriteback.Type) extends InstProperty(op)
object IsWritebackField                              extends InstEnumField(IsWriteback) {
  override def name        = "instruction will writeback"
  override def typeChecker = _.isInstanceOf[IsWritebackProperty]
}

case class BranchControlProperty(op: BranchControl.Type)
    extends InstProperty(op)
object BranchControlField extends InstEnumField(BranchControl) {
  override def name        = "branch unit instruction"
  override def typeChecker = _.isInstanceOf[BranchControlProperty]
}

case class StoreControlProperty(op: StoreControl.Type) extends InstProperty(op)
object StoreControlField                               extends InstEnumField(StoreControl) {
  override def name        = "store"
  override def typeChecker = _.isInstanceOf[StoreControlProperty]
}

case class LoadControlProperty(op: LoadControl.Type) extends InstProperty(op)
object LoadControlField                              extends InstEnumField(LoadControl) {
  override def name        = "load"
  override def typeChecker = _.isInstanceOf[LoadControlProperty]
}

case class ECallProperty(op: Ecall.Type) extends InstProperty(op)
object ECallField                        extends InstEnumField(Ecall) {
  override def name        = "ecall"
  override def typeChecker = _.isInstanceOf[ECallProperty]
}

case class EBreakProperty(op: Ebreak.Type) extends InstProperty(op)
object EBreakField                         extends InstEnumField(Ebreak) {
  override def name        = "ebreak"
  override def typeChecker = _.isInstanceOf[EBreakProperty]
}

case class MRetProperty(op: MRet.Type) extends InstProperty(op)
object MRetField                       extends InstEnumField(MRet) {
  override def name        = "mret"
  override def typeChecker = _.isInstanceOf[MRetProperty]
}

case class SRetProperty(op: Bool) extends InstProperty(op)
object SRetField                  extends InstBoolField {
  override def name        = "sret"
  override def typeChecker = _.isInstanceOf[SRetProperty]
}

case class DRetProperty(op: DRet.Type) extends InstProperty(op)
object DRetField                       extends InstEnumField(DRet) {
  override def name                                 = "dret"
  override def typeChecker: InstProperty => Boolean =
    _.isInstanceOf[DRetProperty]
}

case class FenceProperty(op: Fence.Type) extends InstProperty(op)
object FenceField                        extends InstEnumField(Fence) {
  override def name        = "fence"
  override def typeChecker = _.isInstanceOf[FenceProperty]
}

case class FlushICacheProperty(op: FlushICache.Type) extends InstProperty(op)
object FlushICacheField                              extends InstEnumField(FlushICache) {
  override def name        = "flush i cache"
  override def typeChecker = _.isInstanceOf[FlushICacheProperty]
}

case class FlushTLBProperty(op: Bool) extends InstProperty(op)
object FlushTLBField                  extends InstBoolField {
  override def name        = "flush tlb"
  override def typeChecker = _.isInstanceOf[FlushTLBProperty]
}

case class WFIProperty(op: WFI.Type) extends InstProperty(op)
object WFIField                      extends InstEnumField(WFI) {
  override def name        = "wfiOut"
  override def typeChecker = _.isInstanceOf[WFIProperty]
}

case class AmoProperty(op: AMOType.Type) extends InstProperty(op)
object AmoField                          extends InstEnumField(AMOType) {
  override def name        = "amo"
  override def typeChecker = _.isInstanceOf[AmoProperty]
}

/** Definitions of Composite properties
  */

case class OpCompProperty(a: OperandType.Type, b: OperandType.Type)
    extends InstProperty(
      OpAProperty(a) ++
        OpBProperty(b)
    )

case class AluCompProperty(
    alu: AluControl.Type,
    a: OperandType.Type,
    b: OperandType.Type
) extends InstProperty(
      AluProperty(alu) ++
        OpCompProperty(a, b) ++
        IsWritebackProperty(IsWriteback.EN)
    )

case class BranchCompProperty(br: BranchControl.Type)
    extends InstProperty(
      OpCompProperty(OperandType.Reg, OperandType.Reg) ++
        BranchControlProperty(br)
    )

case class StoreCompProperty(st: StoreControl.Type)
    extends InstProperty(
      OpCompProperty(OperandType.Reg, OperandType.SImmediate) ++
        StoreControlProperty(st)
    )

case class LoadCompProperty(ld: LoadControl.Type)
    extends InstProperty(
      OpCompProperty(OperandType.Reg, OperandType.IImmediate) ++
        LoadControlProperty(ld) ++
        IsWritebackProperty(IsWriteback.EN)
    )

case class JumpCompProperty(br: BranchControl.Type)
    extends InstProperty(
      OpCompProperty.tupled(
        {
          br match {
            case BranchControl.JAL  =>
              (OperandType.PC, OperandType.JImmediate)
            case BranchControl.JALR =>
              (OperandType.Reg, OperandType.IImmediate)
          }
        }
      ) ++
        BranchControlProperty(br) ++
        IsWritebackProperty(IsWriteback.EN)
    )

abstract trait InstDecode {
  val table: Seq[InstPattern]
}

object RV32IDecode extends InstDecode {
  import udacore.common.Instructions._

  override val table = Seq(
    new InstPattern(
      ADD,
      AluCompProperty(AluControl.ADD, OperandType.Reg, OperandType.Reg)
    ),
    new InstPattern(
      ADDI,
      AluCompProperty(AluControl.ADD, OperandType.Reg, OperandType.IImmediate)
    ),
    new InstPattern(
      SUB,
      AluCompProperty(AluControl.SUB, OperandType.Reg, OperandType.Reg)
    ),
    new InstPattern(
      XOR,
      AluCompProperty(AluControl.XOR, OperandType.Reg, OperandType.Reg)
    ),
    new InstPattern(
      XORI,
      AluCompProperty(AluControl.XOR, OperandType.Reg, OperandType.IImmediate)
    ),
    new InstPattern(
      OR,
      AluCompProperty(AluControl.OR, OperandType.Reg, OperandType.Reg)
    ),
    new InstPattern(
      ORI,
      AluCompProperty(AluControl.OR, OperandType.Reg, OperandType.IImmediate)
    ),
    new InstPattern(
      AND,
      AluCompProperty(AluControl.AND, OperandType.Reg, OperandType.Reg)
    ),
    new InstPattern(
      ANDI,
      AluCompProperty(AluControl.AND, OperandType.Reg, OperandType.IImmediate)
    ),
    new InstPattern(
      SLT,
      AluCompProperty(AluControl.SLT, OperandType.Reg, OperandType.Reg)
    ),
    new InstPattern(
      SLTI,
      AluCompProperty(AluControl.SLT, OperandType.Reg, OperandType.IImmediate)
    ),
    new InstPattern(
      SLTU,
      AluCompProperty(AluControl.SLTU, OperandType.Reg, OperandType.Reg)
    ),
    new InstPattern(
      SLTIU,
      AluCompProperty(
        AluControl.SLTU,
        OperandType.Reg,
        OperandType.IImmediate
      )
    ),
    new InstPattern(
      SLL,
      AluCompProperty(AluControl.SLL, OperandType.Reg, OperandType.Reg)
    ),
    new InstPattern(
      SLLI,
      AluCompProperty(AluControl.SLL, OperandType.Reg, OperandType.IImmediate)
    ),
    new InstPattern(
      SRL,
      AluCompProperty(AluControl.SRL, OperandType.Reg, OperandType.Reg)
    ),
    new InstPattern(
      SRLI,
      AluCompProperty(AluControl.SRL, OperandType.Reg, OperandType.IImmediate)
    ),
    new InstPattern(
      SRA,
      AluCompProperty(AluControl.SRA, OperandType.Reg, OperandType.Reg)
    ),
    new InstPattern(
      SRAI,
      AluCompProperty(AluControl.SRA, OperandType.Reg, OperandType.IImmediate)
    ),
    new InstPattern(
      LUI,
      AluCompProperty(
        AluControl.ADD,
        OperandType.None,
        OperandType.UImmediate
      )
    ),
    new InstPattern(
      AUIPC,
      AluCompProperty(AluControl.ADD, OperandType.PC, OperandType.UImmediate)
    ),
    new InstPattern(
      JAL,
      JumpCompProperty(BranchControl.JAL)
    ),
    new InstPattern(
      JALR,
      JumpCompProperty(BranchControl.JALR)
    ),
    new InstPattern(BEQ, BranchCompProperty(BranchControl.BEQ)),
    new InstPattern(BNE, BranchCompProperty(BranchControl.BNE)),
    new InstPattern(BLT, BranchCompProperty(BranchControl.BLT)),
    new InstPattern(BLTU, BranchCompProperty(BranchControl.BLTU)),
    new InstPattern(BGE, BranchCompProperty(BranchControl.BGE)),
    new InstPattern(BGEU, BranchCompProperty(BranchControl.BGEU)),
    new InstPattern(SB, StoreCompProperty(StoreControl.SB)),
    new InstPattern(SH, StoreCompProperty(StoreControl.SH)),
    new InstPattern(SW, StoreCompProperty(StoreControl.SW)),
    new InstPattern(LB, LoadCompProperty(LoadControl.LB)),
    new InstPattern(LH, LoadCompProperty(LoadControl.LH)),
    new InstPattern(LW, LoadCompProperty(LoadControl.LW)),
    new InstPattern(
      LBU,
      LoadCompProperty(LoadControl.LBU)
    ),
    new InstPattern(
      LHU,
      LoadCompProperty(LoadControl.LHU)
    ),

    // CSR: do not use Alu; handled in CSR unit
    new InstPattern(
      CSRRW,
      OpCompProperty(OperandType.Reg, OperandType.None) ++
        CSRControlProperty(CSRControl.RW) ++
        IsWritebackProperty(IsWriteback.EN)
    ),
    new InstPattern(
      CSRRWI,
      OpCompProperty(OperandType.CSRImmediate, OperandType.None) ++
        CSRControlProperty(CSRControl.RW) ++
        IsWritebackProperty(IsWriteback.EN)
    ),
    new InstPattern(
      CSRRS,
      OpCompProperty(OperandType.Reg, OperandType.None) ++
        CSRControlProperty(CSRControl.RS) ++
        IsWritebackProperty(IsWriteback.EN)
    ),
    new InstPattern(
      CSRRSI,
      OpCompProperty(OperandType.CSRImmediate, OperandType.None) ++
        CSRControlProperty(CSRControl.RS) ++
        IsWritebackProperty(IsWriteback.EN)
    ),
    new InstPattern(
      CSRRC,
      OpCompProperty(OperandType.Reg, OperandType.None) ++
        CSRControlProperty(CSRControl.RC) ++
        IsWritebackProperty(IsWriteback.EN)
    ),
    new InstPattern(
      CSRRCI,
      OpCompProperty(OperandType.CSRImmediate, OperandType.None) ++
        CSRControlProperty(CSRControl.RC) ++
        IsWritebackProperty(IsWriteback.EN)
    ),
    new InstPattern(ECALL, ECallProperty(Ecall.EN)),
    new InstPattern(EBREAK, EBreakProperty(Ebreak.EN)),
    // new InstPattern(
    // SRET,
    // SRetProperty(true.B)
    // ),
    new InstPattern(
      MRET,
      MRetProperty(MRet.EN)
    ),
    // for debugger
    new InstPattern(
      DRET,
      DRetProperty(DRet.EN)
    ),
    new InstPattern(
      FENCE,
      IsWritebackProperty(IsWriteback.EN) ++
        FenceProperty(Fence.EN)
    ),
    new InstPattern(FENCE_I, FlushICacheProperty(FlushICache.EN)),
    new InstPattern(SFENCE_VMA, FlushTLBProperty(true.B)),
    new InstPattern(
      WFI,
      WFIProperty(udacore.common.ControlSignal.WFI.EN)
    )
  )
}

case class AmoCompProperty(amo: AMOType.Type)
    extends InstProperty(
      // AluCompProperty(AluControl.BypassA, OperandType.Reg, OperandType.Reg) ++
      OpCompProperty(OperandType.Reg, OperandType.Reg) ++
        LoadControlProperty(LoadControl.LW) ++
        StoreControlProperty(StoreControl.SW) ++
        IsWritebackProperty(IsWriteback.EN) ++
        AmoProperty(amo)
    )

object RV32ADecode extends InstDecode {
  import udacore.common.Instructions._

  override val table = Seq(
    AMOADD_W  -> AMOType.Add,
    AMOXOR_W  -> AMOType.Xor,
    AMOOR_W   -> AMOType.Or,
    AMOAND_W  -> AMOType.And,
    AMOMIN_W  -> AMOType.Min,
    AMOMAX_W  -> AMOType.Max,
    AMOMINU_W -> AMOType.Minu,
    AMOMAXU_W -> AMOType.Maxu,
    AMOSWAP_W -> AMOType.Swap,
    LR_W      -> AMOType.LR,
    SC_W      -> AMOType.SC
  ).map { case (k, v) =>
    new InstPattern(k, AmoCompProperty(v))
  }
}

case class MulDivCompProperty()
    extends InstProperty(
      OpCompProperty(OperandType.Reg, OperandType.Reg) ++
        IsWritebackProperty(IsWriteback.EN)
    )

case class MultiplierProperty(op: MultiplierControl.Type)
    extends InstProperty(op)
object MultiplierField extends InstEnumField(MultiplierControl) {
  override def name = "multiplier"

  override def typeChecker: InstProperty => Boolean =
    _.isInstanceOf[MultiplierProperty]
}

case class MultiplierCtrlProperty(op: MultiplierControl.Type)
    extends InstProperty(
      OpCompProperty(OperandType.Reg, OperandType.Reg) ++
        IsWritebackProperty(IsWriteback.EN) ++
        MultiplierProperty(op)
    )

case class DividerProperty(op: DividerControl.Type) extends InstProperty(op)
object DividerField                                 extends InstEnumField(DividerControl) {
  override def name = "divider"

  override def typeChecker: InstProperty => Boolean =
    _.isInstanceOf[DividerProperty]
}

case class DividerCtrlProperty(op: DividerControl.Type)
    extends InstProperty(
      OpCompProperty(OperandType.Reg, OperandType.Reg) ++
        IsWritebackProperty(IsWriteback.EN) ++
        DividerProperty(op)
    )

object RV32MDecode extends InstDecode {

  import udacore.common.Instructions._

  override val table = Seq(
    new InstPattern(MUL, MultiplierCtrlProperty(MultiplierControl.MUL)),
    new InstPattern(MULH, MultiplierCtrlProperty(MultiplierControl.MULH)),
    new InstPattern(MULHSU, MultiplierCtrlProperty(MultiplierControl.MULHSU)),
    new InstPattern(MULHU, MultiplierCtrlProperty(MultiplierControl.MULHU)),
    new InstPattern(DIV, DividerCtrlProperty(DividerControl.DIV)),
    new InstPattern(DIVU, DividerCtrlProperty(DividerControl.DIVU)),
    new InstPattern(REM, DividerCtrlProperty(DividerControl.REM)),
    new InstPattern(REMU, DividerCtrlProperty(DividerControl.REMU))
  )
}

case class BitAluProperty(op: BitAluControl.Type) extends InstProperty(op)
object BitAluField                                extends InstEnumField(BitAluControl) {
  override def name        = "bitalu"
  override def typeChecker = _.isInstanceOf[BitAluProperty]
}

case class BitAluCtrlProperty(
    op: BitAluControl.Type,
    a: OperandType.Type = OperandType.Reg,
    b: OperandType.Type = OperandType.Reg
) extends InstProperty(
      OpCompProperty(a, b) ++
        IsWritebackProperty(IsWriteback.EN) ++
        BitAluProperty(op)
    )

object RV32BDecode extends InstDecode {

  import udacore.common.Instructions._

  // Bitmanipulation instructions with separate I-type and R-type handling
  override val table = Seq(
    new InstPattern(BCLR, BitAluCtrlProperty(BitAluControl.BCLR)),     // Zbs
    new InstPattern(BSET, BitAluCtrlProperty(BitAluControl.BSET)),
    new InstPattern(BINV, BitAluCtrlProperty(BitAluControl.BINV)),
    new InstPattern(BEXT, BitAluCtrlProperty(BitAluControl.BEXT)),
    new InstPattern(
      BCLRI,
      BitAluCtrlProperty(BitAluControl.BCLRI, b = OperandType.IImmediate)
    ),
    new InstPattern(
      BSETI,
      BitAluCtrlProperty(BitAluControl.BSETI, b = OperandType.IImmediate)
    ),
    new InstPattern(
      BINVI,
      BitAluCtrlProperty(BitAluControl.BINVI, b = OperandType.IImmediate)
    ),
    new InstPattern(
      BEXTI,
      BitAluCtrlProperty(BitAluControl.BEXTI, b = OperandType.IImmediate)
    ),
    new InstPattern(CLZ, BitAluCtrlProperty(BitAluControl.CLZ)),       // Zbb
    new InstPattern(CPOP, BitAluCtrlProperty(BitAluControl.CPOP)),
    new InstPattern(CTZ, BitAluCtrlProperty(BitAluControl.CTZ)),
    new InstPattern(MIN, BitAluCtrlProperty(BitAluControl.MIN)),
    new InstPattern(MINU, BitAluCtrlProperty(BitAluControl.MINU)),
    new InstPattern(MAX, BitAluCtrlProperty(BitAluControl.MAX)),
    new InstPattern(MAXU, BitAluCtrlProperty(BitAluControl.MAXU)),
    new InstPattern(ZEXT_H, BitAluCtrlProperty(BitAluControl.ZEXT_H)),
    new InstPattern(SEXT_B, BitAluCtrlProperty(BitAluControl.SEXT_B)),
    new InstPattern(SEXT_H, BitAluCtrlProperty(BitAluControl.SEXT_H)),
    new InstPattern(ANDN, BitAluCtrlProperty(BitAluControl.ANDN)),
    new InstPattern(ORN, BitAluCtrlProperty(BitAluControl.ORN)),
    new InstPattern(XNOR, BitAluCtrlProperty(BitAluControl.XNOR)),
    new InstPattern(ROL, BitAluCtrlProperty(BitAluControl.ROL)),
    new InstPattern(ROR, BitAluCtrlProperty(BitAluControl.ROR)),
    new InstPattern(
      RORI,
      BitAluCtrlProperty(BitAluControl.RORI, b = OperandType.IImmediate)
    ),
    new InstPattern(REV8, BitAluCtrlProperty(BitAluControl.REV8)),
    new InstPattern(ORC_B, BitAluCtrlProperty(BitAluControl.ORC_B)),
    new InstPattern(SH1ADD, BitAluCtrlProperty(BitAluControl.SH1ADD)), // Zba
    new InstPattern(SH2ADD, BitAluCtrlProperty(BitAluControl.SH2ADD)),
    new InstPattern(SH3ADD, BitAluCtrlProperty(BitAluControl.SH3ADD)),
    new InstPattern(CLMUL, BitAluCtrlProperty(BitAluControl.CLMUL)),   // Zbc
    new InstPattern(CLMULH, BitAluCtrlProperty(BitAluControl.CLMULH)),
    new InstPattern(CLMULR, BitAluCtrlProperty(BitAluControl.CLMULR))
  )
}
