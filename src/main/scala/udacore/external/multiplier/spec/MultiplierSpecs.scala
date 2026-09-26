package udacore.external.multiplier.spec

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

object MultiplierSpecs {
  val contMultiplier = spec {
    CONTRACT("Multiplier")
      .desc("""
        IP level multiplier
        Supports configurable slice widths and multi-cycle execution
        Supports same-cycle early-out for overflow cases
        RISC-V M extension compliant
        """.stripMargin)
      .has(intfMultiplierIO)
      .note(
        "When start signal is asserted, the multiplier begins operation with current src1/src2/op"
      )
      .note(
        "Busy remains asserted while the iterative core processes the request. The shell must maintain stable src1, src2, and op inputs while busy is asserted."
      )
      .note("Once the operation is complete, the done signal will be asserted")
      .note("Only the result value is valid with done signal is asserted")
      .note(
        "If early out condition is met, the multiplier will return the early result not starting multiplier core logic"
      )
      .note(
        "To use this multiplier, build your own shell by your system requirements"
      )
      .note(
        "Multiplier itself does not contain input or output buffers; an integrating shell must latch operands as needed"
      )
      .build()
  }

  val intfMultiplierIO = spec {
    INTERFACE("MultiplierIO")
      .desc("""
        Input/Output interface bundle for the Multiplier
        """.stripMargin)
      .markdownTable(
        List("name", "description", "direction", "type"),
        List(
          List(
            "start",
            "Pulse signal indicates start of operation",
            "Input",
            "Bool"
          ),
          List(
            "kill",
            "Synchronous cancellation request (external-IP capability; in UDACore tied inactive - a killed operation's result is discarded by the MultiplierUnit wrapper per the RecoveryEvent younger-than rule, no-flush doctrine)",
            "Input",
            "Bool"
          ),
          List(
            "op",
            "Operation to be performed",
            "Input",
            "MultiplierControl(): MUL/MULH/MULHSU/MULHU"
          ),
          List("src1", "First source operand", "Input", "UInt(dataWidth.W)"),
          List("src2", "Second source operand", "Input", "UInt(dataWidth.W)"),
          List("busy", "Indicates if the multiplier is busy", "Output", "Bool"),
          List(
            "done",
            "Pulse signal indicates end of operation",
            "Output",
            "Bool"
          ),
          List("result", "Multiplication result", "Output", "UInt(dataWidth.W)")
        )
      )
      .build()
  }

  val paramMultiplier = spec {
    PARAMETER("Multiplier")
      .desc("""
        Configuration parameters for the Multiplier
        """.stripMargin)
      .markdownTable(
        List("name", "description", "type"),
        List(
          List("dataWidth", "Width of the data path", "Int"),
          List("sliceWidth", "Width of each multiplier slice", "Int"),
          List(
            "slicesPerCycle",
            "Number of partial products computed per cycle",
            "Int"
          ),
          List(
            "accumulationMethod",
            "Selects carry-save or iterative accumulation strategy",
            "AccumMethod"
          ),
          List("fusion", "Enables fusion optimizations in the core", "Boolean"),
          List(
            "earlyOutZero",
            "Enable same-cycle zero result early-out",
            "Boolean"
          ),
          List(
            "earlyOutOne",
            "Enable same-cycle one result early-out",
            "Boolean"
          ),
          List(
            "earlyOutUpper16",
            "Enable optimized early-out when high halves are zero",
            "Boolean"
          ),
          List(
            "vectorMode",
            "Enable vector-mode partial-product fusion",
            "Boolean"
          ),
          List(
            "useDSP",
            "Map partial products onto DSP blocks when available",
            "Boolean"
          )
        )
      )
      .build()
  }

  val funcDriveMultiplierCore = spec {
    FUNCTION("driveMultiplierCore")
      .desc("""
        Forward the externally managed operands directly into the iterative multiplier
        core and only launch work when the core is idle. The shell integrating this
        IP must guarantee operand stability for the duration of the operation.
        """.stripMargin)
      .uses(intfMultiplierIO)
      .build()
  }

  val funcExposeMultiplierStatus = spec {
    FUNCTION("exposeMultiplierStatus")
      .desc("""
        Surface the multiplier core's busy, done, and result signals directly on the
        public interface without inserting additional buffering or state.
        """.stripMargin)
      .uses(intfMultiplierIO)
      .build()
  }
}
