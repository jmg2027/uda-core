package verif.spectest

import verif.ai.RunSpec
import verif.config.CoreConfigs
import verif.harness.{CoreHarness, Probe}

/** The verification trace must observe a real fetched instruction and a recovery event. */
object TraceSpecTests {
  implicit val cp = CoreConfigs.byName("minimal")

  val instructions = new SpecTest("trace.instructions", Seq("contCoreTop")) {
    def run(): Seq[TCheck] = {
      val (spec, words, scenario) = RunSpec.toScenario(
        "@name trace-add\nli x1 5\naddi x2, x1, 7\ncheck x2 == 12\nexpect done\n")
      val (result, rows) = CoreHarness.trace(scenario.m, 140)
      val fetched = rows.filter(_.valid)
      val compared = rows.flatMap(_.fetch).filter(_.compareValid)
      val retired = rows.flatMap(_.retire)
      Seq(
        TCheck(rows.map(_.i) == (0 until 140), "one trace row per requested post-reset cycle"),
        TCheck(fetched.nonEmpty && fetched.head.pc == 0x80000L,
          "trace observes the first real instruction at the boot address"),
        TCheck(Set(0, 1).subsetOf(compared.map(_.lane).toSet) &&
          compared.forall(f => f.instRaw == f.expInst) && !rows.exists(_.desync),
          "both fetch lanes match the supplied program image"),
        TCheck(retired.nonEmpty && retired.map(_.order) == retired.indices.map(BigInt(_)) &&
          retired.map(_.pc) == result.pcStream,
          "trace retirement events preserve sequence and match harness PC capture"),
        TCheck(RunSpec.Result(spec, result, words).allPass,
          "instrumented core still completes the program and stores the expected result")
      )
    }
  }

  val recovery = new SpecTest("trace.recovery", Seq("contCoreTop")) {
    def run(): Seq[TCheck] = {
      val (_, _, scenario) = RunSpec.toScenario(
        "@name trace-recovery\nli x1 20\nli x2 3\ndiv x3, x1, x2\n" +
        "beq x0, x0, target\naddi x4, x0, 99\n" +
        "target: check x3 == 6\nexpect done\n")
      val rows = Probe.trace(scenario.m, 180)
      Seq(
        TCheck(rows.exists(_.flush), "cold taken branch produces an observed RecoveryEvent"),
        TCheck(rows.exists(_.jump), "branch resolution is observable"),
        TCheck(rows.exists(_.divBusy), "iterative division has observable occupied cycles"),
        TCheck(rows.exists(_.aluWB), "ALU publication is observable")
      )
    }
  }

  val faults = new SpecTest("trace.faults", Seq("contCoreTop")) {
    def run(): Seq[TCheck] = {
      val source = scala.io.Source.fromFile("verif/scn/sv32_inst_page_fault.scn")
      val text = try source.mkString finally source.close()
      val (spec, words, scenario) = RunSpec.toScenario(text)
      val (result, rows) = CoreHarness.trace(scenario.m, 1800)
      val fault = rows.flatMap(_.fetch).filter(_.fault != 0)
      Seq(
        TCheck(fault.exists(_.pc == 0x401000L) && fault.forall(f => !f.compareValid && !f.desync),
          "instruction page-fault payloads are observed without byte-mismatch verdicts"),
        TCheck(rows.flatMap(_.retire).exists(r => r.trap && r.cause == 12 && r.tval == 0x401000L),
          "trace captures the precise architectural page-fault token"),
        TCheck(RunSpec.Result(spec, result, words).allPass,
          "traced Sv32 scenario preserves its architectural checks")
      )
    }
  }

  val interrupts = new SpecTest("trace.interrupts", Seq("contCoreTop")) {
    def run(): Seq[TCheck] = {
      val source = scala.io.Source.fromFile("verif/scn/irq_timer_retire_boundary.scn")
      val text = try source.mkString finally source.close()
      val (spec, words, scenario) = RunSpec.toScenario(text)
      val (result, rows) = CoreHarness.trace(scenario.m, 1000, scenario.irq)
      val interrupts = rows.filter(_.retire.exists(r => r.trap && r.source == 1))
      Seq(
        TCheck(interrupts.nonEmpty && interrupts.forall(c => c.i >= 400 &&
          c.retire.exists(_.cause == 7)), "scheduled timer pulse appears at a precise retire boundary"),
        TCheck(RunSpec.Result(spec, result, words).allPass,
          "traced interrupt scenario preserves its architectural checks")
      )
    }
  }

  val all: Seq[SpecTest] = Seq(instructions, recovery, faults, interrupts)
}
