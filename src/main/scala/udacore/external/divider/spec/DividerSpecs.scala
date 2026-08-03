package udacore.external.divider.spec

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

object DividerSpecs {
  // === TOP-LEVEL WRAPPER CONTRACT ===
  val contDivider = spec {
    CONTRACT("Divider")
      .desc("Top-level divider wrapper providing unified interface and early-out optimizations")
      .has(intfDividerIO, funcEarlyOut, funcDecodeOperation, funcSelectDivider)
      .uses(contNonRestoringDividerCore, contRestoringDivider, paramDivider)
      .note("RISC-V M extension compliant divider with configurable core algorithms")
      .note("Provides same-cycle early-out for divide-by-zero, overflow, and magnitude comparison cases")
      .note("Eliminates duplicate operation decoding by pre-processing signals for cores")
      .note("Wrapper does not contain input/output buffers - build shell as needed")
      .build()
  }

  val contNonRestoringDividerCore = spec {
    RAW("NonRestoringDividerCore", "subcore")
      .desc("High-performance non-restoring division core with optional CLZ optimization")
      .has(intfDividerCoreIO, funcNonRestoringAlgorithm, funcClzOptimization, funcSignCorrection)
      .uses(paramDivider)
      .note("Optimized for high throughput and minimal cycle count")
      .note("Supports count-leading-zero (CLZ) optimization to skip unnecessary iterations")
      .note("Uses pre-decoded signals from wrapper to eliminate redundant decode logic")
      .note("Emits single-cycle valid pulse on completion")
      .build()
  }

  val contRestoringDivider = spec {
    RAW("RestoringDivider", "subcore")
      .desc("Area and power efficient restoring division core")
      .has(intfDividerCoreIO, funcRestoringAlgorithm, funcSignCorrection)
      .uses(paramDivider)
      .note("Optimized for minimal area and power consumption")
      .note("Suitable for MCU-oriented applications where area/power matter more than speed")
      .note("Uses classical restoring division algorithm with conditional subtraction")
      .note("Emits single-cycle valid pulse on completion")
      .build()
  }

  // === INTERFACE SPECIFICATIONS ===
  val intfDividerIO = spec {
    INTERFACE("DividerIO")
      .desc("External interface bundle for the top-level Divider wrapper")
      .note(
        "Not a ready/valid edge: start/done pulse protocol. The consuming vertex " +
        "(backend DividerUnit) translates its Decoupled edges into this IP protocol, " +
        "same as the Alu/BitAlu/Multiplier IPs."
      )
      .markdownTable(
        List("Signal", "Direction", "Type", "Description"),
        List(
          List("start", "Input", "Bool", "Pulse signal to start division operation"),
          List("kill", "Input", "Bool", "Synchronous cancellation of in-flight operation"),
          List("op", "Input", "DividerControl", "Operation type: DIV/DIVU/REM/REMU"),
          List("src1", "Input", "UInt(dataWidth.W)", "Dividend operand"),
          List("src2", "Input", "UInt(dataWidth.W)", "Divisor operand"),
          List("busy", "Output", "Bool", "Indicates divider is processing"),
          List("done", "Output", "Bool", "Single-cycle pulse when operation completes"),
          List("result", "Output", "UInt(dataWidth.W)", "Division or remainder result")
        )
      )
      .build()
  }

  val intfDividerCoreIO = spec {
    INTERFACE("DividerCoreIO")
      .desc("Internal interface between wrapper and divider cores with pre-decoded signals")
      .markdownTable(
        List("Signal", "Direction", "Type", "Description"),
        List(
          List("start", "Input", "Bool", "Start core operation (pre-filtered by wrapper)"),
          List("kill", "Input", "Bool", "Kill signal propagated from wrapper"),
          List("isSigned", "Input", "Bool", "Pre-decoded: operation uses signed arithmetic"),
          List("isRemainder", "Input", "Bool", "Pre-decoded: operation returns remainder"),
          List("dividendMag", "Input", "UInt(dataWidth.W)", "Pre-computed dividend magnitude"),
          List("divisorMag", "Input", "UInt(dataWidth.W)", "Pre-computed divisor magnitude"),
          List("dividendNeg", "Input", "Bool", "Pre-computed dividend sign bit"),
          List("divisorNeg", "Input", "Bool", "Pre-computed divisor sign bit"),
          List("busy", "Output", "Bool", "Core busy status"),
          List("done", "Output", "Bool", "Core completion pulse"),
          List("result", "Output", "UInt(dataWidth.W)", "Core computation result")
        )
      )
      .note("Eliminates duplicate decoding logic in cores by pre-processing operation details")
      .build()
  }

  // === FUNCTION SPECIFICATIONS ===
  val funcEarlyOut = spec {
    FUNCTION("EarlyOut")
      .desc("Same-cycle early-out detection and result computation")
      .note("Handles three early-out cases without invoking core logic:")
      .note("1. Divide by zero: returns all-ones quotient or original dividend remainder")
      .note("2. Signed overflow (MININT/-1): returns dividend quotient or zero remainder")  
      .note("3. Magnitude comparison (|dividend| < |divisor|): returns zero quotient or dividend remainder")
      .note("Provides true same-cycle response for performance-critical paths")
      .code("scala", """
        val divideByZero = (io.src2 === 0.U)
        val overflowCase = isSigned && (io.src1.asSInt === minIntSigned) && (io.src2.asSInt === (-1).S)
        val magnitudeLessThan = dividendMag < divisorMag
        val earlyOutCondition = divideByZero || overflowCase || magnitudeLessThan
      """)
      .build()
  }

  val funcDecodeOperation = spec {
    FUNCTION("DecodeOperation")
      .desc("Pre-decode operation signals to eliminate duplicate logic in cores")
      .note("Decodes DividerControl enum once in wrapper and passes boolean flags to cores")
      .note("Computes magnitude and sign information once for both early-out and core logic")
      .code("scala", """
        val isSigned = (io.op === DIV) || (io.op === REM)
        val isRemainder = (io.op === REM) || (io.op === REMU)
        val dividendMag = Mux(isSigned && dividendNeg, (0.U - io.src1), io.src1)
        val divisorMag = Mux(isSigned && divisorNeg, (0.U - io.src2), io.src2)
      """)
      .build()
  }

  val funcSelectDivider = spec {
    FUNCTION("SelectDivider")
      .desc("Instantiate appropriate divider core based on configuration parameters")
      .uses(paramDivider, contNonRestoringDividerCore, contRestoringDivider)
      .code("scala", """
        val core = if (useRestoring) Module(new RestoringDivider(param))
                   else Module(new NonRestoringDividerCore(param))
      """)
      .build()
  }

  val funcNonRestoringAlgorithm = spec {
    FUNCTION("NonRestoringAlgorithm")
      .desc("High-performance non-restoring division algorithm implementation")
      .note("Uses 4-state FSM: Idle -> Compute -> Correct -> Done")
      .note("Performs one iteration per cycle with conditional add/subtract")
      .note("Requires correction step for negative remainders")
      .note("Supports early termination via CLZ optimization")
      .code("scala", """
        // Core iteration step
        val subPath = (remainderShift >= 0.S)
        val addend = Mux(subPath, -divisorExt, divisorExt)
        val remNext = remainderShift + addend
        val qBit = (~remNext(dataWidth)).asUInt
        remainder := remNext
        quotient := (quotient << 1) | qBit
      """)
      .build()
  }

  val funcRestoringAlgorithm = spec {
    FUNCTION("RestoringAlgorithm")
      .desc("Area-efficient restoring division algorithm implementation")
      .note("Uses 3-state FSM: Idle -> Compute -> Finish")
      .note("Classical restoring algorithm with conditional subtraction and restoration")
      .note("Maintains combined P register [Remainder, Quotient] for area efficiency")
      .note("No correction step needed - algorithm naturally produces correct remainder sign")
      .code("scala", """
        // Core iteration step  
        val Pshift = P << 1
        val R = Pshift(2 * dataWidth - 1, dataWidth)
        val subRes = R - divisorMagReg
        val qBit = !subRes(dataWidth - 1) // subRes >= 0
        val Rnext = Mux(subRes(dataWidth - 1), R, subRes) // restore if negative
        P := Cat(Rnext, Pshift(dataWidth - 1, 1), qBit)
      """)
      .build()
  }

  val funcClzOptimization = spec {
    FUNCTION("ClzOptimization")
      .desc("Count-leading-zero optimization to reduce division cycles")
      .note("Available only in NonRestoringDividerCore when useClz parameter is enabled")
      .note("Skips leading zero bits in dividend to reduce iteration count")
      .note("Can significantly improve performance for small dividend values")
      .code("scala", """
        if (useClzParam) {
          val leadingZeros = PriorityEncoder(Reverse(io.dividendMag))
          val skipSteps = Mux(io.dividendMag.orR, leadingZeros, dataWidth.U)
          stepsRemaining := dataWidth.U - skipSteps
          dividendShiftReg := (io.dividendMag << skipSteps)(dataWidth - 1, 0)
        }
      """)
      .build()
  }

  val funcSignCorrection = spec {
    FUNCTION("SignCorrection")
      .desc("Final sign correction for signed division operations")
      .note("Applied in both core types during result computation")
      .note("Quotient sign: negative if dividend and divisor have different signs")
      .note("Remainder sign: follows dividend sign in RISC-V specification")
      .code("scala", """
        val qAdj = Mux(signedOpReg && (dividendNegReg ^ divisorNegReg), negU(qRaw), qRaw)
        val rAdj = Mux(signedOpReg && dividendNegReg, negU(rRaw), rRaw)
        outputReg := Mux(isRemainderReg, rAdj, qAdj)
      """)
      .build()
  }

  // === PARAMETER SPECIFICATIONS ===  
  val paramDivider = spec {
    PARAMETER("Divider")
      .desc("Configuration parameters for divider implementation selection and optimization")
      .markdownTable(
        List("Parameter", "Type", "Description", "Impact"),
        List(
          List("dataWidth", "Int", "Bit width of operands and result", "Determines register sizes and iteration count"),
          List("useRestoring", "Boolean", "Select restoring vs non-restoring algorithm", "Area/power vs performance trade-off"),
          List("useClz", "Boolean", "Enable count-leading-zero optimization", "Performance improvement for small dividends")
        )
      )
      .note("useClz only applies to NonRestoringDividerCore")
      .note("Typical configurations: (useRestoring=false, useClz=true) for high performance")
      .note("Typical configurations: (useRestoring=true, useClz=false) for minimal area")
      .build()
  }

  // === PROPERTIES ===
  val propRiscVCompliance = spec {
    PROPERTY("RiscVCompliance") 
      .desc("Divider behavior complies with RISC-V M extension specification")
      .note("Divide by zero: quotient = all ones, remainder = dividend")
      .note("Signed overflow (MININT / -1): quotient = dividend, remainder = 0")
      .note("Remainder sign follows dividend sign")
      .note("Division rounds toward zero")
      .build()
  }

  val propSingleCycleValid = spec {
    PROPERTY("SingleCycleValid")
      .desc("Done signal is asserted for exactly one cycle per operation")
      .note("Guarantees consistent interface behavior regardless of early-out or core completion")
      .note("Wrapper coordinates early-out and core done signals into unified response")
      .build()
  }

  val propKillBehavior = spec {
    PROPERTY("KillBehavior")
      .desc("Kill signal provides immediate operation cancellation")
      .note("Kill is synchronous and takes effect on the next clock edge")
      .note("Killed operations do not produce valid results")
      .note("Core returns to idle state after kill regardless of current operation state")
      .note(
        "External-IP capability only, NOT a core control mechanism: in UDACore no vertex " +
        "drives kill (the DividerUnit wrapper ties it inactive). Wrong-path results are " +
        "dropped by epoch qualification at the wrapper/PublishMux (funcIntegrateExternalDivider), " +
        "per the no-flush doctrine. The port exists so the IP stays reusable outside UDA."
      )
      .build()
  }
}