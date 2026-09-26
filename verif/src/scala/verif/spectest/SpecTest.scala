package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator
import chisel3.simulator.CachedSimulator._
import verif.ai.Js
import verif.harness.HarnessNotReady

/** One check inside a SpecTest: an observed-vs-expected judgment with a label that
  * traces to a sentence of the spec val under test (ADR-018 D-18.4 rule 2). */
case class TCheck(ok: Boolean, label: String, detail: String = "", known: Option[String] = None)

/** L1 of the ADR-018 test ladder: a vertex-level test bound BY NAME to the spec vals it
  * verifies. The runner records the binding in its JSON, and tools/spec-check.py check 6
  * (spec-test-coverage) resolves FUNCTION/PROPERTY obligations against these names.
  *
  * TDD colors (D-18.3): a test whose DUT is still a spec shell raises
  * NotImplementedError inside sim(); the runner reports it as PENDING (the sanctioned
  * red). A FAIL is red against an existing implementation. A first-run PASS against a
  * shell is vacuous and must be rejected in test review.
  */
abstract class SpecTest(val name: String, val verifies: Seq[String]) {
  require(verifies.nonEmpty, s"SpecTest '$name' must bind at least one spec val (ADR-018 D-18.1)")

  /** Simulate one vertex (content-keyed workspace cache, same engine as the harness).
    * Applies reset before the body: without it RegInit state is whatever the backend
    * zero-fills, which is exactly the nondeterministic-corruption trap this harness
    * once fell into (a wrong FAIL set that looked like an RTL bug was missing reset).
    * Public so a suite's shared drivers can open simulations on behalf of its tests. */
  def sim[T <: Module](gen: => T)(body: T => Seq[TCheck]): Seq[TCheck] = {
    var out: Seq[TCheck] = Nil
    CachedSimulator.simulate(gen) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step()
      out = body(dut)
    }
    out
  }

  def run(): Seq[TCheck]
}

object SpecTestRunner {
  sealed trait Status { def tag: String }
  case object Pass    extends Status { def tag = "PASS" }
  case object Fail    extends Status { def tag = "FAIL" }
  case object Pending extends Status { def tag = "PENDING" }

  case class Result(test: SpecTest, status: Status, checks: Seq[TCheck], note: String)

  def runOne(t: SpecTest): Result =
    try {
      val checks = t.run()
      // Known-bug (XFAIL) semantics: a failing check tagged `known` documents a filed
      // finding and does not gate; a PASSING known check is an XPASS - the bug got fixed,
      // so the tag must be removed (reported as FAIL to force the cleanup).
      val hardFail = checks.exists(c => !c.ok && c.known.isEmpty)
      val xpass    = checks.filter(c => c.ok && c.known.nonEmpty)
      val st = if (checks.isEmpty) Fail else if (hardFail || xpass.nonEmpty) Fail else Pass
      val note =
        if (checks.isEmpty) "test returned no checks (vacuous)"
        else if (xpass.nonEmpty) s"XPASS: ${xpass.map(_.label).mkString(", ")} - remove the known-bug tag"
        else {
          val xf = checks.count(c => !c.ok && c.known.nonEmpty)
          if (xf > 0) s"$xf known-bug XFAIL check(s) (non-gating)" else ""
        }
      Result(t, st, checks, note)
    } catch {
      case _: NotImplementedError =>
        Result(t, Pending, Nil, "DUT is a spec shell (red by construction, ADR-018 D-18.3)")
      case e: HarnessNotReady =>
        Result(t, Pending, Nil, s"harness-not-ready: ${e.getMessage.take(120)}")
      case e: Throwable =>
        Result(t, Fail, Nil, s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("").take(200)}")
    }

  def json(rs: Seq[Result]): Js = Js.Arr(rs.map { r =>
    Js.of(
      "test" -> Js.S(r.test.name),
      "verifies" -> Js.Arr(r.test.verifies.map(Js.S)),
      "status" -> Js.S(r.status.tag),
      "note" -> Js.S(r.note),
      "checks" -> Js.Arr(r.checks.map(c =>
        Js.of("label" -> Js.S(c.label),
              "status" -> Js.S(if (c.ok && c.known.nonEmpty) "XPASS"
                               else if (c.ok) "PASS"
                               else if (c.known.nonEmpty) "XFAIL" else "FAIL"),
              "known" -> c.known.map(Js.S).getOrElse(Js.Null),
              "detail" -> Js.S(c.detail))))
    )
  })

  /** Run tests (all, or those whose name contains a positional filter). Exit 1 on any
    * FAIL; PENDING never fails the run - it is the expected color of the shell era. */
  def run(tests: Seq[SpecTest], args: Array[String]): Unit = {
    val wantJson = args.contains("--json")
    val filter = args.filterNot(_.startsWith("--")).headOption
    val selected = filter.map(f => tests.filter(_.name.contains(f))).getOrElse(tests)
    val rs = selected.map(runOne)
    if (wantJson) println(json(rs).render)
    else {
      rs.foreach { r =>
        println(f"[${r.status.tag}%-7s] ${r.test.name}%-28s verifies=${r.test.verifies.mkString(",")}")
        r.checks.filterNot(_.ok).foreach { c =>
          val tag = c.known.map(k => s"XFAIL[$k]").getOrElse("FAIL")
          println(f"    $tag ${c.label}: ${c.detail}")
        }
        if (r.note.nonEmpty) println(s"    note: ${r.note}")
      }
      val (p, f, d) = (rs.count(_.status == Pass), rs.count(_.status == Fail), rs.count(_.status == Pending))
      println(s"===== spectest: PASS=$p FAIL=$f PENDING=$d (of ${rs.size}) =====")
    }
    if (rs.exists(_.status == Fail)) sys.exit(1)
  }
}
