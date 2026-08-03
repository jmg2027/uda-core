package klas

import chiseltest._
import chiseltest.simulator.{SimulatorDebugAnnotation, VerilatorFlags}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.sys.process._

abstract class CoreTest
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  def verilatorSupports(flag: String): Boolean = {
    try {
      // Prefer probing help with the flag; if unsupported, Verilator exits non-zero
      Seq("bash", "-lc", s"verilator ${flag} --help >/dev/null 2>&1").! == 0
    } catch { case _: Throwable => false }
  }

  val baseVFlags = Seq(
    "--threads",
    "4",
    "--trace-fst",
    "--trace-threads",
    "2",
    "--trace-underscore"
  )

  val vFlags         = baseVFlags
  protected val anno = Seq(
    WriteFstAnnotation,
    VerilatorBackendAnnotation,
    VerilatorFlags(vFlags),
    SimulatorDebugAnnotation
  )
}
