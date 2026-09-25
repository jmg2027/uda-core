package udacore.external.divider.design

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.common.ControlSignal.DividerControl
import udacore.external.divider.spec.DividerSpecs._

// Verified RTL ported from the main line (klase32.external.divider.design.Divider).
// The rebuild's refactored divider reintroduced the F-10/F-11 remainder-width bug
// (restoring compare in dataWidth bits broke for divisors >= 2^(dataWidth-1)); this
// is main's fixed, verified implementation, brought in verbatim (ADR-018 owner
// directive). Sub-core CONTRACTs are RAW subcore per the one-CONTRACT-per-vertex ruling.

// Internal core interface, pre-decoded signals
@LocalSpec(intfDividerCoreIO)
class DividerCoreIO(dataWidth: Int) extends Bundle {
  val start = Input(Bool())
  val kill = Input(Bool())
  val isSigned = Input(Bool())
  val isRemainder = Input(Bool())
  val dividendMag = Input(UInt(dataWidth.W))
  val divisorMag = Input(UInt(dataWidth.W))
  val dividendNeg = Input(Bool())
  val divisorNeg = Input(Bool())
  val busy = Output(Bool())
  val done = Output(Bool())
  val result = Output(UInt(dataWidth.W))
}

// Public divider interface (matches spec)
@LocalSpec(intfDividerIO)
class DividerIO(dataWidth: Int) extends Bundle {
  val start = Input(Bool())
  val kill = Input(Bool())
  val op = Input(DividerControl())
  val src1 = Input(UInt(dataWidth.W))
  val src2 = Input(UInt(dataWidth.W))
  val busy = Output(Bool())
  val done = Output(Bool())
  val result = Output(UInt(dataWidth.W))
}

// Marker trait exposing the common core io type
trait DividerCoreLike { val io: DividerCoreIO }

@LocalSpec(paramDivider)
case class DividerParams(
    dataWidth: Int,
    useRestoring: Boolean,
    useClz: Boolean
)

// Top-level FU wrapper with same-cycle early-out.
@LocalSpec(contDivider)
class Divider(param: DividerParams) extends Module {
  val dataWidth = param.dataWidth
  val useRestoring = param.useRestoring
  val useClz = param.useClz

  val io = IO(new DividerIO(dataWidth))

  // Default: non-restoring core.
  @LocalSpec(funcSelectDivider)
  val core =
    if (useRestoring) Module(new RestoringDivider(param))
    else Module(new NonRestoringDividerCore(param))

  import udacore.common.ControlSignal.DividerControl._

  // F-12: MININT must be a literal - `1.S(w.W) << (w-1)` widens in Chisel and
  // yields +2^(w-1), so the overflow early-out (and its compliance assert)
  // compared the wrong constant and never fired.
  @LocalSpec(funcDecodeOperation)
  val decodeOperation = {
    val isSigned = (io.op === DIV) || (io.op === REM)
    val isRemainder = (io.op === REM) || (io.op === REMU)
    val minIntSigned = (-(BigInt(1) << (dataWidth - 1))).S(dataWidth.W)
    (isSigned, isRemainder, minIntSigned)
  }
  val (isSigned, isRemainder, minIntSigned) = decodeOperation

  // Operand magnitudes for signed ops.
  val dividendNeg = io.src1(dataWidth - 1)
  val divisorNeg = io.src2(dataWidth - 1)
  val dividendMag = Mux(isSigned && dividendNeg, (0.U - io.src1), io.src1)
  val divisorMag = Mux(isSigned && divisorNeg, (0.U - io.src2), io.src2)

  val divideByZero = (io.src2 === 0.U)
  val overflowCase = isSigned && (io.src1.asSInt === minIntSigned) && (io.src2.asSInt === (-1).S)
  val magnitudeLessThan = dividendMag < divisorMag

  // Extensible (condition, result) early-out pairs.
  @LocalSpec(funcEarlyOut)
  val earlyOutTable = Seq(
    (divideByZero,      Mux(isRemainder, io.src1, Fill(dataWidth, 1.U))),
    (overflowCase,      Mux(io.op === DIV, io.src1, 0.U)),
    (magnitudeLessThan, Mux(isRemainder, io.src1, 0.U))
  )

  val earlyOutCondition = earlyOutTable.map(_._1).reduce(_ || _)
  val earlyResult = PriorityMux(earlyOutTable)

  val coreCanAccept = !core.io.busy
  val killActive = io.kill
  val sameCycleEarlyOut = io.start && coreCanAccept && earlyOutCondition && !killActive

  // Core inputs carry the wrapper's pre-decoded signals (no duplicate decode).
  core.io.start := (io.start && coreCanAccept && !earlyOutCondition && !killActive)
  core.io.kill := killActive
  core.io.isSigned := isSigned
  core.io.isRemainder := isRemainder
  core.io.dividendMag := dividendMag
  core.io.divisorMag := divisorMag
  core.io.dividendNeg := dividendNeg
  core.io.divisorNeg := divisorNeg

  io.busy := (core.io.busy || sameCycleEarlyOut) && !killActive
  io.done := !killActive && (sameCycleEarlyOut || core.io.done)
  io.result := Mux(sameCycleEarlyOut, earlyResult, core.io.result)

  // RISC-V M extension compliance checks (reuse the wrapper decode above).
  @LocalSpec(propRiscVCompliance)
  val riscVCompliance = {
    when(io.done && divideByZero) {
      when(isRemainder) {
        assert(io.result === io.src1, "Divide by zero: remainder should equal dividend")
      }.otherwise {
        assert(io.result === Fill(dataWidth, 1.U), "Divide by zero: quotient should be all ones")
      }
    }

    when(io.done && overflowCase) {
      when(io.op === DividerControl.DIV) {
        assert(io.result === io.src1, "Signed overflow: quotient should equal dividend")
      }.otherwise {
        assert(io.result === 0.U, "Signed overflow: remainder should be zero")
      }
    }

    cover(io.start && !earlyOutCondition, "Normal division operation started")
    cover(io.done && !sameCycleEarlyOut, "Core division completed")
  }

  // Done must be a single-cycle pulse, one per operation.
  @LocalSpec(propSingleCycleValid)
  val singleCycleValid = {
    val donePrev = RegNext(io.done, false.B)

    when(donePrev) {
      assert(!io.done || io.start, "Done signal should not be held for multiple cycles")
    }

    when(io.done) {
      assert(sameCycleEarlyOut || core.io.done, "Done must correspond to either early-out or core completion")
    }

    cover(io.done, "Operation completed")
  }

  // Kill is an immediate synchronous cancellation.
  @LocalSpec(propKillBehavior)
  val killBehavior = {
    val killPrev = RegNext(io.kill, false.B)

    when(io.kill) {
      assert(!io.busy, "Kill should immediately clear busy signal")
      assert(!io.done, "Kill should prevent done signal assertion")
    }

    when(killPrev && !io.kill) {
      assert(!core.io.busy, "Core should return to idle after kill")
    }

    cover(io.kill && core.io.busy, "Operation killed while core was busy")
  }
}

// Leading-zero skip shared by both cores (funcClzOptimization): pre-shift the
// dividend so its top bit is significant, and return the remaining compute steps.
// All-zero is clamped to dataWidth-1 so at least one step remains.
object DividerClz {
  def skip(mag: UInt, dataWidth: Int): (UInt, UInt) = {
    val leadingZeros = PriorityEncoder(Reverse(mag))
    val skipSteps = Mux(mag.orR, leadingZeros, (dataWidth - 1).U)
    val steps = (dataWidth.U - skipSteps)(log2Ceil(dataWidth + 1) - 1, 0)
    val shifted = (mag << skipSteps)(dataWidth - 1, 0)
    (steps, shifted)
  }
}

// Non-restoring divider core (funcNonRestoringAlgorithm, optional CLZ skip). Emits
// a 1-cycle valid pulse in sFinish with combinational sign correction.
@LocalSpec(contNonRestoringDividerCore)
class NonRestoringDividerCore(param: DividerParams) extends Module with DividerCoreLike {

  val useClzParam = param.useClz
  val dataWidth = param.dataWidth

  val io = IO(new DividerCoreIO(dataWidth))

  @inline def negU(x: UInt): UInt = (0.U(dataWidth.W) - x)

  val sIdle :: sCompute :: sFinish :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // Operation info and signs, pre-decoded by the wrapper.
  val signedOpReg = Reg(Bool())
  val isRemainderReg = Reg(Bool())
  val dividendNegReg = Reg(Bool())
  val divisorNegReg = Reg(Bool())
  val divisorMagReg = Reg(UInt(dataWidth.W))

  // F-10: the partial remainder leaves [0, divisor) during the iteration, so it
  // needs dataWidth+2 signed bits; a narrower reg loses the sign for unsigned
  // divisors >= 2^(dataWidth-1).
  val remainder = Reg(SInt((dataWidth + 2).W))
  val quotientDividend = Reg(UInt(dataWidth.W))
  val stepsRemaining = Reg(UInt(log2Ceil(dataWidth + 1).W))

  val divisorExt = Cat(0.U(2.W), divisorMagReg).asSInt

  // Combinational sign-corrected output (funcSignCorrection), valid in sFinish.
  @LocalSpec(funcSignCorrection)
  val signCorrection = {
    val remCorr = Mux(remainder < 0.S, remainder + divisorExt, remainder)
    val rRaw = remCorr.asUInt(dataWidth - 1, 0)
    val qRaw = quotientDividend
    val qAdj = Mux(signedOpReg && (dividendNegReg ^ divisorNegReg), negU(qRaw), qRaw)
    val rAdj = Mux(signedOpReg && dividendNegReg, negU(rRaw), rRaw)
    (qAdj, rAdj)
  }
  val (qAdj, rAdj) = signCorrection

  io.busy := (state =/= sIdle)
  io.done := (state === sFinish)
  io.result := Mux(isRemainderReg, rAdj, qAdj)

  when(io.kill) {
    state := sIdle
  }.otherwise {
    switch(state) {
      is(sIdle) {
        when(io.start) {
          assert(io.dividendMag.orR, "core started with zero dividend magnitude")

          signedOpReg := io.isSigned
          isRemainderReg := io.isRemainder
          dividendNegReg := io.dividendNeg
          divisorNegReg := io.divisorNeg
          divisorMagReg := io.divisorMag

          remainder := 0.S
          if (useClzParam) {
            val (steps, shifted) = DividerClz.skip(io.dividendMag, dataWidth)
            stepsRemaining := steps
            quotientDividend := shifted
          } else {
            stepsRemaining := dataWidth.U
            quotientDividend := io.dividendMag
          }

          state := sCompute
        }
      }

      is(sCompute) {
        val nextBit = quotientDividend(dataWidth - 1)
        // F-10: 2*rem+bit fits exactly in dataWidth+2 signed bits.
        val remainderShift = Cat(remainder.asUInt(dataWidth, 0), nextBit).asSInt
        val subPath = (remainderShift >= 0.S)
        val remNext = remainderShift + Mux(subPath, -divisorExt, divisorExt)
        val qBit = (~remNext(dataWidth + 1)).asUInt

        remainder := remNext
        quotientDividend := Cat(quotientDividend(dataWidth - 2, 0), qBit)

        when(stepsRemaining === 1.U) { state := sFinish }
        stepsRemaining := stepsRemaining - 1.U
      }

      is(sFinish) {
        state := sIdle // 1-cycle valid pulse; output is combinational above
      }
    }
  }

  @LocalSpec(propSingleCycleValid)
  val nonRestoringValidPulse = {
    val statePrev = RegNext(state, sIdle)

    when(io.done) {
      assert(state === sFinish, "Done should only be asserted in sFinish state")
    }
    when(statePrev === sFinish) {
      assert(state === sIdle, "Should return to idle after finish state")
    }
    when(RegNext(io.kill, false.B)) {
      assert(state === sIdle, "Kill should force return to idle state")
    }
    when(state === sCompute && statePrev === sCompute) {
      assert(stepsRemaining === RegNext(stepsRemaining) - 1.U, "Steps should decrease by 1 each cycle")
    }

    cover(state === sFinish, "Non-restoring division completed")
    cover(state === sCompute && stepsRemaining === 1.U, "Final compute iteration")
  }
}

// Restoring divider core (funcRestoringAlgorithm). Emits a 1-cycle valid pulse on finish.
@LocalSpec(contRestoringDivider)
class RestoringDivider(param: DividerParams) extends Module with DividerCoreLike {
  val dataWidth = param.dataWidth
  val useClzParam = param.useClz

  val io = IO(new DividerCoreIO(dataWidth))

  val sIdle :: sCompute :: sFinish :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // Algorithm registers; operation info pre-decoded by wrapper.
  val dividendSign = Reg(Bool())
  val divisorSign = Reg(Bool())
  // F-11: corrections apply to signed ops only; raw operand MSBs are meaningless for divu/remu.
  val signedOp = Reg(Bool())
  val isRemainder = Reg(Bool())
  val divisorMagReg = Reg(UInt(dataWidth.W))
  // The compare/subtract of 2R+bit (up to 2^(dataWidth+1)) uses dataWidth+1 bits with an
  // explicit borrow - the old packed-P form compared in dataWidth bits and broke for
  // divisors >= 2^(dataWidth-1).
  val Rreg = Reg(UInt(dataWidth.W))
  val Qreg = Reg(UInt(dataWidth.W))
  val cnt = Reg(UInt(log2Ceil(dataWidth + 1).W))

  when(io.kill) {
    state := sIdle
  }.otherwise {
    switch(state) {
      is(sIdle) {
        when(io.start) {
          assert(io.dividendMag.orR, "core started with zero dividend magnitude")

          dividendSign := io.dividendNeg
          divisorSign := io.divisorNeg
          signedOp := io.isSigned
          isRemainder := io.isRemainder

          divisorMagReg := io.divisorMag
          Rreg := 0.U
          if (useClzParam) {
            val (steps, shifted) = DividerClz.skip(io.dividendMag, dataWidth)
            cnt := steps
            Qreg := shifted
          } else {
            cnt := dataWidth.U
            Qreg := io.dividendMag
          }
          state := sCompute
        }
      }

      is(sCompute) {
        val Rsh = Cat(Rreg, Qreg(dataWidth - 1))            // 2R + next dividend bit, dataWidth+1 bits
        val divisorExt = Cat(0.U(1.W), divisorMagReg)
        val qBit = Rsh >= divisorExt
        val Rnext = Mux(qBit, Rsh - divisorExt, Rsh)        // restore if it would go negative
        Rreg := Rnext(dataWidth - 1, 0)                     // invariant: Rnext < divisor < 2^dataWidth
        Qreg := Cat(Qreg(dataWidth - 2, 0), qBit)
        cnt := cnt - 1.U
        when(cnt === 1.U) { state := sFinish }
      }

      is(sFinish) {
        state := sIdle // 1-cycle valid pulse
      }
    }
  }

  @LocalSpec(funcSignCorrection)
  val signCorrectionOutput = {
    val qAbs = Qreg
    val rAbs = Rreg
    val qFin = Mux(signedOp && (dividendSign ^ divisorSign), (0.U - qAbs), qAbs)
    val rFin = Mux(signedOp && dividendSign, (0.U - rAbs), rAbs)
    (qFin, rFin)
  }
  val (qFin, rFin) = signCorrectionOutput

  io.busy := (state =/= sIdle)
  io.done := (state === sFinish)
  io.result := Mux(isRemainder, rFin, qFin)

  @LocalSpec(propSingleCycleValid)
  val coreValidPulse = {
    val statePrev = RegNext(state, sIdle)

    when(io.done) {
      assert(state === sFinish, "Done should only be asserted in sFinish state")
    }

    when(statePrev === sFinish) {
      assert(state === sIdle, "Should return to idle after finish state")
    }

    when(RegNext(io.kill, false.B)) {
      assert(state === sIdle, "Kill should force return to idle state")
    }

    cover(state === sFinish, "Restoring division completed")
  }
}
