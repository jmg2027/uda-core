package udacore.common

import chisel3._
import chisel3.experimental.BundleLiterals._

// Thanks to d.hyun.ahn

object enums {
  trait SnitchEnum extends ChiselEnum {
    val default: this.Type

    def getValue(field: String): this.Type = {
      this.all.find(_.name == field).getOrElse(this.all.head)
    }

    def getDefaultValue: this.Type = {
      getValue("default")
    }

  }
  object DataSize extends SnitchEnum {
    val Byte     = Value
    val HalfWord = Value
    val Word     = Value
    val Double   = Value

    override val default = this.Byte
  }

  object AccType extends SnitchEnum {
    val SharedMulDiv = Value
    val IntSS        = Value

    override val default = this.SharedMulDiv
  }

  object AMOType extends SnitchEnum {
    val None = Value
    val Swap = Value
    val Add  = Value
    val And  = Value
    val Or   = Value
    val Xor  = Value
    val Max  = Value
    val Maxu = Value
    val Min  = Value
    val Minu = Value
    val LR   = Value
    val SC   = Value

    override val default = this.None
  }

  object PrivLevel extends SnitchEnum {
    val U = Value(0.U)
    val S = Value(1.U)
    val M = Value(3.U)

    override val default = this.U
  }

  object AluOp extends SnitchEnum {
    val Add     = Value
    val Sub     = Value
    val Slt     = Value
    val Sltu    = Value
    val Sll     = Value
    val Srl     = Value
    val Sra     = Value
    val LXor    = Value
    val LOr     = Value
    val LAnd    = Value
    val LNAnd   = Value
    val Eq      = Value
    val Neq     = Value
    val Ge      = Value
    val Geu     = Value
    val BypassA = Value

    override val default = this.Add
  }

  object OperandType extends SnitchEnum {
    val None         = Value
    val Reg          = Value
    val IImmediate   = Value
    val UImmediate   = Value
    val JImmediate   = Value
    val SImmediate   = Value
    // val SFImmediate     = Value
    val PC           = Value
    val CSRImmediate = Value

    override val default = this.None
  }

  object PcType extends SnitchEnum {
    val Consec = Value
    val Branch = Value
    val Alu    = Value
    val MRet   = Value
    val SRet   = Value
    val DRet   = Value

    override val default = this.Consec
  }

  object Cause extends SnitchEnum {
    val InstrAddrMisaligned = Value(0.U)
    val InstrAccessFalut    = Value(1.U)
    val IllegalInstr        = Value(2.U)
    val BreakPoint          = Value(3.U)
    val LoadAddrMisaligned  = Value(4.U)
    val LoadAccessFault     = Value(5.U)
    val StoreAddrMisaligned = Value(6.U)
    val StoreAccessFault    = Value(7.U)
    val EnvCallUMode        = Value(8.U)
    val EnvCallSMode        = Value(9.U)
    val EnvCallMMode        = Value(11.U)
    val InstrPageFault      = Value(12.U)
    val LoadPageFault       = Value(13.U)
    val StorePageFault      = Value(15.U)

    override val default = this.InstrAddrMisaligned
  }
}

object types {
  class Operand(width: Int) extends Bundle {
    val a = UInt(width.W)
    val b = UInt(width.W)

    def map(f: UInt => UInt) = {
      val fa = f(a)
      val fb = f(b)

      val res = Wire(new Operand(scala.math.max(fa.getWidth, fb.getWidth)))
      res.a := fa
      res.b := fb
      res
    }
  }

  class Interrupt extends Bundle {
    val e = Bool() // external
    val t = Bool() // timer
    val s = Bool() // software

    def &(op: Interrupt) = {
      val ret    = Wire(new Interrupt())
      val eltret = (this.getElements zip op.getElements).map { case (a, b) =>
        a.asInstanceOf[UInt] & b.asInstanceOf[UInt]
      }
      (ret.getElements zip eltret).foreach { case (a, b) =>
        a := b
      }
      ret
    }

    def orR = this.getElements.map(_.asInstanceOf[Bool]).reduce(_ || _)
  }

  object Interrupt {
    def default = {
      (new Interrupt).Lit(
        _.e -> false.B,
        _.t -> false.B,
        _.s -> false.B
      )
    }
  }
}

// Common exception flags for memory/instruction paths
class HeartXcpt extends Bundle {
  val loc = Bool()
  val ma  = Bool()
  val pf  = Bool()
  val gf  = Bool()
  val ae  = Bool()
}
