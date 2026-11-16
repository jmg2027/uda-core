package verif.suites

import circt.stage.ChiselStage
import udacore.DefaultCoreConfig
import udacore.core.design.top.CoreTop

/** Emit the whole CoreTop to SystemVerilog for out-of-context synthesis, so the global
 *  (whole-core) critical path and area can be measured. Configs use the product parameter
 *  points (default, minimal), without verification retire ports or the harness MMIO map.
 *
 *    run.sh verif.suites.EmitCore <configName> <outDir>
 *
 *  CoreTop.sv is the entry module; sta.sh core consumes the complete emitted .sv set. */
object EmitCore {
  def main(args: Array[String]): Unit = {
    val cfgName = if (args.length > 0) args(0) else "default"
    val outDir  = if (args.length > 1) args(1) else "/tmp/emit/core_" + cfgName
    val params = cfgName match {
      case "default" => DefaultCoreConfig.default
      case "minimal" => DefaultCoreConfig.minimal
      case other => sys.error(s"unknown product core config '$other' (default, minimal)")
    }
    require(!params.usingRvvi, "synthesis config must not include the verification retire stream")
    java.nio.file.Files.createDirectories(java.nio.file.Paths.get(outDir))
    ChiselStage.emitSystemVerilogFile(
        new CoreTop(params),
        args = Array("--split-verilog", "--target-dir", outDir),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info",
          "--lowering-options=disallowLocalVariables,locationInfoStyle=none")
      )
    val out = java.nio.file.Paths.get(outDir, "CoreTop.sv")
    val lines = java.nio.file.Files.readAllLines(out).size
    println(s"[emit] core $cfgName -> $out  ($lines lines)")
  }
}
