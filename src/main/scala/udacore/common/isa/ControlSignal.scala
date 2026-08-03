package udacore.common

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import udacore.common.enums.SnitchEnum

trait ControlEnum extends SnitchEnum

object ControlSignal {
  object AluControl extends ControlEnum {
    val None = Value
    val AND  = Value // aluR 00001
    val OR   = Value //       00010
    val SRL  = Value //      00011
    val XOR  = Value //      00100
    val EQ   = Value //       00101
    val NE   = Value //       00110
    val SLL  = Value //      00111
    val LT   = Value // 8    01000
    val LTU  = Value //      01001
    val GE   = Value //       01010
    val GEU  = Value //      01011
    val SLT  = Value // 12   01100
    val SUB  = Value //      01101
    val SRA  = Value //      01110
    val SLTU = Value //     01111
    val ADD  = Value //      10000

    override val default = this.None
  }

  object DividerControl extends ControlEnum {
    val None = Value
    val DIV  = Value
    val DIVU = Value
    val REM  = Value
    val REMU = Value

    override val default = this.None
  }

  object MultiplierControl extends ControlEnum {
    val None   = Value
    val MUL    = Value
    val MULH   = Value
    val MULHU  = Value
    val MULHSU = Value

    override val default = this.None
  }

  object BranchControl extends ControlEnum {
    val None = Value
    val BEQ  = Value
    val BNE  = Value
    val BLT  = Value
    val BLTU = Value
    val BGE  = Value
    val BGEU = Value
    val JAL  = Value
    val JALR = Value

    override val default = this.None
  }

  object IsWriteback extends ControlEnum {
    val None = Value
    val EN   = Value

    override val default = this.None
  }

  object IllegalInstruction extends ControlEnum {
    val normal  = Value
    val illegal = Value

    override val default = this.normal
  }

  object StoreControl extends ControlEnum {
    val None = Value
    val SB   = Value
    val SH   = Value
    val SW   = Value

    override val default = this.None
  }

  object LoadControl extends ControlEnum {
    val None = Value
    val LB   = Value
    val LH   = Value
    val LW   = Value
    val LBU  = Value
    val LHU  = Value

    override val default = this.None
  }

  object CSRControl extends ControlEnum {
    val None = Value
    val RW   = Value
    val RS   = Value
    val RC   = Value

    override val default = this.None
  }

  object Fence extends ControlEnum {
    val None = Value
    val EN   = Value

    override val default = this.None
  }

  object FlushICache extends ControlEnum {
    val None = Value
    val EN   = Value

    override val default = this.None
  }

  object Ecall extends ControlEnum {
    val None = Value
    val EN   = Value

    override val default = this.None
  }

  object Ebreak extends ControlEnum {
    val None = Value
    val EN   = Value

    override val default = this.None
  }

  object MRet extends ControlEnum {
    val None = Value
    val EN   = Value

    override val default = this.None
  }

  object DRet extends ControlEnum {
    val None = Value
    val EN   = Value

    override val default = this.None
  }

  object WFI extends ControlEnum {
    val None = Value
    val EN   = Value

    override val default = this.None
  }

  object BitAluControl extends ControlEnum {
    val None                       = Value
    // Zbs
    val BCLR, BSET, BINV, BEXT     = Value
    val BCLRI, BSETI, BINVI, BEXTI = Value

    // Zbb
    val CLZ, CTZ, CPOP         = Value
    val MIN, MINU, MAX, MAXU   = Value
    val ZEXT_H, SEXT_B, SEXT_H = Value
    val ANDN, ORN, XNOR        = Value
    val ROL, ROR, RORI         = Value
    val REV8, ORC_B            = Value

    // Zba
    val SH1ADD, SH2ADD, SH3ADD = Value

    // Zbc
    val CLMUL, CLMULH, CLMULR = Value
    override val default      = this.None
  }
}
