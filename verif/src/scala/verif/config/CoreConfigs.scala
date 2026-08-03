package verif.config

import udacore.DefaultCoreConfig
import udacore.core.design.shared.CoreParams

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

  private def withRetireStream(p: CoreParams): CoreParams =
    p.copy(tuning = p.tuning.copy(usingRvvi = true))

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
