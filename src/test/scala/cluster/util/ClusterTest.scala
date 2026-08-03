package cluster.util

import chiseltest._
import java.io.File
import org.scalatest.freespec.AnyFreeSpec

class ClusterTest extends AnyFreeSpec with ChiselScalatestTester {
  val useVerilator = Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)
  val useTreadle   = Seq(WriteVcdAnnotation, TreadleBackendAnnotation)

  def getTestDir(testName: String): String = {
    val testDir = new File(s"test_run_dir/$testName")
    testDir.mkdirs()
    testDir.getAbsolutePath
  }
}
