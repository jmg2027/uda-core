package verif

import assembler.RISCVAssembler
import udacore.core.design.shared.CoreParams
import verif.harness.{CoreHarness, MemModel, RunResult}

/** A named group of scenarios. `run` assembles each program, drives it on the real core via the
 *  single CoreHarness, applies its checks, prints a graded line per check, and aggregates
 *  PASS / FAIL / DERAIL. `runMain` sets a CI-friendly exit status. */
case class Suite(name: String, scenarios: Seq[Scenario]) {
  case class Tally(pass: Int, fail: Int, nostore: Int) {
    def +(c: CheckResult): Tally =
      if (!c.stored) copy(nostore = nostore + 1) else if (c.ok) copy(pass = pass + 1) else copy(fail = fail + 1)
    def total: Int = pass + fail + nostore
  }

  /** Assemble the scenarios into harness form. No DUT compile happens here, so a multi-suite
   *  battery can flatten every suite's prepared scenarios into one runBatch (one compile). */
  def prepared(verbose: Boolean = false): Seq[CoreHarness.Scenario] = scenarios.map { s =>
    val words = RISCVAssembler.fromString(s.asm).trim.split("\n").filter(_.nonEmpty)
      .map(h => java.lang.Long.parseLong(h, 16) & 0xFFFFFFFFL).toSeq
    CoreHarness.Scenario(s.name, MemModel.fromWords(words, s.iLat, s.dLat), s.maxCycles, verbose)
  }

  /** Grade results paired 1:1 with this suite's scenarios. */
  def grade(results: Seq[RunResult]): Tally = {
    println(s"\n##### suite: $name #####")
    var t = Tally(0, 0, 0)
    scenarios.zip(results).foreach { case (s, rr) =>
      for (c <- s.check(rr)) {
        t = t + c
        println(f"  [${c.tag}%-6s] ${s.name}%-22s ${c.label}%-26s ${c.detail}")
      }
    }
    println(s"===== $name: PASS=${t.pass} FAIL=${t.fail} NOSTORE=${t.nostore} (of ${t.total}) =====")
    t
  }

  def run(verbose: Boolean = false)(implicit p: CoreParams): Tally = {
    // Compile the DUT once and run every scenario on it (reset between each) instead of paying a
    // fresh FIRRTL->SV->Verilator compile per scenario. Compile dominates wall-clock, so this turns
    // N compiles into 1. Programs are self-contained (the register file is reset-less but each
    // scenario sets its own regs), validated on main by batch-vs-isolated equivalence.
    grade(CoreHarness.runBatch(prepared(verbose)))
  }

  /** Run and exit non-zero on any FAIL (DERAIL is reported but, being often a known bug, does
   *  not by itself fail the suite unless you treat it as such). */
  def runMain(implicit p: CoreParams): Unit = {
    val t = run()
    if (t.fail > 0) sys.exit(1)
  }
}

/** Runs several suites and aggregates. Each suite's `object ... Main` calls this. */
object SuiteRunner {
  def runAll(suites: Seq[Suite])(implicit p: CoreParams): Unit = {
    // Every suite in one battery runs under the same config, so the DUT is identical: flatten
    // all scenarios into a single CoreHarness.runBatch (one compile for the whole battery
    // instead of one per suite) and re-split the results for per-suite grading.
    val prepared = suites.map(_.prepared())
    val results = CoreHarness.runBatch(prepared.flatten)
    val offsets = prepared.scanLeft(0)(_ + _.length)
    val tallies = suites.zip(offsets.zip(offsets.tail)).map { case (s, (from, until)) =>
      (s.name, s.grade(results.slice(from, until)))
    }
    println("\n========== SUMMARY ==========")
    var fails = 0
    tallies.foreach { case (n, t) =>
      println(f"  $n%-16s PASS=${t.pass}%-4d FAIL=${t.fail}%-4d NOSTORE=${t.nostore}")
      fails += t.fail
    }
    if (fails > 0) sys.exit(1)
  }
}
