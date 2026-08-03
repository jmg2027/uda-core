package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.common.ControlSignal.{AluControl, DividerControl, MultiplierControl}
import udacore.external.alu.design.{Alu, AluParams}
import udacore.external.divider.design.{Divider, DividerParams}
import udacore.external.multiplier.design.{Multiplier, MultiplierParams}
import verif.ref

/** L1 SpecTests for the implemented external functional units (ADR-018 first
  * conforming examples - pinning the units against verif.ref).
  *
  * The start/done sampling protocol is copied verbatim from the main line's
  * VERIFIED DividerEngineTest (both cores + CLZ on/off passed there): poke
  * operands + start, check same-cycle done before stepping, else hold start
  * through the first edge and poll done, reading result on the done cycle, then
  * one idle step. Using the proven protocol keeps this test honest - the Gate
  * discipline forbids promoting a harness-timing artifact to a finding, at L1
  * as much as at the core level.
  */

private class AluWrap extends Module {
  val io = IO(new Bundle {
    val a      = Input(UInt(32.W))
    val b      = Input(UInt(32.W))
    val ctrl   = Input(UInt(AluControl.getWidth.W))
    val result = Output(UInt(32.W))
  })
  private val alu = Module(new Alu(AluParams(dataWidth = 32)))
  alu.io.srcA := io.a
  alu.io.srcB := io.b
  alu.io.ctrl := io.ctrl.asTypeOf(AluControl())
  io.result := alu.io.result
}

private class MulWrap(sliceWidth: Int) extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val op     = Input(UInt(MultiplierControl.getWidth.W))
    val a      = Input(UInt(32.W))
    val b      = Input(UInt(32.W))
    val busy   = Output(Bool())
    val done   = Output(Bool())
    val result = Output(UInt(32.W))
  })
  private val m = Module(new Multiplier(MultiplierParams(sliceWidth = sliceWidth)))
  m.io.start := io.start
  m.io.kill  := false.B  // ADR-017: kill is external-IP capability, tied inactive in UDACore
  m.io.op    := io.op.asTypeOf(MultiplierControl())
  m.io.src1  := io.a
  m.io.src2  := io.b
  io.busy   := m.io.busy
  io.done   := m.io.done
  io.result := m.io.result
}

private class DivWrap(useRestoring: Boolean, useClz: Boolean) extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val op     = Input(UInt(DividerControl.getWidth.W))
    val a      = Input(UInt(32.W))
    val b      = Input(UInt(32.W))
    val busy   = Output(Bool())
    val done   = Output(Bool())
    val result = Output(UInt(32.W))
  })
  private val d = Module(new Divider(DividerParams(dataWidth = 32, useRestoring = useRestoring, useClz = useClz)))
  d.io.start := io.start
  d.io.kill  := false.B
  d.io.op    := io.op.asTypeOf(DividerControl())
  d.io.src1  := io.a
  d.io.src2  := io.b
  io.busy   := d.io.busy
  io.done   := d.io.done
  io.result := d.io.result
}

object UnitSpecTests {
  private def u32(i: Int): Long = i.toLong & 0xFFFFFFFFL

  /** main-line DividerEngineTest sampling, factored over the shared start/done/result
    * IO surface. `set` pokes start, `done`/`result` peek, `step` advances the clock.
    * Returns (done, result, cycles). */
  private def startDone(setStart: Boolean => Unit, done: () => Boolean, result: () => Long,
                        step: () => Unit, guardMax: Int): (Boolean, Long, Int) = {
    setStart(true)
    var got = 0L
    var cycles = 0
    if (done()) {                       // same-cycle early-out / fast-hit
      got = result()
      step(); setStart(false)
    } else {
      step(); setStart(false); cycles = 1
      var guard = 0
      while (!done() && guard < guardMax) { step(); cycles += 1; guard += 1 }
      got = result()
      step()                            // done -> idle
    }
    (done(), got, cycles)
  }

  /** Alu: combinational op sweep against verif.ref.Alu. */
  val aluCompute = new SpecTest("alu.compute", Seq("funcComputeAlu", "intfAluIO")) {
    private val ops: Seq[(String, AluControl.Type)] = Seq(
      "add" -> AluControl.ADD, "sub" -> AluControl.SUB, "and" -> AluControl.AND,
      "or" -> AluControl.OR, "xor" -> AluControl.XOR, "sll" -> AluControl.SLL,
      "srl" -> AluControl.SRL, "sra" -> AluControl.SRA, "slt" -> AluControl.SLT,
      "sltu" -> AluControl.SLTU)
    private val vectors = Seq(
      (0x12345678, 0x0000000C), (-1, 1), (Int.MinValue, -1), (0, 0),
      (0x7FFFFFFF, 0x80000000), (0xDEADBEEF, 5), (5, 0xDEADBEEF))
    def run(): Seq[TCheck] = sim(new AluWrap) { dut =>
      for {
        (opName, opEnum) <- ops
        (a, b) <- vectors
      } yield {
        dut.io.a.poke(u32(a).U)
        dut.io.b.poke(u32(b).U)
        dut.io.ctrl.poke(opEnum.litValue.U)
        dut.clock.step()
        val got = dut.io.result.peek().litValue.toLong & 0xFFFFFFFFL
        val exp = u32(ref.Alu.exec(opName, a, b))
        TCheck(got == exp, f"$opName(0x${u32(a)}%08x,0x${u32(b)}%08x)", f"got=0x$got%08x exp=0x$exp%08x")
      }
    }
  }

  // Both verified multiplier configs are tested here. (sliceWidth=32, slicesPerCycle=1) is
  // the single-cycle production point ProductSpecs ships and MultiplierUnit selects; (16,1)
  // is the multi-slice multi-cycle path. Both run the corrected rvv_coprocessor core (main
  // line): the slice product is exposed untruncated (pFull) and sign-extended before the
  // carry-propagate fold, and the upper-half fast-out requires BOTH halves zero. This fixes
  // the former MULH/MULHSU high-word bug that only showed at multi-slice configs, so the
  // (16,1) MULH/MULHSU sweep now passes alongside (32,1). UDA-M1 is resolved.

  /** Multiplier: start/done protocol + result sweep against verif.ref.Mul at the verified
    * config, incl. the early-out corners (funcEarlyOut: 0 and 1 operands). */
  private def mulTest(tag: String, sliceWidth: Int) =
    new SpecTest(s"multiplier.$tag", Seq("funcDriveMultiplierCore", "funcExposeMultiplierStatus", "funcEarlyOut", "intfMultiplierIO")) {
      private val ops: Seq[(String, MultiplierControl.Type)] = Seq(
        "mul" -> MultiplierControl.MUL, "mulh" -> MultiplierControl.MULH,
        "mulhu" -> MultiplierControl.MULHU, "mulhsu" -> MultiplierControl.MULHSU)
      private val vectors = Seq(
        (7, 6), (-3, 5), (0x12345678, 0x9ABCDEF0), (Int.MinValue, -1),
        (0, 0x1234), (0x1234, 0), (1, 0xBEEF), (0xBEEF, 1), (0xFFFF, 0x10001))
      def run(): Seq[TCheck] = sim(new MulWrap(sliceWidth)) { dut =>
        dut.io.start.poke(false.B); dut.clock.step(2)
        for {
          (opName, opEnum) <- ops
          (a, b) <- vectors
        } yield {
          dut.io.op.poke(opEnum.litValue.U)
          dut.io.a.poke(u32(a).U); dut.io.b.poke(u32(b).U)
          val (done, got, cycles) = startDone(
            b => dut.io.start.poke(b.B),
            () => dut.io.done.peek().litToBoolean,
            () => dut.io.result.peek().litValue.toLong & 0xFFFFFFFFL,
            () => dut.clock.step(), guardMax = 40)
          val exp = u32(ref.Mul.exec(opName, a, b))
          TCheck(got == exp, f"$opName(0x${u32(a)}%08x,0x${u32(b)}%08x)",
                 f"done=$done cyc=$cycles got=0x$got%08x exp=0x$exp%08x")
        }
      }
    }
  val mulCsa32 = mulTest("csa32", 32)
  val mulCsa16 = mulTest("csa16", 16)

  /** Divider: RISC-V M corner semantics (div-by-zero, overflow) + normals against
    * verif.ref.Mul, both cores; sign correction and early-outs ride the same sweep. */
  private def divTest(tag: String, restoring: Boolean, clz: Boolean) =
    new SpecTest(s"divider.$tag",
        Seq("funcSelectDivider", "funcSignCorrection", "funcEarlyOut",
            if (restoring) "funcRestoringAlgorithm" else "funcNonRestoringAlgorithm",
            "intfDividerIO", "propRiscVCompliance")) {
      private val ops: Seq[(String, DividerControl.Type)] = Seq(
        "div" -> DividerControl.DIV, "divu" -> DividerControl.DIVU,
        "rem" -> DividerControl.REM, "remu" -> DividerControl.REMU)
      private val vectors = Seq(
        (20, 3), (-20, 3), (20, -3), (-20, -3),
        (7, 0), (-7, 0),                    // div-by-zero: q=-1, r=dividend
        (Int.MinValue, -1),                 // overflow: q=MinValue, r=0
        (0, 5), (1, 1), (0x12345678, 0x100))
      def run(): Seq[TCheck] = sim(new DivWrap(restoring, clz)) { dut =>
        dut.io.start.poke(false.B); dut.clock.step(2)
        for {
          (opName, opEnum) <- ops
          (a, b) <- vectors
        } yield {
          dut.io.op.poke(opEnum.litValue.U)
          dut.io.a.poke(u32(a).U); dut.io.b.poke(u32(b).U)
          val (done, got, cycles) = startDone(
            b => dut.io.start.poke(b.B),
            () => dut.io.done.peek().litToBoolean,
            () => dut.io.result.peek().litValue.toLong & 0xFFFFFFFFL,
            () => dut.clock.step(), guardMax = 80)
          val exp = u32(ref.Mul.exec(opName, a, b))
          TCheck(got == exp, f"$opName(0x${u32(a)}%08x,0x${u32(b)}%08x)",
                 f"done=$done cyc=$cycles got=0x$got%08x exp=0x$exp%08x")
        }
      }
    }
  val divNrClz     = divTest("nrclz", restoring = false, clz = true)
  val divRestoring = divTest("restoring", restoring = true, clz = false)

  val all: Seq[SpecTest] = Seq(aluCompute, mulCsa32, mulCsa16, divNrClz, divRestoring)
}
