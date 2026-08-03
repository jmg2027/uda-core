package udacore.external.alu.spec

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

object AluSpecs {
  val contAlu = spec {
    CONTRACT("Alu")
      .desc("External arithmetic logic unit compute block.")
      .has(intfAluIO)
      .build()
  }

  val intfAluIO = spec {
    INTERFACE("AluIO")
      .desc("Operand/control interface for the external ALU core.")
      .markdownTable(
        List("name", "description", "direction", "type"),
        List(
          List("srcA", "First operand", "Input", "UInt(dataWidth.W)"),
          List("srcB", "Second operand", "Input", "UInt(dataWidth.W)"),
          List(
            "ctrl",
            "Operation selector",
            "Input",
            "AluControl(): ADD/SUB/logic/compare"
          ),
          List("result", "ALU result", "Output", "UInt(dataWidth.W)")
        )
      )
      .note("Interface does not carry metadata; wrapper modules must manage ready/valid handshakes.")
      .build()
  }

  val paramAlu = spec {
    PARAMETER("Alu")
      .desc("Configuration for the external ALU core.")
      .build()
  }

  val funcComputeAlu = spec {
    FUNCTION("computeAlu")
      .desc("Perform ALU operations based on inputs and control signals.")
      .uses(intfAluIO)
      .build()
  }
}
