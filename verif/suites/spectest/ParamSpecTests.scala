package verif.spectest

import udacore.backend.design.shared.{BackendParams, BackendTuningParams}
import udacore.core.design.shared.{CacheParams, CoreParams}

/** L0/L1 SpecTests for the ADR-019 elaboration-legality PROPERTYs (ADR-018).
  *
  * These are parameter-level contracts, so the "DUT" is the parameter case class
  * that carries the paired @LocalSpec require: an illegal point must be rejected at
  * construction and the v0 reference point must be accepted. No simulation is needed.
  */
object ParamSpecTests {
  private def rejects(label: String)(build: => Any): TCheck =
    try { build; TCheck(ok = false, label, "accepted an illegal configuration") }
    catch { case _: IllegalArgumentException => TCheck(ok = true, label) }

  private def accepts(label: String)(build: => Any): TCheck =
    try { build; TCheck(ok = true, label) }
    catch { case e: IllegalArgumentException => TCheck(ok = false, label, e.getMessage) }

  /** PrfSizingCoversRob: IntegerPrfEntries >= ArchRegNum + RobDepth, RobDepth a power of two. */
  val prfSizing = new SpecTest("params.prfSizingCoversRob", Seq("propPrfSizingCoversRob")) {
    def run(): Seq[TCheck] = Seq(
      accepts("v0 reference (ROB 16, PRF 48) is legal")(BackendParams()),
      accepts("PRF exactly ArchRegNum + RobDepth (ROB 32, PRF 64) is legal")(
        BackendTuningParams(robDepth = 32, integerPrfEntries = 64)),
      rejects("PRF one short of ArchRegNum + RobDepth (ROB 16, PRF 47) is rejected")(
        BackendTuningParams(robDepth = 16, integerPrfEntries = 47)),
      rejects("non-power-of-two ROB depth (12) is rejected")(
        BackendTuningParams(robDepth = 12, integerPrfEntries = 48))
    )
  }

  /** ViptGeometryLegal: sets * lineBytes <= 4 KiB for both L1 caches. */
  val viptGeometry = new SpecTest("params.viptGeometryLegal", Seq("propViptGeometryLegal")) {
    def run(): Seq[TCheck] = Seq(
      accepts("v0 reference caches (64 sets x 64 B) are legal")(CoreParams()),
      accepts("32 KiB 8-way with 64 sets x 64 B is legal (associativity is free)")(
        CacheParams(sets = 64, ways = 8, blockBytes = 64)),
      rejects("128 sets x 64 B (8 KiB per way) is rejected")(
        CacheParams(sets = 128, ways = 4, blockBytes = 64)),
      rejects("64 sets x 128 B (8 KiB per way) is rejected")(
        CacheParams(sets = 64, ways = 2, blockBytes = 128))
    )
  }

  val all: Seq[SpecTest] = Seq(prfSizing, viptGeometry)
}
