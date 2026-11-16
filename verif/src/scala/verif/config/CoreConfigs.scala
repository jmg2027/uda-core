package verif.config

import udacore.DefaultCoreConfig
import udacore.core.design.shared.{CoreParams, PmaMap, PmaRegion}
import verif.harness.MemModel

/** Named core configurations for the verification framework.
 *
 *  The rebuild line keeps configuration in plain constructor case classes (CoreParams), not an
 *  implicit Parameters map, so a config here is a named value derived from DefaultCoreConfig.
 *  Verification opts the ADR-010 retire stream back in (usingRvvi, off in the product default)
 *  because the harness committed-pc capture rides it - the same opt-in the main line's
 *  verification config makes for its RVVI trace port. The ai entrypoints select a config with
 *  `--config=<name>` (default: "default"); the scn daemon runs one resident DUT per config name.
 */
object CoreConfigs {
  val names: Seq[String] = Seq("default", "minimal")

  // Result slots and the completion flag are platform MMIO. Keep each boundary aligned to
  // a cache line so no cacheable line fill or writeback can straddle an MMIO window.
  private val result = BigInt(MemModel.RESULT_BASE)
  private val resultEnd = result + 0x100
  private val done = BigInt(MemModel.DONE)
  private val doneEnd = done + 0x40
  private val ramEnd = BigInt("F0000000", 16)
  private def region(lo: BigInt, hi: BigInt, cached: Boolean): PmaRegion =
    PmaRegion(lo, hi - lo, cacheable = cached, executable = cached,
      readable = true, writable = true)
  private val scenarioPma = PmaMap(Seq(
    region(0, result, cached = true),
    region(result, resultEnd, cached = false),
    region(resultEnd, done, cached = true),
    region(done, doneEnd, cached = false),
    region(doneEnd, ramEnd, cached = true)
  ) ++ PmaMap.verificationDefault.regions.drop(1))

  private def withRetireStream(p: CoreParams): CoreParams =
    p.copy(contract = p.contract.copy(pma = scenarioPma),
      tuning = p.tuning.copy(usingRvvi = true))

  def byName(name: String): CoreParams = name match {
    case "default" => withRetireStream(DefaultCoreConfig.default)
    case "minimal" => withRetireStream(DefaultCoreConfig.minimal)
    case other     => sys.error(s"unknown config '$other' (known: ${names.mkString(", ")})")
  }

  /** Parse `--config=<name>` from an arg list; absent means "default". */
  def nameFromArgs(args: Array[String]): String =
    args.find(_.startsWith("--config=")).map(_.stripPrefix("--config=")).getOrElse("default")

  def fromArgs(args: Array[String]): (String, CoreParams) = {
    val n = nameFromArgs(args)
    (n, byName(n))
  }
}
