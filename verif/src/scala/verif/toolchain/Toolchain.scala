package verif.toolchain

import assembler.RISCVAssembler
import udacore.core.design.shared.CoreParams
import verif.harness.{CoreHarness, MemModel, RunResult}
import scala.sys.process._

/** C/asm -> flat image -> words, via riscv64-unknown-elf-gcc (the main-line Phase-2 pipeline as
 *  an API). crt0.S/link.ld/build_c.sh live in verif/tests. main()'s return value lands at
 *  RESULT (a0). */
object CProgram {
  val RESULT = 0x80000F00L
  /** compile sources (paths relative to verif/tests or absolute) and return the image words. */
  def build(srcs: Seq[String], march: String = "rv32im", testsDir: String = "verif/tests"): Seq[Long] = {
    val out = "/tmp/verif_cprog"
    val cmd = Seq("bash", s"$testsDir/build_c.sh", out) ++ srcs.map(s => if (s.startsWith("/")) s else s"$testsDir/$s")
    val rc = Process(cmd, None, "MARCH" -> march).!
    require(rc == 0, s"build_c.sh failed (rc=$rc)")
    scala.io.Source.fromFile(s"$out.words").getLines().map(_.trim).filter(_.nonEmpty)
      .map(h => java.lang.Long.parseLong(h, 16) & 0xFFFFFFFFL).toSeq
  }
  /** compile + run on the real core; returns (main's return value, full run result). */
  def run(srcs: Seq[String], march: String = "rv32im", maxCycles: Int = 20000)
         (implicit p: CoreParams): (Int, RunResult) = {
    val rr = CoreHarness.run("c-program", MemModel.fromWords(build(srcs, march)), maxCycles)
    ((rr.stores.getOrElse(RESULT, MemModel.SENTINEL) & 0xFFFFFFFFL).toInt, rr)
  }
}

/** Differential validation of the Scala RISCVAssembler against riscv64-unknown-elf-as. */
object AsmDiff {
  /** assemble `line` with the Scala assembler; return (scalaHex, golden-or-None-if-as-unavailable). */
  def check(line: String, march: String = "rv32imf_zba_zbb_zbc_zbs", abi: String = "ilp32f"): (String, Option[String]) = {
    val scalaHex = RISCVAssembler.fromString(line).trim.toLowerCase
    val golden = try {
      val tmp = java.io.File.createTempFile("asmdiff", ".s"); val o = tmp.getPath + ".o"
      java.nio.file.Files.write(tmp.toPath, (line + "\n").getBytes)
      if (Seq("riscv64-unknown-elf-as", s"-march=$march", s"-mabi=$abi", tmp.getPath, "-o", o).! == 0) {
        val dis = Seq("riscv64-unknown-elf-objdump", "-d", o).!!
        dis.linesIterator.find(_.matches("""\s+[0-9a-f]+:.*"""))
           .map(_.trim.split("\\s+")(1).toLowerCase)
      } else None
    } catch { case _: Throwable => None }
    (scalaHex, golden)
  }
}
