package verif.spectest

import chisel3._
import chisel3.util._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.modules.{CommitUnit, CsrController, RecoveryController, TrapController}
import udacore.backend.design.shared._
import udacore.core.design.shared.DebugReq

/** L1 integration SpecTests for the ADR-019D trap/CSR seam (ADR-018).
  *
  * CommitUnit + TrapController + CsrController + RecoveryController wired like BackendTop; the
  * test plays the ReorderBuffer head, the DispatchUnit CSR edge, the raw interrupt lines, and
  * the debug request. Maintenance services answer one cycle after each request.
  */
object CsrTrapSeamSpecTests {

  val p = BackendParams()
  val D = p.robDepth
  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)
  def code(u: UInt): Int = u.litValue.toInt

  class SeamHarness(p: BackendParams = CsrTrapSeamSpecTests.p) extends Module {
    val io = IO(new Bundle {
      val head     = Flipped(Decoupled(new RobHead(p)))
      val csrReq   = Flipped(Decoupled(new IssuedUop(p)))
      val csrRes   = Decoupled(new FuResult(p))
      val irq      = Input(new Interrupt)
      val debugReq = Input(Bool())
      val event    = Output(new RecoveryEvent(p))
      val tctx     = Output(new TranslationContext)
      val trapRead = Output(new CsrTrapRead(p))
      val ic       = Output(new InterruptCtrl)
      val twFire   = Output(Bool())
      val grant    = Output(new CommitGrant(p))
    })
    val com = Module(new CommitUnit(p)); val tc = Module(new TrapController(p))
    val csr = Module(new CsrController(p)); val rc = Module(new RecoveryController(p))
    com.io.robHeadIn <> io.head
    Seq(com.io.renameCommitOut.ready, com.io.storeCommitOut.ready, com.io.ftqCommitOut.ready, com.io.headMemGrantOut.ready,
      com.io.storeBufferDrainReqOut.ready, com.io.iCacheInvalidateOut.ready, com.io.dCacheCleanReqOut.ready,
      com.io.sfenceVmaOut.ready).foreach(_ := true.B)
    com.io.storeBufferDrainRespIn.valid := RegNext(com.io.storeBufferDrainReqOut.fire, false.B)
    com.io.storeBufferDrainRespIn.bits  := DontCare
    com.io.dCacheCleanRespIn.valid := RegNext(com.io.dCacheCleanReqOut.fire, false.B)
    com.io.dCacheCleanRespIn.bits  := RegNext(com.io.dCacheCleanReqOut.bits)
    com.io.debugReqIn.debugReq := io.debugReq
    com.io.interruptCtrlIn     := csr.io.interruptCtrlOut
    csr.io.commitGrantIn       := com.io.commitGrantOut
    tc.io.exceptionIn <> com.io.exceptionOut
    tc.io.csrTrapReadIn := csr.io.csrTrapReadOut
    csr.io.csrTrapWriteIn <> tc.io.csrTrapWriteOut
    rc.io.archRedirectIn <> tc.io.archRedirectOut
    rc.io.branchResolutionIn.valid := false.B; rc.io.branchResolutionIn.bits := 0.U.asTypeOf(rc.io.branchResolutionIn.bits)
    com.io.recoveryEventIn := rc.io.recoveryEventOut
    csr.io.csrReqIn <> io.csrReq
    io.csrRes <> csr.io.csrResultOut
    csr.io.interruptIn := io.irq
    io.event    := rc.io.recoveryEventOut
    io.tctx     := csr.io.translationContextOut
    io.trapRead := csr.io.csrTrapReadOut
    io.ic       := csr.io.interruptCtrlOut
    io.twFire   := tc.io.csrTrapWriteOut.fire
    io.grant    := com.io.commitGrantOut
  }

  /** A ROB head: exc = Some(cause) for a synchronous exception. */
  case class H(s: Int, pc: Long, rd: Int = 0, serialize: Boolean = false, sysOp: UInt = SysOp.None, exc: Option[Int] = None)
  case class Ev(kind: Int, cause: Int, target: Long, tag: (Boolean, Int))
  case class O(headFire: Boolean, ev: Option[Ev], twFire: Boolean, grant: Boolean, tr: Map[String, Long], tcPriv: Int,
      tcDataPriv: Int, debugMode: Boolean)

  class Drv(val h: SeamHarness) {
    val io = h.io
    var lines: Map[String, Boolean] = Map().withDefaultValue(false)
    var debugReq = false
    def cycle(head: Option[H] = None, req: Option[(Int, CsrControllerSpecTests.Req)] = None): O = {
      val hd = io.head
      hd.valid.poke(head.nonEmpty.B)
      head.foreach { x =>
        hd.bits.robTag.wrap.poke(tagOf(x.s)._1.B); hd.bits.robTag.idx.poke(tagOf(x.s)._2.U)
        val e = hd.bits.entry
        e.valid.poke(true.B); e.done.poke(true.B); e.pc.poke(x.pc.U); e.insn.poke(0x13.U)
        e.archRd.poke(x.rd.U); e.hasDest.poke((x.rd != 0).B); e.newPrd.poke(40.U); e.oldPrd.poke(x.rd.U)
        e.exception.valid.poke(x.exc.nonEmpty.B); e.exception.cause.poke(x.exc.getOrElse(0).U); e.exception.tval.poke(0.U)
        e.isCfi.poke(false.B); e.checkpointId.id.poke(0.U); e.cfiOutcome.cfiType.poke(0.U); e.cfiOutcome.slot.poke(0.U)
        e.cfiOutcome.taken.poke(false.B); e.cfiOutcome.target.poke(0.U); e.ftqIdx.poke(1.U); e.blockEnd.poke(false.B)
        e.isLoad.poke(false.B); e.isStore.poke(false.B); e.headExecute.poke(false.B)
        e.serialize.poke(x.serialize.B); e.sysOp.poke(x.sysOp); e.predictionFault.poke(false.B)
      }
      val q = io.csrReq
      q.valid.poke(req.nonEmpty.B)
      req.foreach { case (s, r) =>
        q.bits.robTag.wrap.poke(tagOf(s)._1.B); q.bits.robTag.idx.poke(tagOf(s)._2.U)
        q.bits.fuType.poke(FuType.Csr); q.bits.op.poke(r.f3.U); q.bits.src1.poke(r.src1.U); q.bits.prd.poke(40.U)
        q.bits.hasDest.poke((r.rd != 0).B); q.bits.insn.poke(r.insn.U); q.bits.sysOp.poke(r.sys.U)
      }
      io.csrRes.ready.poke(true.B)
      val i = io.irq
      i.meip.poke(lines("meip").B); i.mtip.poke(lines("mtip").B); i.msip.poke(lines("msip").B)
      i.seip.poke(lines("seip").B); i.stip.poke(lines("stip").B); i.ssip.poke(lines("ssip").B)
      io.debugReq.poke(debugReq.B)
      def b(x: Bool) = x.peek().litToBoolean
      def l(x: UInt) = x.peek().litValue.toLong
      val ev = io.event
      val t = io.trapRead
      val o = O(head.nonEmpty && b(hd.ready),
        if (!b(ev.valid)) None else Some(Ev(l(ev.kind).toInt, l(ev.cause).toInt, l(ev.target), (b(ev.robTag.wrap), l(ev.robTag.idx).toInt))),
        b(io.twFire), b(io.grant.valid),
        Map("mstatus" -> l(t.mstatus), "mepc" -> l(t.mepc), "mcause" -> l(t.mcause), "mtvec" -> l(t.mtvec),
          "sepc" -> l(t.sepc), "scause" -> l(t.scause), "priv" -> l(t.priv), "dpc" -> l(t.dpc), "dcsr" -> l(t.dcsr)),
        l(io.tctx.priv).toInt, l(io.tctx.dataPriv).toInt, b(io.ic.debugMode))
      h.clock.step()
      o
    }
    def peek(): O = cycle()
    /** Execute a CSR uop with sequence number s (accept + drain its result). */
    def csrExec(s: Int, r: CsrControllerSpecTests.Req): Unit = {
      var k = 0
      while (!io.csrReq.ready.peek().litToBoolean && k < 10) { cycle(); k += 1 }
      cycle(req = Some((s, r))); cycle()
    }
    /** Present head x until it retires or an ArchRedirect ends it; returns the observations. */
    def retire(x: H, max: Int = 12): Seq[O] = {
      var os = Seq.empty[O]
      var done = false
      while (!done && os.size < max) {
        val o = cycle(Some(x))
        os :+= o
        done = o.ev.exists(_.kind == code(RecoveryKind.ArchRedirect)) || (o.headFire && !x.serialize && x.exc.isEmpty)
      }
      os
    }
    /** A CSR write committed as the serialize head s. */
    def commitWrite(s: Int, r: CsrControllerSpecTests.Req): Seq[O] = {
      csrExec(s, r); retire(H(s, 0x100 + 4 * s, rd = 1, serialize = true, sysOp = SysOp.CsrWrite))
    }
  }

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  import CsrControllerSpecTests.{rw, MTVEC, MIE, MSTATUS, MEPC, MEDELEG, STVEC}
  val AR = code(RecoveryKind.ArchRedirect)

  val seam = new SpecTest("seam.trapCsr", Seq("funcTrapSingleOwner", "funcCsrTrapWriteApply", "funcTranslationContextPublish")) {
    def run(): Seq[TCheck] = sim(new SeamHarness) { h =>
      val d = new Drv(h); d.cycle()
      // A committed CSR write: CommitGrant applies it, the Refetch redirect carries no CSRTrapWrite.
      val w0 = d.commitWrite(0, rw(MTVEC, 0x400))
      val afterW0 = d.peek()
      d.commitWrite(1, rw(MIE, 0x80)); d.commitWrite(2, rw(MSTATUS, 0)); d.commitWrite(3, rw(MEPC, 0x2000))
      // MRET to U.
      val mret = d.retire(H(4, 0x110, serialize = true, sysOp = SysOp.Mret)); val afterMret = d.peek()
      // Undelegated ecall from U: TrapEntryM.
      val ecall = d.retire(H(5, 0x2000, exc = Some(8))); val afterEcall = d.peek()
      // Delegate ecall-from-U, return to U, and trap again: TrapEntryS.
      d.commitWrite(6, rw(MEDELEG, 1L << 8)); d.commitWrite(7, rw(STVEC, 0x800)); d.commitWrite(8, rw(MEPC, 0x3000))
      d.retire(H(9, 0x124, serialize = true, sysOp = SysOp.Mret))
      val secall = d.retire(H(10, 0x3000, exc = Some(8))); val afterS = d.peek()
      // An M timer interrupt taken from S at a normal head.
      d.lines = d.lines.updated("mtip", true)
      val intr = d.retire(H(11, 0x800)); val afterInt = d.peek()
      d.lines = d.lines.updated("mtip", false)
      // A debug request at the next head.
      d.debugReq = true
      val dbg = d.retire(H(12, 0x404)); val afterDbg = d.peek()
      d.debugReq = false
      def ev(os: Seq[O]) = os.flatMap(_.ev).find(_.kind == AR)
      Seq(
        chk(w0.exists(o => o.headFire && o.grant && o.ev.exists(e => e.cause == code(RecoveryCause.Refetch) && e.target == 0x104)) &&
          !w0.exists(_.twFire) && afterW0.tr("mtvec") == 0x400,
          "a committed CSR write applies through its CommitGrant; its Refetch carries no CSRTrapWrite", s"$w0 ${afterW0.tr}"),
        chk(ev(mret).exists(e => e.cause == code(RecoveryCause.XRet) && e.target == 0x2000) && mret.exists(_.twFire) &&
          afterMret.tr("priv") == 0 && afterMret.tcPriv == 0 && (afterMret.tr("mstatus") & 0x80) == 0x80,
          "MRET: XRet to mepc; priv U and MPIE := 1 applied; TranslationContext follows", s"${ev(mret)} ${afterMret.tr}"),
        chk(ev(ecall).exists(e => e.cause == code(RecoveryCause.Trap) && e.target == 0x400) && afterEcall.tr("mepc") == 0x2000 &&
          afterEcall.tr("mcause") == 8 && afterEcall.tr("priv") == 3 && afterEcall.tcPriv == 3 && ((afterEcall.tr("mstatus") >> 11) & 3) == 0,
          "TrapEntryM from U: mepc/mcause written, MPP := U, priv M, target mtvec", s"${ev(ecall)} ${afterEcall.tr}"),
        chk(ev(secall).exists(e => e.cause == code(RecoveryCause.Trap) && e.target == 0x800) && afterS.tr("sepc") == 0x3000 &&
          afterS.tr("scause") == 8 && afterS.tr("priv") == 1 && afterS.tcPriv == 1 && afterS.tr("mepc") == 0x3000,
          "TrapEntryS (medeleg): sepc/scause written, M registers untouched, priv S, target stvec", s"${ev(secall)} ${afterS.tr}"),
        chk(ev(intr).exists(e => e.cause == code(RecoveryCause.Interrupt) && e.target == 0x400 && e.tag == tagOf(11)) &&
          afterInt.tr("mcause") == 0x80000007L && afterInt.tr("mepc") == 0x800 && afterInt.tr("priv") == 3 &&
          ((afterInt.tr("mstatus") >> 11) & 3) == 1 && !intr.exists(_.headFire),
          "an M interrupt from S: the head is not retired; TrapEntryM with the interrupt cause and MPP := S", s"${ev(intr)} ${afterInt.tr}"),
        chk(ev(dbg).exists(e => e.cause == code(RecoveryCause.Debug) && e.target == p.debugEntryAddr) && afterDbg.debugMode &&
          afterDbg.tr("dpc") == 0x404 && ((afterDbg.tr("dcsr") >> 6) & 7) == 3 && (afterDbg.tr("dcsr") & 3) == 3,
          "a debug request: DebugEntry (dpc = head pc, dcsr.cause 3, prv M), debug mode, cause Debug", s"${ev(dbg)} ${afterDbg.tr}")
      )
    }
  }

  /** ADR-019D E-5 end to end: a staged CSR write whose head is presented while an interrupt is
    * pending retires first (its grant applies), and the interrupt is taken at the next head. */
  val stagedSurvives = new SpecTest("seam.stagedWriteSurvivesInterrupt", Seq("funcInterruptSampling", "propCsrSingleOwner")) {
    def run(): Seq[TCheck] = sim(new SeamHarness) { h =>
      val d = new Drv(h); d.cycle()
      d.commitWrite(0, rw(MIE, 0x80)); d.commitWrite(1, rw(MSTATUS, 0x8))
      d.lines = d.lines.updated("mtip", true)
      d.csrExec(2, rw(MEPC, 0x154))
      val head = d.retire(H(2, 0x108, rd = 1, serialize = true, sysOp = SysOp.CsrWrite))
      val afterHead = d.peek()
      val next = d.retire(H(3, 0x10c)); val afterNext = d.peek()
      Seq(
        chk(head.exists(o => o.headFire && o.grant && o.ev.exists(_.cause == code(RecoveryCause.Refetch))) && !head.exists(_.twFire) &&
          afterHead.tr("mepc") == 0x154, "the serialize CSR head retires and its staged write applies despite the pending interrupt",
          s"$head ${afterHead.tr}"),
        chk(next.flatMap(_.ev).exists(e => e.cause == code(RecoveryCause.Interrupt) && e.tag == tagOf(3)) && afterNext.tr("mepc") == 0x10c,
          "the interrupt is taken at the next head (mepc = its pc)", s"$next ${afterNext.tr}")
      )
    }
  }

  /** ADR-019E E-2/E-3/E-5 end to end: U-mode code -> halt request -> Debug Mode at a
    * non-default debugEntryAddr -> DRET -> normal execution resumes at dpc in U. */
  val pAlt = p.copy(debugEntryAddr = 0x40000800L)
  val debugDret = new SpecTest("seam.debugDret", Seq("funcDebugCommitBoundary", "funcXRet", "funcSystemOpSequencing")) {
    def run(): Seq[TCheck] = sim(new SeamHarness(pAlt)) { h =>
      val d = new Drv(h); d.cycle()
      d.commitWrite(0, rw(MSTATUS, 0)); d.commitWrite(1, rw(MEPC, 0x2000))
      d.retire(H(2, 0x108, serialize = true, sysOp = SysOp.Mret))
      val inU = d.peek()
      d.retire(H(3, 0x2000)) // one U-mode instruction retires, so dpc (0x2004) differs from mepc (0x2000)
      d.debugReq = true
      val entry = d.retire(H(4, 0x2004)); val inDbg = d.peek()
      d.debugReq = false
      // Debug-mode code at debugEntryAddr runs as ordinary heads.
      val dbgCode = d.retire(H(5, 0x40000800L))
      val dret = d.retire(H(6, 0x40000804L, serialize = true, sysOp = SysOp.Dret)); val after = d.peek()
      val resumed = d.retire(H(7, 0x2004))
      def ev(os: Seq[O]) = os.flatMap(_.ev).find(_.kind == AR)
      Seq(
        chk(inU.tr("priv") == 0, "setup: the hart runs in U", s"${inU.tr}"),
        chk(ev(entry).exists(e => e.cause == code(RecoveryCause.Debug) && e.target == 0x40000800L) && !entry.exists(_.headFire) &&
          inDbg.debugMode && inDbg.tr("dpc") == 0x2004 && inDbg.tr("mepc") == 0x2000 && (inDbg.tr("dcsr") & 3) == 0 && inDbg.tr("priv") == 3,
          "DebugEntry: redirect to the configured debugEntryAddr; dpc = the next normal PC; dcsr.prv = U", s"${ev(entry)} ${inDbg.tr}"),
        chk(dbgCode.exists(_.headFire) && dbgCode.forall(_.ev.isEmpty), "debug-mode code retires normally", s"$dbgCode"),
        chk(dret.exists(o => o.headFire && o.twFire && o.ev.exists(e => e.cause == code(RecoveryCause.XRet) && e.target == 0x2004)),
          "DRET retires with its CSRTrapWrite and its XRet ArchRedirect to dpc in one (zero-latency) cycle", s"$dret"),
        chk(!after.debugMode && after.tr("priv") == 0 && after.tcPriv == 0, "after DRET: Debug Mode cleared, priv U restored from dcsr.prv", s"${after.tr}"),
        chk(resumed.exists(_.headFire) && resumed.forall(_.ev.isEmpty), "normal execution resumes at dpc", s"$resumed")
      )
    }
  }

  val all: Seq[SpecTest] = Seq(seam, stagedSurvives, debugDret)
}
