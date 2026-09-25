package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.modules.DecodeUnit
import udacore.backend.design.shared._
import udacore.common.ControlSignal.{AluControl, BranchControl, DividerControl, MultiplierControl}
import udacore.frontend.design.shared.FetchFault

/** L1 SpecTests for the ADR-019 DecodeUnit (ADR-018; spec 242feaf + ADR-019A..E).
  *
  * The driver plays the FetchBuffer (FetchPacket lanes), the CsrController (DecodePrivView),
  * the RenameUnit (DecodedPacketOut ready), and the RecoveryController.
  */
object DecodeUnitSpecTests {

  val p  = BackendParams()
  val DW = p.decodeWidth

  // ---- RV32 encoders -------------------------------------------------------------------------
  def R(f7: Int, rs2: Int, rs1: Int, f3: Int, rd: Int, op: Int): Long =
    (f7.toLong << 25) | (rs2.toLong << 20) | (rs1.toLong << 15) | (f3.toLong << 12) | (rd.toLong << 7) | op
  def I(imm: Int, rs1: Int, f3: Int, rd: Int, op: Int): Long =
    ((imm & 0xfff).toLong << 20) | (rs1.toLong << 15) | (f3.toLong << 12) | (rd.toLong << 7) | op
  def S(imm: Int, rs2: Int, rs1: Int, f3: Int, op: Int = 0x23): Long =
    (((imm >> 5) & 0x7f).toLong << 25) | (rs2.toLong << 20) | (rs1.toLong << 15) | (f3.toLong << 12) | ((imm & 0x1f).toLong << 7) | op
  def B(imm: Int, rs2: Int, rs1: Int, f3: Int): Long =
    (((imm >> 12) & 1).toLong << 31) | (((imm >> 5) & 0x3f).toLong << 25) | (rs2.toLong << 20) | (rs1.toLong << 15) |
      (f3.toLong << 12) | (((imm >> 1) & 0xf).toLong << 8) | (((imm >> 11) & 1).toLong << 7) | 0x63
  def U(imm20: Int, rd: Int, op: Int): Long = ((imm20 & 0xfffff).toLong << 12) | (rd.toLong << 7) | op
  def J(imm: Int, rd: Int): Long =
    (((imm >> 20) & 1).toLong << 31) | (((imm >> 1) & 0x3ff).toLong << 21) | (((imm >> 11) & 1).toLong << 20) |
      (((imm >> 12) & 0xff).toLong << 12) | (rd.toLong << 7) | 0x6f
  def csr(f3: Int, rs1: Int, rd: Int, addr: Int = 0x300): Long = I(addr, rs1, f3, rd, 0x73)
  def u32(v: Long): Long = v & 0xffffffffL

  def code(e: Any): Int = e match {
    case x: chisel3.EnumType => x.litValue.toInt
    case x: UInt => x.litValue.toInt
  }
  val ALU = code(FuType.Alu); val MUL = code(FuType.Mul); val DIV = code(FuType.Div); val BR = code(FuType.Branch)
  val MEM = code(FuType.Mem); val CSR = code(FuType.Csr); val SYS = code(FuType.System)

  case class View(priv: Int = 3, dm: Boolean = false, tvm: Boolean = false, tw: Boolean = false, tsr: Boolean = false)
  case class FI(inst: Long, pc: Long = 0x1000, predTaken: Boolean = false, predTarget: Long = 0, ftq: Int = 3, slot: Int = 1,
      blockEnd: Boolean = false, fault: UInt = FetchFault.None)
  case class Uop(pc: Long, insn: Long, fu: Int, op: Int, rd: Int, rs1: Int, rs2: Int, imm: Long, cfi: Boolean, ld: Boolean,
      st: Boolean, ser: Boolean, sysOp: Int, predTaken: Boolean, predTarget: Long, ftq: Int, slot: Int, blockEnd: Boolean,
      predFault: Boolean, exc: Option[(Int, Long)])
  case class Obs(fetchReady: Boolean, outValid: Boolean, lanes: Seq[Option[Uop]])

  class Drv(val dut: DecodeUnit) {
    val io = dut.io
    var view = View()
    var outReady = true
    def cycle(pkt: Option[Seq[FI]] = None, event: Option[UInt] = None): Obs = {
      val f = io.fetchPacketIn
      f.valid.poke(pkt.nonEmpty.B)
      for (l <- 0 until DW) {
        val x = pkt.flatMap(_.lift(l))
        f.bits.valid(l).poke(x.nonEmpty.B)
        val fi = f.bits.insts(l)
        x.foreach { v =>
          fi.inst.poke(v.inst.U); fi.pc.poke(v.pc.U); fi.ftqIdx.poke(v.ftq.U); fi.slot.poke(v.slot.U); fi.blockEnd.poke(v.blockEnd.B)
          fi.predictedTaken.poke(v.predTaken.B); fi.predictedTarget.poke(v.predTarget.U); fi.fault.poke(v.fault)
        }
      }
      val v = io.decodePrivViewIn
      v.priv.poke(view.priv.U); v.debugMode.poke(view.dm.B); v.tvm.poke(view.tvm.B); v.tw.poke(view.tw.B); v.tsr.poke(view.tsr.B)
      io.decodedPacketOut.ready.poke(outReady.B)
      val e = io.recoveryEventIn
      e.valid.poke(event.nonEmpty.B); event.foreach(k => e.kind.poke(k))
      def b(x: Bool) = x.peek().litToBoolean
      def l(x: UInt) = x.peek().litValue.toLong
      val o = io.decodedPacketOut
      val lanes = (0 until DW).map { i =>
        val ln = o.bits.lanes(i)
        if (!b(o.valid) || !b(ln.valid)) None else {
          val u = ln.uop
          Some(Uop(l(u.pc), l(u.insn), l(u.fuType).toInt, l(u.op).toInt, l(u.rd).toInt, l(u.rs1).toInt, l(u.rs2).toInt, l(u.imm),
            b(u.isCfi), b(u.isLoad), b(u.isStore), b(u.serialize), l(u.sysOp).toInt, b(u.prediction.predictedTaken),
            l(u.prediction.predictedTarget), l(u.prediction.ftqIdx).toInt, l(u.prediction.slot).toInt, b(u.prediction.blockEnd),
            b(u.predictionFault), if (b(u.exception.valid)) Some((l(u.exception.cause).toInt, l(u.exception.tval))) else None))
        }
      }
      val obs = Obs(b(f.ready), b(o.valid), lanes)
      dut.clock.step()
      obs
    }
    /** Decode one instruction in lane 0 and return its uop. */
    def one(x: FI): Option[Uop] = { cycle(Some(Seq(x))); cycle().lanes.head }
  }

  def withDrv(t: SpecTest, params: BackendParams = p)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new DecodeUnit(params)) { dut => val d = new Drv(dut); d.cycle(); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  // Expected: (name, inst, fu, op, rd, rs1, rs2, imm, cfi, ld, st)
  def alu(c: AluControl.Type, immB: Boolean = false, pcA: Boolean = false, zeroA: Boolean = false) = AluOp.encode(code(c), immB, pcA, zeroA)
  def br(c: BranchControl.Type, t: UInt) = BranchOp.encode(code(c), code(t))
  val table: Seq[(String, Long, Int, Int, Int, Int, Int, Long, Boolean, Boolean, Boolean)] = Seq(
    ("add x3,x1,x2", R(0, 2, 1, 0, 3, 0x33), ALU, alu(AluControl.ADD), 3, 1, 2, 0L, false, false, false),
    ("sub x4,x5,x6", R(0x20, 6, 5, 0, 4, 0x33), ALU, alu(AluControl.SUB), 4, 5, 6, 0L, false, false, false),
    ("sltu x4,x5,x6", R(0, 6, 5, 3, 4, 0x33), ALU, alu(AluControl.SLTU), 4, 5, 6, 0L, false, false, false),
    ("addi x5,x6,-3", I(-3, 6, 0, 5, 0x13), ALU, alu(AluControl.ADD, immB = true), 5, 6, 0, 0xfffffffdL, false, false, false),
    ("andi x5,x6,0x7f", I(0x7f, 6, 7, 5, 0x13), ALU, alu(AluControl.AND, immB = true), 5, 6, 0, 0x7fL, false, false, false),
    ("srai x7,x8,3", I(0x403, 8, 5, 7, 0x13), ALU, alu(AluControl.SRA, immB = true), 7, 8, 0, 0x403L, false, false, false),
    ("lui x9,0x12345", U(0x12345, 9, 0x37), ALU, alu(AluControl.ADD, immB = true, zeroA = true), 9, 0, 0, 0x12345000L, false, false, false),
    ("auipc x10,0x80001", U(0x80001, 10, 0x17), ALU, alu(AluControl.ADD, immB = true, pcA = true), 10, 0, 0, 0x80001000L, false, false, false),
    ("jal x1,+8 (call)", J(8, 1), BR, br(BranchControl.JAL, CfiType.Call), 1, 0, 0, 8L, true, false, false),
    ("jal x0,-4", J(-4, 0), BR, br(BranchControl.JAL, CfiType.Jal), 0, 0, 0, 0xfffffffcL, true, false, false),
    ("jalr x0,0(x1) (ret)", I(0, 1, 0, 0, 0x67), BR, br(BranchControl.JALR, CfiType.Ret), 0, 1, 0, 0L, true, false, false),
    ("jalr x1,4(x5) (call)", I(4, 5, 0, 1, 0x67), BR, br(BranchControl.JALR, CfiType.Call), 1, 5, 0, 4L, true, false, false),
    ("jalr x0,0(x6)", I(0, 6, 0, 0, 0x67), BR, br(BranchControl.JALR, CfiType.Jalr), 0, 6, 0, 0L, true, false, false),
    ("beq x1,x2,+16", B(16, 2, 1, 0), BR, br(BranchControl.BEQ, CfiType.Branch), 0, 1, 2, 16L, true, false, false),
    ("bltu x3,x4,-8", B(-8, 4, 3, 6), BR, br(BranchControl.BLTU, CfiType.Branch), 0, 3, 4, 0xfffffff8L, true, false, false),
    ("lw x11,12(x2)", I(12, 2, 2, 11, 0x03), MEM, MemOp.encode(2), 11, 2, 0, 12L, false, true, false),
    ("lbu x12,-1(x3)", I(-1, 3, 4, 12, 0x03), MEM, MemOp.encode(0, unsigned = true), 12, 3, 0, 0xffffffffL, false, true, false),
    ("lh x13,2(x4)", I(2, 4, 1, 13, 0x03), MEM, MemOp.encode(1), 13, 4, 0, 2L, false, true, false),
    ("lhu x13,2(x4)", I(2, 4, 5, 13, 0x03), MEM, MemOp.encode(1, unsigned = true), 13, 4, 0, 2L, false, true, false),
    ("sw x5,8(x6)", S(8, 5, 6, 2), MEM, MemOp.encode(2, store = true), 0, 6, 5, 8L, false, false, true),
    ("sb x7,-2(x8)", S(-2, 7, 8, 0), MEM, MemOp.encode(0, store = true), 0, 8, 7, 0xfffffffeL, false, false, true),
    ("mul x3,x4,x5", R(1, 5, 4, 0, 3, 0x33), MUL, MulDivOp.encode(code(MultiplierControl.MUL)), 3, 4, 5, 0L, false, false, false),
    ("mulhu x3,x4,x5", R(1, 5, 4, 3, 3, 0x33), MUL, MulDivOp.encode(code(MultiplierControl.MULHU)), 3, 4, 5, 0L, false, false, false),
    ("div x6,x7,x8", R(1, 8, 7, 4, 6, 0x33), DIV, MulDivOp.encode(code(DividerControl.DIV)), 6, 7, 8, 0L, false, false, false),
    ("remu x6,x7,x8", R(1, 8, 7, 7, 6, 0x33), DIV, MulDivOp.encode(code(DividerControl.REMU)), 6, 7, 8, 0L, false, false, false),
    ("csrrw x1,mstatus,x2", csr(1, 2, 1), CSR, 1, 1, 2, 0, 0L, false, false, false),
    ("csrrs x1,mstatus,x0", csr(2, 0, 1), CSR, 2, 1, 0, 0, 0L, false, false, false),
    ("csrrci x1,mstatus,5", csr(7, 5, 1), CSR, 7, 1, 0, 0, 0L, false, false, false)
  )

  // ---- funcDecodeRv32im ----------------------------------------------------------------------------

  val rv32im = new SpecTest("decode.rv32im", Seq("funcDecodeRv32im")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      table.map { case (name, inst, fu, op, rd, rs1, rs2, imm, cfi, ld, st) =>
        val u = d.one(FI(inst, pc = 0x2000))
        val ok = u.exists(x => x.fu == fu && x.op == op && x.rd == rd && x.rs1 == rs1 && x.rs2 == rs2 && x.imm == imm &&
          x.cfi == cfi && x.ld == ld && x.st == st && x.exc.isEmpty && x.insn == inst && x.pc == 0x2000)
        chk(ok, f"$name%-22s -> fu $fu op 0x$op%x rd $rd rs1 $rs1 rs2 $rs2 imm 0x$imm%x", s"$u")
      }
    }
  }

  val exceptions = new SpecTest("decode.exceptions", Seq("funcDecodeRv32im", "funcSystemPrivLegality")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      def exc(x: FI, v: View = View()): Option[Uop] = { d.view = v; d.one(x) }
      val ecallM = exc(FI(0x00000073L)); val ecallS = exc(FI(0x00000073L), View(priv = 1)); val ecallU = exc(FI(0x00000073L), View(priv = 0))
      val ebreak = exc(FI(0x00100073L, pc = 0x4444))
      val zero   = exc(FI(0x00000000L)); val ones = exc(FI(0xffffffffL)); val rsvd = exc(FI(0x0000000bL))
      val sretU  = exc(FI(0x10200073L), View(priv = 0))
      val sfenceTvm = exc(FI(0x12000073L), View(priv = 1, tvm = true))
      val dretOut = exc(FI(0x7b200073L))
      val wfiS = exc(FI(0x10500073L), View(priv = 1)); val wfiSTw = exc(FI(0x10500073L), View(priv = 1, tw = true))
      val pf = exc(FI(R(0, 2, 1, 0, 3, 0x33), pc = 0x5000, fault = FetchFault.InstPageFault))
      val af = exc(FI(0x00000073L, pc = 0x5004, fault = FetchFault.InstAccessFault))
      val pfBranch = exc(FI(B(16, 2, 1, 0), pc = 0x5008, fault = FetchFault.InstPageFault))
      val ecallDbg = exc(FI(0x00000073L), View(priv = 0, dm = true))
      def is(u: Option[Uop], c: Int, t: Long) = u.exists(x => x.exc.contains((c, t)) && x.sysOp == 0 && !x.ser && x.fu == SYS && !x.cfi && !x.ld && !x.st)
      Seq(
        chk(is(ecallM, 11, 0) && is(ecallS, 9, 0) && is(ecallU, 8, 0), "ECALL raises environment call from M / S / U by DecodePrivView.priv", s"$ecallM $ecallS $ecallU"),
        chk(is(ebreak, 3, 0x4444), "EBREAK raises breakpoint with tval = pc", s"$ebreak"),
        chk(is(zero, 2, 0) && is(ones, 2, 0xffffffffL) && is(rsvd, 2, 0xbL), "undefined encodings raise illegal instruction with tval = insn", s"$zero $ones $rsvd"),
        chk(is(sretU, 2, 0x10200073L) && is(sfenceTvm, 2, 0x12000073L) && is(dretOut, 2, 0x7b200073L) && is(wfiSTw, 2, 0x10500073L),
          "privilege-illegal SRET (U), SFENCE.VMA (S + TVM), DRET (not debug), WFI (S + TW) enter the ROB as illegal instructions",
          s"$sretU $sfenceTvm $dretOut $wfiSTw"),
        chk(wfiS.exists(x => x.exc.isEmpty && x.sysOp == code(SysOp.Wfi)), "WFI in S without TW is legal", s"$wfiS"),
        chk(is(pf, 12, 0x5000) && is(af, 1, 0x5004) && is(pfBranch, 12, 0x5008),
          "a fetch fault overrides decoding (instruction page / access fault, tval = pc; a faulting branch is no CFI)", s"$pf $af $pfBranch"),
        chk(is(ecallDbg, 11, 0), "ECALL in Debug Mode decodes as M (cause 11)", s"$ecallDbg")
      )
    }
  }

  // ---- funcExtensionDecodeContribution / propDisabledExtensionTraps ------------------------------------------

  val extensions = new SpecTest("decode.extensions", Seq("funcExtensionDecodeContribution", "propDisabledExtensionTraps")) {
    def run(): Seq[TCheck] = {
      val mulDiv = Seq(R(1, 5, 4, 0, 3, 0x33), R(1, 5, 4, 1, 3, 0x33), R(1, 8, 7, 4, 6, 0x33), R(1, 8, 7, 7, 6, 0x33))
      val on = withDrv(this) { d =>
        val us = mulDiv.map(i => d.one(FI(i)))
        Seq(chk(us.forall(_.exists(u => u.exc.isEmpty && (u.fu == MUL || u.fu == DIV))), "with M enabled its rows decode", s"$us"))
      }
      val off = withDrv(this, p.copy(contract = p.contract.copy(enableMulDiv = false))) { d =>
        val us = mulDiv.map(i => d.one(FI(i)))
        val add = d.one(FI(R(0, 2, 1, 0, 3, 0x33)))
        Seq(
          chk(us.forall(_.exists(u => u.exc.exists(_._1 == 2) && u.fu != MUL && u.fu != DIV)),
            "with M disabled its encodings decode to the illegal-instruction default", s"$us"),
          chk(add.exists(_.exc.isEmpty), "the base rows are unaffected", s"$add"))
      }
      on ++ off
    }
  }

  // ---- propNoCompressedDecode ---------------------------------------------------------------------------------

  val noCompressed = new SpecTest("decode.noCompressed", Seq("propNoCompressedDecode")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val rnd = new scala.util.Random(3)
      val words = Seq(0x4501L, 0x8082L, 0x0001L) ++ (0 until 40).map(_ => (rnd.nextLong() & 0xfffffffcL) | rnd.nextInt(3))
      val bad = words.flatMap { w => val u = d.one(FI(w)); if (u.exists(_.exc.contains((2, w)))) None else Some(f"$w%08x -> $u") }
      Seq(chk(bad.isEmpty, "every word with bits[1:0] != 11 decodes to illegal instruction (tval = insn)", bad.take(3).mkString("; ")))
    }
  }

  // ---- funcSerializingTag ----------------------------------------------------------------------------------------

  val serializing = new SpecTest("decode.serializing", Seq("funcSerializingTag")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val cases = Seq(
        ("fence", 0x0ff0000fL, SYS, SysOp.Fence), ("fence.i", 0x0000100fL, SYS, SysOp.FenceI),
        ("sfence.vma", 0x12b50073L, SYS, SysOp.SfenceVma), ("wfi", 0x10500073L, SYS, SysOp.Wfi),
        ("mret", 0x30200073L, SYS, SysOp.Mret), ("sret", 0x10200073L, SYS, SysOp.Sret),
        ("csrrw", csr(1, 2, 1), CSR, SysOp.CsrWrite), ("csrrs x0-src", csr(2, 0, 1), CSR, SysOp.None),
        ("csrrsi uimm 3", csr(6, 3, 1), CSR, SysOp.CsrWrite))
      val out = cases.map { case (n, i, fu, op) =>
        val u = d.one(FI(i))
        chk(u.exists(x => x.fu == fu && x.ser && x.sysOp == code(op) && x.exc.isEmpty), s"$n: fu $fu, serialize, sysOp ${code(op)}", s"$u")
      }
      d.view = View(dm = true)
      val dret = d.one(FI(0x7b200073L))
      d.view = View()
      val add = d.one(FI(R(0, 2, 1, 0, 3, 0x33)))
      out ++ Seq(
        chk(dret.exists(x => x.fu == SYS && x.ser && x.sysOp == code(SysOp.Dret) && x.exc.isEmpty), "dret in Debug Mode: System, serialize, Dret", s"$dret"),
        chk(add.exists(x => !x.ser && x.sysOp == 0), "an ordinary instruction is not serializing", s"$add"))
    }
  }

  // ---- funcPredictionCheck -----------------------------------------------------------------------------------------

  val prediction = new SpecTest("decode.prediction", Seq("funcPredictionCheck")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val nonCfi = d.one(FI(R(0, 2, 1, 0, 3, 0x33), predTaken = true, predTarget = 0x3000, ftq = 5, slot = 2, blockEnd = true))
      val branch = d.one(FI(B(16, 2, 1, 0), predTaken = true, predTarget = 0x1010))
      val notPred = d.one(FI(R(0, 2, 1, 0, 3, 0x33)))
      val faulty = d.one(FI(0xffffffffL, predTaken = true))
      Seq(
        chk(nonCfi.exists(u => u.predFault && !u.cfi && u.exc.isEmpty && u.predTaken && u.predTarget == 0x3000 && u.ftq == 5 && u.slot == 2 && u.blockEnd),
          "a predicted-taken non-CFI executes normally with predictionFault; the prediction fields are carried", s"$nonCfi"),
        chk(branch.exists(u => !u.predFault && u.cfi), "a predicted-taken branch is not a prediction fault", s"$branch"),
        chk(notPred.exists(!_.predFault), "no fault without a prediction", s"$notPred"),
        chk(faulty.exists(u => !u.predFault && u.exc.nonEmpty), "an excepting uop carries its exception instead of predictionFault", s"$faulty"))
    }
  }

  // ---- Packet flow and funcDecodeRecovery ---------------------------------------------------------------------------

  val packet = new SpecTest("decode.packet", Seq("funcDecodeRecovery", "funcDecodeRv32im")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val a = FI(R(0, 2, 1, 0, 3, 0x33), pc = 0x100); val b2 = FI(I(1, 3, 0, 4, 0x13), pc = 0x104)
      val acc = d.cycle(Some(Seq(a, b2)))
      d.outReady = false
      val held = (0 until 3).map(_ => d.cycle(Some(Seq(FI(0x13, pc = 0x108)))))
      d.outReady = true
      val drain = d.cycle(Some(Seq(FI(0x13, pc = 0x108))))
      val next = d.cycle()
      // Recovery: the held packet and an input offered in the event cycle are discarded.
      d.outReady = false
      d.cycle(Some(Seq(FI(0x13, pc = 0x200))))
      val ev = d.cycle(Some(Seq(FI(0x13, pc = 0x204))), event = Some(RecoveryKind.BranchMispredict))
      val after = d.cycle()
      // An input offered in an event cycle to an empty stage is not accepted either.
      val evEmpty = d.cycle(Some(Seq(FI(0x13, pc = 0x208))), event = Some(RecoveryKind.BranchMispredict))
      val afterEmpty = d.cycle()
      d.outReady = true
      val ar = { d.cycle(Some(Seq(FI(0x13, pc = 0x300)))); d.outReady = false; d.cycle(event = Some(RecoveryKind.ArchRedirect)) }
      val afterAr = d.cycle()
      Seq(
        chk(acc.fetchReady, "an empty decode stage accepts a packet", s"$acc"),
        chk(held.forall(o => o.outValid && !o.fetchReady && o.lanes.map(_.map(_.pc)) == Seq(Some(0x100L), Some(0x104L))),
          "the decoded packet is held stable (lanes contiguous, program order) until DecodedPacketOut is ready", s"$held"),
        chk(drain.outValid && drain.fetchReady && next.lanes.head.exists(_.pc == 0x108) && next.lanes(1).isEmpty,
          "the next packet enters in the cycle the held one transfers; a one-lane packet has lane 1 invalid", s"$drain $next"),
        chk(!ev.outValid && !ev.fetchReady && !after.outValid, "a BranchMispredict discards the held packet and refuses the event-cycle input", s"$ev $after"),
        chk(!evEmpty.fetchReady && !afterEmpty.outValid, "no packet is accepted in an event cycle even into an empty stage", s"$evEmpty $afterEmpty"),
        chk(!ar.outValid && !afterAr.outValid, "an ArchRedirect discards it too", s"$ar $afterAr"))
    }
  }

  val all: Seq[SpecTest] = Seq(rv32im, exceptions, extensions, noCompressed, serializing, prediction, packet)
}
