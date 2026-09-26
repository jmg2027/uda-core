package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.top.BackendTop
import udacore.backend.design.shared.BackendParams
import udacore.core.design.shared.{AccessType, LoadStatus, TranslationStatus}
import udacore.frontend.design.shared.FetchFault

/** L1 integration SpecTests for the ADR-019 BackendTop (ADR-018): real RV32IM_Zicsr programs
  * run through every backend vertex.
  *
  * The driver plays a sequential frontend without prediction (every taken CFI is recovered
  * by a BranchMispredict; every RecoveryEvent refetches at its target), a Bare-mode DTLB, a
  * D-cache over a RAM image (answers after a latency; committed stores reach RAM only through
  * StoreBuffer drains), an MMIO region on the uncached port, and the maintenance services.
  * Programs report results by storing them to RAM and finish by storing 1 to DONE.
  */
object BackendTopSpecTests {

  val p = BackendParams(usingRvvi = true)
  val DONE = 0x3ff0L
  val MMIO = 0x10000000L
  def isMmio(a: Long): Boolean = (a & 0xf0000000L) == MMIO

  // ---- A two-pass assembler ------------------------------------------------------------------
  class Asm(val base: Long) {
    private val items = ArrayBuffer[Map[String, Long] => Long]()
    private val labels = mutable.Map[String, Long]()
    def pc: Long = base + 4L * items.size
    def label(n: String): Unit = labels(n) = pc
    private def emit(f: Map[String, Long] => Long): Unit = items += f
    private def raw(w: Long): Unit = emit(_ => w)
    import DecodeUnitSpecTests.{R, I, S, B, U, J}
    def addi(rd: Int, rs1: Int, imm: Int) = raw(I(imm, rs1, 0, rd, 0x13))
    def andi(rd: Int, rs1: Int, imm: Int) = raw(I(imm, rs1, 7, rd, 0x13))
    def slli(rd: Int, rs1: Int, sh: Int) = raw(I(sh, rs1, 1, rd, 0x13))
    def srli(rd: Int, rs1: Int, sh: Int) = raw(I(sh, rs1, 5, rd, 0x13))
    def srai(rd: Int, rs1: Int, sh: Int) = raw(I(0x400 | sh, rs1, 5, rd, 0x13))
    def add(rd: Int, a: Int, b: Int) = raw(R(0, b, a, 0, rd, 0x33))
    def sub(rd: Int, a: Int, b: Int) = raw(R(0x20, b, a, 0, rd, 0x33))
    def slt(rd: Int, a: Int, b: Int) = raw(R(0, b, a, 2, rd, 0x33))
    def sltu(rd: Int, a: Int, b: Int) = raw(R(0, b, a, 3, rd, 0x33))
    def xor(rd: Int, a: Int, b: Int) = raw(R(0, b, a, 4, rd, 0x33))
    def or(rd: Int, a: Int, b: Int) = raw(R(0, b, a, 6, rd, 0x33))
    def and(rd: Int, a: Int, b: Int) = raw(R(0, b, a, 7, rd, 0x33))
    def mulOp(f3: Int)(rd: Int, a: Int, b: Int) = raw(R(1, b, a, f3, rd, 0x33))
    def lui(rd: Int, imm20: Long) = raw(U(imm20.toInt, rd, 0x37))
    def auipc(rd: Int, imm20: Int) = raw(U(imm20, rd, 0x17))
    def li(rd: Int, v: Long): Unit = {
      val x = v & 0xffffffffL
      val lo = ((x & 0xfff).toInt << 20) >> 20 // sign-extended low 12
      val hi = ((x - lo) >>> 12) & 0xfffff
      if (hi != 0) { lui(rd, hi); if (lo != 0) addi(rd, rd, lo) } else addi(rd, 0, lo)
    }
    def load(f3: Int)(rd: Int, off: Int, rs1: Int) = raw(I(off, rs1, f3, rd, 0x03))
    def store(f3: Int)(rs2: Int, off: Int, rs1: Int) = raw(S(off, rs2, rs1, f3))
    def lw = load(2) _; def lb = load(0) _; def lh = load(1) _; def lbu = load(4) _; def lhu = load(5) _
    def sw = store(2) _; def sb = store(0) _; def sh = store(1) _
    private def rel(n: String, m: Map[String, Long], at: Long): Int = (m(n) - at).toInt
    def branch(f3: Int)(a: Int, b: Int, target: String): Unit = { val at = pc; emit(m => B(rel(target, m, at), b, a, f3)) }
    def beq = branch(0) _; def bne = branch(1) _; def blt = branch(4) _; def bge = branch(5) _
    def jal(rd: Int, target: String): Unit = { val at = pc; emit(m => J(rel(target, m, at), rd)) }
    def j(target: String): Unit = jal(0, target)
    def jalr(rd: Int, rs1: Int, off: Int) = raw(I(off, rs1, 0, rd, 0x67))
    def csrOp(f3: Int)(rd: Int, csr: Int, rs1: Int) = raw(I(csr, rs1, f3, rd, 0x73))
    def csrrw = csrOp(1) _; def csrrs = csrOp(2) _; def csrrc = csrOp(3) _; def csrrsi = csrOp(6) _; def csrrwi = csrOp(5) _
    def ecall() = raw(0x00000073L); def mret() = raw(0x30200073L); def dret() = raw(0x7b200073L)
    def fence() = raw(0x0ff0000fL); def fenceI() = raw(0x0000100fL); def wfi() = raw(0x10500073L); def word(w: Long) = raw(w)
    /** Store 1 to DONE and spin. */
    def done(): Unit = { li(31, 1); li(30, DONE); sw(31, 0, 30); label("__end"); j("__end") }
    def assemble(): Map[Long, Long] = { val m = labels.toMap; items.zipWithIndex.map { case (f, i) => (base + 4L * i) -> f(m) }.toMap }
  }

  case class Tok(pc: Long, rd: Int, wdata: Long, wen: Boolean, trap: Boolean, cause: Int)

  class Drv(val dut: BackendTop, val imem: Map[Long, Long], start: Long) {
    val io = dut.io
    var cyc = 0
    var fetchPc = start
    val ram  = mutable.Map[Long, Long]().withDefaultValue(0L) // word address -> word
    val mmio = mutable.Map[Long, Long]().withDefaultValue(0L)
    val mmioWrites = ArrayBuffer[(Int, Long, Long)]()
    /** RAM word observed at each MMIO write (ordering of uncached accesses after older committed stores). */
    var mmioWatch = 0L
    val mmioSeenRam = ArrayBuffer[Long]()
    var lines: Map[String, Boolean] = Map().withDefaultValue(false)
    var debugReq = false
    var dcLatency = 2
    var drainDelay = 1
    val rnd = new scala.util.Random(7)
    private val tlbq = ArrayBuffer[(Int, Long, Long)]()             // (due, reqId, paddr)
    private val dcq  = ArrayBuffer[(Int, Long, Long, Long)]()       // (due, lqIdx, gen, paddr)
    private val drq  = ArrayBuffer[(Int, Long, Long, Int)]()        // (due, paddr, data, mask)
    private val ulq  = ArrayBuffer[(Int, Long, Long, Long)]()       // (due, lqIdx, gen, data)
    private val usq  = ArrayBuffer[(Int, Long)]()                   // (due, sqIdx)
    private val clq  = ArrayBuffer[(Int, Long)]()
    val retired = ArrayBuffer[Tok]()
    var events = 0
    private def b(x: Bool) = x.peek().litToBoolean
    private def l(x: UInt) = x.peek().litValue.toLong
    private def merge(w: Long, d: Long, m: Int) =
      (0 until 4).foldLeft(w) { (acc, i) => if ((m >> i & 1) == 1) (acc & ~(0xffL << 8 * i)) | (d & (0xffL << 8 * i)) else acc } & 0xffffffffL

    def cycle(): Unit = {
      // Frontend: two sequential slots at fetchPc.
      val f = io.fetchPacketIn
      f.valid.poke(true.B)
      for (i <- 0 until p.decodeWidth) {
        val pc = fetchPc + 4 * i
        val fi = f.bits.insts(i)
        f.bits.valid(i).poke(true.B)
        fi.inst.poke(imem.getOrElse(pc, 0L).U); fi.pc.poke(pc.U); fi.ftqIdx.poke(0.U); fi.slot.poke(0.U)
        fi.blockEnd.poke(false.B); fi.predictedTaken.poke(false.B); fi.predictedTarget.poke(0.U); fi.fault.poke(FetchFault.None)
      }
      val i = io.interruptIn
      i.meip.poke(lines("meip").B); i.mtip.poke(lines("mtip").B); i.msip.poke(lines("msip").B)
      i.seip.poke(false.B); i.stip.poke(false.B); i.ssip.poke(false.B)
      io.debugReqIn.debugReq.poke(debugReq.B)
      // Due answers.
      val t = tlbq.find(_._1 <= cyc)
      io.dtlbStoreRespIn.valid.poke(t.nonEmpty.B)
      t.foreach { x => val r = io.dtlbStoreRespIn.bits
        r.reqId.poke(x._2.U); r.status.poke(TranslationStatus.Hit); r.paddr.poke(x._3.U); r.cacheable.poke((!isMmio(x._3)).B) }
      io.dtlbRefillIn.valid.poke(false.B)
      val dc = dcq.find(_._1 <= cyc)
      io.dCacheLoadRespIn.valid.poke(dc.nonEmpty.B)
      dc.foreach { x => val r = io.dCacheLoadRespIn.bits
        r.lqIdx.poke(x._2.U); r.lqGen.poke(x._3.U); r.paddr.poke(x._4.U)
        r.status.poke(if (isMmio(x._4)) LoadStatus.Uncacheable else LoadStatus.Data); r.data.poke(ram(x._4 >>> 2).U) }
      val dr = drq.find(_._1 <= cyc)
      io.storeDrainRespIn.valid.poke(dr.nonEmpty.B)
      val ul = ulq.find(_._1 <= cyc)
      io.uncachedLoadRespIn.valid.poke(ul.nonEmpty.B)
      ul.foreach { x => val r = io.uncachedLoadRespIn.bits; r.lqIdx.poke(x._2.U); r.lqGen.poke(x._3.U); r.data.poke(x._4.U); r.accessFault.poke(false.B) }
      val us = usq.find(_._1 <= cyc)
      io.uncachedStoreRespIn.valid.poke(us.nonEmpty.B)
      us.foreach { x => io.uncachedStoreRespIn.bits.sqIdx.poke(x._2.U); io.uncachedStoreRespIn.bits.accessFault.poke(false.B) }
      val cl = clq.find(_._1 <= cyc)
      io.dCacheCleanRespIn.valid.poke(cl.nonEmpty.B); cl.foreach(x => io.dCacheCleanRespIn.bits.op.poke(x._2.U))
      Seq(io.ftqCommitOut.ready, io.dtlbReqOut.ready, io.dCacheLoadReqOut.ready, io.uncachedStoreReqOut.ready,
        io.uncachedLoadReqOut.ready, io.storeDrainReqOut.ready, io.sfenceVmaOut.ready, io.iCacheInvalidateOut.ready,
        io.dCacheCleanReqOut.ready, io.retireStreamOut.get.ready).foreach(_.poke(true.B))

      // Observe.
      val ev = io.recoveryEventOut
      val evValid = b(ev.valid); val evTarget = l(ev.target)
      val fetchFire = b(f.ready)
      val dq = io.dtlbReqOut
      if (b(dq.valid)) {
        val va = l(dq.bits.vaddr)
        if (l(dq.bits.access) == AccessType.Store.litValue.toLong) tlbq += ((cyc + 1, l(dq.bits.reqId), va))
        else {
          val c = io.dCacheLoadReqOut.bits
          require(b(io.dCacheLoadReqOut.valid), s"cycle $cyc: load DtlbReq without its D-cache lookup")
          dcq += ((cyc + dcLatency, l(c.lqIdx), l(c.lqGen), va))
        }
      }
      if (t.nonEmpty && b(io.dtlbStoreRespIn.ready)) tlbq -= t.get
      if (dc.nonEmpty && b(io.dCacheLoadRespIn.ready)) dcq -= dc.get
      val sd = io.storeDrainReqOut
      if (b(sd.valid)) drq += ((cyc + drainDelay + rnd.nextInt(2), l(sd.bits.paddr), l(sd.bits.data), l(sd.bits.mask).toInt))
      val uls = io.uncachedLoadReqOut
      if (b(uls.valid)) ulq += ((cyc + 2, l(uls.bits.lqIdx), l(uls.bits.lqGen), mmio(l(uls.bits.paddr) >>> 2)))
      val uss = io.uncachedStoreReqOut
      if (b(uss.valid)) {
        val pa = l(uss.bits.paddr); val d = l(uss.bits.data); val m = l(uss.bits.mask).toInt
        mmio(pa >>> 2) = merge(mmio(pa >>> 2), d, m); mmioWrites += ((cyc, pa, d)); mmioSeenRam += ram(mmioWatch >>> 2)
        usq += ((cyc + 2, l(uss.bits.sqIdx)))
      }
      if (b(io.dCacheCleanReqOut.valid)) clq += ((cyc + 1, l(io.dCacheCleanReqOut.bits.op)))
      val rt = io.retireStreamOut.get
      if (b(rt.valid)) retired += Tok(l(rt.bits.pc), l(rt.bits.rd).toInt, l(rt.bits.wdata), b(rt.bits.wen), b(rt.bits.trap), l(rt.bits.cause).toInt)
      if (dr.nonEmpty && b(io.storeDrainRespIn.ready)) { drq -= dr.get }
      if (ul.nonEmpty && b(io.uncachedLoadRespIn.ready)) ulq -= ul.get
      if (us.nonEmpty && b(io.uncachedStoreRespIn.ready)) usq -= us.get
      if (cl.nonEmpty && b(io.dCacheCleanRespIn.ready)) clq -= cl.get
      dut.clock.step(); cyc += 1
      // End of cycle: a completed drain is in the array; the frontend advances or redirects.
      dr.foreach { x => ram(x._2 >>> 2) = merge(ram(x._2 >>> 2), x._3, x._4) }
      if (evValid) { fetchPc = evTarget; events += 1 }
      else if (fetchFire) fetchPc += 4 * p.decodeWidth
    }
    def word(a: Long): Long = ram(a >>> 2)
    /** Run until DONE holds 1 (plus a drain margin) or the budget runs out. */
    def runToDone(budget: Int = 4000, each: Drv => Unit = _ => ()): Boolean = {
      var k = 0
      while (word(DONE) != 1 && k < budget) { each(this); cycle(); k += 1 }
      (0 until 20).foreach(_ => cycle())
      word(DONE) == 1
    }
  }

  def run(t: SpecTest, a: Asm, start: Long, extra: Map[Long, Long] = Map())(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new BackendTop(p)) { dut => val d = new Drv(dut, a.assemble() ++ extra, start); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  def hx(v: Long): String = f"0x$v%x"
  val BASE = 0x1000L; val OUT = 0x4000L
  def expectWords(d: Drv, exp: Seq[(Long, Long)], what: String): TCheck = {
    val bad = exp.filter { case (a, v) => d.word(a) != (v & 0xffffffffL) }
    chk(bad.isEmpty, what, bad.map { case (a, v) => s"${hx(a)}=${hx(d.word(a))} want ${hx(v & 0xffffffffL)}" }.mkString("; "))
  }

  // ---- Programs ---------------------------------------------------------------------------------

  val arith = new SpecTest("top.arith", Seq("funcDecodeRv32im", "funcPublishArbitrate", "funcCommitHead")) {
    def run(): Seq[TCheck] = {
      val a = new Asm(BASE)
      a.li(20, OUT); a.li(1, 5); a.li(2, 7)
      a.add(3, 1, 2); a.sub(4, 1, 2); a.slli(5, 3, 4); a.li(6, 0x12345678L); a.srai(7, 4, 1); a.srli(8, 6, 8)
      a.slt(9, 4, 1); a.sltu(10, 4, 1); a.xor(11, 6, 3); a.label("au"); val auPc = a.pc; a.auipc(12, 0)
      Seq(3, 4, 5, 6, 7, 8, 9, 10, 11, 12).zipWithIndex.foreach { case (r, k) => a.sw(r, 4 * k, 20) }
      a.done()
      BackendTopSpecTests.run(this, a, BASE) { d =>
        val ok = d.runToDone()
        Seq(
          chk(ok, "the program runs to completion", s"cycles ${d.cyc}"),
          expectWords(d, Seq(OUT -> 12, OUT + 4 -> -2, OUT + 8 -> 192, OUT + 12 -> 0x12345678L, OUT + 16 -> -1,
            OUT + 20 -> 0x00123456L, OUT + 24 -> 1, OUT + 28 -> 0, OUT + 32 -> (0x12345678L ^ 12), OUT + 36 -> auPc),
            "ALU results (add/sub/shifts/lui/slt/xor/auipc) reach memory in program semantics"),
          chk(d.retired.nonEmpty && d.retired.map(_.pc).take(3) == Seq(BASE, BASE + 4, BASE + 8), "the retire stream starts in program order",
            s"${d.retired.take(3)}")
        )
      }
    }
  }

  val loop = new SpecTest("top.loop", Seq("funcBranchResolve", "funcMispredictDetect", "funcRecoveryBroadcast")) {
    def run(): Seq[TCheck] = {
      val a = new Asm(BASE)
      a.li(20, OUT); a.li(1, 0); a.li(2, 1); a.li(3, 11)
      a.label("loop"); a.add(1, 1, 2); a.addi(2, 2, 1); a.bne(2, 3, "loop")
      a.sw(1, 0, 20)
      // A taken forward branch must skip the poisoning instruction.
      a.li(4, 9); a.beq(4, 4, "skip"); a.li(4, 0x666); a.label("skip"); a.sw(4, 4, 20)
      a.li(5, -3); a.blt(5, 0, "neg"); a.li(5, 0x777); a.label("neg"); a.sw(5, 8, 20)
      a.done()
      BackendTopSpecTests.run(this, a, BASE) { d =>
        val ok = d.runToDone()
        Seq(
          chk(ok, "the program runs to completion", s"cycles ${d.cyc}"),
          expectWords(d, Seq(OUT -> 55, OUT + 4 -> 9, OUT + 8 -> -3), "a counted loop and taken branches produce program results"),
          chk(d.events >= 11, "every taken CFI was recovered by a RecoveryEvent (the frontend predicts none)", s"${d.events}")
        )
      }
    }
  }

  val memory = new SpecTest("top.memory", Seq("funcStoreToLoadForward", "funcLoadComplete", "funcCommittedForward", "funcCommitOrderDrain")) {
    def run(): Seq[TCheck] = {
      val a = new Asm(BASE)
      a.li(20, OUT); a.li(21, OUT + 0x100)
      a.li(1, 0x80ff7f01L); a.sw(1, 0, 20)
      a.lb(2, 0, 20); a.lb(3, 1, 20); a.lb(4, 2, 20); a.lbu(5, 3, 20); a.lh(6, 2, 20); a.lhu(7, 2, 20)
      a.sb(0, 1, 20); a.lw(8, 0, 20)
      a.li(9, 0xbeef); a.sh(9, 2, 20); a.lw(10, 0, 20)
      Seq(2, 3, 4, 5, 6, 7, 8, 10).zipWithIndex.foreach { case (r, k) => a.sw(r, 4 * k, 21) }
      a.done()
      BackendTopSpecTests.run(this, a, BASE) { d =>
        val ok = d.runToDone()
        Seq(
          chk(ok, "the program runs to completion", s"cycles ${d.cyc}"),
          expectWords(d, Seq(OUT -> 0xbeef0001L, OUT + 0x100 -> 1, OUT + 0x104 -> 0x7f, OUT + 0x108 -> -1, OUT + 0x10c -> 0x80,
            OUT + 0x110 -> 0xffff80ffL, OUT + 0x114 -> 0x80ff, OUT + 0x118 -> 0x80ff0001L, OUT + 0x11c -> 0xbeef0001L),
            "byte/half/word loads see older stores (SQ, StoreBuffer, or memory) with the right extension")
        )
      }
    }
  }

  val mext = new SpecTest("top.mext", Seq("funcIntegrateExternalMultiplier", "funcIntegrateExternalDivider")) {
    def run(): Seq[TCheck] = {
      val a = new Asm(BASE)
      a.li(20, OUT); a.li(1, -7); a.li(2, 3); a.li(3, 0x80000000L); a.li(4, -1)
      a.mulOp(0)(5, 1, 2); a.mulOp(1)(6, 3, 3); a.mulOp(3)(7, 4, 4); a.mulOp(2)(8, 1, 2)
      a.mulOp(4)(9, 1, 2); a.mulOp(5)(10, 1, 2); a.mulOp(6)(11, 1, 2); a.mulOp(7)(12, 1, 2)
      a.mulOp(4)(13, 1, 0); a.mulOp(6)(14, 1, 0); a.mulOp(4)(15, 3, 4)
      (5 to 15).zipWithIndex.foreach { case (r, k) => a.sw(r, 4 * k, 20) }
      a.done()
      BackendTopSpecTests.run(this, a, BASE) { d =>
        val ok = d.runToDone(6000)
        Seq(
          chk(ok, "the program runs to completion", s"cycles ${d.cyc}"),
          expectWords(d, Seq(OUT -> -21, OUT + 4 -> 0x40000000L, OUT + 8 -> 0xfffffffeL, OUT + 12 -> -1, OUT + 16 -> -2,
            OUT + 20 -> 0x55555553L, OUT + 24 -> -1, OUT + 28 -> 0, OUT + 32 -> -1, OUT + 36 -> -7, OUT + 40 -> 0x80000000L),
            "MUL/MULH/MULHU/MULHSU/DIV/DIVU/REM/REMU incl. divide-by-zero and overflow")
        )
      }
    }
  }

  val calls = new SpecTest("top.calls", Seq("funcBranchResolve")) {
    def run(): Seq[TCheck] = {
      val a = new Asm(BASE)
      a.li(20, OUT); a.li(10, 1)
      a.jal(1, "f"); a.jal(1, "f"); a.sw(10, 0, 20)
      a.li(11, 0); a.jal(5, "g"); a.sw(11, 4, 20)
      a.done()
      a.label("f"); a.addi(10, 10, 3); a.jalr(0, 1, 0)
      a.label("g"); a.addi(11, 11, 40); a.jalr(0, 5, 0)
      BackendTopSpecTests.run(this, a, BASE) { d =>
        val ok = d.runToDone()
        Seq(chk(ok, "the program runs to completion", s"cycles ${d.cyc}"),
          expectWords(d, Seq(OUT -> 7, OUT + 4 -> 40), "JAL/JALR call and return (x1 and x5 links)"))
      }
    }
  }

  val traps = new SpecTest("top.traps", Seq("funcTrapSingleOwner", "funcPreciseTrapHandoff", "funcXRet", "funcCsrExecuteAtCommit")) {
    def run(): Seq[TCheck] = {
      val a = new Asm(BASE)
      a.li(20, OUT); a.li(12, 0)
      a.li(1, 0x2000); a.csrrw(0, 0x305, 1)          // mtvec = handler
      a.li(2, 0x55); a.csrrw(3, 0x340, 2)             // mscratch = 0x55, x3 = old
      a.csrrs(4, 0x340, 0)                            // x4 = 0x55
      a.ecall()
      a.sw(10, 0, 20); a.sw(11, 4, 20)
      a.word(0)                                       // illegal instruction
      a.sw(11, 8, 20); a.sw(12, 12, 20); a.sw(3, 16, 20); a.sw(4, 20, 20)
      a.csrrs(13, 0x300, 0); a.sw(13, 24, 20)         // mstatus after two MRETs: MPIE set
      a.done()
      val h = new Asm(0x2000)
      h.csrrs(5, 0x341, 0); h.addi(5, 5, 4); h.csrrw(0, 0x341, 5)
      h.li(10, 0x77); h.csrrs(11, 0x342, 0); h.addi(12, 12, 1); h.mret()
      BackendTopSpecTests.run(this, a, BASE, h.assemble()) { d =>
        val ok = d.runToDone()
        Seq(chk(ok, "the program runs to completion", s"cycles ${d.cyc}"),
          expectWords(d, Seq(OUT -> 0x77, OUT + 4 -> 11, OUT + 8 -> 2, OUT + 12 -> 2, OUT + 16 -> 0, OUT + 20 -> 0x55,
            OUT + 24 -> 0x80), "ECALL and an illegal instruction trap precisely; the handler returns past them with MRET"),
          chk(d.retired.count(_.trap) == 2 && d.retired.filter(_.trap).map(_.cause) == Seq(11, 2), "two trap-entry tokens (causes 11, 2)",
            s"${d.retired.filter(_.trap)}"))
      }
    }
  }

  val interrupt = new SpecTest("top.interrupt", Seq("funcInterruptSampling", "funcUncacheableAtHead", "funcInterruptCtrlPublish")) {
    def run(): Seq[TCheck] = {
      val a = new Asm(BASE)
      a.li(20, OUT); a.li(13, 0); a.li(1, 0)
      a.li(2, 0x2000); a.csrrw(0, 0x305, 2); a.li(2, 0x80); a.csrrw(0, 0x304, 2); a.csrrsi(0, 0x300, 8)
      a.label("spin"); a.addi(1, 1, 1); a.beq(13, 0, "spin")
      a.sw(1, 0, 20); a.sw(13, 4, 20)
      a.done()
      val h = new Asm(0x2000)
      h.li(9, 0x5a); h.sw(9, 0x200, 20)             // a cached store older than the device access
      h.li(6, MMIO); h.li(7, 0xa5); h.sw(7, 0, 6)     // acknowledge the device (uncached store)
      h.lw(8, 4, 6); h.sw(8, 8, 20)                   // an uncached load
      h.li(13, 1); h.mret()
      BackendTopSpecTests.run(this, a, BASE, h.assemble()) { d =>
        d.mmio((MMIO + 4) >>> 2) = 0x1234; d.mmioWatch = OUT + 0x200; d.drainDelay = 25
        val ok = d.runToDone(6000, dd => {
          if (dd.cyc == 150) dd.lines = dd.lines.updated("mtip", true)
          if (dd.mmioWrites.nonEmpty) dd.lines = dd.lines.updated("mtip", false)
        })
        Seq(chk(ok, "the program runs to completion", s"cycles ${d.cyc}"),
          chk(d.word(OUT) > 0 && d.word(OUT + 4) == 1 && d.word(OUT + 8) == 0x1234, "the timer interrupt interrupted the spin loop; the handler ran",
            s"${d.word(OUT)} ${d.word(OUT + 4)} ${hx(d.word(OUT + 8))}"),
          chk(d.mmioWrites.size == 1 && d.mmioWrites.head._3 == 0xa5, "the MMIO store was performed exactly once", s"${d.mmioWrites}"),
          chk(d.mmioSeenRam == Seq(0x5aL), "the uncached access waited until the older committed store had drained to memory", s"${d.mmioSeenRam}"),
          chk(d.retired.exists(t => t.trap && t.cause == 7), "an interrupt trap-entry token (cause 7)", s"${d.retired.filter(_.trap)}"))
      }
    }
  }

  val debug = new SpecTest("top.debugDret", Seq("funcDebugCommitBoundary", "funcXRet")) {
    def run(): Seq[TCheck] = {
      val a = new Asm(BASE)
      a.li(20, OUT); a.li(1, 0)
      a.label("wait"); a.addi(1, 1, 1); a.lw(5, 0x300, 20); a.beq(5, 0, "wait")
      a.sw(1, 4, 20)
      a.done()
      val dbg = new Asm(p.debugEntryAddr)
      dbg.li(6, OUT + 0x300); dbg.li(7, 0xd0); dbg.sw(7, 0, 6); dbg.dret()
      BackendTopSpecTests.run(this, a, BASE, dbg.assemble()) { d =>
        val ok = d.runToDone(6000, dd => { dd.debugReq = dd.cyc >= 120 && dd.cyc < 124 })
        Seq(chk(ok, "the program runs to completion (DRET resumed the interrupted code)", s"cycles ${d.cyc}"),
          chk(d.word(OUT + 0x300) == 0xd0 && d.word(OUT + 4) > 0, "debug-mode code ran at debugEntryAddr and normal code resumed at dpc",
            s"${hx(d.word(OUT + 0x300))} ${d.word(OUT + 4)}"),
          chk(d.retired.exists(t => t.trap && t.cause == 3), "a debug-entry token (cause 3)", s"${d.retired.filter(_.trap)}"))
      }
    }
  }

  val fences = new SpecTest("top.fences", Seq("funcSystemOpSequencing", "funcDrainFence")) {
    def run(): Seq[TCheck] = {
      val a = new Asm(BASE)
      a.li(20, OUT); a.li(1, 0x11); a.sw(1, 0, 20); a.fence(); a.lw(2, 0, 20)
      a.li(3, 0x22); a.sw(3, 4, 20); a.fenceI(); a.lw(4, 4, 20); a.wfi()
      a.sw(2, 8, 20); a.sw(4, 12, 20)
      a.done()
      BackendTopSpecTests.run(this, a, BASE) { d =>
        val ok = d.runToDone()
        Seq(chk(ok, "the program runs to completion", s"cycles ${d.cyc}"),
          expectWords(d, Seq(OUT -> 0x11, OUT + 4 -> 0x22, OUT + 8 -> 0x11, OUT + 12 -> 0x22), "FENCE / FENCE.I / WFI sequence correctly"))
      }
    }
  }

  val all: Seq[SpecTest] = Seq(arith, loop, memory, mext, calls, traps, interrupt, debug, fences)
}
