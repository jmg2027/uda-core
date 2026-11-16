package udacore.verif

import org.scalatest.funsuite.AnyFunSuite
import _root_.verif.spectest.{RunSpecTests, SpecTestRunner}

/** Expose every active ADR-018 L1 test through the sbt ScalaTest gate. */
class ActiveSpecTestSuite extends AnyFunSuite {
  private val registry = RunSpecTests.registry
  private val names    = registry.map(_.name)
  require(
    registry.size >= 376,
    "The active L1 registry must retain at least 376 tests"
  )
  require(
    names.distinct.size == names.size,
    "Active L1 test names must be unique"
  )

  registry.foreach { specTest =>
    test(s"L1: ${specTest.name}") {
      val result = SpecTestRunner.runOne(specTest)
      val checks =
        result.checks.filter(c => !c.ok || c.known.nonEmpty).map { c =>
          s"${c.label}: ${c.detail}${c.known.map(k => s" (known: $k)").getOrElse("")}"
        }
      val detail =
        (Seq(s"${result.status.tag}: ${result.note}") ++ checks).mkString("\n")
      // Preserve the runner's XFAIL/XPASS decisions; PENDING cannot pass this gate.
      assert(result.status == SpecTestRunner.Pass, detail)
    }
  }
}
