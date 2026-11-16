package verif.spectest

import verif.ai.RunSpec
import verif.config.CoreConfigs
import verif.harness.{CoreHarness, MemModel, Probe}

/** The verification trace must observe a real fetched instruction and a
  * recovery event.
  */
object TraceSpecTests {
  implicit val cp = CoreConfigs.byName("minimal")

  val instructions = new SpecTest("trace.instructions", Seq("contCoreTop")) {
    def run(): Seq[TCheck] = {
      val (spec, words, scenario) = RunSpec.toScenario(
        "@name trace-add\nli x1 5\naddi x2, x1, 7\ncheck x2 == 12\nexpect done\n"
      )
      val (result, rows)          = CoreHarness.trace(scenario.m, 140)
      val fetched                 = rows.filter(_.valid)
      val compared                = rows.flatMap(_.fetch).filter(_.compareValid)
      val retired                 = rows.flatMap(_.retire)
      Seq(
        TCheck(
          rows.map(_.i) == (0 until 140),
          "one trace row per requested post-reset cycle"
        ),
        TCheck(
          fetched.nonEmpty && fetched.head.pc == 0x80000L,
          "trace observes the first real instruction at the boot address"
        ),
        TCheck(
          Set(0, 1).subsetOf(compared.map(_.lane).toSet) &&
            compared.forall(f => f.instRaw == f.expInst) && !rows.exists(
              _.desync
            ),
          "both fetch lanes match the supplied program image"
        ),
        TCheck(
          retired.nonEmpty && retired.map(_.order) == retired.indices.map(
            BigInt(_)
          ) &&
            retired.map(_.pc) == result.pcStream,
          "trace retirement events preserve sequence and match harness PC capture"
        ),
        TCheck(
          RunSpec.Result(spec, result, words).allPass,
          "instrumented core still completes the program and stores the expected result"
        )
      )
    }
  }

  val recovery = new SpecTest("trace.recovery", Seq("contCoreTop")) {
    def run(): Seq[TCheck] = {
      val (_, _, scenario) = RunSpec.toScenario(
        "@name trace-recovery\nli x1 20\nli x2 3\ndiv x3, x1, x2\n" +
          "beq x0, x0, target\naddi x4, x0, 99\n" +
          "target: check x3 == 6\nexpect done\n"
      )
      val rows             = Probe.trace(scenario.m, 180)
      Seq(
        TCheck(
          rows.exists(_.flush),
          "cold taken branch produces an observed RecoveryEvent"
        ),
        TCheck(rows.exists(_.jump), "branch resolution is observable"),
        TCheck(
          rows.exists(_.divBusy),
          "iterative division has observable occupied cycles"
        ),
        TCheck(rows.exists(_.aluWB), "ALU publication is observable")
      )
    }
  }

  val oooIssueOverlap = new SpecTest(
    "trace.oooIssueOverlap",
    Seq(
      "funcSelectOldestReady",
      "propRsIssueOnlyReady",
      "funcCommitHead",
      "propCommitInOrder"
    )
  ) {
    def run(): Seq[TCheck] = {
      val source                  = scala.io.Source.fromFile("verif/scn/ooo_issue_overlap.scn")
      val text                    =
        try source.mkString
        finally source.close()
      val (spec, words, scenario) = RunSpec.toScenario(text)
      val (result, rows)          = CoreHarness.trace(scenario.m, scenario.maxCycles)
      val fences                  = words.indices.filter(i => (words(i) & 0x707fL) == 0x0fL)
      // Exact instruction encodings protect the inference from extra ALU work in this window.
      val expectedBody            = Seq(0x0262c3b3L, 0x00a38433L, 0x00b504b3L)
      val isolatedBody            = fences.size == 2 &&
        words.slice(fences.head + 1, fences.last) == expectedBody
      val fencePcs                = fences.map(i => MemModel.PROG_BASE + 4L * i)
      val fenceRows               =
        rows.filter(c => c.retire.exists(r => fencePcs.contains(r.pc)))
      val boundaries              = fenceRows.size == 2 &&
        fenceRows.flatMap(_.retire).map(_.pc) == fencePcs &&
        fenceRows.flatMap(_.retire).forall(r => !r.trap && !r.wen)
      val window                  =
        if (boundaries)
          rows.filter(c => c.i > fenceRows.head.i && c.i < fenceRows.last.i)
        else Seq.empty
      val retired                 = window.flatMap(_.retire)
      val busy                    = window.filter(_.divBusy)
      val publications            = window.filter(_.aluWB)
      val overlap                 = publications.filter(_.divBusy)
      val before                  =
        if (boundaries) rows.filter(_.i < fenceRows.head.i).flatMap(_.retire)
        else Seq.empty
      val inputs                  = before.filter(_.wen).groupBy(_.rd).map { case (rd, rs) =>
        rd -> rs.last.wdata
      }
      val readyInputs             =
        Seq(5 -> 0x7fffffffL, 6 -> 3L, 10 -> 11L, 11 -> 31L).forall {
          case (rd, value) => inputs.get(rd).contains(value)
        }
      val expectedRetire          = expectedBody.zipWithIndex.map { case (insn, i) =>
        (
          fencePcs.headOption.getOrElse(0L) + 4L * (i + 1),
          insn,
          i + 7,
          Seq(0x2aaaaaaaL, 0x2aaaaab5L, 42L)(i)
        )
      }
      val bodyRetires             =
        retired.map(r => (r.pc, r.insn, r.rd, r.wdata)) == expectedRetire &&
          retired.forall(r => r.wen && !r.trap) &&
          retired
            .map(_.order)
            .sliding(2)
            .forall(pair => pair.size < 2 || pair(1) == pair(0) + 1)
      val divRetire               = window.find(_.retire.exists(_.rd == 7))
      val busyBeforeRetire        = busy.nonEmpty &&
        divRetire.exists(d => busy.forall(_.i < d.i))
      // FENCE drains the prologue and blocks the result-store tail. Of the two ADDs,
      // x8 needs DIV's final value; only the younger x9 can publish while DIV is busy.
      val youngerRanEarly         = publications.size == 2 && overlap.size == 1 &&
        busyBeforeRetire && overlap.forall(c => divRetire.exists(_.i > c.i)) &&
        publications.exists(c => c.i > busy.last.i)
      val detail                  = s"fences=${fenceRows.map(_.i)} busy=${busy.map(_.i)} " +
        s"alu=${publications.map(_.i)} retired=$retired"
      Seq(
        TCheck(
          isolatedBody && boundaries && readyInputs && window.forall(
            _.recovery.isEmpty
          ),
          "two retired fences isolate DIV, its older dependent ADD and its younger independent ADD",
          detail
        ),
        TCheck(
          youngerRanEarly,
          "the younger independent ADD publishes during DIV occupancy before the dependent ADD",
          detail
        ),
        TCheck(
          bodyRetires,
          "DIV, dependent ADD and independent ADD retire in program order with their final values",
          detail
        ),
        TCheck(
          RunSpec.Result(spec, result, words).allPass,
          "the persistent OoO scenario completes all architectural checks"
        )
      )
    }
  }

  val faults = new SpecTest("trace.faults", Seq("contCoreTop")) {
    def run(): Seq[TCheck] = {
      val source                  =
        scala.io.Source.fromFile("verif/scn/sv32_inst_page_fault.scn")
      val text                    =
        try source.mkString
        finally source.close()
      val (spec, words, scenario) = RunSpec.toScenario(text)
      val (result, rows)          = CoreHarness.trace(scenario.m, 1800)
      val fault                   = rows.flatMap(_.fetch).filter(_.fault != 0)
      Seq(
        TCheck(
          fault.exists(_.pc == 0x401000L) && fault.forall(f =>
            !f.compareValid && !f.desync
          ),
          "instruction page-fault payloads are observed without byte-mismatch verdicts"
        ),
        TCheck(
          rows
            .flatMap(_.retire)
            .exists(r => r.trap && r.cause == 12 && r.tval == 0x401000L),
          "trace captures the precise architectural page-fault token"
        ),
        TCheck(
          RunSpec.Result(spec, result, words).allPass,
          "traced Sv32 scenario preserves its architectural checks"
        )
      )
    }
  }

  val interrupts = new SpecTest("trace.interrupts", Seq("contCoreTop")) {
    def run(): Seq[TCheck] = {
      val source                  =
        scala.io.Source.fromFile("verif/scn/irq_timer_retire_boundary.scn")
      val text                    =
        try source.mkString
        finally source.close()
      val (spec, words, scenario) = RunSpec.toScenario(text)
      val (result, rows)          = CoreHarness.trace(scenario.m, 1000, scenario.irq)
      val interrupts              =
        rows.filter(_.retire.exists(r => r.trap && r.source == 1))
      Seq(
        TCheck(
          interrupts.nonEmpty && interrupts.forall(c =>
            c.i >= 400 &&
              c.retire.exists(_.cause == 7)
          ),
          "scheduled timer pulse appears at a precise retire boundary"
        ),
        TCheck(
          RunSpec.Result(spec, result, words).allPass,
          "traced interrupt scenario preserves its architectural checks"
        )
      )
    }
  }

  val all: Seq[SpecTest] =
    Seq(instructions, recovery, oooIssueOverlap, faults, interrupts)
}
