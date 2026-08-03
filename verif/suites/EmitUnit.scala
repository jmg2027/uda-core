package verif.suites

import chisel3.RawModule
import circt.stage.ChiselStage
import udacore.external.alu.design.{Alu, AluParams}
import udacore.external.bitalu.design.{BitAlu, BitAluParams}
import udacore.external.divider.design.{Divider, DividerParams}
import udacore.external.multiplier.design.{Multiplier, MultiplierParams}

/** Emit an implemented external functional unit to SystemVerilog for out-of-context
 *  synthesis/STA - the working end of the ported PPA flow while CoreTop is a spec shell.
 *
 *    run.sh verif.suites.EmitUnit <configName> <outDir>
 *    run.sh verif.suites.EmitUnit list
 *
 *  Each config names a unit + parameter point; the emitted file is <TopName>.sv and the top
 *  module name matches (Alu / Multiplier / Divider / BitAlu), which ppa-unit.sh / sta scripts
 *  pick up for link_design. */
object EmitUnit {
  case class Cfg(top: String, gen: () => RawModule, desc: String)

  val configs: Map[String, Cfg] = Map(
    "alu"          -> Cfg("Alu", () => new Alu(AluParams(dataWidth = 32)), "baseline 32-bit ALU"),
    "mul_csa16"    -> Cfg("Multiplier", () => new Multiplier(MultiplierParams(sliceWidth = 16)),
                          "iterative CSA multiplier, 16-bit slice (default point)"),
    "mul_csa32"    -> Cfg("Multiplier", () => new Multiplier(MultiplierParams(sliceWidth = 32)),
                          "single-iteration 32-bit slice multiplier"),
    "mul_noearly"  -> Cfg("Multiplier", () => new Multiplier(MultiplierParams(sliceWidth = 16,
                          earlyOutZero = false, earlyOutOne = false, earlyOutUpper16IsZero = false)),
                          "csa16 with every early-out disabled (early-out cost isolator)"),
    "div_nrclz"    -> Cfg("Divider", () => new Divider(DividerParams(dataWidth = 32, useRestoring = false, useClz = true)),
                          "non-restoring divider with CLZ skip"),
    "div_nrnoclz"  -> Cfg("Divider", () => new Divider(DividerParams(dataWidth = 32, useRestoring = false, useClz = false)),
                          "non-restoring divider, no CLZ skip"),
    "div_restoring"-> Cfg("Divider", () => new Divider(DividerParams(dataWidth = 32, useRestoring = true, useClz = false)),
                          "restoring divider"),
    "bitalu"       -> Cfg("BitAlu", () => new BitAlu(BitAluParams(dataWidth = 32)), "full Zba+Zbb+Zbs+Zbc"),
    "bitalu_nozbc" -> Cfg("BitAlu", () => new BitAlu(BitAluParams(dataWidth = 32, enableZbc = false)),
                          "without the carry-less multiply array")
  )

  def main(args: Array[String]): Unit = {
    if (args.headOption.contains("list") || args.isEmpty) {
      configs.toSeq.sortBy(_._1).foreach { case (n, c) => println(f"$n%-14s ${c.top}%-11s ${c.desc}") }
      if (args.isEmpty) sys.exit(2)
      return
    }
    val cfgName = args(0)
    val outDir  = if (args.length > 1) args(1) else "/tmp/emit/unit_" + cfgName
    val cfg = configs.getOrElse(cfgName,
      sys.error(s"unknown unit config '$cfgName' (run with 'list' for the catalog)"))
    java.nio.file.Files.createDirectories(java.nio.file.Paths.get(outDir))
    val verilog = ChiselStage.emitSystemVerilog(
      cfg.gen(),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info",
        "--lowering-options=disallowLocalVariables,locationInfoStyle=none")
    )
    val out = java.nio.file.Paths.get(outDir, s"${cfg.top}.sv")
    java.nio.file.Files.write(out, verilog.getBytes)
    println(s"[emit] unit $cfgName (${cfg.top}) -> $out  (${verilog.linesIterator.size} lines)")
  }
}
