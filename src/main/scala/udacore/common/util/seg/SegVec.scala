package udacore.common.util.seg

import chisel3._
import chisel3.util._
import udacore.common.util.bit.BitSliceUtil._

/** Constructs Vector of Segment which has same widths
  *
  * val sv: SegVec = SegVec(1.U(2.W), 2.U(2.W), 3.U(2.W))
  *
  * sv(1) = 2.U(2.W), sv(Default) = 0.U(2.W)
  *
  * Use SegmentSel as index
  * @param vec:
  *   Vector of UInt
  * @param segWidth:
  *   width of segment
  */
final case class SegVec(vec: Vec[UInt], segWidth: Int) {
  def apply(sel: SegmentSel): UInt = sel match {
    case Index(i) => vec(i)
    case Default  => 0.U(segWidth.W)
  }
}

object SegVec {

  /** Helper to slice UInt type to SegVec
    * @param data:
    *   UInt data to be sliced
    * @param segWidth:
    *   width of Segment
    * @return
    *   SegVec(Vec(data, # of segment), segWidth)
    */
  def apply(data: UInt, segWidth: Int): SegVec = {
    SegVec(data.slices(segWidth), segWidth)
  }
}
