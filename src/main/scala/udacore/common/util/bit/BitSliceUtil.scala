package udacore.common.util.bit

import chisel3._
import chisel3.util._

object BitSliceUtil {
  @inline def split(u: UInt, w: Int): Vec[UInt] = {
    require(
      u.getWidth % w == 0,
      s"slice width $w does not divide ${u.getWidth}"
    )
    u.asTypeOf(Vec(u.getWidth / w, UInt(w.W)))
  }

  @inline def concat(v: Vec[UInt]): UInt = Cat(v.reverse)
  implicit final class UIntSliceOps(val data: UInt) extends AnyVal {
    def slices(width: Int): Vec[UInt] = split(data, width)
  }
}
