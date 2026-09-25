package verif.spectest

import chisel3._
import chisel3.util.DecoupledIO
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.modules.{AddressGenerationUnit, AluUnit, DividerUnit, MultiplierUnit}
import udacore.backend.design.shared._
import udacore.common.ControlSignal.{AluControl, DividerControl, MultiplierControl}
import verif.ref

/** L1 SpecTests for the ALU / MUL / DIV / AGU execution wrappers (ADR-018; spec 242feaf +
  * ADR-019C E-2).
  *
  * Every wrapper: request ready (canAccept) depends only on registered state and the drain
  * of its held result, never on the presented request; the held result is stable under
  * PublishMux backpressure; a result whose uop a RecoveryEvent kills is never offered again,
  * including the eventual result of an uncancelable MUL/DIV core; older results survive.
  */
object ExecUnitSpecTests {

  val p = BackendParams()
  val D = p.robDepth
  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)
  def u32(i: Long): Long = i & 0xFFFFFFFFL

  case class U(s: Int, op: Int = 0, src1: Long = 0, src2: Long = 0, imm: Long = 0, pc: Long = 0, prd: Int = 40,
      hasDest: Boolean = true)
  case class Obs(reqReady: Boolean, fire: Boolean, outValid: Boolean, out: Map[String, Long])

  class Drv(clock: Clock, req: DecoupledIO[IssuedUop], outV: Bool, outR: Bool, ev: RecoveryEvent,
      peekOut: () => Map[String, Long]) {
    var outReady = true
    def cycle(u: Option[U] = None, event: Option[(UInt, Int)] = None, step: Boolean = true): Obs = {
      req.valid.poke(u.nonEmpty.B)
      u.foreach { x =>
        req.bits.robTag.wrap.poke(tagOf(x.s)._1.B); req.bits.robTag.idx.poke(tagOf(x.s)._2.U)
        req.bits.op.poke(x.op.U); req.bits.src1.poke(u32(x.src1).U); req.bits.src2.poke(u32(x.src2).U)
        req.bits.imm.poke(u32(x.imm).U); req.bits.pc.poke(u32(x.pc).U); req.bits.prd.poke(x.prd.U)
        req.bits.hasDest.poke(x.hasDest.B)
      }
      outR.poke(outReady.B)
      ev.valid.poke(event.nonEmpty.B)
      event.foreach { case (k, s) => ev.kind.poke(k); ev.robTag.wrap.poke(tagOf(s)._1.B); ev.robTag.idx.poke(tagOf(s)._2.U) }
      val rr = req.ready.peek().litToBoolean
      val o = Obs(rr, rr && u.nonEmpty, outV.peek().litToBoolean, peekOut())
      if (step) clock.step()
      o
    }
    /** Run until the result is offered (max n cycles); returns it. */
    def until(n: Int): Option[Obs] = (0 until n).iterator.map(_ => cycle()).find(_.outValid)
  }

  def fuOut(o: FuResult): () => Map[String, Long] = () => Map(
    "s" -> o.robTag.idx.peek().litValue.toLong, "wrap" -> (if (o.robTag.wrap.peek().litToBoolean) 1L else 0L),
    "data" -> o.data.peek().litValue.toLong, "prd" -> o.prd.peek().litValue.toLong,
    "wen" -> (if (o.wen.peek().litToBoolean) 1L else 0L), "exc" -> (if (o.exception.valid.peek().litToBoolean) 1L else 0L))

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  val BM = RecoveryKind.BranchMispredict
  val AR = RecoveryKind.ArchRedirect
  def code(e: chisel3.EnumType): Int = e.litValue.toInt

  /** The request-independence sweep: in the current state, presenting any request (or none)
    * must not change request ready. */
  def readyIndependent(d: Drv, reqs: Seq[U]): Boolean = {
    val base = d.cycle(None, step = false).reqReady
    reqs.forall(u => d.cycle(Some(u), step = false).reqReady == base)
  }

  /** Shared holder behavior: stability, drain-and-accept, kill of a held result, older survival. */
  def holderChecks(d: Drv, mk: Int => U, latency: Int, unit: String): Seq[TCheck] = {
    val sweep = Seq(mk(1), mk(2).copy(src1 = 123), mk(3).copy(prd = 7))
    val emptyInd = readyIndependent(d, sweep)
    val emptyReady = d.cycle(None, step = false).reqReady
    // Fill the holder under backpressure.
    d.outReady = false
    d.cycle(Some(mk(4)))
    val held = (0 until latency + 3).map(_ => d.cycle())
    val offered = held.filter(_.outValid)
    val fullInd = readyIndependent(d, sweep)
    val fullBlocked = !d.cycle(None, step = false).reqReady
    // Drain and accept in one cycle.
    d.outReady = true
    val drainIndep = readyIndependent(d, sweep)
    val da = d.cycle(Some(mk(5)))
    val next = d.until(latency + 3)
    // A held result killed by a BranchMispredict is dropped at once and never offered again.
    d.outReady = false
    d.cycle(Some(mk(9))); (0 until latency + 1).foreach(_ => d.cycle())
    val k0 = d.cycle(event = Some((BM, 7)))
    val k1 = (0 until 3).map(_ => d.cycle())
    // An older held result survives the recovery.
    d.cycle(Some(mk(6))); (0 until latency + 1).foreach(_ => d.cycle())
    val o0 = d.cycle(event = Some((BM, 7)))
    d.outReady = true
    val o1 = d.cycle()
    // A request killed by the RecoveryEvent of its own accept cycle never produces a result.
    val ka = d.cycle(Some(mk(12)), event = Some((BM, 7)))
    val kaAfter = (0 until latency + 4).map(_ => d.cycle())
    Seq(
      chk(kaAfter.forall(o => !(o.outValid && o.out("s") == 12)),
        s"$unit: a request killed in its accept cycle never publishes", s"$ka $kaAfter"),
      chk(emptyReady && emptyInd, s"$unit: an empty holder accepts, independent of the presented request", ""),
      chk(offered.nonEmpty && offered.forall(o => o.out("s") == 4 && o.out == offered.head.out),
        s"$unit: the held result stays offered and unchanged under backpressure", s"$offered"),
      chk(fullBlocked && fullInd, s"$unit: a full holder that cannot drain does not accept, independent of the request", ""),
      chk(drainIndep && da.fire && da.outValid && da.out("s") == 4, s"$unit: a draining holder accepts the next request in the same cycle", s"$da"),
      chk(next.exists(_.out("s") == 5), s"$unit: the next result follows", s"$next"),
      chk(!k0.outValid && k1.forall(!_.outValid), s"$unit: a killed held result is dropped in the event cycle and never offered", s"$k0 $k1"),
      chk(o0.outValid && o0.out("s") == 6 && o1.outValid, s"$unit: an older held result survives the recovery", s"$o0 $o1")
    )
  }

  // ---- AluUnit ------------------------------------------------------------------------------

  val alu = new SpecTest("alu.unit", Seq("funcIntegrateExternalAlu")) {
    def run(): Seq[TCheck] = sim(new AluUnit(p)) { dut =>
      val d = new Drv(dut.clock, dut.io.aluReqIn, dut.io.aluResultOut.valid, dut.io.aluResultOut.ready,
        dut.io.recoveryEventIn, fuOut(dut.io.aluResultOut.bits))
      val A = AluControl
      val cases = Seq(
        ("add", U(1, AluOp.encode(code(A.ADD)), 0x7fffffff, 1, prd = 33), u32(0x80000000L)),
        ("addi", U(2, AluOp.encode(code(A.ADD), immB = true), 10, 99, imm = -3, prd = 34), 7L),
        ("sub", U(3, AluOp.encode(code(A.SUB)), 5, 9, prd = 35), u32(-4)),
        ("sltu", U(4, AluOp.encode(code(A.SLTU)), 1, 0xffffffffL, prd = 36), 1L),
        ("sra", U(5, AluOp.encode(code(A.SRA)), 0x80000000L, 4, prd = 37), u32(ref.Alu.exec("sra", Int.MinValue, 4))),
        ("lui", U(6, AluOp.encode(code(A.ADD), immB = true, zeroA = true), 0x1234, 0, imm = 0xabcde000L, prd = 38), 0xabcde000L),
        ("auipc", U(7, AluOp.encode(code(A.ADD), immB = true, pcA = true), 0x1234, 0, imm = 0x1000, pc = 0x80000000L, prd = 39), 0x80001000L)
      )
      val res = cases.map { case (n, u, exp) =>
        d.cycle(Some(u)); val o = d.until(3)
        chk(o.exists(r => r.out("data") == exp && r.out("s") == u.s && r.out("prd") == u.prd && r.out("wen") == 1 && r.out("exc") == 0),
          s"ALU $n = 0x${exp.toHexString} with the uop's robTag and prd", s"$o")
      }
      d.cycle(Some(U(8, AluOp.encode(code(A.ADD)), 1, 1, hasDest = false))); val nd = d.until(3)
      res ++ Seq(chk(nd.exists(_.out("wen") == 0), "no destination: wen = 0", s"$nd")) ++
        holderChecks(d, s => U(s, AluOp.encode(code(A.ADD)), s, 1), 0, "ALU")
    }
  }

  // ---- AddressGenerationUnit --------------------------------------------------------------------

  val agu = new SpecTest("agu.unit", Seq("funcAddressGenerate")) {
    def run(): Seq[TCheck] = sim(new AddressGenerationUnit(p)) { dut =>
      val o = dut.io.memAddressOut.bits
      val peek = () => Map("s" -> o.robTag.idx.peek().litValue.toLong, "vaddr" -> o.vaddr.peek().litValue.toLong,
        "data" -> o.storeData.peek().litValue.toLong, "mis" -> (if (o.misaligned.peek().litToBoolean) 1L else 0L))
      val d = new Drv(dut.clock, dut.io.addressGenerationReqIn, dut.io.memAddressOut.valid, dut.io.memAddressOut.ready,
        dut.io.recoveryEventIn, peek)
      val cases = Seq(
        ("lw aligned", U(1, MemOp.encode(2), 0x1000, 0, imm = 8), 0x1008L, 0L),
        ("lw misaligned", U(2, MemOp.encode(2), 0x1000, 0, imm = 2), 0x1002L, 1L),
        ("lh aligned", U(3, MemOp.encode(1), 0x1000, 0, imm = 2), 0x1002L, 0L),
        ("lhu misaligned", U(4, MemOp.encode(1, unsigned = true), 0x1001, 0, imm = 0), 0x1001L, 1L),
        ("lb any", U(5, MemOp.encode(0), 0x1003, 0, imm = 0), 0x1003L, 0L),
        ("sw negative imm", U(6, MemOp.encode(2, store = true), 0x2000, 0xcafef00dL, imm = -4), 0x1ffcL, 0L)
      )
      val res = cases.map { case (n, u, va, mis) =>
        d.cycle(Some(u)); val r = d.until(3)
        chk(r.exists(x => x.out("vaddr") == va && x.out("mis") == mis && x.out("s") == u.s), s"AGU $n: vaddr = src1 + imm, misaligned = $mis", s"$r")
      }
      d.cycle(Some(U(7, MemOp.encode(2, store = true), 0x3000, 0x12345678, imm = 0))); val st = d.until(3)
      res ++ Seq(chk(st.exists(_.out("data") == 0x12345678), "a store carries storeData = src2", s"$st")) ++
        holderChecks(d, s => U(s, MemOp.encode(2), 0x100 * s, 0), 0, "AGU")
    }
  }

  // ---- MultiplierUnit / DividerUnit -----------------------------------------------------------------

  def mulDivChecks(d: Drv, unit: String, ops: Seq[(String, Int)], vectors: Seq[(Int, Int)], latency: Int): Seq[TCheck] = {
    var s = 1
    val results = for ((name, ctrl) <- ops; (a, b) <- vectors) yield {
      d.cycle(Some(U(s, MulDivOp.encode(ctrl), a.toLong, b.toLong, prd = 32 + (s % 16))))
      val r = d.until(latency + 4); val exp = u32(ref.Mul.exec(name, a, b)); s = (s % 12) + 1
      chk(r.exists(_.out("data") == exp), f"$unit $name(0x${u32(a)}%x, 0x${u32(b)}%x) = 0x$exp%x", s"$r")
    }
    // Kill while the core computes: the core cannot be canceled; its eventual result is discarded.
    d.cycle(Some(U(9, MulDivOp.encode(ops.head._2), 0x12345, 0x6789, prd = 45)))
    val busy = d.cycle(event = Some((BM, 7)))
    val after = (0 until latency + 6).map(_ => d.cycle())
    val busyInd = true
    // The next operation after the killed one publishes correctly.
    d.cycle(Some(U(10, MulDivOp.encode(ops.head._2), 6, 7, prd = 46)))
    val nxt = d.until(latency + 4)
    // Back-to-back: B is presented every cycle from A's start; it is accepted only once A's
    // core is free, and both results publish with their own values.
    val (opName, opCode) = ops.head
    d.cycle(Some(U(1, MulDivOp.encode(opCode), 100, 7, prd = 41)))
    var accepted = false
    val bb = (0 until latency + 6).map { _ =>
      val o = if (!accepted) d.cycle(Some(U(2, MulDivOp.encode(opCode), 50, 3, prd = 42))) else d.cycle()
      if (o.fire) accepted = true
      o
    } ++ (0 until latency + 4).map(_ => d.cycle())
    val pubs = bb.filter(_.outValid).map(o => (o.out("s"), o.out("data")))
    val bbOk = pubs == Seq((1L, u32(ref.Mul.exec(opName, 100, 7))), (2L, u32(ref.Mul.exec(opName, 50, 3))))
    // An older in-flight operation survives a younger recovery.
    d.cycle(Some(U(3, MulDivOp.encode(ops.head._2), 6, 7, prd = 47)))
    val evObs = d.cycle(event = Some((BM, 7))) // an early-out result may publish in the event cycle
    val older = if (evObs.outValid) Some(evObs) else d.until(latency + 4)
    // ArchRedirect kills even an older in-flight operation.
    d.cycle(Some(U(4, MulDivOp.encode(ops.head._2), 6, 7, prd = 44)))
    d.cycle(event = Some((AR, 4)))
    val arch = (0 until latency + 6).map(_ => d.cycle())
    results ++ Seq(
      chk(bbOk, s"$unit: back-to-back requests: the second is held off while the core is busy; both publish", s"$pubs"),
      chk(!busy.outValid && after.forall(!_.outValid), s"$unit: a killed in-flight operation never publishes its later core result", s"$after"),
      chk(nxt.exists(o => o.out("s") == 10 && o.out("data") == ref.Mul.exec(ops.head._1, 6, 7).toLong),
        s"$unit: the next operation after a killed one publishes correctly", s"$nxt"),
      chk(older.exists(o => o.out("s") == 3 && o.out("prd") == 47), s"$unit: an older in-flight operation survives a younger recovery", s"$older"),
      chk(arch.forall(!_.outValid), s"$unit: an ArchRedirect discards the in-flight result", s"$arch")
    )
  }

  /** While the core is busy the wrapper does not accept, independent of the presented request. */
  def busyIndependence(d: Drv, op: Int, unit: String): TCheck = {
    d.cycle(Some(U(11, op, 0x7fffffff, 3)))
    val ind = readyIndependent(d, Seq(U(12, op, 1, 1), U(13, op, 0, 0)))
    (0 until 50).foreach(_ => d.cycle())
    chk(ind, s"$unit: request ready while busy does not depend on the presented request", "")
  }

  val mul = new SpecTest("mul.unit", Seq("funcIntegrateExternalMultiplier")) {
    def run(): Seq[TCheck] = sim(new MultiplierUnit(p)) { dut =>
      val d = new Drv(dut.clock, dut.io.multiplierReqIn, dut.io.multiplierResultOut.valid, dut.io.multiplierResultOut.ready,
        dut.io.recoveryEventIn, fuOut(dut.io.multiplierResultOut.bits))
      val M = MultiplierControl
      val ops = Seq("mul" -> code(M.MUL), "mulh" -> code(M.MULH), "mulhu" -> code(M.MULHU), "mulhsu" -> code(M.MULHSU))
      mulDivChecks(d, "MUL", ops, Seq((7, 6), (-3, 5), (0x12345678, 0x9abcdef0), (Int.MinValue, -1)), 4) ++
        Seq(busyIndependence(d, MulDivOp.encode(code(M.MUL)), "MUL")) ++
        holderChecks(d, s => U(s, MulDivOp.encode(code(M.MUL)), s, 3), 4, "MUL")
    }
  }

  val div = new SpecTest("div.unit", Seq("funcIntegrateExternalDivider")) {
    def run(): Seq[TCheck] = sim(new DividerUnit(p)) { dut =>
      val d = new Drv(dut.clock, dut.io.dividerReqIn, dut.io.dividerResultOut.valid, dut.io.dividerResultOut.ready,
        dut.io.recoveryEventIn, fuOut(dut.io.dividerResultOut.bits))
      val V = DividerControl
      val ops = Seq("div" -> code(V.DIV), "divu" -> code(V.DIVU), "rem" -> code(V.REM), "remu" -> code(V.REMU))
      mulDivChecks(d, "DIV", ops, Seq((20, 3), (-20, 3), (7, 0), (Int.MinValue, -1), (0x12345678, 0x100)), 40) ++
        Seq(busyIndependence(d, MulDivOp.encode(code(V.DIV)), "DIV")) ++
        holderChecks(d, s => U(s, MulDivOp.encode(code(V.DIV)), 100 + s, 7), 40, "DIV")
    }
  }

  val all: Seq[SpecTest] = Seq(alu, agu, mul, div)
}
