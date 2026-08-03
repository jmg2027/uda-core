package verif.ai

import udacore.core.design.shared.CoreParams
import verif.config.CoreConfigs
import verif.harness.HarnessNotReady

/** Run several `.scn` files on one compiled DUT (persistent simulator): RunSpec.runBatch elaborates
 *  + Verilates the core once and resets between scenarios, instead of recompiling per `.scn`. The
 *  dominant per-run cost (FIRRTL -> SV -> Verilator) is paid once, so a sweep that was N compiles is
 *  now 1. All files share the one config (`--config=<name>`, default "default"). Emits a JSON array
 *  (`--json`) or stacked human blocks; exit 1 if any fail. */
object RunBatch {
  def main(args: Array[String]): Unit = {
    implicit val cfg: CoreParams = CoreConfigs.fromArgs(args)._2
    val json = args.contains("--json")
    val files = args.filterNot(_.startsWith("--"))
    require(files.nonEmpty, "usage: RunBatch <a.scn> <b.scn> ... [--json] [--config=<name>]")
    try {
      val results = RunSpec.runBatch(files.map(f => scala.io.Source.fromFile(f).mkString))
      if (json) println("[" + results.map(_.json.render).mkString(",") + "]")
      else results.foreach(r => print(r.human))
      if (results.exists(!_.allPass)) sys.exit(1)
    } catch {
      case e: HarnessNotReady =>
        if (json) println(RunSpec.notReadyJson(e).render) else println(s"harness-not-ready: ${e.getMessage}")
        sys.exit(3)
    }
  }
}
