package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.core.design.shared._

/** Test-only probe: the shared PmaLookup on an arbitrary physical address. */
class PmaProbe(val map: PmaMap, val width: Int) extends Module {
  val io = IO(new Bundle {
    val pa   = Input(UInt(width.W))
    val hit  = Output(Bool())
    val attr = Output(new PmaAttr)
  })
  val (h, a) = PmaLookup(map, io.pa)
  io.hit := h; io.attr := a
}

/** paramPmaMap / funcPmaCheck: elaboration legality of the static map and the shared hardware lookup. */
object PmaSpecTests {
  val W = CoreParams().pAddrWidth
  private def rejects(label: String)(build: => Any): TCheck =
    try { build; TCheck(ok = false, label, "accepted an illegal PMA map") }
    catch { case _: IllegalArgumentException => TCheck(ok = true, label) }
  private def accepts(label: String)(build: => Any): TCheck =
    try { build; TCheck(ok = true, label) }
    catch { case e: IllegalArgumentException => TCheck(ok = false, label, e.getMessage) }
  def ram(base: BigInt, size: BigInt) = PmaRegion(base, size, cacheable = true, executable = true, readable = true, writable = true)

  val legality = new SpecTest("pma.legality", Seq("paramPmaMap")) {
    def run(): Seq[TCheck] = Seq(
      accepts("the verification default map is legal for the 34-bit physical space")(CoreParams()),
      rejects("overlapping regions are rejected")(PmaMap(Seq(ram(0, 0x2000), ram(0x1000, 0x1000)))),
      accepts("adjacent regions are legal")(PmaMap(Seq(ram(0, 0x1000), ram(0x1000, 0x1000)))),
      rejects("a zero-size region is rejected")(ram(0x1000, 0)),
      rejects("a negative base is rejected")(ram(-4, 0x10)),
      rejects("a region beyond 2^PAddrWidth is rejected")(
        CoreParams(CoreContractParams(pma = PmaMap(Seq(ram((BigInt(1) << W) - 0x1000, 0x2000)))))),
      accepts("a region ending exactly at 2^PAddrWidth is legal")(
        CoreParams(CoreContractParams(pma = PmaMap(Seq(ram((BigInt(1) << W) - 0x1000, 0x1000)))))))
  }

  /** The hardware lookup equals the software reference on region boundaries and unmapped holes. */
  def probe(t: SpecTest, map: PmaMap, pas: Seq[BigInt]): Seq[TCheck] =
    t.sim(new PmaProbe(map, W)) { dut =>
      pas.map { pa =>
        dut.io.pa.poke(pa.U); dut.clock.step(0)
        val hit = dut.io.hit.peek().litToBoolean
        val a = dut.io.attr
        val got = (hit, a.cacheable.peek().litToBoolean, a.executable.peek().litToBoolean, a.readable.peek().litToBoolean,
          a.writable.peek().litToBoolean)
        val exp = map.find(pa).map(r => (true, r.cacheable, r.executable, r.readable, r.writable))
          .getOrElse((false, false, false, false, false))
        TCheck(got == exp, f"PmaLookup(0x$pa%x) = $exp", if (got == exp) "" else s"got $got")
      }
    }

  val lookupDefault = new SpecTest("pma.lookupDefault", Seq("funcPmaCheck", "paramPmaMap")) {
    def run(): Seq[TCheck] = probe(this, PmaMap.verificationDefault, Seq(
      BigInt(0), BigInt("80000000", 16), BigInt("EFFFFFFF", 16),      // RAM first / legacy code / last
      BigInt("F0000000", 16), BigInt("FFFFFFFF", 16),                   // device first / last
      BigInt("100000000", 16), BigInt("3FFFFFFFF", 16)))                // unmapped above 4 GiB
  }

  val lookupCustom = new SpecTest("pma.lookupCustom", Seq("funcPmaCheck")) {
    def run(): Seq[TCheck] = {
      val m = PmaMap(Seq(ram(0x1000, 0x1000),
        PmaRegion(0x4000, 0x100, cacheable = false, executable = true, readable = true, writable = false),
        PmaRegion(BigInt("200000000", 16), 0x10, cacheable = false, executable = false, readable = true, writable = true)))
      probe(this, m, Seq(0xfff, 0x1000, 0x1ffc, 0x2000, 0x3fff, 0x4000, 0x40ff, 0x4100).map(BigInt(_)) ++
        Seq("200000000", "20000000f", "200000010").map(BigInt(_, 16)))
    }
  }

  val all: Seq[SpecTest] = Seq(legality, lookupDefault, lookupCustom)
}
