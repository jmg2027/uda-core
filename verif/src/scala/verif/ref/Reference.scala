package verif.ref

/** Pure-Scala 32-bit reference emulators with exact RV semantics. Used by suites for
 *  differential checks so expected values are computed, not hand-typed. */
object Alu {
  def exec(op: String, a: Int, b: Int): Int = op match {
    case "add"  => a + b
    case "sub"  => a - b
    case "sll"  => a << (b & 31)
    case "srl"  => a >>> (b & 31)
    case "sra"  => a >> (b & 31)
    case "slt"  => if (a < b) 1 else 0
    case "sltu" => if (java.lang.Integer.compareUnsigned(a, b) < 0) 1 else 0
    case "xor"  => a ^ b
    case "or"   => a | b
    case "and"  => a & b
  }
}

object Mul {
  def exec(op: String, a: Int, b: Int): Int = op match {
    case "mul"    => a * b
    case "mulh"   => ((a.toLong * b.toLong) >> 32).toInt
    case "mulhu"  => (((a.toLong & 0xFFFFFFFFL) * (b.toLong & 0xFFFFFFFFL)) >> 32).toInt
    case "mulhsu" => ((a.toLong * (b.toLong & 0xFFFFFFFFL)) >> 32).toInt
    case "div"    => if (b == 0) -1 else if (a == Int.MinValue && b == -1) Int.MinValue else a / b
    case "divu"   => if (b == 0) -1 else java.lang.Integer.divideUnsigned(a, b)
    case "rem"    => if (b == 0) a else if (a == Int.MinValue && b == -1) 0 else a % b
    case "remu"   => if (b == 0) a else java.lang.Integer.remainderUnsigned(a, b)
  }
}

object Bit {
  private def clmulFull(a: Int, b: Int): Long = {
    var p = 0L; val ua = a.toLong & 0xFFFFFFFFL
    var i = 0; while (i < 32) { if (((b >>> i) & 1) != 0) p ^= (ua << i); i += 1 }; p
  }
  private def orcb(a: Int): Int = {
    var r = 0; var j = 0
    while (j < 4) { if (((a >>> (j * 8)) & 0xFF) != 0) r |= (0xFF << (j * 8)); j += 1 }; r
  }
  def exec(op: String, a: Int, b: Int): Int = op match {
    case "sh1add" => (a << 1) + b
    case "sh2add" => (a << 2) + b
    case "sh3add" => (a << 3) + b
    case "andn"   => a & ~b
    case "orn"    => a | ~b
    case "xnor"   => ~(a ^ b)
    case "min"    => math.min(a, b)
    case "minu"   => if (java.lang.Integer.compareUnsigned(a, b) < 0) a else b
    case "max"    => math.max(a, b)
    case "maxu"   => if (java.lang.Integer.compareUnsigned(a, b) > 0) a else b
    case "rol"    => Integer.rotateLeft(a, b & 31)
    case "ror"    => Integer.rotateRight(a, b & 31)
    case "clz"    => Integer.numberOfLeadingZeros(a)
    case "ctz"    => Integer.numberOfTrailingZeros(a)
    case "cpop"   => Integer.bitCount(a)
    case "sext.b" => a.toByte.toInt
    case "sext.h" => a.toShort.toInt
    case "orc.b"  => orcb(a)
    case "rev8"   => Integer.reverseBytes(a)
    case "zext.h" => a & 0xFFFF
    case "bclr"   => a & ~(1 << (b & 31))
    case "bset"   => a | (1 << (b & 31))
    case "binv"   => a ^ (1 << (b & 31))
    case "bext"   => (a >>> (b & 31)) & 1
    case "clmul"  => (clmulFull(a, b) & 0xFFFFFFFFL).toInt
    case "clmulh" => ((clmulFull(a, b) >>> 32) & 0xFFFFFFFFL).toInt
    case "clmulr" => ((clmulFull(a, b) >>> 31) & 0xFFFFFFFFL).toInt
  }
  def execImm(op: String, a: Int, sh: Int): Int = op match {
    case "rori"   => Integer.rotateRight(a, sh)
    case "bclri"  => a & ~(1 << sh)
    case "bseti"  => a | (1 << sh)
    case "binvi"  => a ^ (1 << sh)
    case "bexti"  => (a >>> sh) & 1
  }
}
