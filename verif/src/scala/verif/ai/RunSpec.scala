package verif.ai

import assembler.RISCVAssembler
import udacore.core.design.shared.CoreParams
import verif.config.CoreConfigs
import verif.harness.{CoreHarness, HarnessNotReady, MemModel, RunResult}

/** No-recompile entrypoint: read a `.scn` (file arg or stdin), run it on the real core, emit JSON
 *  (`--json`) or a terse human line. This is the AI's tight loop - author text, get structured
 *  results, never recompile the framework. */
object RunSpec {
  def assemble(asm: String): Seq[Long] =
    RISCVAssembler.fromString(asm).trim.split("\n").filter(_.nonEmpty)
      .map(h => java.lang.Long.parseLong(h, 16) & 0xFFFFFFFFL).toSeq

  def hash(words: Seq[Long]): String =
    f"${words.foldLeft(0x811c9dc5L) { (h, w) => ((h ^ (w & 0xFFFFFFFFL)) * 0x01000193L) & 0xFFFFFFFFL }}%08x"

  /** A HarnessNotReady as structured output: the ai layer reports harness status as data, not a
   *  stack trace, so a fresh session probing `scn.sh run` learns the state machine-readably. */
  def notReadyJson(e: HarnessNotReady): Js =
    Js.of("error" -> Js.S("harness-not-ready"), "detail" -> Js.S(e.getMessage))

  case class Result(spec: Spec, rr: RunResult, words: Seq[Long]) {
    def checkRows: Seq[(Check, Boolean, Boolean, Int)] = spec.checks.map { c =>
      val stored = rr.resultStored(c.slot * 4L); val got = rr.result(c.slot * 4L)
      (c, stored && got == c.exp, stored, got)
    }
    def expectRows: Seq[(String, Boolean)] = {
      val a = if (spec.expectDone) Seq("done" -> rr.done) else Nil
      val b = if (spec.expectDerail) Seq("derail" -> rr.derailed) else Nil
      a ++ b
    }
    def allPass: Boolean = checkRows.forall(_._2) && expectRows.forall(_._2)
    def json: Js = Js.of(
      "name" -> Js.S(spec.name),
      "verifies" -> Js.Arr(spec.verifies.map(Js.S)),
      "program_hash" -> Js.S(hash(words)),
      "instrs" -> Js.N(words.size),
      "cycles" -> Js.N(rr.cycles),
      "done" -> Js.B(rr.done),
      "derailed" -> Js.B(rr.derailed),
      "pass" -> Js.B(allPass),
      "checks" -> Js.Arr(checkRows.map { case (c, ok, stored, got) =>
        Js.of("reg" -> Js.S(c.reg), "slot" -> Js.N(c.slot),
              "status" -> Js.S(if (!stored) "NOSTORE" else if (ok) "PASS" else "FAIL"),
              "got" -> Js.hex(got & 0xFFFFFFFFL), "exp" -> Js.hex(c.exp & 0xFFFFFFFFL)) }),
      "expect" -> Js.Arr(expectRows.map { case (k, ok) => Js.of("kind" -> Js.S(k), "status" -> Js.S(if (ok) "PASS" else "FAIL")) }),
      "diagnosis" -> (Diagnose.of(words, rr) match { case Some(d) => Js.S(d); case None => Js.Null })
    )
    def human: String = {
      val sb = new StringBuilder
      sb.append(s"[${if (allPass) "PASS" else "FAIL"}] ${spec.name}  (${words.size} instrs, ${rr.cycles} cyc, done=${rr.done}, derail=${rr.derailed})\n")
      checkRows.foreach { case (c, ok, stored, got) =>
        sb.append(f"  ${if (!stored) "NOSTORE" else if (ok) "PASS" else "FAIL"}%-7s ${c.reg}%-4s got=0x${got & 0xFFFFFFFFL}%08X exp=0x${c.exp & 0xFFFFFFFFL}%08X\n") }
      expectRows.foreach { case (k, ok) => sb.append(f"  ${if (ok) "PASS" else "FAIL"}%-7s expect-$k\n") }
      Diagnose.of(words, rr).foreach(d => sb.append(s"  diagnosis: $d\n"))
      sb.toString
    }
  }

  /** Parse + assemble a `.scn` into a CoreHarness.Scenario (carrying the Spec + words for result
   *  scoring). The building block for both the single-shot `run` and the batched `runBatch`. */
  def toScenario(text: String): (Spec, Seq[Long], CoreHarness.Scenario) = {
    val spec = Spec.parse(text)
    val words = assemble(spec.asm)
    val pulses = spec.irq.map { case (k, a, d) => CoreHarness.IrqPulse(k, a, d) }
    (spec, words, CoreHarness.Scenario(spec.name, MemModel.fromWords(words, spec.iLat, spec.dLat), spec.maxCycles, irq = pulses))
  }

  def run(text: String)(implicit p: CoreParams): Result = {
    val (spec, words, scn) = toScenario(text)
    val rr = CoreHarness.run(scn.name, scn.m, scn.maxCycles, irq = scn.irq)
    Result(spec, rr, words)
  }

  /** Run many `.scn` in one compiled DUT (CoreHarness.runBatch) - compile once, reset between. */
  def runBatch(texts: Seq[String])(implicit p: CoreParams): Seq[Result] = {
    val built = texts.map(toScenario)
    val rrs = CoreHarness.runBatch(built.map(_._3))
    built.zip(rrs).map { case ((spec, words, _), rr) => Result(spec, rr, words) }
  }

  def main(args: Array[String]): Unit = {
    implicit val cfg: CoreParams = CoreConfigs.fromArgs(args)._2
    val json = args.contains("--json")
    val file = args.find(a => !a.startsWith("--"))
    val text = file.map(f => scala.io.Source.fromFile(f).mkString)
                   .getOrElse(scala.io.Source.stdin.mkString)
    try {
      val r = run(text)
      if (json) println(r.json.render) else print(r.human)
      if (!r.allPass) sys.exit(1)
    } catch {
      case e: HarnessNotReady =>
        if (json) println(notReadyJson(e).render) else println(s"harness-not-ready: ${e.getMessage}")
        sys.exit(3)
    }
  }
}
