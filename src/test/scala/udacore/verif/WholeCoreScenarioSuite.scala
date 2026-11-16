package udacore.verif

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters._
import scala.util.Try
import _root_.verif.ai.{RunSpec, Spec}
import _root_.verif.config.CoreConfigs

/** Run the full scenario corpus once with the real CoreHarness reset/batch
  * driver.
  */
class WholeCoreScenarioSuite extends AnyFunSuite {
  private case class ScenarioFile(path: Path, text: String, spec: Spec)

  private val scenarioDirectory = Paths.get("verif/scn")
  require(
    Files.isDirectory(scenarioDirectory),
    s"Missing scenario corpus: $scenarioDirectory"
  )
  private val paths             = {
    val stream = Files.walk(scenarioDirectory)
    try
      stream.iterator.asScala
        .filter(p =>
          Files.isRegularFile(p) && p.getFileName.toString.endsWith(".scn")
        )
        .toVector
        .sortBy(_.toString)
    finally stream.close()
  }
  require(
    paths.size >= 23,
    "The whole-core corpus must retain at least 23 scenarios"
  )
  private val basenames         = paths.map(_.getFileName.toString)
  require(
    basenames.distinct.size == basenames.size,
    "Scenario basenames must be unique"
  )
  private val scenarios         = paths.map { path =>
    val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    ScenarioFile(path, text, Spec.parse(text))
  }
  private val names             = scenarios.map(_.spec.name)
  require(
    names.distinct.size == names.size,
    "Declared scenario names must be unique"
  )

  // Memoize failures as well, so a broken batch is not rerun for every reported scenario.
  private lazy val batchResults = Try {
    RunSpec.runBatch(scenarios.map(_.text))(CoreConfigs.byName("default"))
  }

  scenarios.zipWithIndex.foreach { case (scenario, index) =>
    test(s"L2: ${scenario.path.getFileName} (${scenario.spec.name})") {
      val results = batchResults.get
      assert(
        results.size == scenarios.size,
        "Every discovered scenario must return one result"
      )
      val result  = results(index)
      assert(
        result.spec.name == scenario.spec.name,
        "Batch results must match discovery order"
      )
      assert(
        result.spec.checks.nonEmpty,
        "Each scenario must check an architectural result"
      )
      // The parser appends DONE even when the source omits an explicit expect directive.
      assert(result.rr.done, result.human)
      assert(result.allPass, result.human)
    }
  }
}
