package verif.gates

import assembler.RISCVAssembler
import udacore.core.design.shared.CoreParams
import verif.harness.{CoreHarness, MemModel}

/** The 3-gate discipline (main-line PLAYBOOK section 6), as reusable combinators. A candidate bug
 *  must clear all three before it is trusted; these helpers run the sweeps and return structured
 *  verdicts so a suite never silently promotes a harness artifact to a "finding". */
object Gates {
  /** Run the same program across several memory latencies. A real RTL bug is latency-invariant;
   *  a result that flips across latency is harness-suspect. Returns (lat -> resultSlot0). */
  def latencySweep(asm: String, lats: Seq[Int] = Seq(1, 2, 3), slot: Long = 0, maxCycles: Int = 300)
                  (implicit p: CoreParams): Map[Int, Int] = {
    val words = assemble(asm)
    lats.map { l =>
      val rr = CoreHarness.run(s"latsweep@$l", MemModel.fromWords(words, l, l), maxCycles)
      l -> rr.result(slot)
    }.toMap
  }
  def latencyInvariant(asm: String, lats: Seq[Int] = Seq(1, 2, 3), slot: Long = 0)
                      (implicit p: CoreParams): Boolean = {
    val r = latencySweep(asm, lats, slot); r.values.toSet.size == 1
  }

  /** Differential: two programs that differ in only the suspected variable. Returns their
   *  result slots; a suite asserts the expected asymmetry (e.g. control passes, variant fails). */
  def differential(asmA: String, asmB: String, slot: Long = 0, maxCycles: Int = 300)
                  (implicit p: CoreParams): (Int, Int) = {
    val a = CoreHarness.run("diffA", MemModel.fromWords(assemble(asmA)), maxCycles).result(slot)
    val b = CoreHarness.run("diffB", MemModel.fromWords(assemble(asmB)), maxCycles).result(slot)
    (a, b)
  }

  private def assemble(asm: String): Seq[Long] =
    RISCVAssembler.fromString(asm).trim.split("\n").filter(_.nonEmpty)
      .map(h => java.lang.Long.parseLong(h, 16) & 0xFFFFFFFFL).toSeq
}
