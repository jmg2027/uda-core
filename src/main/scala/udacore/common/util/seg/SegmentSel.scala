package udacore.common.util.seg

/** Special type for indexing SegVec
  *
  * Int: Select segment by index
  *
  * Default: Do not select segment -> return zero padded segment
  *
  * Compare with Default always return true
  *
  * May extend for UInt case?
  */
sealed trait SegmentSel {
  def toInt: Int
  def compare(that: SegmentSel, cmp: (Int, Int) => Boolean): Boolean =
    this match {
      case Index(a) =>
        that match {
          case Index(b) => cmp(a, b)
          case Default  => true
        }
      case Default  => true
    }

  def >=(that: SegmentSel): Boolean = this.compare(that, _ >= _)
  def <=(that: SegmentSel): Boolean = this.compare(that, _ <= _)
  def >(that: SegmentSel): Boolean  = this.compare(that, _ > _)
  def <(that: SegmentSel): Boolean  = this.compare(that, _ < _)
}

case object Default            extends SegmentSel { def toInt: Int = -1 }
final case class Index(i: Int) extends SegmentSel { def toInt: Int = i  }

/** Helper for SegmentSel
  */
object SegIdx {
  def apply(i: Int): SegmentSel = Index(i)
  val default: SegmentSel       = Default
}
