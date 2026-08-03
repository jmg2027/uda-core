package udacore.external.bitalu.spec

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

object BitAluSpecs {
  val contBitAlu = spec {
    CONTRACT("BitAlu")
      .desc("External bit-manipulation compute block for RV32B operations.")
      .has(intfBitAluIO)
      .build()
  }

  val intfBitAluIO = spec {
    INTERFACE("BitAluIO")
      .desc("Bit manipulation operation interface for the external core.")
      .markdownTable(
        List("name", "description", "direction", "type"),
        List(
          List("start", "Pulse asserting the beginning of an operation", "Input", "Bool"),
          List("srcA", "First operand", "Input", "UInt(dataWidth.W)"),
          List("srcB", "Second operand", "Input", "UInt(dataWidth.W)"),
          List(
            "ctrl",
            "Bit manipulation opcode",
            "Input",
            "BitAluControl(): RV32B/Zba/Zbb/Zbs/Zbc"
          ),
          List("result", "Operation result", "Output", "UInt(dataWidth.W)"),
          List("done", "Pulse indicating completion", "Output", "Bool")
        )
      )
      .note("Wrapper modules must translate ready/valid handshake into start/done pulses.")
      .build()
  }

  val paramBitAlu = spec {
    PARAMETER("BitAlu")
      .desc("Configuration for enabling RV32B extension subsets.")
      .markdownTable(
        List("name", "type", "default", "description"),
        List(
          List("dataWidth", "Int", "32", "Operand width in bits"),
          List("enableZba", "Boolean", "true", "Enable shift-and-add instructions"),
          List("enableZbb", "Boolean", "true", "Enable base bit-manipulation ops"),
          List("enableZbs", "Boolean", "true", "Enable single-bit set/clear ops"),
          List("enableZbc", "Boolean", "true", "Enable carry-less multiply ops")
        )
      )
      .build()
  }

  val funcExecuteBitAlu = spec {
    FUNCTION("executeBitAlu")
      .desc("Compute bit manipulation results for the requested operation.")
      .uses(intfBitAluIO)
      .build()
  }
}
