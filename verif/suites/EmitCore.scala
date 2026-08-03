package verif.suites

import circt.stage.ChiselStage
import udacore.core.design.top.CoreTop
import verif.config.CoreConfigs

/** Emit the whole CoreTop to SystemVerilog for out-of-context synthesis, so the global
 *  (whole-core) critical path and area can be measured. Config names are the CoreConfigs ones
 *  (default, minimal).
 *
 *    run.sh verif.suites.EmitCore <configName> <outDir>
 *
 *  Port status: elaboration requires CoreTop RTL; while the vertices are spec shells this
 *  entrypoint reports the shell state and exits 3. The synthesis flow itself is exercised today
 *  through EmitUnit (the implemented external functional units). */
object EmitCore {
  def main(args: Array[String]): Unit = {
    val cfgName = if (args.length > 0) args(0) else "default"
    val outDir  = if (args.length > 1) args(1) else "/tmp/emit/core_" + cfgName
    val params = CoreConfigs.byName(cfgName)
    java.nio.file.Files.createDirectories(java.nio.file.Paths.get(outDir))
    val verilog = try
      ChiselStage.emitSystemVerilog(
        new CoreTop(params),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info",
          "--lowering-options=disallowLocalVariables,locationInfoStyle=none")
      )
    catch {
      case _: NotImplementedError =>
        System.err.println("[emit] CoreTop is a spec shell (unimplemented io/vertices); " +
          "EmitCore activates once the RTL fill-in reaches an elaborating CoreTop. " +
          "Use EmitUnit for the implemented functional units meanwhile.")
        sys.exit(3)
    }
    val out = java.nio.file.Paths.get(outDir, "CoreTop.sv")
    java.nio.file.Files.write(out, verilog.getBytes)
    println(s"[emit] core $cfgName -> $out  (${verilog.linesIterator.size} lines)")
  }
}
