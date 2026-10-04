package udacore.core.design.shared

import chisel3._
import framework.macros.LocalSpec
import udacore.core.spec.shared.CoreParamsSpecs.paramPmaMap
import udacore.core.spec.shared.Sv32Specs.funcPmaCheck

/** One static physical-memory-attribute region [base, base + size). */
case class PmaRegion(
    base: BigInt,
    size: BigInt,
    cacheable: Boolean,
    executable: Boolean,
    readable: Boolean,
    writable: Boolean
) {
  require(base >= 0, s"PmaRegion base must be non-negative ($base)")
  require(size > 0, s"PmaRegion size must be positive ($size)")
  def end: BigInt = base + size // exclusive
  def overlaps(o: PmaRegion): Boolean = base < o.end && o.base < end
}

/** Static PMA map (paramPmaMap). Addresses in no region are unmapped: there is no default match,
  * so an unmapped access is an access fault (funcPmaCheck). */
@LocalSpec(paramPmaMap)
case class PmaMap(regions: Seq[PmaRegion]) {
  for (i <- regions.indices; j <- regions.indices if i < j)
    require(!regions(i).overlaps(regions(j)), s"PmaMap regions ${regions(i)} and ${regions(j)} overlap")

  /** The map is legal for a physical address space of `pAddrWidth` bits. */
  def checkWidth(pAddrWidth: Int): Unit =
    regions.foreach(r => require(r.end <= (BigInt(1) << pAddrWidth),
      s"PmaMap region $r extends beyond the $pAddrWidth-bit physical address space"))

  /** Software reference of the lookup (tests and elaboration-time queries). */
  def find(pa: BigInt): Option[PmaRegion] = regions.find(r => pa >= r.base && pa < r.end)
}

object PmaMap {
  /** VERIFICATION-PLATFORM DEFAULT, not an architectural map: executable cacheable RAM over
    * [0, 0xF000_0000) (keeps the low test addresses and the legacy 0x8000_0000 code region), an
    * uncacheable, non-executable read/write device window [0xF000_0000, 0x1_0000_0000), and
    * nothing mapped above 4 GiB. An integrator supplies the real map through CoreContractParams. */
  val verificationDefault: PmaMap = PmaMap(Seq(
    PmaRegion(BigInt(0), BigInt("F0000000", 16), cacheable = true, executable = true, readable = true, writable = true),
    PmaRegion(BigInt("F0000000", 16), BigInt("10000000", 16), cacheable = false, executable = false, readable = true,
      writable = true)
  ))
}

/** The one hardware PMA lookup shared by the ITLB, DTLB, PTW and caches (funcPmaCheck's region
  * match): hit = the address lies in some region; attr = that region's attributes (all false when
  * unmapped). Callers derive their access-type fault from hit and attr. */
object PmaLookup {
  @LocalSpec(funcPmaCheck)
  def apply(map: PmaMap, pa: UInt): (Bool, PmaAttr) = {
    val w    = pa.getWidth + 1
    val hits = map.regions.map(r => pa.pad(w) >= r.base.U(w.W) && pa.pad(w) < r.end.U(w.W))
    def any(bs: Seq[Bool]): Bool = bs.foldLeft(false.B)(_ || _)
    def attrOf(f: PmaRegion => Boolean): Bool = any(map.regions.zip(hits).collect { case (r, h) if f(r) => h })
    val attr = Wire(new PmaAttr)
    attr.cacheable  := attrOf(_.cacheable)
    attr.executable := attrOf(_.executable)
    attr.readable   := attrOf(_.readable)
    attr.writable   := attrOf(_.writable)
    (any(hits), attr)
  }
}
