package verif.ai

import udacore.core.design.shared.CoreParams
import verif.config.CoreConfigs
import verif.harness.{CoreHarness, HarnessNotReady, MemModel}

/** The discipline as a guardrail. Given a scenario (and optionally a control variant), runs the
 *  three PLAYBOOK gates automatically and returns a verdict the AI is meant to trust over its own
 *  intuition - so a plausible-but-fake bug (harness artifact) cannot be promoted to a finding.
 *
 *  1. latency-invariant - same behavior across symmetric imem=dmem latencies {1,2,3}.
 *  2. harness-independent - same behavior under skewed memory (iLat != dLat). A result that
 *     depends on the fetch-vs-data timing relationship is a harness artifact, not architecture.
 *  3. differential - the suspected asymmetry survives against a control that changes only the
 *     suspected variable.
 *
 *  Behavior = (result slot0, derailed, done). */
object Gate {
  case class Behavior(r0: Int, derailed: Boolean, done: Boolean) {
    def key: (Int, Boolean, Boolean) = (r0, derailed, done)
    def js: Js = Js.of("r0" -> Js.hex(r0 & 0xFFFFFFFFL), "derailed" -> Js.B(derailed), "done" -> Js.B(done))
  }
  def behaviorAt(words: Seq[Long], iLat: Int, dLat: Int, maxCycles: Int)(implicit p: CoreParams): Behavior = {
    val rr = CoreHarness.run(s"gate@i${iLat}d${dLat}", MemModel.fromWords(words, iLat, dLat), maxCycles)
    Behavior(rr.result(0), rr.derailed, rr.done)
  }

  def evaluate(scnText: String, controlText: Option[String])(implicit p: CoreParams): Js = {
    val spec = Spec.parse(scnText)
    val words = RunSpec.assemble(spec.asm)
    // Gate 1: symmetric latency sweep.  Gate 2: skewed (iLat != dLat) memory - the
    // harness-independence axis. Labels: "i<I>d<D>".
    val symPts  = Seq((1, 1), (2, 2), (3, 3))
    val skewPts = Seq((1, 3), (3, 1))
    def label(i: Int, d: Int) = s"i${i}d${d}"
    val byMem = (symPts ++ skewPts).map { case (i, d) => label(i, d) -> behaviorAt(words, i, d, spec.maxCycles) }.toMap
    val latInvariant     = symPts.map { case (i, d) => byMem(label(i, d)).key }.toSet.size == 1
    val harnessInvariant = (symPts ++ skewPts).map { case (i, d) => byMem(label(i, d)).key }.toSet.size == 1

    val ctl = controlText.map { t =>
      val cs = Spec.parse(t); behaviorAt(RunSpec.assemble(cs.asm), 2, 2, cs.maxCycles)
    }
    val variant = byMem(label(2, 2))
    val differ = ctl.map(c => c.key != variant.key)

    def fmt = byMem.toSeq.sortBy(_._1).map { case (k, b) => s"$k=0x${(b.r0 & 0xFFFFFFFFL).toHexString}/derail=${b.derailed}/done=${b.done}" }.mkString(", ")
    val (verdict, reason) =
      if (!latInvariant)
        ("HARNESS-SUSPECT", "result is not latency-invariant: behavior flips across symmetric imem=dmem latency [" +
          fmt + "] - a real RTL bug does not care about latency; this is harness-timing-sensitive.")
      else if (!harnessInvariant)
        ("HARNESS-SUSPECT", "latency-invariant under symmetric memory but flips under skewed iLat!=dLat [" +
          fmt + "] - the result depends on the fetch-vs-data timing relationship, not the architecture; " +
          "this is exactly the harness-dependence class. Re-examine before claiming a bug.")
      else differ match {
        case Some(true)  => ("CONFIRMED", "latency-invariant, harness-invariant (stable across iLat!=dLat skew), and the differential asymmetry survives an identical harness (variant != control). Trust it.")
        case Some(false) => ("INCONCLUSIVE", "latency- and harness-invariant but variant behaves identically to the control - the suspected difference is not real; not a bug.")
        case None        => ("INCONCLUSIVE", "latency- and harness-invariant, but no control variant was given. Provide a differential (change only the suspected variable) before claiming a bug.")
      }

    Js.of(
      "scenario" -> Js.S(spec.name),
      "memory" -> Js.Obj(byMem.toSeq.sortBy(_._1).map { case (k, b) => k -> b.js }),
      "latency_invariant" -> Js.B(latInvariant),
      "harness_invariant" -> Js.B(harnessInvariant),
      "differential" -> (ctl match {
        case Some(c) => Js.of("variant" -> variant.js, "control" -> c.js, "differ" -> Js.B(differ.get))
        case None    => Js.Null }),
      "verdict" -> Js.S(verdict),
      "reason" -> Js.S(reason)
    )
  }

  def main(args: Array[String]): Unit = {
    implicit val cfg: CoreParams = CoreConfigs.fromArgs(args)._2
    val files = args.filterNot(_.startsWith("--"))
    val json = args.contains("--json")
    require(files.nonEmpty, "usage: Gate <scenario.scn> [control.scn] [--json] [--config=<name>]")
    val scn = scala.io.Source.fromFile(files(0)).mkString
    val ctl = files.lift(1).map(f => scala.io.Source.fromFile(f).mkString)
    try {
      val out = evaluate(scn, ctl)
      if (json) println(out.render)
      else {
        val o = out.asInstanceOf[Js.Obj].fields.toMap
        println(s"verdict: ${o("verdict").asInstanceOf[Js.S].v}")
        println(s"reason:  ${o("reason").asInstanceOf[Js.S].v}")
      }
    } catch {
      case e: HarnessNotReady =>
        if (json) println(RunSpec.notReadyJson(e).render) else println(s"harness-not-ready: ${e.getMessage}")
        sys.exit(3)
    }
  }
}
