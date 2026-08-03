package verif.harness

import scala.collection.mutable

/** One configurable in-order memory model - the unification of the old CoreScn / CoreScnF / CEnv.
 *
 *  - shift-register request latency (`iLat` for fetch, `dLat` for data),
 *  - byte-masked stores (ported from the repo's ClusterTestMem),
 *  - unified instruction+data backing so rodata/data loads resolve against the program image,
 *  - `done` sentinel: a store to [[MemModel.DONE]] ends the run.
 *
 *  Construct from assembled words ([[fromWords]]) or a {addr -> word} image ([[fromImage]]).
 */
class MemModel(val mem: mutable.Map[Long, Long], val iLat: Int = 2, val dLat: Int = 2,
               val regions: Seq[MemModel.Region] = Nil) {
  import MemModel._

  /** Data-access latency for an address: the first matching region's `lat`, else the default
   *  `dLat`. Used by CoreHarness to model a per-region (TCM / bufferable / device) bus when
   *  `regions` is non-empty; with no regions the harness keeps the flat-`dLat` shift register. */
  def dLatOf(addr: Long): Int = {
    val a = addr & ~3L
    var i = 0
    while (i < regions.length) { if (regions(i).contains(a)) return regions(i).lat; i += 1 }
    dLat
  }

  /** aligned word read; default NOP for unmapped fetch. */
  def load(a: Long): Long = mem.getOrElse(a & ~3L, NOP) & 0xFFFFFFFFL

  /** byte-masked store into the aligned word. */
  def storeStrb(addr: Long, data: Long, strb: Long): Unit = {
    val a = addr & ~3L
    val orig = mem.getOrElse(a, 0L) & 0xFFFFFFFFL
    var v = 0L
    var j = 0
    while (j < 4) {
      val m = 0xFFL << (j * 8)
      v |= (if ((strb & (1L << j)) != 0) data & m else orig & m)
      j += 1
    }
    mem(a) = v & 0xFFFFFFFFL
  }

  /** result-slot read as a 32-bit Int (RESULT_BASE + off); sentinel if never written. */
  def result(off: Long): Int = (mem.getOrElse(RESULT_BASE + off, SENTINEL) & 0xFFFFFFFFL).toInt
  def contains(addr: Long): Boolean = mem.contains(addr)
}

object MemModel {
  /** A half-open data-address range [lo, hi) with its own bus latency (cycles to ack). Lets one
   *  run carry distinct latencies for the TCM, bufferable, and strict-device regions. */
  case class Region(lo: Long, hi: Long, lat: Int) { def contains(a: Long): Boolean = a >= lo && a < hi }

  val PROG_BASE   = 0x80000L
  val RESULT_BASE = 0x80200L
  val DONE        = 0x70000000L
  val NOP         = 0x00000013L
  val SENTINEL    = 0xFFFFF667L // -0x999, distinguishable "never stored"

  def fromWords(words: Seq[Long], iLat: Int = 2, dLat: Int = 2): MemModel = {
    val m = mutable.Map[Long, Long]()
    words.zipWithIndex.foreach { case (w, i) => m(PROG_BASE + i * 4) = w & 0xFFFFFFFFL }
    new MemModel(m, iLat, dLat)
  }
  def fromImage(image: collection.Map[Long, Long], iLat: Int = 2, dLat: Int = 2): MemModel =
    new MemModel(mutable.Map(image.toSeq: _*), iLat, dLat)
}
