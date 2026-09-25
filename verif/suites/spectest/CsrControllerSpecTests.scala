package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.modules.{CsrController, CsrMapContribution, CsrMapEntry, PublishMux}
import udacore.backend.design.shared._

/** L1 SpecTests for the ADR-019 CsrController (ADR-018; spec 242feaf + ADR-019A..D).
  *
  * The driver plays the DispatchUnit (CSR IssuedUops built from real Zicsr encodings), the
  * PublishMux (result ready), the CommitUnit (CommitGrant), the TrapController (CSRTrapWrite),
  * and the raw interrupt lines. CSR uops are named by program-order sequence numbers s
  * (robTag = {s / robDepth odd, s mod robDepth}).
  */
object CsrControllerSpecTests {

  val p = BackendParams()
  val D = p.robDepth
  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)

  // CSR addresses (v0 map).
  val MSTATUS = 0x300; val MISA = 0x301; val MEDELEG = 0x302; val MIDELEG = 0x303; val MIE = 0x304
  val MTVEC = 0x305; val MCOUNTEREN = 0x306; val MSTATUSH = 0x310; val MSCRATCH = 0x340; val MEPC = 0x341
  val MCAUSE = 0x342; val MTVAL = 0x343; val MIP = 0x344; val MVENDORID = 0xf11; val MHARTID = 0xf14
  val SSTATUS = 0x100; val SIE = 0x104; val STVEC = 0x105; val SCOUNTEREN = 0x106; val SSCRATCH = 0x140
  val SEPC = 0x141; val SCAUSE = 0x142; val STVAL = 0x143; val SIP = 0x144; val SATP = 0x180
  val DCSR = 0x7b0; val DPC = 0x7b1; val DSCRATCH0 = 0x7b2
  val CYCLE = 0xc00

  val ILLEGAL = 2
  val CSRWRITE = 7

  /** A CSR uop from its encoding: f3 = funct3 (1 RW, 2 RS, 3 RC, 5 RWI, 6 RSI, 7 RCI); rs1 is
    * the encoded rs1 field (uimm for the immediate forms); src1 the register operand value. */
  case class Req(csr: Int, f3: Int, rs1: Int = 5, rd: Int = 1, src1: Long = 0, prd: Int = 40,
      sysOp: Option[Int] = None, s: Int = -1) {
    def insn: Long  = (csr.toLong << 20) | (rs1.toLong << 15) | (f3.toLong << 12) | (rd.toLong << 7) | 0x73L
    /** ADR-019D E-3 write intent from the encoding. */
    def intent: Boolean = (f3 & 3) == 1 || rs1 != 0
    def sys: Int = sysOp.getOrElse(if (intent) CSRWRITE else 0)
  }
  def rw(csr: Int, v: Long, rd: Int = 1) = Req(csr, 1, 5, rd, v)
  def rs(csr: Int, v: Long, rs1: Int = 5) = Req(csr, 2, rs1, 1, v)
  def rc(csr: Int, v: Long, rs1: Int = 5) = Req(csr, 3, rs1, 1, v)
  def rwi(csr: Int, u: Int, rd: Int = 1) = Req(csr, 5, u, rd)
  def rsi(csr: Int, u: Int) = Req(csr, 6, u)
  def rci(csr: Int, u: Int) = Req(csr, 7, u)
  def rd(csr: Int) = Req(csr, 2, 0, 1)

  case class Res(s: Int, prd: Int, wen: Boolean, data: Long, exc: Boolean, cause: Int, tval: Long, cfiType: Int)
  case class TW(kind: UInt, xepc: Long = 0, xcause: Long = 0, xtval: Long = 0, mstatus: Long = 0, priv: Int = 3,
      dpc: Long = 0, dcsr: Long = 0)
  case class IC(pending: Boolean, cause: Int, debugMode: Boolean, priv: Int)
  case class TC(mode: Boolean, asid: Int, ppn: Long, priv: Int, dataPriv: Int, sum: Boolean, mxr: Boolean)
  case class Obs(reqReady: Boolean, reqFire: Boolean, res: Option[Res], resFire: Boolean, trapReady: Boolean,
      ic: IC, tc: TC, tr: Map[String, Long])

  val lineNames = Seq("meip", "mtip", "msip", "seip", "stip", "ssip")

  class Drv(val dut: CsrController) {
    val io = dut.io
    var resReady = true
    var lines: Map[String, Boolean] = lineNames.map(_ -> false).toMap
    var next = 0 // next program-order sequence number
    private var near = 0
    private def seqOf(t: (Boolean, Int)): Int = (near - D until near + D).find(s => s >= 0 && tagOf(s) == t).getOrElse(-1)

    def cycle(req: Option[Req] = None, grant: Option[Int] = None, trap: Option[TW] = None): Obs = {
      val q = io.csrReqIn
      q.valid.poke(req.nonEmpty.B)
      req.foreach { r =>
        near = math.max(near, r.s)
        q.bits.robTag.wrap.poke(tagOf(r.s)._1.B); q.bits.robTag.idx.poke(tagOf(r.s)._2.U)
        q.bits.fuType.poke(FuType.Csr); q.bits.op.poke(r.f3.U); q.bits.src1.poke(r.src1.U); q.bits.src2.poke(0.U)
        q.bits.imm.poke(0.U); q.bits.pc.poke(0x100.U); q.bits.prd.poke(r.prd.U); q.bits.hasDest.poke((r.rd != 0).B)
        q.bits.insn.poke(r.insn.U); q.bits.sysOp.poke(r.sys.U)
      }
      io.csrResultOut.ready.poke(resReady.B)
      val g = io.commitGrantIn
      g.valid.poke(grant.nonEmpty.B)
      grant.foreach { s => g.robTag.wrap.poke(tagOf(s)._1.B); g.robTag.idx.poke(tagOf(s)._2.U) }
      val w = io.csrTrapWriteIn
      w.valid.poke(trap.nonEmpty.B)
      trap.foreach { t =>
        w.bits.kind.poke(t.kind); w.bits.xepc.poke(t.xepc.U); w.bits.xcause.poke(t.xcause.U); w.bits.xtval.poke(t.xtval.U)
        w.bits.mstatusNext.poke(t.mstatus.U); w.bits.privNext.poke(t.priv.U); w.bits.dpc.poke(t.dpc.U)
        w.bits.dcsrNext.poke(t.dcsr.U)
      }
      val i = io.interruptIn
      i.meip.poke(lines("meip").B); i.mtip.poke(lines("mtip").B); i.msip.poke(lines("msip").B)
      i.seip.poke(lines("seip").B); i.stip.poke(lines("stip").B); i.ssip.poke(lines("ssip").B)
      def b(x: Bool) = x.peek().litToBoolean
      def l(x: UInt) = x.peek().litValue.toLong
      val r = io.csrResultOut
      val res = if (!b(r.valid)) None else Some(Res(seqOf((b(r.bits.robTag.wrap), l(r.bits.robTag.idx).toInt)),
        l(r.bits.prd).toInt, b(r.bits.wen), l(r.bits.data), b(r.bits.exception.valid), l(r.bits.exception.cause).toInt,
        l(r.bits.exception.tval), l(r.bits.cfiOutcome.cfiType).toInt))
      val c = io.interruptCtrlOut
      val t = io.translationContextOut
      val tr = io.csrTrapReadOut
      val o = Obs(b(q.ready), req.nonEmpty && b(q.ready), res, res.nonEmpty && resReady, b(w.ready),
        IC(b(c.interruptPending), l(c.interruptCause).toInt, b(c.debugMode), l(c.priv).toInt),
        TC(b(t.satpMode), l(t.asid).toInt, l(t.rootPpn), l(t.priv).toInt, l(t.dataPriv).toInt, b(t.sum), b(t.mxr)),
        Map("mstatus" -> l(tr.mstatus), "mepc" -> l(tr.mepc), "mcause" -> l(tr.mcause), "mtval" -> l(tr.mtval),
          "mtvec" -> l(tr.mtvec), "medeleg" -> l(tr.medeleg), "mideleg" -> l(tr.mideleg), "mie" -> l(tr.mie),
          "mip" -> l(tr.mip), "sepc" -> l(tr.sepc), "scause" -> l(tr.scause), "stval" -> l(tr.stval),
          "stvec" -> l(tr.stvec), "priv" -> l(tr.priv), "dpc" -> l(tr.dpc), "dcsr" -> l(tr.dcsr)))
      dut.clock.step()
      o
    }
    def run(n: Int): Seq[Obs] = (0 until n).map(_ => cycle())
    def peek(): Obs = cycle()

    /** Present `r` until accepted, drain its result, then grant it when `grant` and legal.
      * Returns the result and every observation from the presentation on. */
    def exec(r0: Req, grant: Boolean = true, stall: Int = 0): (Res, Seq[Obs]) = {
      val r = r0.copy(s = next); next += 1
      var os = Seq(cycle(Some(r)))
      var k = 0
      while (!os.last.reqFire && k < 20) { os :+= cycle(Some(r)); k += 1 }
      require(os.last.reqFire, s"CSR request $r was never accepted")
      k = 0
      val keep = resReady
      while (!os.last.resFire && k < 20) { resReady = keep && k >= stall; os :+= cycle(); k += 1 }
      resReady = keep
      val res = os.last.res.getOrElse(sys.error(s"no CSR result for $r"))
      if (grant && !res.exc) os :+= cycle(grant = Some(r.s))
      (res, os)
    }
    def read(csr: Int): Long = { val (x, _) = exec(rd(csr)); require(!x.exc, f"read of 0x$csr%x trapped"); x.data }
    def write(csr: Int, v: Long): Res = exec(rw(csr, v))._1
    def trap(t: TW): Obs = cycle(trap = Some(t))
    /** Switch the committed privilege through the single trap-write path (MRet kind). */
    def setPriv(pv: Int): Obs = { val ms = peek().tr("mstatus"); trap(TW(TrapWriteKind.MRet, mstatus = ms, priv = pv)) }
  }

  def withDrv(t: SpecTest, contribs: Seq[CsrMapContribution] = Nil)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new CsrController(p, contribs)) { dut => val d = new Drv(dut); d.cycle(); body(d) }

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  def hx(v: Long): String = f"0x$v%x"

  def mustAssert(t: SpecTest, label: String, expect: String, contribs: Seq[CsrMapContribution] = Nil)(body: Drv => Unit): TCheck = {
    var reachedEnd = false
    try {
      withDrv(t, contribs) { d => body(d); d.run(3); reachedEnd = true; Nil }
      TCheck(false, label, "simulation ended normally: the design assertion did not fire")
    } catch {
      case e: NotImplementedError => throw e
      case e: Throwable =>
        val log   = chisel3.simulator.CachedSimulator.lastSimulationLog
        val fired = log.linesIterator.filter(_.contains("Assertion failed")).toSeq
        val ok    = !reachedEnd && fired.exists(_.contains(expect))
        val why   = if (fired.isEmpty) s"no assertion in the simulation log (${e.getClass.getSimpleName})"
                    else fired.head.trim.take(200)
        TCheck(ok, label, if (ok) "" else s"expected '$expect'; got: $why")
    }
  }

  // ---- funcCsrOpSemantics / funcCsrExecuteAtCommit ---------------------------------------------

  val ops = new SpecTest("csr.ops", Seq("funcCsrOpSemantics", "funcCsrExecuteAtCommit")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val w  = d.exec(rw(MSCRATCH, 0x1234))._1;      val v0 = d.read(MSCRATCH)
      val s  = d.exec(rs(MSCRATCH, 0xf0))._1;        val v1 = d.read(MSCRATCH)
      val c  = d.exec(rc(MSCRATCH, 0x1004))._1;      val v2 = d.read(MSCRATCH)
      val wi = d.exec(rwi(MSCRATCH, 0x15))._1;       val v3 = d.read(MSCRATCH)
      val si = d.exec(rsi(MSCRATCH, 0x0a))._1;       val v4 = d.read(MSCRATCH)
      val ci = d.exec(rci(MSCRATCH, 0x03))._1;       val v5 = d.read(MSCRATCH)
      // The register forms use src1 (not the rs1 field); the immediate forms use zext(uimm), not src1.
      d.exec(rs(MSCRATCH, 0x100, rs1 = 7));           val v6 = d.read(MSCRATCH)
      d.exec(Req(MSCRATCH, 6, rs1 = 0x01, src1 = 0xffff0000L)); val v7 = d.read(MSCRATCH)
      Seq(
        chk(w.data == 0 && w.wen && !w.exc && v0 == 0x1234, "CSRRW writes src1 and returns the old value", s"$w ${hx(v0)}"),
        chk(s.data == 0x1234 && v1 == 0x12f4, "CSRRS sets the src1 bits", s"$s ${hx(v1)}"),
        chk(c.data == 0x12f4 && v2 == 0x02f0, "CSRRC clears the src1 bits", s"$c ${hx(v2)}"),
        chk(wi.data == 0x02f0 && v3 == 0x15, "CSRRWI writes zext(uimm)", s"$wi ${hx(v3)}"),
        chk(si.data == 0x15 && v4 == 0x1f, "CSRRSI sets the uimm bits", s"$si ${hx(v4)}"),
        chk(ci.data == 0x1f && v5 == 0x1c, "CSRRCI clears the uimm bits", s"$ci ${hx(v5)}"),
        chk(v6 == 0x11c, "a register form takes its operand from src1, not the rs1 field", hx(v6)),
        chk(v7 == 0x11d, "an immediate form takes zext(insn[19:15]), not src1", hx(v7)),
        chk(Seq(w, s, c, wi, si, ci).forall(_.cfiType == 0), "every CSR result carries cfiOutcome None", "")
      )
    }
  }

  // ---- write-intent boundaries (ADR-019D E-2/E-3) -------------------------------------------------

  val boundaries = new SpecTest("csr.readOnlyBoundaries", Seq("funcCsrOpSemantics", "funcCsrAccessCheck")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      def legal(r: Req) = !d.exec(r)._1.exc
      // On a read-only CSR a true read is legal and every write form is illegal.
      val cases = Seq(
        ("CSRRS rs1=x0", rs(MVENDORID, 0, rs1 = 0), true),
        ("CSRRS rs1!=x0 holding 0", rs(MVENDORID, 0, rs1 = 5), false),
        ("CSRRC rs1=x0", rc(MVENDORID, 0, rs1 = 0), true),
        ("CSRRC rs1!=x0 holding 0", rc(MVENDORID, 0, rs1 = 5), false),
        ("CSRRSI uimm=0", rsi(MHARTID, 0), true),
        ("CSRRSI uimm=1", rsi(MHARTID, 1), false),
        ("CSRRCI uimm=0", rci(MHARTID, 0), true),
        ("CSRRCI uimm=1", rci(MHARTID, 1), false),
        ("CSRRW rs1=x0", Req(MVENDORID, 1, rs1 = 0), false),
        ("CSRRWI uimm=0", rwi(MVENDORID, 0), false)
      )
      val ro = cases.map { case (n, r, want) => chk(legal(r) == want, s"$n on a read-only CSR is ${if (want) "legal" else "illegal"}", "") }
      // On a writable CSR the read-only form stages nothing (the next uop is accepted at once);
      // a write whose operand is zero at run time is still a staged write (held until its grant).
      d.write(MSCRATCH, 0x55)
      val (_, osRead) = d.exec(rs(MSCRATCH, 0, rs1 = 0), grant = false)
      val readyAfterRead = d.peek().reqReady
      val (_, osZero) = d.exec(rs(MSCRATCH, 0, rs1 = 5), grant = false)
      val stagedAfterZero = d.run(2).map(_.reqReady)
      d.cycle(grant = Some(d.next - 1))
      val readyAfterGrant = d.peek().reqReady
      ro ++ Seq(
        chk(readyAfterRead, "a read-only CSR access stages nothing (no commit wait)", ""),
        chk(stagedAfterZero.forall(!_) && readyAfterGrant,
          "CSRRS with rs1 != x0 holding 0 is a write: staged until its CommitGrant", s"$stagedAfterZero $readyAfterGrant")
      )
    }
  }

  val rdX0 = new SpecTest("csr.rdX0", Seq("funcCsrExecuteAtCommit")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val a = d.exec(rw(MSCRATCH, 0x77, rd = 0))._1; val va = d.read(MSCRATCH)
      val b = d.exec(rwi(MSCRATCH, 9, rd = 0))._1;   val vb = d.read(MSCRATCH)
      Seq(
        chk(!a.wen && !a.exc && va == 0x77, "CSRRW rd=x0: no register write (wen = 0), the CSR is still written", s"$a ${hx(va)}"),
        chk(!b.wen && !b.exc && vb == 9, "CSRRWI rd=x0: no register write, the CSR is still written", s"$b ${hx(vb)}")
      )
    }
  }

  // ---- metadata: robTag / prd / wen survive request -> FuResult ----------------------------------

  val metadata = new SpecTest("csr.metadata", Seq("funcCsrExecuteAtCommit")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val rnd = new scala.util.Random(0x0d)
      var bad = Seq.empty[String]
      var n = 0
      for (_ <- 0 until 3 * D) {
        val rdSel = if (rnd.nextInt(4) == 0) 0 else 1 + rnd.nextInt(31)
        val prd   = 1 + rnd.nextInt(p.tuning.integerPrfEntries - 1)
        val (r, legalWant) = rnd.nextInt(4) match {
          case 0 => (Req(MSCRATCH, 1, 5, rdSel, rnd.nextInt(1 << 20)), true)
          case 1 => (Req(SSCRATCH, 2, 0, rdSel), true)
          case 2 => (Req(MHARTID, 1, 5, rdSel, 1), false)
          case _ => (Req(CYCLE, 2, 0, rdSel), false)
        }
        val s = d.next
        val (x, os) = d.exec(r.copy(prd = prd), stall = rnd.nextInt(3))
        n += os.count(_.resFire)
        if (x.s != s) bad :+= s"s$s: result robTag names s${x.s}"
        if (x.prd != prd) bad :+= s"s$s: prd ${x.prd} vs $prd"
        if (x.wen != (rdSel != 0 && legalWant)) bad :+= s"s$s: wen ${x.wen} (rd $rdSel legal $legalWant)"
        if (x.exc == legalWant) bad :+= s"s$s: exception ${x.exc}"
      }
      Seq(
        chk(bad.isEmpty, "robTag, prd, and wen = hasDest && legal survive request -> FuResult (across a tag wrap)", bad.take(3).mkString("; ")),
        chk(n == 3 * D, "exactly one result per accepted CSR uop; no CSR result loses its robTag", s"$n")
      )
    }
  }

  // ---- funcCsrAccessCheck -----------------------------------------------------------------------

  val access = new SpecTest("csr.accessCheck", Seq("funcCsrAccessCheck")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      def ill(x: Res, r: Req) = x.exc && x.cause == ILLEGAL && x.tval == r.insn && !x.wen
      val nx = Req(CYCLE, 2, 0); val nxr = d.exec(nx)._1
      val nx2 = Req(0x7c0, 1, 5, src1 = 1); val nx2r = d.exec(nx2)._1
      val readyAfterIll = d.peek().reqReady
      val roW = rw(MHARTID, 1); val roWr = d.exec(roW)._1
      val misaW = d.exec(rw(MISA, 0))._1
      val dbg = rd(DCSR); val dbgr = d.exec(dbg)._1
      // Privilege: S may not touch M CSRs; U may not touch S CSRs.
      d.setPriv(1)
      val sM = rd(MSCRATCH); val sMr = d.exec(sM)._1
      val sS = d.exec(rd(SSCRATCH))._1
      d.setPriv(0)
      val uS = rd(SSCRATCH); val uSr = d.exec(uS)._1
      d.setPriv(3)
      // satp + TVM: illegal from S with TVM set, legal from M, legal from S once TVM clears.
      d.exec(rs(MSTATUS, 1L << 20))
      d.setPriv(1)
      val tv = rd(SATP); val tvr = d.exec(tv)._1
      val tvw = rw(SATP, 0); val tvwr = d.exec(tvw)._1
      d.setPriv(3)
      val mSatp = d.exec(rd(SATP))._1
      d.exec(rc(MSTATUS, 1L << 20)); d.setPriv(1)
      val sSatp = d.exec(rd(SATP))._1
      Seq(
        chk(ill(nxr, nx) && ill(nx2r, nx2), "a nonexistent CSR (cycle without Zicntr, 0x7c0) is illegal: cause 2, tval = insn, wen = 0", s"$nxr $nx2r"),
        chk(readyAfterIll, "an illegal access stages nothing", ""),
        chk(ill(roWr, roW) && !misaW.exc, "a write to a read-only CSR is illegal; misa (WARL, read-only content) accepts writes", s"$roWr $misaW"),
        chk(ill(dbgr, dbg), "a debug CSR outside debug mode is illegal", s"$dbgr"),
        chk(ill(sMr, sM) && !sS.exc, "S-mode: an M CSR is illegal, an S CSR is legal", s"$sMr $sS"),
        chk(ill(uSr, uS), "U-mode: an S CSR is illegal", s"$uSr"),
        chk(ill(tvr, tv) && ill(tvwr, tvw), "S-mode satp access with mstatus.TVM set is illegal (read or write)", s"$tvr $tvwr"),
        chk(!mSatp.exc && !sSatp.exc, "satp is legal from M with TVM, and from S without TVM", s"$mSatp $sSatp")
      )
    }
  }

  // ---- result backpressure --------------------------------------------------------------------------

  val backpressure = new SpecTest("csr.backpressure", Seq("funcCsrExecuteAtCommit")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.write(MSCRATCH, 0xabc)
      d.resReady = false
      val r = rd(MSCRATCH).copy(s = d.next, prd = 44); d.next += 1
      val acc = d.cycle(Some(r))
      val held = d.run(4)
      val second = d.cycle(Some(rd(SSCRATCH).copy(s = d.next)))
      d.resReady = true
      val fire = d.cycle()
      val after = d.run(2)
      val first = held.head.res
      Seq(
        chk(acc.reqFire && held.forall(o => o.res.nonEmpty && o.res == first && !o.resFire) && first.exists(x => x.data == 0xabc && x.prd == 44),
          "an unready result is held with a stable payload", s"$acc $held"),
        chk(!second.reqReady && held.forall(!_.reqReady), "no second CSR uop is accepted while the result is held", s"$second"),
        chk(fire.resFire && fire.res == first && after.forall(_.res.isEmpty), "the held result drains exactly once", s"$fire $after")
      )
    }
  }

  // ---- propNoSpeculativeCsrWrite: commit gating --------------------------------------------------

  val gating = new SpecTest("csr.commitGating", Seq("propNoSpeculativeCsrWrite", "funcCsrExecuteAtCommit")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // Publication never mutates state; the matching grant applies the staged write.
      val (_, os) = d.exec(rw(MEPC, 0x400), grant = false)
      val s0 = d.next - 1
      val pre = d.run(3)
      val g = d.cycle(grant = Some(s0))
      val post = d.peek()
      // Exactly once: after a trap write changes mepc, a duplicate grant of the same tag writes nothing.
      d.trap(TW(TrapWriteKind.TrapEntryM, xepc = 0x800, mstatus = 0x1800, priv = 3))
      d.cycle(grant = Some(s0))
      val dup = d.peek().tr("mepc")
      // A read-only CSR uop's grant writes nothing.
      d.exec(rs(MEPC, 0, rs1 = 0)); val roGrant = d.peek().tr("mepc")
      // An illegal access stages nothing: even a grant naming it writes nothing.
      d.exec(rw(MHARTID, 5), grant = false); d.cycle(grant = Some(d.next - 1))
      val illReady = d.peek().reqReady
      // TranslationContext changes only at the grant of a satp write.
      val sv = (1L << 31) | (5L << 22) | 0x1234L
      d.exec(rw(SATP, sv), grant = false)
      val tcPre = d.run(2).map(_.tc)
      d.cycle(grant = Some(d.next - 1))
      val tcPost = d.peek().tc
      Seq(
        chk((os ++ pre).forall(_.tr("mepc") == 0) && os.exists(_.resFire), "result publication does not mutate CSR state", ""),
        chk(post.tr("mepc") == 0x400, "the matching CommitGrant applies the staged write", s"${post.tr("mepc")}"),
        chk(dup == 0x800, "the staged write applies exactly once (a repeated grant writes nothing)", hx(dup)),
        chk(roGrant == 0x800, "a read-only CSR CommitGrant writes nothing", hx(roGrant)),
        chk(illReady && d.read(MHARTID) == 0, "an illegal CSR creates no staged write", ""),
        chk(tcPre.forall(!_.mode) && tcPost.mode && tcPost.asid == 5 && tcPost.ppn == 0x1234,
          "a satp CsrWrite changes TranslationContext only at its commit", s"$tcPre $tcPost")
      )
    } :+ mustAssert(this, "a CommitGrant naming another robTag while a write is staged asserts",
      "CsrExecuteAtCommit: CommitGrant does not name the staged write") { d =>
      d.exec(rw(MSCRATCH, 1), grant = false)
      d.cycle(grant = Some(d.next))
    }
  }

  // ---- propCsrWriteIntent ----------------------------------------------------------------------------

  val writeIntent = new SpecTest("csr.writeIntent", Seq("propCsrWriteIntent")) {
    def run(): Seq[TCheck] = Seq(
      mustAssert(this, "CSRRS rs1 != x0 presented with sysOp None asserts", "CsrWriteIntent") { d =>
        d.exec(rs(MSCRATCH, 0, rs1 = 5).copy(sysOp = Some(0)), grant = false) },
      mustAssert(this, "CSRRW presented with sysOp None asserts", "CsrWriteIntent") { d =>
        d.exec(Req(MSCRATCH, 1, rs1 = 0).copy(sysOp = Some(0)), grant = false) },
      mustAssert(this, "CSRRSI uimm = 0 presented with sysOp CsrWrite asserts", "CsrWriteIntent") { d =>
        d.exec(rsi(MSCRATCH, 0).copy(sysOp = Some(CSRWRITE)), grant = false) },
      mustAssert(this, "CSRRC rs1 = x0 presented with sysOp CsrWrite asserts", "CsrWriteIntent") { d =>
        d.exec(rc(MSCRATCH, 0, rs1 = 0).copy(sysOp = Some(CSRWRITE)), grant = false) }
    )
  }

  // ---- propCsrSingleOwner ----------------------------------------------------------------------------

  val singleOwner = new SpecTest("csr.singleOwner", Seq("propCsrSingleOwner")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // The two paths in separate cycles: each change is attributable to exactly one of them.
      d.exec(rw(MSCRATCH, 3), grant = false)
      val s0 = d.next - 1
      val before = d.peek()
      val tw = d.trap(TW(TrapWriteKind.TrapEntryM, xepc = 0x10, xcause = 2, mstatus = 0x1800, priv = 3))
      val afterTrap = d.peek()
      d.cycle(grant = Some(s0))
      val afterGrant = d.read(MSCRATCH)
      Seq(
        chk(tw.trapReady && afterTrap.tr("mepc") == 0x10 && before.tr("mepc") != 0x10, "CSRTrapWrite.fire is a mutation path", ""),
        chk(afterGrant == 3, "a staged write survives an intervening trap write and applies at its grant", hx(afterGrant))
      )
    } :+ mustAssert(this, "a software write applied in the cycle of a CSRTrapWrite asserts", "CsrSingleOwner") { d =>
      d.exec(rw(MSCRATCH, 3), grant = false)
      d.cycle(grant = Some(d.next - 1), trap = Some(TW(TrapWriteKind.TrapEntryM, mstatus = 0x1800, priv = 3)))
    }
  }

  // ---- funcSupervisorCsrs ----------------------------------------------------------------------------

  val supervisor = new SpecTest("csr.supervisor", Seq("funcSupervisorCsrs")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val all = 0xffffffffL
      d.write(MSTATUS, all); val ms = d.read(MSTATUS); val ss = d.read(SSTATUS)
      d.write(SSTATUS, 0); val ms2 = d.read(MSTATUS)
      d.write(MSTATUS, 0); d.write(MSTATUS, 2L << 11); val mpp2 = (d.read(MSTATUS) >> 11) & 3
      d.write(MSTATUS, 1L << 11); d.write(MSTATUS, 2L << 11); val mpp2b = (d.read(MSTATUS) >> 11) & 3
      d.write(MIDELEG, all); val mid = d.read(MIDELEG)
      d.write(MEDELEG, all); val med = d.read(MEDELEG)
      d.write(MIE, all); val mie = d.read(MIE); val sie = d.read(SIE)
      d.write(SIE, 0); val mie2 = d.read(MIE)
      d.write(SIP, 2); val mip = d.read(MIP); val sip = d.read(SIP)
      d.write(MTVEC, 0x1003); d.write(STVEC, 0x2002); val mtv = d.read(MTVEC); val stv = d.read(STVEC)
      d.write(MEPC, 0x1003); d.write(SEPC, 0x2002); val mep = d.read(MEPC); val sep = d.read(SEPC)
      d.write(MISA, 0); val misa = d.read(MISA)
      d.write(MCOUNTEREN, all); d.write(SCOUNTEREN, all)
      val cnt = (d.read(MCOUNTEREN), d.read(SCOUNTEREN), d.read(MSTATUSH))
      Seq(
        chk(ms == 0x007e19aaL, "mstatus keeps only the v0 WARL fields (SIE MIE SPIE MPIE SPP MPP MPRV SUM MXR TVM TW TSR)", hx(ms)),
        chk(ss == 0x000c0122L, "sstatus is the restricted view (SIE SPIE SPP SUM MXR)", hx(ss)),
        chk(ms2 == 0x00721888L, "an sstatus write changes only the S fields of mstatus", hx(ms2)),
        chk(mpp2 == 0 && mpp2b == 1, "MPP is WARL: the reserved value 2 keeps the previous MPP", s"$mpp2 $mpp2b"),
        chk(mid == 0x222 && med == 0xb3ff, "mideleg delegates only SSI/STI/SEI; medeleg only delegable causes", s"${hx(mid)} ${hx(med)}"),
        chk(mie == 0xaaa && sie == 0x222 && mie2 == 0x888, "sie is mie restricted to mideleg; an sie write touches only those bits", s"${hx(mie)} ${hx(sie)} ${hx(mie2)}"),
        chk((mip & 2) == 2 && sip == 2, "sip exposes the software SSIP bit through mideleg", s"${hx(mip)} ${hx(sip)}"),
        chk(mtv == 0x1001 && stv == 0x2000, "xtvec keeps a legal MODE (0 or 1) and a 4-byte aligned base", s"${hx(mtv)} ${hx(stv)}"),
        chk(mep == 0x1000 && sep == 0x2000, "xepc low two bits read zero (no RVC)", s"${hx(mep)} ${hx(sep)}"),
        chk(misa == 0x40141100L, "misa reads RV32 IMSU and ignores writes", hx(misa)),
        chk(cnt == ((0L, 0L, 0L)), "mcounteren/scounteren (no counters) and mstatush read zero", s"$cnt"),
        chk(d.read(MHARTID) == p.hartId && d.read(MVENDORID) == 0, "mhartid reads the hart id; mvendorid reads zero", "")
      )
    }
  }

  // ---- funcInterruptCtrlPublish -----------------------------------------------------------------------

  val interrupts = new SpecTest("csr.interruptView", Seq("funcInterruptCtrlPublish")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val bit = Map("meip" -> 11, "mtip" -> 7, "msip" -> 3, "seip" -> 9, "stip" -> 5, "ssip" -> 1)
      val raw = lineNames.map { n =>
        d.lines = lineNames.map(_ -> false).toMap.updated(n, true)
        n -> d.peek().tr("mip")
      }
      d.lines = lineNames.map(_ -> false).toMap
      d.write(MIP, 1L << 9); val soft = d.peek().tr("mip"); d.write(MIP, 0)
      // M-mode, MIE clear: nothing is takeable.
      d.write(MIE, 0xaaa)
      d.lines = d.lines.updated("mtip", true)
      val offMie = d.peek().ic
      d.exec(rs(MSTATUS, 1L << 3))
      val onMie = d.peek().ic
      d.lines = d.lines ++ Map("meip" -> true, "msip" -> true)
      val prioAll = d.peek().ic
      d.lines = d.lines.updated("meip", false)
      val prioMsi = d.peek().ic
      // Delegated STI: not taken in M; taken in S only with SIE; always in U.
      d.lines = lineNames.map(_ -> false).toMap.updated("stip", true)
      d.write(MIDELEG, 0x222)
      val stiM = d.peek().ic
      d.setPriv(1); val stiS0 = d.peek().ic
      d.exec(rs(SSTATUS, 1L << 1)); val stiS1 = d.peek().ic
      d.exec(rc(SSTATUS, 1L << 1)); d.setPriv(0); val stiU = d.peek().ic
      // M-destined before S-destined: SEI delegated vs MTI (not delegated) in S-mode.
      d.setPriv(1); d.exec(rs(SSTATUS, 1L << 1))
      d.lines = lineNames.map(_ -> false).toMap ++ Map("seip" -> true, "mtip" -> true)
      val order = d.peek().ic
      // Same with the S-destined cause nominally higher: SEI delegated vs STI kept in M.
      d.setPriv(3); d.write(MIDELEG, 1L << 9); d.setPriv(1)
      d.lines = lineNames.map(_ -> false).toMap ++ Map("seip" -> true, "stip" -> true)
      val order2 = d.peek().ic
      d.trap(TW(TrapWriteKind.DebugEntry, dpc = 0x10, dcsr = 1, priv = 3)); val dbg = d.peek().ic
      Seq(
        chk(raw.forall { case (n, v) => v == (1L << bit(n)) }, "each raw line appears as its mip bit (MEIP MTIP MSIP SEIP STIP SSIP)",
          raw.map { case (n, v) => s"$n=${hx(v)}" }.mkString(" ")),
        chk(soft == (1L << 9), "the software SEIP bit is ORed into the mip view", hx(soft)),
        chk(!offMie.pending && onMie.pending && onMie.cause == 7, "M-mode takes an M interrupt only with MIE", s"$offMie $onMie"),
        chk(prioAll.cause == 11 && prioMsi.cause == 3, "priority MEI > MSI > MTI", s"$prioAll $prioMsi"),
        chk(!stiM.pending && !stiS0.pending && stiS1.pending && stiS1.cause == 5 && stiU.pending && stiU.cause == 5,
          "a delegated interrupt: never in M, in S only with SIE, always in U", s"$stiM $stiS0 $stiS1 $stiU"),
        chk(order.pending && order.cause == 7 && order2.pending && order2.cause == 5,
          "an M-destined interrupt is taken before an S-destined one, whatever their nominal order", s"$order $order2"),
        chk(dbg.debugMode && dbg.priv == 3 && onMie.priv == 3 && stiU.priv == 0, "debugMode and priv are the committed state", s"$dbg")
      )
    }
  }

  // ---- funcCsrTrapWriteApply ---------------------------------------------------------------------------

  val trapWrite = new SpecTest("csr.trapWrite", Seq("funcCsrTrapWriteApply")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val m = d.trap(TW(TrapWriteKind.TrapEntryM, xepc = 0x2000, xcause = 0x8000000bL, xtval = 0x55, mstatus = 0x1880, priv = 3))
      val am = d.peek()
      val s = d.trap(TW(TrapWriteKind.TrapEntryS, xepc = 0x3000, xcause = 13, xtval = 0x66, mstatus = 0x120, priv = 1))
      val as = d.peek()
      d.trap(TW(TrapWriteKind.MRet, xepc = 0x9999, mstatus = 0x80, priv = 0)); val mr = d.peek()
      d.trap(TW(TrapWriteKind.SRet, mstatus = 0x20, priv = 1)); val sr = d.peek()
      d.trap(TW(TrapWriteKind.DebugEntry, dpc = 0x4000, dcsr = (3 << 6) | 1, priv = 3)); val de = d.peek()
      val dcsrRead = d.exec(rd(DCSR))._1; val dpcRead = d.exec(rd(DPC))._1
      d.trap(TW(TrapWriteKind.DRet, priv = 1)); val dr = d.peek()
      val dcsrAfter = d.exec(rd(DCSR))._1
      // TranslationContext after trap/xRET: MPRV with MPP = S gives dataPriv S in M-mode.
      d.trap(TW(TrapWriteKind.TrapEntryM, xepc = 0x10, mstatus = (1L << 17) | (1L << 11), priv = 3)); val tm = d.peek().tc
      d.trap(TW(TrapWriteKind.MRet, mstatus = 0, priv = 0)); val tu = d.peek().tc
      Seq(
        chk(m.trapReady && s.trapReady, "CSRTrapWriteIn is always ready", ""),
        chk(am.tr("mepc") == 0x2000 && am.tr("mcause") == 0x8000000bL && am.tr("mtval") == 0x55 && am.tr("mstatus") == 0x1880 &&
          am.tr("priv") == 3 && am.tr("sepc") == 0, "TrapEntryM writes mepc/mcause/mtval, mstatus, priv only", s"${am.tr}"),
        chk(as.tr("sepc") == 0x3000 && as.tr("scause") == 13 && as.tr("stval") == 0x66 && as.tr("mstatus") == 0x120 &&
          as.tr("priv") == 1 && as.tr("mepc") == 0x2000 && as.tr("mcause") == 0x8000000bL,
          "TrapEntryS writes sepc/scause/stval, mstatus, priv; the M trap registers are untouched", s"${as.tr}"),
        chk(mr.tr("mstatus") == 0x80 && mr.tr("priv") == 0 && mr.tr("mepc") == 0x2000, "MRet writes mstatus and priv only", s"${mr.tr}"),
        chk(sr.tr("mstatus") == 0x20 && sr.tr("priv") == 1 && sr.tr("sepc") == 0x3000, "SRet writes mstatus and priv only", s"${sr.tr}"),
        chk(de.ic.debugMode && de.tr("dpc") == 0x4000 && (de.tr("dcsr") & 0x1ff) == ((3 << 6) | 1) && (de.tr("dcsr") >>> 28) == 4 &&
          de.tr("priv") == 3, "DebugEntry writes dpc, dcsr (xdebugver kept), priv, and enters debug mode", s"${de.tr} ${de.ic}"),
        chk(!dcsrRead.exc && !dpcRead.exc && dpcRead.data == 0x4000, "debug CSRs are accessible in debug mode", s"$dcsrRead $dpcRead"),
        chk(!dr.ic.debugMode && dr.tr("priv") == 1 && dcsrAfter.exc, "DRet leaves debug mode and restores priv", s"${dr.ic} $dcsrAfter"),
        chk(tm.priv == 3 && tm.dataPriv == 1 && tu.priv == 0 && tu.dataPriv == 0,
          "TranslationContext follows trap/xRET: dataPriv = MPRV ? MPP : priv", s"$tm $tu")
      )
    }
  }

  // ---- funcTranslationContextPublish ----------------------------------------------------------------------

  val tctx = new SpecTest("csr.translationContext", Seq("funcTranslationContextPublish")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val t0 = d.peek().tc
      d.write(SATP, (1L << 31) | (0x1ffL << 22) | 0x3fffffL); val t1 = d.peek().tc
      d.write(MSTATUS, (1L << 18) | (1L << 19)); val t2 = d.peek().tc
      d.write(MSTATUS, (1L << 17) | (0L << 11)); val t3 = d.peek().tc
      d.write(MSTATUS, 0); d.setPriv(1); val t4 = d.peek().tc
      Seq(
        chk(!t0.mode && t0.priv == 3 && t0.dataPriv == 3, "reset: Bare, M-mode", s"$t0"),
        chk(t1.mode && t1.asid == 0x1ff && t1.ppn == 0x3fffff, "satp MODE/ASID/PPN are published", s"$t1"),
        chk(t2.sum && t2.mxr, "SUM and MXR are published", s"$t2"),
        chk(t3.priv == 3 && t3.dataPriv == 0, "MPRV selects MPP as the data privilege", s"$t3"),
        chk(t4.priv == 1 && t4.dataPriv == 1 && !t4.sum, "priv follows the committed privilege", s"$t4")
      )
    }
  }

  // ---- CSR result through PublishMux ---------------------------------------------------------------------

  /** CsrController + PublishMux wired like BackendTop (other result inputs idle). */
  class PublishHarness extends Module {
    val io = IO(new Bundle {
      val req   = Flipped(Decoupled(new IssuedUop(p)))
      val grant = Input(new CommitGrant(p))
      val prfW  = Valid(new PhysicalRegWrite(p))
      val wake  = Output(new WakeupBroadcast(p))
      val robC  = Valid(new RobCompletion(p))
    })
    val csr = Module(new CsrController(p)); val pm = Module(new PublishMux(p))
    csr.io.csrReqIn <> io.req
    csr.io.commitGrantIn := io.grant
    csr.io.csrTrapWriteIn.valid := false.B; csr.io.csrTrapWriteIn.bits := 0.U.asTypeOf(csr.io.csrTrapWriteIn.bits)
    csr.io.interruptIn := 0.U.asTypeOf(csr.io.interruptIn)
    pm.io.csrResultIn <> csr.io.csrResultOut
    Seq(pm.io.aluResultIn, pm.io.multiplierResultIn, pm.io.dividerResultIn, pm.io.branchResultIn).foreach { x =>
      x.valid := false.B; x.bits := 0.U.asTypeOf(x.bits) }
    pm.io.bitAluResultIn.foreach { x => x.valid := false.B; x.bits := 0.U.asTypeOf(x.bits) }
    pm.io.memResultIn.valid := false.B; pm.io.memResultIn.bits := 0.U.asTypeOf(pm.io.memResultIn.bits)
    pm.io.physicalRegWriteOut.ready := true.B; pm.io.robCompletionOut.ready := true.B
    io.prfW.valid := pm.io.physicalRegWriteOut.fire; io.prfW.bits := pm.io.physicalRegWriteOut.bits
    io.wake := pm.io.wakeupBroadcastOut
    io.robC.valid := pm.io.robCompletionOut.fire; io.robC.bits := pm.io.robCompletionOut.bits
  }

  val publish = new SpecTest("csr.publish", Seq("funcCsrExecuteAtCommit", "funcPublishArbitrate")) {
    def run(): Seq[TCheck] = sim(new PublishHarness) { h =>
      def present(r: Req): Unit = {
        val q = h.io.req
        q.valid.poke(true.B); q.bits.robTag.wrap.poke(tagOf(r.s)._1.B); q.bits.robTag.idx.poke(tagOf(r.s)._2.U)
        q.bits.fuType.poke(FuType.Csr); q.bits.op.poke(r.f3.U); q.bits.src1.poke(r.src1.U); q.bits.prd.poke(r.prd.U)
        q.bits.hasDest.poke((r.rd != 0).B); q.bits.insn.poke(r.insn.U); q.bits.sysOp.poke(r.sys.U)
      }
      case class P(prf: Option[(Int, Long)], wake: Option[Int], rob: Option[(Int, Boolean)])
      def step(): P = {
        val o = P(
          if (h.io.prfW.valid.peek().litToBoolean) Some((h.io.prfW.bits.prd.peek().litValue.toInt, h.io.prfW.bits.data.peek().litValue.toLong)) else None,
          if (h.io.wake.valid.peek().litToBoolean) Some(h.io.wake.prd.peek().litValue.toInt) else None,
          if (h.io.robC.valid.peek().litToBoolean) Some((h.io.robC.bits.robTag.idx.peek().litValue.toInt,
            h.io.robC.bits.exception.valid.peek().litToBoolean)) else None)
        h.clock.step(); o
      }
      h.io.grant.valid.poke(false.B)
      // CSRRW mscratch <- 0x99 (old value 0) to prd 45, then commit it.
      present(rw(MSCRATCH, 0x99).copy(s = 3, prd = 45)); val a0 = step(); h.io.req.valid.poke(false.B)
      val a = (0 until 3).map(_ => step())
      h.io.grant.valid.poke(true.B); h.io.grant.robTag.wrap.poke(false.B); h.io.grant.robTag.idx.poke(3.U); step()
      h.io.grant.valid.poke(false.B)
      // CSRRS mscratch read into prd 46 returns 0x99.
      present(rd(MSCRATCH).copy(s = 4, prd = 46)); step(); h.io.req.valid.poke(false.B)
      val b = (0 until 3).map(_ => step())
      // An illegal access completes its ROB entry with an exception and writes no register.
      present(rw(MHARTID, 1).copy(s = 5, prd = 47)); step(); h.io.req.valid.poke(false.B)
      val c = (0 until 3).map(_ => step())
      val all = Seq(a0) ++ a
      Seq(
        chk(all.exists(o => o.prf.contains((45, 0L)) && o.wake.contains(45) && o.rob.contains((3, false))) && all.count(_.rob.nonEmpty) == 1,
          "a CSR result through PublishMux writes the PRF, wakes its prd, and completes its ROB entry once", s"$all"),
        chk(b.exists(o => o.prf.contains((46, 0x99L)) && o.rob.contains((4, false))), "the committed CSR value is read back through the PRF", s"$b"),
        chk(c.exists(o => o.rob.contains((5, true)) && o.prf.isEmpty && o.wake.isEmpty) && c.forall(_.prf.isEmpty),
          "an illegal CSR access completes with an exception and no register write", s"$c")
      )
    }
  }

  // ---- funcDecodePrivViewPublish (ADR-019E E-4) -------------------------------------------------------

  val decodePrivView = new SpecTest("csr.decodePrivView", Seq("funcDecodePrivViewPublish")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      def v(): (Int, Boolean, Boolean, Boolean, Boolean) = {
        val x = d.io.decodePrivViewOut
        val o = (x.priv.peek().litValue.toInt, x.debugMode.peek().litToBoolean, x.tvm.peek().litToBoolean,
          x.tw.peek().litToBoolean, x.tsr.peek().litToBoolean)
        d.cycle(); o
      }
      val v0 = v()
      d.write(MSTATUS, 1L << 20); val tvm = v()
      d.write(MSTATUS, 1L << 21); val tw = v()
      d.write(MSTATUS, 1L << 22); val tsr = v()
      // Staged (uncommitted) write: the view does not move before the grant.
      d.exec(rw(MSTATUS, 0), grant = false); val staged = v(); d.cycle(grant = Some(d.next - 1)); val cleared = v()
      d.setPriv(1); val s = v()
      d.trap(TW(TrapWriteKind.DebugEntry, dpc = 0x10, dcsr = 1, priv = 3)); val dbg = v()
      // DRET applied by the trap-write path with privNext = dcsr.prv (the TrapController's value).
      val prv = (d.peek().tr("dcsr") & 3).toInt
      d.trap(TW(TrapWriteKind.DRet, priv = prv)); val back = v()
      Seq(
        chk(v0 == ((3, false, false, false, false)), "reset view: M, not in Debug Mode, TVM/TW/TSR clear", s"$v0"),
        chk(tvm._3 && !tvm._4 && !tvm._5 && tw._4 && !tw._3 && tsr._5 && !tsr._4, "TVM, TW, TSR follow committed mstatus", s"$tvm $tw $tsr"),
        chk(staged._5 && !cleared._5, "a staged mstatus write changes the view only at its CommitGrant", s"$staged $cleared"),
        chk(s._1 == 1, "priv follows the committed privilege", s"$s"),
        chk(dbg._2 && dbg._1 == 3, "DebugEntry sets debugMode", s"$dbg"),
        chk(!back._2 && back._1 == 1, "DRet clears debugMode and restores priv from dcsr.prv", s"$back")
      )
    }
  }

  // ---- funcCsrMapContribution (ADR-019E E-1) ------------------------------------------------------------

  /** A synthetic extension CSR: one register with an 8-bit WARL mask. */
  class SyntheticCsr(addr: Int, reset: Long, mask: Long) extends CsrMapContribution {
    def entries(): Seq[CsrMapEntry] = {
      val r = RegInit(reset.U(32.W))
      Seq(CsrMapEntry(addr, r, v => r := v & mask.U(32.W)))
    }
  }
  /** A read-only contributed CSR (address class 11 = read-only). */
  class SyntheticRoCsr(addr: Int, value: Long) extends CsrMapContribution {
    def entries(): Seq[CsrMapEntry] = Seq(CsrMapEntry(addr, value.U(32.W)))
  }
  /** A contribution that illegally owns a second write path (its register moves by itself). */
  class RogueCsr(addr: Int) extends CsrMapContribution {
    def entries(): Seq[CsrMapEntry] = {
      val r = RegInit(0.U(32.W)); r := r + 1.U
      Seq(CsrMapEntry(addr, r, v => r := v))
    }
  }
  val CUSTOM = 0x7c0; val CUSTOM_RO = 0xfc0

  val contribution = new SpecTest("csr.mapContribution", Seq("funcCsrMapContribution")) {
    def run(): Seq[TCheck] = {
      val main = withDrv(this, Seq(new SyntheticCsr(CUSTOM, 0x5a, 0xff), new SyntheticRoCsr(CUSTOM_RO, 0xabcd))) { d =>
        val r0 = d.read(CUSTOM)
        // Staged write: legalized by the descriptor, applied only at the grant.
        val (w, os) = d.exec(rw(CUSTOM, 0x1234), grant = false)
        val held = d.run(3).map(_.reqReady)
        d.cycle(grant = Some(d.next - 1))
        val r1 = d.read(CUSTOM)
        // Encoded write intent is authoritative with a zero operand (a staged write, not a read).
        d.exec(rs(CUSTOM, 0, rs1 = 5), grant = false)
        val zeroStaged = d.run(2).map(_.reqReady)
        d.cycle(grant = Some(d.next - 1))
        val roRead = d.exec(rd(CUSTOM_RO))._1
        val roZero = d.exec(rs(CUSTOM_RO, 0, rs1 = 5))._1
        d.setPriv(1)
        val fromS = d.exec(rd(CUSTOM))._1
        Seq(
          chk(r0 == 0x5a, "a contributed CSR is read through the one map", hx(r0)),
          chk(w.data == 0x5a && !w.exc && held.forall(!_) && r1 == 0x34,
            "a contributed CSR write is staged, legalized by its descriptor, and applied only at the CommitGrant", s"$w $held ${hx(r1)}"),
          chk(zeroStaged.forall(!_), "encoded write intent stays authoritative for a contributed CSR with operand 0", s"$zeroStaged"),
          chk(!roRead.exc && roRead.data == 0xabcd && roZero.exc && roZero.cause == ILLEGAL,
            "a read-only contributed CSR reads; a write-intent access (operand 0) is illegal", s"$roRead $roZero"),
          chk(fromS.exc, "a contributed CSR keeps its address privilege (M-level custom CSR is illegal from S)", s"$fromS")
        )
      }
      def elabFails(label: String, cs: Seq[CsrMapContribution]): TCheck = {
        val msg = try { sim(new CsrController(p, cs)) { _ => Seq.empty[TCheck] }; "elaborated" }
                  catch { case e: Throwable => Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).map(x => String.valueOf(x.getMessage)).mkString(" | ") }
        TCheck(msg.contains("CsrMapContribution: duplicate CSR address"), label, if (msg.contains("duplicate")) "" else msg.take(200))
      }
      main ++ Seq(
        elabFails("a contribution colliding with a base CSR (mscratch) is rejected at elaboration", Seq(new SyntheticCsr(MSCRATCH, 0, 0xff))),
        elabFails("two contributions at one address are rejected at elaboration", Seq(new SyntheticCsr(CUSTOM, 0, 1), new SyntheticRoCsr(CUSTOM, 2))),
        mustAssert(this, "a contribution cannot own a second write path (its CSR changed outside the CommitGrant)",
          "CsrMapContribution: a contributed CSR changed outside its CommitGrant", Seq(new RogueCsr(CUSTOM))) { d => d.run(4) }
      )
    }
  }

  val all: Seq[SpecTest] = Seq(ops, boundaries, rdX0, metadata, access, backpressure, gating, writeIntent, singleOwner,
    supervisor, interrupts, trapWrite, tctx, publish, decodePrivView, contribution)
}
