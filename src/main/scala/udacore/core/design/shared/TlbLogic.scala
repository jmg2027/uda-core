package udacore.core.design.shared

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.core.spec.shared.Sv32Specs.{funcSv32Decompose, funcTlbMatch, propNoFaultCaching, propSfenceFlushesAll}

/** Combinational TLB logic shared by the InstructionTlb and the DataTlb: the entry match, the leaf
  * address composition, the refill conflict test, and the tree pseudo-LRU. Each TLB owns its own
  * state and its own translate / refill / flush functions; only these pure helpers are shared. */
object TlbLogic {
  /** funcTlbMatch: valid, (global or ASID equal), and the page of the entry's size contains vpn. */
  @LocalSpec(funcTlbMatch)
  def matches(e: TlbEntry, vpn: UInt, asid: UInt): Bool =
    e.valid && (e.global || e.asid === asid) && Mux(e.superpage, e.vpn(19, 10) === vpn(19, 10), e.vpn === vpn)

  /** funcSv32Decompose: 4 KiB {PPN, va[11:0]}; 4 MiB {PPN[21:10], va[21:0]}. */
  @LocalSpec(funcSv32Decompose)
  def compose(e: TlbEntry, va: UInt): UInt =
    Mux(e.superpage, Cat(e.ppn(21, 10), va(21, 0)), Cat(e.ppn, va(11, 0)))

  /** Two entries can match one lookup: their pages overlap and their address spaces overlap. A refill
    * invalidates every live entry that conflicts with the new one, so at most one entry ever matches. */
  def conflicts(a: TlbEntry, b: TlbEntry): Bool = mapOverlap(a, b) && asOverlap(a, b)
  def mapOverlap(a: TlbEntry, b: TlbEntry): Bool =
    Mux(a.superpage || b.superpage, a.vpn(19, 10) === b.vpn(19, 10), a.vpn === b.vpn)
  def asOverlap(a: TlbEntry, b: TlbEntry): Bool = a.global || b.global || a.asid === b.asid

  /** propNoFaultCaching (TLB side): a live entry appears or changes only in the cycle after a
    * non-stale Leaf walk result was taken (`leafRefill`); faults, Retry and stale results install
    * nothing. (The PTW asserts that a Leaf result is a legal Sv32 leaf.) */
  @LocalSpec(propNoFaultCaching)
  def assertNoFaultCaching(entries: Vec[TlbEntry], leafRefill: Bool, tag: String): Unit = {
    val prev = RegNext(entries, 0.U.asTypeOf(entries))
    val ok   = RegNext(leafRefill, false.B)
    for (i <- entries.indices) {
      val changed = entries(i).valid && (!prev(i).valid || entries(i).asUInt =/= prev(i).asUInt)
      assert(!changed || ok, s"$tag: NoFaultCaching: an entry was installed without a non-stale Leaf walk result")
    }
  }

  /** propSfenceFlushesAll (TLB side): in the cycle after a flush token, no entry is valid. */
  @LocalSpec(propSfenceFlushesAll)
  def assertFlushed(entries: Vec[TlbEntry], flushFire: Bool, tag: String): Unit =
    assert(!RegNext(flushFire, false.B) || !entries.map(_.valid).reduce(_ || _),
      s"$tag: SfenceFlushesAll: a TLB entry is valid after the flush")

  /** Tree pseudo-LRU over 2^levels ways: heap node k (1..2^levels-1) is bit k-1; a set bit sends the
    * victim search right. */
  def plruVictim(b: UInt, levels: Int): UInt = {
    var node = 1.U(1.W)
    for (_ <- 0 until levels) node = Cat(node, b((node - 1.U).pad(levels)))
    node(levels - 1, 0)
  }

  /** The tree bits after touching way w: every node on w's path points away from w. */
  def plruTouch(b: UInt, w: UInt, levels: Int): UInt = {
    val bits = VecInit(b.asBools)
    for (lvl <- 0 until levels) {
      val node = if (lvl == 0) 1.U else Cat(1.U(1.W), w(levels - 1, levels - lvl))
      bits((node - 1.U).pad(levels)) := !w(levels - 1 - lvl)
    }
    bits.asUInt
  }
}
