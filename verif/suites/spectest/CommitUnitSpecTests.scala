package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.backend.design.modules.CommitUnit
import udacore.backend.design.shared.{BackendParams, CfiType, ExceptionSource, RecoveryKind, SysOp}
import udacore.core.design.shared.MaintOp

/** L1 SpecTests for the ADR-019 CommitUnit (ADR-018; spec 242feaf + ADR-019A + ADR-019B).
  *
  * The driver plays the ReorderBuffer (a head queue presenting done/headExecute heads, locking
  * on a trap hand-off), every commit-broadcast consumer (per-consumer ready knobs), the
  * TrapController (ExceptionOut ready and the later ArchRedirect), the StoreBuffer /
  * D-cache / I-cache / PTW maintenance services, the CsrController InterruptCtrl view, the
  * debug line, the LSQ (HeadMemGrant sink), and - under usingRvvi - the PRF commit read port
  * (answered combinationally from a test function of prd).
  *
  * Heads are named by program-order sequence numbers s (robTag = {s / 16 odd, s mod 16}).
  */
object CommitUnitSpecTests {

  val pv = BackendParams(usingRvvi = true)
  val pn = BackendParams(usingRvvi = false)
  val D  = pv.robDepth

  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)
  def code(u: UInt): Int = u.litValue.toInt

  /** One ROB head as the test presents it. */
  case class HE(
      s: Int,
      pc: Long = 0x1000,
      insn: Long = 0x13,
      rd: Int = 0,
      newPrd: Int = 0,
      oldPrd: Int = 0,
      exc: Option[Int] = None,
      tval: Long = 0,
      cfi: Boolean = false,
      cfiTaken: Boolean = false,
      cfiType: Int = 0,
      cfiTarget: Long = 0,
      ftqIdx: Int = 0,
      blockEnd: Boolean = false,
      load: Boolean = false,
      store: Boolean = false,
      var hx: Boolean = false,
      var done: Boolean = true,
      serialize: Boolean = false,
      sysOp: UInt = SysOp.None,
      predFault: Boolean = false
  ) {
    def withExc(c: Int): HE = copy(exc = Some(c))
  }

  case class Tok(order: Long, pc: Long, insn: Long, rd: Int, wdata: Long, wen: Boolean, trap: Boolean,
      source: Int, cause: Int, priv: Int)

  /** Everything the CommitUnit drives in one cycle (sampled before the clock edge). */
  case class Obs(
      headValid: Boolean, headFire: Boolean, headS: Option[Int],
      rcV: Boolean, rcF: Boolean, rc: (Int, Int, Int, Boolean),
      scV: Boolean, scF: Boolean, scTag: (Boolean, Int),
      ftqV: Boolean, ftqF: Boolean, ftq: (Int, Int, Boolean, Long),
      grantV: Boolean, grantTag: (Boolean, Int),
      excV: Boolean, excF: Boolean, exc: (Int, Int, Int, (Boolean, Int), Long, Long),
      mgV: Boolean, mgF: Boolean, mgTag: (Boolean, Int),
      drV: Boolean, drF: Boolean, drRespF: Boolean,
      clV: Boolean, clF: Boolean, clOp: Int, clRespF: Boolean,
      icV: Boolean, icF: Boolean, icOp: Int,
      sfV: Boolean, sfF: Boolean,
      tok: Option[Tok], prfReq: Option[Int]
  ) {
    def anyProjection: Boolean = rcV || scV || ftqV || grantV
    /** A view actually transferred (CommitGrant has no ready: its valid is its transfer). */
    def anyViewFire: Boolean = rcF || scF || ftqF || grantV
    def excSource: Int = exc._1
  }

  class Drv(val dut: CommitUnit, val rvvi: Boolean) {
    val io = dut.io
    val rob    = ArrayBuffer.empty[HE]
    var locked = false

    var rcReady, scReady, ftqReady, excReady, mgReady = true
    var drainReqReady, cleanReqReady, icInvReady, sfenceReady, tokenReady = true
    var drainRespValid, cleanRespValid = false
    /** Auto services: a drain / clean request is answered this many cycles after it fires. */
    var autoService: Option[Int] = None
    private var drainCountdown, cleanCountdown = -1
    var intPending, debugMode, debugReq = false
    var intCause = 7
    var priv     = 3
    var event: Option[(UInt, Int)] = None
    /** A zero-latency TrapController: it returns the matching ArchRedirect in the same cycle
      * it accepts an ExceptionOut (a legal instance of funcTrapHold). */
    var zeroLatencyTc = false
    var prf: Int => Long = p => 0x10000L + p

    private def pokeHead(h: Option[HE]): Unit = {
      val r = io.robHeadIn
      r.valid.poke(h.nonEmpty.B)
      h.foreach { h =>
        r.bits.robTag.wrap.poke(tagOf(h.s)._1.B); r.bits.robTag.idx.poke(tagOf(h.s)._2.U)
        val e = r.bits.entry
        e.valid.poke(true.B); e.done.poke(h.done.B); e.pc.poke(h.pc.U); e.insn.poke(h.insn.U)
        e.archRd.poke(h.rd.U); e.hasDest.poke((h.rd != 0).B); e.newPrd.poke(h.newPrd.U); e.oldPrd.poke(h.oldPrd.U)
        e.exception.valid.poke(h.exc.nonEmpty.B); e.exception.cause.poke(h.exc.getOrElse(0).U)
        e.exception.tval.poke(h.tval.U)
        e.isCfi.poke(h.cfi.B); e.checkpointId.id.poke(0.U)
        e.cfiOutcome.cfiType.poke(h.cfiType.U); e.cfiOutcome.slot.poke(1.U)
        e.cfiOutcome.taken.poke(h.cfiTaken.B); e.cfiOutcome.target.poke(h.cfiTarget.U)
        e.ftqIdx.poke(h.ftqIdx.U); e.blockEnd.poke(h.blockEnd.B)
        e.isLoad.poke(h.load.B); e.isStore.poke(h.store.B)
        e.headExecute.poke(h.hx.B); e.serialize.poke(h.serialize.B); e.sysOp.poke(h.sysOp)
        e.predictionFault.poke(h.predFault.B)
      }
    }

    def cycle(): Obs = {
      val head = rob.headOption.filter(h => !locked && (h.done || h.hx))
      pokeHead(head)
      io.renameCommitOut.ready.poke(rcReady.B)
      io.storeCommitOut.ready.poke(scReady.B)
      io.ftqCommitOut.ready.poke(ftqReady.B)
      io.exceptionOut.ready.poke(excReady.B)
      io.headMemGrantOut.ready.poke(mgReady.B)
      io.storeBufferDrainReqOut.ready.poke(drainReqReady.B)
      io.storeBufferDrainRespIn.valid.poke(drainRespValid.B)
      io.dCacheCleanReqOut.ready.poke(cleanReqReady.B)
      io.dCacheCleanRespIn.valid.poke(cleanRespValid.B)
      io.dCacheCleanRespIn.bits.op.poke(MaintOp.DCacheCleanAll)
      io.iCacheInvalidateOut.ready.poke(icInvReady.B)
      io.sfenceVmaOut.ready.poke(sfenceReady.B)
      io.interruptCtrlIn.interruptPending.poke(intPending.B)
      io.interruptCtrlIn.interruptCause.poke(intCause.U)
      io.interruptCtrlIn.debugMode.poke(debugMode.B)
      io.interruptCtrlIn.priv.poke(priv.U)
      io.debugReqIn.debugReq.poke(debugReq.B)
      val ev = io.recoveryEventIn
      ev.valid.poke(event.nonEmpty.B)
      event.foreach { case (k, s) =>
        ev.kind.poke(k); ev.robTag.wrap.poke(tagOf(s)._1.B); ev.robTag.idx.poke(tagOf(s)._2.U)
      }
      if (zeroLatencyTc && event.isEmpty && excReady && io.exceptionOut.valid.peek().litToBoolean) {
        val s = head.map(_.s).getOrElse(-1)
        event = Some((RecoveryKind.ArchRedirect, s))
        ev.valid.poke(true.B); ev.kind.poke(RecoveryKind.ArchRedirect)
        ev.robTag.wrap.poke(tagOf(s)._1.B); ev.robTag.idx.poke(tagOf(s)._2.U)
      }
      // The PRF commit read answers combinationally (ADR-019B E-3).
      var prfReq: Option[Int] = None
      if (rvvi) {
        val q = io.commitPrfReadReqOut.get
        val a = io.commitPrfReadRespIn.get
        q.ready.poke(true.B)
        val qv = q.valid.peek().litToBoolean
        val prd = q.bits.prd.peek().litValue.toInt
        if (qv) prfReq = Some(prd)
        a.valid.poke(qv.B)
        a.bits.data.poke((if (qv) prf(prd) else 0xbad0bad0L).U)
        io.retireStreamOut.get.ready.poke(tokenReady.B)
      }
      def b(x: Bool) = x.peek().litToBoolean
      def i(x: UInt) = x.peek().litValue.toInt
      def l(x: UInt) = x.peek().litValue.toLong
      def t(r: udacore.backend.design.shared.RobTag) = (b(r.wrap), i(r.idx))
      val hv = b(io.robHeadIn.valid)
      val hf = hv && b(io.robHeadIn.ready)
      val rc = io.renameCommitOut; val sc = io.storeCommitOut; val fq = io.ftqCommitOut
      val ex = io.exceptionOut; val mg = io.headMemGrantOut
      val tok = if (!rvvi) None else {
        val tk = io.retireStreamOut.get
        if (!b(tk.valid)) None
        else Some(Tok(l(tk.bits.order), l(tk.bits.pc), l(tk.bits.insn), i(tk.bits.rd), l(tk.bits.wdata),
          b(tk.bits.wen), b(tk.bits.trap), i(tk.bits.source), i(tk.bits.cause), i(tk.bits.priv)))
      }
      val o = Obs(
        hv, hf, head.map(_.s),
        b(rc.valid), b(rc.valid) && rcReady, (i(rc.bits.archRd), i(rc.bits.newPrd), i(rc.bits.oldPrd), b(rc.bits.hasDest)),
        b(sc.valid), b(sc.valid) && scReady, t(sc.bits.robTag),
        b(fq.valid), b(fq.valid) && ftqReady,
        (i(fq.bits.ftqIdx), i(fq.bits.exit.cfiType), b(fq.bits.exit.taken), l(fq.bits.exit.target)),
        b(io.commitGrantOut.valid), t(io.commitGrantOut.robTag),
        b(ex.valid), b(ex.valid) && excReady,
        (i(ex.bits.source), i(ex.bits.cause), i(ex.bits.sysOp), t(ex.bits.robTag), l(ex.bits.pc), l(ex.bits.tval)),
        b(mg.valid), b(mg.valid) && mgReady, t(mg.bits.robTag),
        b(io.storeBufferDrainReqOut.valid), b(io.storeBufferDrainReqOut.valid) && drainReqReady,
        drainRespValid && b(io.storeBufferDrainRespIn.ready),
        b(io.dCacheCleanReqOut.valid), b(io.dCacheCleanReqOut.valid) && cleanReqReady, i(io.dCacheCleanReqOut.bits.op),
        cleanRespValid && b(io.dCacheCleanRespIn.ready),
        b(io.iCacheInvalidateOut.valid), b(io.iCacheInvalidateOut.valid) && icInvReady, i(io.iCacheInvalidateOut.bits.op),
        b(io.sfenceVmaOut.valid), b(io.sfenceVmaOut.valid) && sfenceReady,
        tok, prfReq
      )
      dut.clock.step()
      // ROB model: a transfer retires the head, or locks it when it carries an exception.
      if (o.headFire) { if (rob.head.exc.nonEmpty) locked = true else rob.remove(0) }
      if (o.drRespF) drainRespValid = false
      if (o.clRespF) cleanRespValid = false
      autoService.foreach { n =>
        if (o.drF) drainCountdown = n
        if (o.clF) cleanCountdown = n
        if (drainCountdown == 0) drainRespValid = true
        if (cleanCountdown == 0) cleanRespValid = true
        if (drainCountdown >= 0) drainCountdown -= 1
        if (cleanCountdown >= 0) cleanCountdown -= 1
      }
      event.foreach { case (k, _) => if (k.litValue == RecoveryKind.ArchRedirect.litValue) { rob.clear(); locked = false } }
      event = None
      o
    }

    def run(n: Int): Seq[Obs] = (0 until n).map(_ => cycle())
    def arch(s: Int): Obs = { event = Some((RecoveryKind.ArchRedirect, s)); cycle() }
    def branch(s: Int): Obs = { event = Some((RecoveryKind.BranchMispredict, s)); cycle() }
  }

  def withDrv(t: SpecTest, p: BackendParams = pv)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new CommitUnit(p)) { dut => val d = new Drv(dut, p.usingRvvi); body(d) }

  def mustAssert(t: SpecTest, label: String, expect: String)(body: Drv => Unit): TCheck = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.run(3); reachedEnd = true; Nil }
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

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  val SYNC = code(ExceptionSource.Sync); val INTR = code(ExceptionSource.Interrupt)
  val DBG  = code(ExceptionSource.Debug); val SYSOP = code(ExceptionSource.SysOp)
  def noFire(o: Obs): Boolean = !o.headFire && !o.rcF && !o.scF && !o.ftqF && !o.grantV && !o.excF
  def csrRead(s: Int)  = HE(s, rd = 5, newPrd = 40, oldPrd = 5, serialize = true, sysOp = SysOp.None)
  def csrWrite(s: Int) = HE(s, rd = 5, newPrd = 41, oldPrd = 5, serialize = true, sysOp = SysOp.CsrWrite)
  def sys(s: Int, op: UInt) = HE(s, serialize = true, sysOp = op)

  // ---- funcCommitHead ------------------------------------------------------------------

  val commitHead = new SpecTest("commit.head", Seq("funcCommitHead")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.rob += HE(0, rd = 5, newPrd = 32, oldPrd = 5)
      val o0 = d.cycle()
      d.rob += HE(1, store = true, blockEnd = true, ftqIdx = 3)
      // Each consumer alone withholds ready: nothing fires.
      d.rcReady = false; val bRc = d.run(2); d.rcReady = true
      d.scReady = false; val bSc = d.run(2); d.scReady = true
      d.ftqReady = false; val bFq = d.run(2); d.ftqReady = true
      val o1 = d.cycle()
      d.rob += csrRead(2); d.rob += HE(3)
      val o2 = d.cycle(); val o3 = d.cycle()
      val blocked = bRc ++ bSc ++ bFq
      Seq(
        chk(o0.headFire && o0.rcF && o0.rc == ((5, 32, 5, true)) && !o0.scV && !o0.ftqV && !o0.grantV && !o0.excV,
          "a done, exception-free head retires with its RenameCommit only", s"$o0"),
        chk(blocked.forall(noFire), "any required consumer not ready: no view fires and the head stays", s"${blocked.filterNot(noFire)}"),
        chk(bRc.forall(o => !o.scV && !o.ftqV) && bSc.forall(o => !o.rcV && !o.ftqV) && bFq.forall(o => !o.rcV && !o.scV),
          "views other than the blocked one never offer (no partial commit)", s"$blocked"),
        chk(o1.headFire && o1.rcF && o1.scF && o1.ftqF && o1.scTag == tagOf(1),
          "with every consumer ready all required views fire in one cycle", s"$o1"),
        chk(o2.headFire && o2.grantV && o2.grantTag == tagOf(2) && !o2.excV,
          "a read-only CSR retires with its CommitGrant and no Refetch", s"$o2"),
        chk(o3.headFire && o3.headS.contains(3), "no hold after a read-only CSR", s"$o3")
      )
    }
  }

  // ---- funcBlockEndCommit -----------------------------------------------------------------

  val blockEnd = new SpecTest("commit.blockEnd", Seq("funcBlockEndCommit")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val br = code(CfiType.Branch); val jal = code(CfiType.Jal)
      d.rob ++= Seq(
        HE(0, cfi = true, cfiType = jal, cfiTaken = true, cfiTarget = 0x4000, blockEnd = true, ftqIdx = 7),
        HE(1, cfi = true, cfiType = br, cfiTaken = false, cfiTarget = 0x5000, blockEnd = true, ftqIdx = 8),
        HE(2, blockEnd = true, ftqIdx = 9),
        HE(3, cfi = true, cfiType = br, cfiTaken = true, cfiTarget = 0x6000, blockEnd = false, ftqIdx = 10))
      val os = d.run(4)
      Seq(
        chk(os(0).ftqF && os(0).ftq == ((7, jal, true, 0x4000L)), "a taken CFI exit is sent as its cfiOutcome", s"${os(0)}"),
        chk(os(1).ftqF && os(1).ftq._1 == 8 && os(1).ftq._2 == 0, "a not-taken branch ends its block with cfiType None", s"${os(1)}"),
        chk(os(2).ftqF && os(2).ftq._1 == 9 && os(2).ftq._2 == 0, "a non-CFI blockEnd sends cfiType None", s"${os(2)}"),
        chk(os(3).headFire && !os(3).ftqV, "no FtqCommit without blockEnd", s"${os(3)}")
      )
    }
  }

  // ---- funcSystemOpSequencing ---------------------------------------------------------------

  val sysopRedirect = new SpecTest("commit.sysop.redirect", Seq("funcSystemOpSequencing")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // CsrWrite: the retirement waits for the TrapController (atomic with ExceptionOut).
      d.rob += csrWrite(0); d.rob += HE(1)
      d.excReady = false; val wait = d.run(2); d.excReady = true
      val fin = d.cycle()
      val held = d.run(3)
      d.arch(0); d.rob += HE(1)
      val after = d.cycle()
      // MRET and SRET: retire + XRet, priv stamped before the transition.
      d.rob += sys(2, SysOp.Mret)
      val mret = d.cycle(); d.run(1); d.priv = 0; d.arch(2)
      d.rob += sys(3, SysOp.Sret)
      val sret = d.cycle(); d.arch(3)
      // predictionFault: retire + Refetch.
      d.rob += HE(4, rd = 6, newPrd = 44, oldPrd = 6, predFault = true)
      val pf = d.cycle(); d.arch(4)
      // WFI: a serializing NOP - retires, sends nothing, waits for nothing.
      d.rob += sys(5, SysOp.Wfi); d.rob += HE(6)
      val w = d.run(2)
      Seq(
        chk(wait.forall(noFire), "a CsrWrite does not retire while ExceptionOut is not ready", s"$wait"),
        chk(fin.headFire && fin.rcF && fin.grantV && fin.excF && fin.excSource == SYSOP && fin.exc._3 == code(SysOp.CsrWrite) &&
          fin.tok.exists(t => !t.trap),
          "CsrWrite: RenameCommit, CommitGrant, retire token, and ExceptionOut{SysOp, CsrWrite} in one cycle", s"$fin"),
        chk(held.forall(o => !o.headFire && !o.anyProjection && !o.excV), "after the hand-off nothing commits (trapPending)", s"$held"),
        chk(after.headFire && after.headS.contains(1), "the matching ArchRedirect releases the hold", s"$after"),
        chk(!mret.grantV && !sret.grantV && !pf.grantV && !w(0).grantV && fin.grantV,
          "CommitGrant accompanies only CSR uops (not xRET, WFI, or predictionFault)", s"$mret $sret $pf ${w(0)}"),
        chk(mret.headFire && mret.excF && mret.excSource == SYSOP && mret.exc._3 == code(SysOp.Mret) && mret.tok.exists(_.priv == 3),
          "MRET retires with ExceptionOut{SysOp, Mret}; its token carries the pre-xRET privilege", s"$mret"),
        chk(sret.headFire && sret.excF && sret.exc._3 == code(SysOp.Sret) && sret.tok.exists(_.priv == 0),
          "SRET retires with XRet; the privilege changed by the previous xRET is seen from the next token on", s"$sret"),
        chk(pf.headFire && pf.rcF && pf.excF && pf.excSource == SYSOP && pf.exc._3 == code(SysOp.None),
          "a predictionFault head retires with a Refetch request (sysOp None)", s"$pf"),
        chk(w(0).headFire && !w(0).excV && !w(0).drV && w(1).headFire && w(1).headS.contains(6),
          "WFI retires as a NOP and the next head commits in the following cycle", s"$w")
      )
    }
  }

  val sysopMaintenance = new SpecTest("commit.sysop.maintenance", Seq("funcSystemOpSequencing")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // FENCE: drain, then an ordinary retirement.
      d.rob += sys(0, SysOp.Fence); d.rob += HE(1)
      val f1 = d.run(4)
      d.drainRespValid = true
      val f2 = d.run(3)
      // FENCE.I: drain -> D$ clean -> I$ invalidate -> retire + Refetch.
      d.rob.clear(); d.rob += sys(2, SysOp.FenceI)
      val i1 = d.run(3); d.drainRespValid = true
      val i2 = d.run(3); d.cleanRespValid = true
      d.icInvReady = false; val i3 = d.run(3); d.icInvReady = true
      d.excReady = false; val i4 = d.run(3); d.excReady = true
      val i5 = d.run(2); d.arch(2)
      // SFENCE.VMA: drain -> TLB flush -> retire + Refetch.
      d.rob += sys(3, SysOp.SfenceVma)
      val s1 = d.run(2); d.drainRespValid = true
      d.sfenceReady = false; val s2 = d.run(3); d.sfenceReady = true
      val s3 = d.run(2)
      val f = f1 ++ f2; val i = i1 ++ i2 ++ i3 ++ i4 ++ i5; val s = s1 ++ s2 ++ s3
      def first(os: Seq[Obs], p: Obs => Boolean) = os.indexWhere(p)
      val iDrain = first(i, _.drF); val iDrainResp = first(i, _.drRespF); val iClean = first(i, _.clF)
      val iCleanResp = first(i, _.clRespF); val iInv = first(i, _.icF); val iRet = first(i, _.headFire)
      val sDrainResp = first(s, _.drRespF); val sFlush = first(s, _.sfF); val sRet = first(s, _.headFire)
      Seq(
        chk(f1.count(_.drF) == 1 && !f1.exists(_.headFire), "FENCE requests one drain and waits for its completion", s"$f1"),
        chk(!(f ++ i ++ s).exists(_.grantV), "no CommitGrant for FENCE, FENCE.I, or SFENCE.VMA", ""),
        chk(f2.exists(o => o.headFire && o.headS.contains(0) && !o.excV) && f2.exists(_.headS.contains(1)),
          "after the drain FENCE retires without a redirect and the next head follows", s"$f2"),
        chk(iDrain >= 0 && iDrain < iDrainResp && iDrainResp <= iClean && iClean < iCleanResp && iCleanResp <= iInv && iInv <= iRet,
          "FENCE.I: drain, then D$ clean, then I$ invalidate, then the retirement", s"drain=$iDrain resp=$iDrainResp clean=$iClean cresp=$iCleanResp inv=$iInv ret=$iRet"),
        chk(i.exists(o => o.clV && o.clOp == code(MaintOp.DCacheCleanAll)) && i.exists(o => o.icV && o.icOp == code(MaintOp.ICacheInvalidateAll)),
          "the maintenance tokens carry DCacheCleanAll and ICacheInvalidateAll", ""),
        chk(i4.forall(noFire), "the final retirement waits for ExceptionOut (atomic)", s"$i4"),
        chk(iRet >= 0 && { val o = i(iRet); o.excF && o.rcF && o.exc._3 == code(SysOp.FenceI) && o.excSource == SYSOP },
          "FENCE.I retires with RenameCommit and ExceptionOut{SysOp, FenceI} in one cycle", s"${i.lift(iRet)}"),
        chk(i.count(_.drF) == 1 && i.count(_.clF) == 1 && i.count(_.icF) == 1, "each maintenance step is issued exactly once", ""),
        chk(sDrainResp >= 0 && sDrainResp <= sFlush && sFlush <= sRet && s(sRet).excF && s(sRet).exc._3 == code(SysOp.SfenceVma),
          "SFENCE.VMA: drain, then the TLB flush, then retire + Refetch", s"resp=$sDrainResp flush=$sFlush ret=$sRet")
      )
    }
  }

  /** ADR-019B E-6: only intrinsic retiring redirects keep their sysOp; a redirect that exists
    * only because of predictionFault carries sysOp None. */
  val sysopPredictionFault = new SpecTest("commit.sysop.predictionFault", Seq("funcSystemOpSequencing")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.autoService = Some(1)
      val cases = Seq(
        ("ALU + predictionFault", HE(0, rd = 5, newPrd = 40, oldPrd = 5, predFault = true), SysOp.None),
        ("WFI + predictionFault", sys(0, SysOp.Wfi).copy(predFault = true), SysOp.None),
        ("FENCE + predictionFault", sys(0, SysOp.Fence).copy(predFault = true), SysOp.None),
        ("CsrWrite + predictionFault", csrWrite(0).copy(predFault = true), SysOp.CsrWrite),
        ("MRET + predictionFault", sys(0, SysOp.Mret).copy(predFault = true), SysOp.Mret),
        ("SRET + predictionFault", sys(0, SysOp.Sret).copy(predFault = true), SysOp.Sret)
      )
      var s = 0
      cases.map { case (name, h, want) =>
        d.rob += h.copy(s = s)
        val os = d.run(8)
        val ret = os.find(_.headFire)
        d.arch(s); s += 1
        chk(ret.exists(o => o.excF && o.rcF && o.excSource == SYSOP && o.exc._3 == code(want)),
          s"$name retires with Refetch/XRet carrying sysOp ${code(want)}", s"$ret")
      }
    }
  }

  // ---- funcPreciseTrapHandoff -----------------------------------------------------------------

  val trap = new SpecTest("commit.trap", Seq("funcPreciseTrapHandoff")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.rob += HE(0, pc = 0x2000, insn = 0xabc, rd = 5, newPrd = 33, oldPrd = 5, store = true, blockEnd = true, tval = 0xdead)
        .withExc(7)
      d.rob += HE(1)
      d.excReady = false; val w = d.run(2); d.excReady = true
      val h = d.cycle()
      val held = d.run(3)
      d.arch(0); d.rob += HE(1)
      val after = d.cycle()
      Seq(
        chk(w.forall(noFire), "the hand-off waits for the TrapController", s"$w"),
        chk(h.headFire && h.excF && h.excSource == SYNC && h.exc._2 == 7 && h.exc._4 == tagOf(0) && h.exc._5 == 0x2000 && h.exc._6 == 0xdead,
          "the head is taken as a hand-off with Exception{Sync, cause, tval, pc, robTag}", s"$h"),
        chk(!h.anyProjection, "no RenameCommit, StoreCommit, FtqCommit, or CommitGrant for a trapping head", s"$h"),
        chk(h.tok.exists(t => t.trap && t.source == SYNC && t.cause == 7 && !t.wen && t.pc == 0x2000),
          "a trap-entry observation token is emitted (trap = 1, no write)", s"${h.tok}"),
        chk(held.forall(o => !o.headFire && !o.anyProjection && !o.excV), "the unit holds until the ArchRedirect", s"$held"),
        chk(after.headFire && after.headS.contains(1), "commit resumes after the matching ArchRedirect", s"$after")
      )
    }
  }

  // ---- funcInterruptSampling ------------------------------------------------------------------

  val interrupt = new SpecTest("commit.interrupt", Seq("funcInterruptSampling")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.rob += HE(0, pc = 0x3000); d.intPending = true; d.intCause = 7
      val i = d.cycle(); d.intPending = false
      val held = d.run(2); d.arch(0)
      // Debug has priority over a pending interrupt.
      d.rob += HE(1, pc = 0x3004); d.intPending = true; d.debugReq = true
      val g = d.cycle(); d.debugReq = false; d.intPending = false; d.arch(1)
      // In debug mode neither is sampled.
      d.rob += HE(2); d.debugMode = true; d.intPending = true; d.debugReq = true
      val m = d.cycle(); d.debugMode = false; d.intPending = false; d.debugReq = false
      // A hand-off offered under TrapController backpressure stays stable after the line drops.
      d.rob += HE(3); d.excReady = false; d.intPending = true
      val s1 = d.cycle(); d.intPending = false; val s2 = d.run(2); d.excReady = true
      val s3 = d.cycle(); d.arch(3)
      // Not sampled inside a serialization sequence: FENCE waiting for its drain.
      d.rob += sys(4, SysOp.Fence); d.rob += HE(5)
      d.run(2); d.intPending = true; val q = d.run(3); d.drainRespValid = true
      val q2 = d.run(3); d.intPending = false
      Seq(
        chk(i.excF && i.excSource == INTR && i.exc._2 == 7 && i.exc._4 == tagOf(0) && i.exc._5 == 0x3000 && !i.headFire && !i.anyProjection,
          "an interrupt is taken in front of the head without dequeuing it", s"$i"),
        chk(i.tok.exists(t => t.trap && t.source == INTR && t.cause == 7), "interrupt entry emits a trap-entry token", s"${i.tok}"),
        chk(held.forall(o => !o.headFire && !o.excV), "the interrupt hand-off holds", s"$held"),
        chk(g.excF && g.excSource == DBG && g.exc._2 == 3 && !g.headFire, "debug has priority over an interrupt (cause 3)", s"$g"),
        chk(m.headFire && !m.excV, "in debug mode no interrupt or debug request is sampled", s"$m"),
        chk(s1.excV && !s1.excF && s2.forall(o => o.excV && o.excSource == INTR && !o.headFire) && s3.excF && s3.excSource == INTR,
          "an offered hand-off stays stable until accepted", s"$s1 $s2 $s3"),
        chk(!q.exists(_.excV) && q2.exists(o => o.headFire && o.headS.contains(4)) &&
          q2.exists(o => o.excF && o.excSource == INTR && o.exc._4 == tagOf(5)),
          "never sampled during a serialization sequence; taken at the next boundary", s"$q $q2")
      )
    }
  }

  /** ADR-019D E-5: a presented serialize head suppresses interrupt and debug sampling from its
    * first presented cycle until it retires or traps; the pending request is taken at the next
    * retire boundary (the following head). */
  val interruptSerializeHead = new SpecTest("commit.interrupt.serializeHead", Seq("funcInterruptSampling")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.autoService = Some(1)
      val classes = Seq(
        ("CSR read", (s: Int) => csrRead(s), false),
        ("CSR write", (s: Int) => csrWrite(s), true),
        ("FENCE", (s: Int) => sys(s, SysOp.Fence), false),
        ("FENCE.I", (s: Int) => sys(s, SysOp.FenceI), true),
        ("SFENCE.VMA", (s: Int) => sys(s, SysOp.SfenceVma), true),
        ("WFI", (s: Int) => sys(s, SysOp.Wfi), false),
        ("MRET", (s: Int) => sys(s, SysOp.Mret), true),
        ("SRET", (s: Int) => sys(s, SysOp.Sret), true)
      )
      var s = 0
      classes.flatMap { case (name, mk, redirects) =>
        d.rob += mk(s); d.rob += HE(s + 1, pc = 0x5000 + 4 * s)
        // Both requests are already pending in the first cycle the serialize head is presented.
        d.intPending = true; d.debugReq = true
        val os = d.run(16).toSeq
        val retIdx = os.indexWhere(o => o.headFire && o.headS.contains(s))
        val before = if (retIdx < 0) os else os.take(retIdx + 1)
        val preempted = before.exists(o => o.excV && (o.excSource == INTR || o.excSource == DBG))
        val retired = retIdx >= 0 && (!redirects || os(retIdx).excSource == SYSOP)
        // A retiring redirect holds until its ArchRedirect, which also kills the next head.
        if (redirects && retIdx >= 0) { d.arch(s); d.rob += HE(s + 1, pc = 0x5000 + 4 * s) }
        // The pending debug request (priority over the interrupt) is taken at the next head.
        val next = (if (retIdx >= 0 && !redirects) os.drop(retIdx + 1) else Nil) ++ d.run(2).toSeq
        val taken = next.exists(o => o.excF && o.excSource == DBG && o.exc._4 == tagOf(s + 1) && !o.headFire)
        d.intPending = false; d.debugReq = false
        d.arch(s + 1)
        d.rob.clear(); s += 2
        Seq(
          chk(!preempted && retired, s"$name: pending interrupt/debug on its first presented cycle does not preempt it",
            s"ret=$retIdx " + before.map(o => (o.headFire, o.excV, o.excSource)).mkString(" ")),
          chk(taken, s"$name: the pending debug request is taken at the next retire boundary",
            next.map(o => (o.headS, o.excF, o.excSource, o.exc._4)).mkString(" "))
        )
      }
    }
  }

  // ---- funcTrapHold -----------------------------------------------------------------------------

  val trapHold = new SpecTest("commit.trapHold", Seq("funcTrapHold")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.rob += csrWrite(0)
      d.excReady = false; val bp = d.run(5); d.excReady = true
      val fin = d.cycle()
      d.rob += HE(1); d.rob += HE(2)
      val h1 = d.run(4)
      val bm = d.branch(0)       // an unrelated BranchMispredict
      val h2 = d.run(2)
      d.event = Some((RecoveryKind.ArchRedirect, 9)); val wrong = d.cycle() // another robTag
      d.rob += HE(1)
      val h3 = d.run(2)
      val rel = d.arch(0)
      d.rob += HE(1)
      val go = d.cycle()
      val all = h1 ++ Seq(bm) ++ h2 ++ Seq(wrong) ++ h3 ++ Seq(rel)
      Seq(
        chk(bp.forall(noFire) && fin.excF && fin.headFire, "TrapController backpressure delays the atomic hand-off", s"$bp $fin"),
        chk(all.forall(o => !o.headFire && !o.anyProjection && !o.excV && !o.mgV),
          "trapPending persists: no transfer, view, hand-off, or grant", s"${all.filter(o => o.headFire || o.anyProjection || o.excV)}"),
        chk(go.headFire, "only the ArchRedirect naming the hand-off robTag clears it", s"$go")
      )
    }
  }

  /** A zero-latency TrapController: the matching ArchRedirect arrives in the ExceptionOut
    * transfer cycle and must satisfy the hold at once (no deadlock). */
  val trapHoldZeroLatency = new SpecTest("commit.trapHold.zeroLatency", Seq("funcTrapHold")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.zeroLatencyTc = true
      d.autoService = Some(1)
      val cases: Seq[(String, Int => HE, Boolean, Boolean)] = Seq(
        ("Sync trap", s => HE(s).withExc(2), false, false),
        ("Interrupt", s => HE(s), true, false),
        ("Debug", s => HE(s), false, true),
        ("CsrWrite Refetch", s => csrWrite(s), false, false),
        ("predictionFault Refetch", s => HE(s, predFault = true), false, false),
        ("MRET XRet", s => sys(s, SysOp.Mret), false, false),
        ("SRET XRet", s => sys(s, SysOp.Sret), false, false),
        ("FENCE.I Refetch", s => sys(s, SysOp.FenceI), false, false)
      )
      var s = 0
      cases.map { case (name, mk, intr, dbg) =>
        d.rob += mk(s)
        d.intPending = intr; d.debugReq = dbg
        val hand = d.run(16).find(_.excF) // FENCE.I needs drain, clean, and invalidate first
        d.intPending = false; d.debugReq = false
        d.rob += HE(s + 1)
        val next = d.run(3).find(_.headFire)
        d.rob.clear()
        s += 2
        chk(hand.nonEmpty && next.exists(_.headS.contains(s - 1)),
          s"$name: a same-cycle matching ArchRedirect releases the hold; the next head retires", s"hand=$hand next=$next")
      }
    }
  }

  // ---- funcHeadMemGrant -----------------------------------------------------------------------------

  val headMemGrant = new SpecTest("commit.headMemGrant", Seq("funcHeadMemGrant")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val ld = HE(0, rd = 7, newPrd = 45, oldPrd = 7, load = true, hx = true, done = false)
      d.rob += ld
      d.mgReady = false; val w = d.run(2); d.mgReady = true
      val g = d.cycle()
      d.intPending = true; d.debugReq = true
      val infl = d.run(4)
      ld.done = true; ld.hx = false
      val ret = d.cycle()
      d.rob += HE(1)
      val next = d.cycle(); d.intPending = false; d.debugReq = false; d.arch(1)
      // Completion with an exception: a precise trap, then no new grant.
      val st = HE(2, store = true, hx = true, done = false)
      d.rob += st
      val g2 = d.run(2)
      d.rob(0) = st.copy(done = true, hx = false, exc = Some(5))
      val tr = d.run(2).filter(_.excV)
      d.arch(2)
      // An interrupt pending before the grant is taken instead of the grant.
      d.rob += HE(3, load = true, hx = true, done = false); d.intPending = true
      val pre = d.cycle(); d.intPending = false
      Seq(
        chk(w.forall(o => o.mgV && !o.headFire) && g.mgF && g.mgTag == tagOf(0), "HeadMemGrant{robTag} is offered until accepted", s"$w $g"),
        chk((w ++ Seq(g) ++ infl).forall(o => !o.headFire && !o.rcV), "RobHeadIn.ready stays low for a headExecute head", s"$infl"),
        chk(infl.forall(o => !o.mgV), "the grant is sent exactly once", s"$infl"),
        chk(infl.forall(o => !o.excV), "no interrupt or debug request is sampled while the grant is in flight", s"$infl"),
        chk(ret.headFire && ret.rcF && ret.rc._2 == 45 && !ret.excV, "the completed head retires through the ordinary commit", s"$ret"),
        chk(next.excF && next.excSource == DBG, "grantInFlight clears at retirement; sampling resumes", s"$next"),
        chk(g2.count(_.mgF) == 1 && tr.headOption.exists(o => o.excF && o.excSource == SYNC && o.headFire),
          "a granted access that completes with an exception traps precisely", s"$g2 $tr"),
        chk(pre.excF && pre.excSource == INTR && !pre.mgV, "before the grant an interrupt may still be taken (no grant then)", s"$pre")
      )
    }
  }

  // ---- funcRetireStreamEmit ------------------------------------------------------------------------

  val retireStream = new SpecTest("commit.retireStream", Seq("funcRetireStreamEmit")) {
    def run(): Seq[TCheck] = {
      val live = withDrv(this) { d =>
        d.prf = p => 0x5000L + 3 * p
        d.rob ++= Seq(HE(0, pc = 0x100, insn = 0x00a00293, rd = 5, newPrd = 32, oldPrd = 5), HE(1, pc = 0x104, newPrd = 37),
          HE(2, pc = 0x108, rd = 0, newPrd = 38),
          HE(3, pc = 0x10c, rd = 6, newPrd = 33, oldPrd = 6).withExc(2))
        d.priv = 1
        val os = d.run(4)
        d.arch(3); d.priv = 3
        d.rob += HE(4, pc = 0x200, rd = 9, newPrd = 34, oldPrd = 9); d.intPending = true
        val it = d.cycle(); d.intPending = false; d.arch(4)
        d.rob += HE(5, pc = 0x200, rd = 9, newPrd = 35, oldPrd = 9)
        val last = d.cycle()
        val toks = (os ++ Seq(it, last)).flatMap(_.tok)
        Seq(
          chk(toks.map(_.order) == (0L until toks.size.toLong), "order increases by one per observation event", s"${toks.map(_.order)}"),
          chk(os(0).prfReq.contains(32) && os(0).tok.exists(t => t.wen && t.wdata == 0x5060 && t.rd == 5 && t.insn == 0x00a00293 && t.priv == 1),
            "a retirement reads its newPrd in the retire cycle; wdata, rd, insn, priv", s"${os(0)}"),
          chk(os(1).prfReq.isEmpty && os(1).tok.exists(t => !t.wen && t.wdata == 0) && os(2).tok.exists(t => !t.wen && t.wdata == 0),
            "no read and wdata 0 without a destination (wen = 0)", s"${os(1)} ${os(2)}"),
          chk(os(3).prfReq.isEmpty && os(3).tok.exists(t => t.trap && !t.wen && t.source == SYNC && t.pc == 0x10c),
            "a trap entry is an event without a register write", s"${os(3)}"),
          chk(it.tok.exists(t => t.trap && t.source == INTR && t.priv == 3), "interrupt entry is an event", s"$it"),
          chk(last.tok.exists(t => t.pc == 0x200 && !t.trap && t.wdata == 0x5000 + 3 * 35), "the re-executed head retires normally", s"$last")
        )
      }
      // Structure: at usingRvvi = false no port, read, mux, register, or counter exists.
      def fir(p: BackendParams) = circt.stage.ChiselStage.emitCHIRRTL(new CommitUnit(p))
      val off = fir(pn); val on = fir(pv)
      val names = Seq("retireStreamOut", "commitPrfReadReqOut", "commitPrfReadRespIn", "retireOrder")
      val struct = Seq(
        chk(names.forall(on.contains), "control: usingRvvi = true elaborates the ports and the order counter",
          names.filterNot(on.contains).mkString(",")),
        chk(!names.exists(off.contains) && !off.contains("UInt<64>"),
          "usingRvvi = false elaborates no retire port, PRF read, or 64-bit counter", names.filter(off.contains).mkString(","))
      )
      val offRun = withDrv(this, pn) { d =>
        d.rob += HE(0, rd = 5, newPrd = 32, oldPrd = 5)
        val o = d.cycle()
        Seq(chk(o.headFire && o.rcF && o.tok.isEmpty, "usingRvvi = false still commits", s"$o"))
      }
      live ++ struct ++ offRun
    }
  }

  // ---- Randomized reference history ------------------------------------------------------------------

  /** Random heads of every kind, random consumer readiness, random TrapController latency,
    * auto-answered maintenance services, interrupts and debug requests. Returns checks
    * against a reference: retired heads are consecutive except across an ArchRedirect, views
    * fire only with a real retirement, and nothing happens between a hand-off and its redirect. */
  def randomRun(d: Drv, seed: Long, steps: Int): Seq[TCheck] = {
    val rnd = new scala.util.Random(seed)
    d.autoService = Some(2)
    var next = 0
    var tc: Option[(Int, Int)] = None // (robTag seq, cycles left)
    var expect = 0
    var bad = Seq.empty[String]
    var retired, traps, intrs, sysops, grants, orderSeen = 0
    var lastOrder = -1L
    def newHead(s: Int): HE = rnd.nextInt(100) match {
      case k if k < 45 => HE(s, rd = rnd.nextInt(4), newPrd = 32 + rnd.nextInt(16), oldPrd = rnd.nextInt(32))
      case k if k < 55 => HE(s, store = true)
      case k if k < 63 => HE(s, cfi = true, cfiType = 1, cfiTaken = rnd.nextBoolean(), cfiTarget = 0x100, blockEnd = true, ftqIdx = rnd.nextInt(32))
      case k if k < 67 => HE(s).withExc(rnd.nextInt(16))
      case k if k < 71 => csrWrite(s)
      case k if k < 74 => csrRead(s)
      case k if k < 77 => sys(s, SysOp.Fence)
      case k if k < 79 => sys(s, SysOp.FenceI)
      case k if k < 81 => sys(s, SysOp.SfenceVma)
      case k if k < 83 => sys(s, if (rnd.nextBoolean()) SysOp.Mret else SysOp.Sret)
      case k if k < 85 => sys(s, SysOp.Wfi)
      case k if k < 87 => HE(s, predFault = true)
      case _           => HE(s, load = rnd.nextBoolean(), store = false, hx = true, done = false)
    }
    val pendingDone = ArrayBuffer.empty[(HE, Int, Boolean)]
    for (step <- 0 until steps) {
      while (d.rob.size < 3) { d.rob += newHead(next); next += 1 }
      d.rcReady = rnd.nextInt(5) > 0; d.scReady = rnd.nextInt(5) > 0; d.ftqReady = rnd.nextInt(5) > 0
      d.excReady = rnd.nextInt(3) > 0; d.mgReady = rnd.nextInt(3) > 0
      d.intPending = rnd.nextInt(40) == 0; d.debugReq = rnd.nextInt(120) == 0
      tc match {
        case Some((s, 0)) => d.event = Some((RecoveryKind.ArchRedirect, s)); tc = None; next = s + 1; expect = s + 1
        case Some((s, n)) => tc = Some((s, n - 1)); if (rnd.nextInt(6) == 0) d.event = Some((RecoveryKind.BranchMispredict, s))
        case None =>
      }
      val redirecting = d.event.exists(_._1.litValue == RecoveryKind.ArchRedirect.litValue)
      val held = tc.nonEmpty || redirecting
      val o = d.cycle()
      if (redirecting) pendingDone.clear()
      // Uncached completion model: the LSQ completes a granted head 1-3 cycles later,
      // with an access fault one time in four.
      val (due, waiting) = pendingDone.map { case (h, n, e) => (h, n - 1, e) }.partition(_._2 <= 0)
      pendingDone.clear(); pendingDone ++= waiting
      due.foreach { case (h, _, e) =>
        val i = d.rob.indexWhere(_ eq h)
        if (i >= 0) d.rob(i) = h.copy(done = true, hx = false, exc = if (e) Some(5) else None)
      }
      if (o.mgF) { grants += 1; d.rob.headOption.foreach(h => pendingDone += ((h, 1 + rnd.nextInt(3), rnd.nextInt(4) == 0))) }
      if (held && (o.headFire || o.anyProjection || o.excV || o.mgV)) bad :+= s"step $step: activity during hold $o"
      if (o.anyViewFire && !(o.headFire && o.headS.nonEmpty)) bad :+= s"step $step: view without retirement $o"
      if (o.headFire) {
        val s = o.headS.get
        if (s != expect) bad :+= s"step $step: retired/handed s$s expected s$expect"
        if (o.excF && o.excSource == SYNC) { traps += 1; if (o.anyProjection) bad :+= s"step $step: views on a trap $o" }
        else if (!o.rcF) bad :+= s"step $step: retirement without RenameCommit $o"
        else { retired += 1; expect = s + 1 }
      }
      if (o.excF) {
        if (o.excSource == INTR || o.excSource == DBG) intrs += 1
        if (o.excSource == SYSOP) sysops += 1
        tc = Some((o.headS.getOrElse(-1), rnd.nextInt(4)))
      }
      o.tok.foreach { t => if (t.order != lastOrder + 1) bad :+= s"step $step: order ${t.order} after $lastOrder"; lastOrder = t.order; orderSeen += 1 }
    }
    Seq(
      chk(bad.isEmpty, s"random history agrees with the reference (seed $seed)", bad.take(4).mkString("; ")),
      chk(retired > steps / 8 && traps > 0 && intrs > 0 && sysops > 3 && grants > 0 && orderSeen > retired,
        s"history exercised retire/trap/interrupt/sysop/grant (seed $seed)",
        s"retired=$retired traps=$traps intrs=$intrs sysops=$sysops grants=$grants tokens=$orderSeen")
    )
  }

  // ---- Properties -----------------------------------------------------------------------------------------

  val inOrder = new SpecTest("commit.inOrder", Seq("propCommitInOrder")) {
    def run(): Seq[TCheck] =
      withDrv(this)(d => randomRun(d, 41L, 700)) :+
        mustAssert(this, "a head that skips a robTag (ROB presents s0 then s2) stops the run",
          "CommitInOrder: retired robTag is not the next in program order") { d =>
          d.rob += HE(0); d.cycle(); d.rob += HE(2); d.cycle()
        }
  }

  val noPastException = new SpecTest("commit.noPastException", Seq("propNoCommitPastException")) {
    def run(): Seq[TCheck] = {
      val directed = withDrv(this) { d =>
        d.rob += HE(0).withExc(4); d.rob += HE(1, store = true, blockEnd = true)
        val os = d.run(6)
        Seq(chk(os.head.excF && os.forall(o => !o.anyProjection) && os.tail.forall(!_.headFire),
          "no uop younger than a trapping head fires any view before the ArchRedirect", s"$os"))
      }
      directed ++ withDrv(this)(d => randomRun(d, 43L, 500))
    }
  }

  val trapHoldProp = new SpecTest("commit.trapHoldProp", Seq("propTrapHoldUntilRedirect")) {
    def run(): Seq[TCheck] = withDrv(this)(d => randomRun(d, 47L, 600))
  }

  val retireNonBlocking = new SpecTest("commit.retireNonBlocking", Seq("propRetireNonBlocking")) {
    def run(): Seq[TCheck] =
      withDrv(this)(d => randomRun(d, 53L, 300)) :+
        mustAssert(this, "a retire sink that withholds ready stops the run", "RetireNonBlocking: retire token not accepted") { d =>
          d.tokenReady = false; d.rob += HE(0); d.cycle()
        }
  }

  val all: Seq[SpecTest] = Seq(commitHead, blockEnd, sysopRedirect, sysopMaintenance, sysopPredictionFault, trap,
    interrupt, interruptSerializeHead, trapHold, trapHoldZeroLatency,
    headMemGrant, retireStream, inOrder, noPastException, trapHoldProp, retireNonBlocking)
}
