package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.modules.TrapController
import udacore.backend.design.shared._

/** L1 SpecTests for the ADR-019 TrapController (ADR-018; spec 242feaf + ADR-019A..D).
  *
  * The driver plays the CommitUnit (ExceptionIn hand-offs), the CsrController (a CSRTrapRead
  * snapshot and CSRTrapWrite ready), and the RecoveryController (ArchRedirect ready).
  */
object TrapControllerSpecTests {

  val p = BackendParams()
  val D = p.robDepth
  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)
  def code(u: UInt): Int = u.litValue.toInt

  val SYNC = ExceptionSource.Sync; val INTR = ExceptionSource.Interrupt
  val DBG = ExceptionSource.Debug; val SYSOP = ExceptionSource.SysOp

  case class Exc(source: UInt, cause: Int = 0, tval: Long = 0, pc: Long = 0x1000, s: Int = 3, ftq: Int = 2,
      sysOp: UInt = SysOp.None)
  /** A CSRTrapRead snapshot; unnamed fields read zero. */
  case class Snap(priv: Int = 3, mstatus: Long = 0, mtvec: Long = 0x100, stvec: Long = 0x200, medeleg: Long = 0,
      mideleg: Long = 0, mepc: Long = 0x3000, sepc: Long = 0x4000, dcsr: Long = (4L << 28) | 3, dpc: Long = 0x6000)
  case class TW(kind: Int, xepc: Long, xcause: Long, xtval: Long, mstatus: Long, priv: Int, dpc: Long, dcsr: Long)
  case class AR(tag: (Boolean, Int), ftq: Int, target: Long, cause: Int)
  case class Obs(excReady: Boolean, tw: Option[TW], twFire: Boolean, ar: Option[AR], arFire: Boolean)

  class Drv(val dut: TrapController) {
    val io = dut.io
    var twReady, arReady = true
    def cycle(e: Option[Exc], c: Snap = Snap()): Obs = {
      val x = io.exceptionIn
      x.valid.poke(e.nonEmpty.B)
      e.foreach { e =>
        x.bits.source.poke(e.source); x.bits.cause.poke(e.cause.U); x.bits.tval.poke(e.tval.U); x.bits.pc.poke(e.pc.U)
        x.bits.robTag.wrap.poke(tagOf(e.s)._1.B); x.bits.robTag.idx.poke(tagOf(e.s)._2.U); x.bits.ftqIdx.poke(e.ftq.U)
        x.bits.sysOp.poke(e.sysOp)
      }
      val r = io.csrTrapReadIn
      r.priv.poke(c.priv.U); r.mstatus.poke(c.mstatus.U); r.mtvec.poke(c.mtvec.U); r.stvec.poke(c.stvec.U)
      r.medeleg.poke(c.medeleg.U); r.mideleg.poke(c.mideleg.U); r.mepc.poke(c.mepc.U); r.sepc.poke(c.sepc.U)
      r.dcsr.poke(c.dcsr.U); r.dpc.poke(c.dpc.U)
      Seq(r.mcause, r.mtval, r.mie, r.mip, r.scause, r.stval).foreach(_.poke(0.U))
      io.csrTrapWriteOut.ready.poke(twReady.B); io.archRedirectOut.ready.poke(arReady.B)
      def b(v: Bool) = v.peek().litToBoolean
      def l(v: UInt) = v.peek().litValue.toLong
      val w = io.csrTrapWriteOut; val a = io.archRedirectOut
      val tw = if (!b(w.valid)) None else Some(TW(l(w.bits.kind).toInt, l(w.bits.xepc), l(w.bits.xcause), l(w.bits.xtval),
        l(w.bits.mstatusNext), l(w.bits.privNext).toInt, l(w.bits.dpc), l(w.bits.dcsrNext)))
      val ar = if (!b(a.valid)) None else Some(AR((b(a.bits.robTag.wrap), l(a.bits.robTag.idx).toInt), l(a.bits.ftqIdx).toInt,
        l(a.bits.target), l(a.bits.cause).toInt))
      val o = Obs(b(x.ready), tw, tw.nonEmpty && twReady, ar, ar.nonEmpty && arReady)
      dut.clock.step()
      o
    }
  }

  def withDrv(t: SpecTest, params: BackendParams = p)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new TrapController(params)) { dut => val d = new Drv(dut); body(d) }
  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  val K = TrapWriteKind
  def one(o: Obs): Boolean = o.excReady && o.arFire

  // ---- funcTrapSingleOwner -----------------------------------------------------------------------

  val entryM = new SpecTest("trap.entryM", Seq("funcTrapSingleOwner")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      // Illegal instruction in M with MIE set: MPIE := 1, MIE := 0, MPP := M.
      val a = d.cycle(Some(Exc(SYNC, 2, tval = 0xdead, pc = 0x1234, s = 19, ftq = 5)), Snap(priv = 3, mstatus = 0x8))
      // Timer interrupt from U with a vectored mtvec: base + 4 * cause; tval 0.
      val b = d.cycle(Some(Exc(INTR, 7, tval = 0x77, pc = 0x2000)), Snap(priv = 0, mtvec = 0x101, mstatus = 0x0))
      // A synchronous exception with a vectored mtvec still goes to the base.
      val c = d.cycle(Some(Exc(SYNC, 5, pc = 0x2004)), Snap(priv = 3, mtvec = 0x101))
      Seq(
        chk(one(a) && a.twFire && a.tw.contains(TW(code(K.TrapEntryM), 0x1234, 2, 0xdead, 0x1880, 3, 0, 0).copy(dpc = a.tw.get.dpc, dcsr = a.tw.get.dcsr)),
          "Sync in M: TrapEntryM {xepc = pc, xcause, xtval, MPIE := MIE, MIE := 0, MPP := M, priv M}", s"${a.tw}"),
        chk(a.ar.contains(AR(tagOf(19), 5, 0x100, code(RecoveryCause.Trap))), "Sync: ArchRedirect {robTag, ftqIdx, mtvec base, Trap}", s"${a.ar}"),
        chk(b.tw.exists(t => t.kind == code(K.TrapEntryM) && t.xcause == 0x80000007L && t.xtval == 0 && t.mstatus == 0 && t.priv == 3) &&
          b.ar.exists(r => r.target == 0x100 + 4 * 7 && r.cause == code(RecoveryCause.Interrupt)),
          "Interrupt: xcause has the interrupt bit, tval 0, MPP := U, vectored target base + 4 * cause", s"${b.tw} ${b.ar}"),
        chk(c.ar.exists(_.target == 0x100), "a synchronous exception ignores vectored mode", s"${c.ar}")
      )
    }
  }

  val refetch = new SpecTest("trap.refetch", Seq("funcTrapSingleOwner")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val ops = Seq(SysOp.FenceI, SysOp.SfenceVma, SysOp.CsrWrite, SysOp.None)
      val os = ops.map(op => d.cycle(Some(Exc(SYSOP, pc = 0x5000, sysOp = op))))
      d.twReady = false
      val noWait = d.cycle(Some(Exc(SYSOP, pc = 0x5000, sysOp = SysOp.FenceI)))
      d.twReady = true
      Seq(
        chk(os.forall(o => one(o) && o.tw.isEmpty && o.ar.exists(r => r.target == 0x5004 && r.cause == code(RecoveryCause.Refetch))),
          "FenceI / SfenceVma / CsrWrite / predictionFault: Refetch to pc + 4 with no CSRTrapWrite", s"$os"),
        chk(one(noWait), "a Refetch does not wait for the CSRTrapWrite edge", s"$noWait")
      )
    }
  }

  // ---- funcTrapDelegation ----------------------------------------------------------------------------

  val delegation = new SpecTest("trap.delegation", Seq("funcTrapDelegation")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val ecallU = 8
      // U-mode ecall delegated by medeleg: TrapEntryS, SPIE := SIE, SIE := 0, SPP := U.
      val u = d.cycle(Some(Exc(SYNC, ecallU, tval = 0, pc = 0x600)), Snap(priv = 0, medeleg = 1L << ecallU, mstatus = 0x2))
      // S-mode page fault delegated: SPP := S.
      val s = d.cycle(Some(Exc(SYNC, 13, tval = 0xbad0, pc = 0x604)), Snap(priv = 1, medeleg = 1L << 13, mstatus = 0x0))
      // Not delegated from S (medeleg clear) and never from M.
      val sm = d.cycle(Some(Exc(SYNC, 13, pc = 0x608)), Snap(priv = 1, medeleg = 0))
      val mm = d.cycle(Some(Exc(SYNC, 13, pc = 0x60c)), Snap(priv = 3, medeleg = 1L << 13))
      // A delegated STI from S with a vectored stvec; an undelegated one goes to M.
      val si = d.cycle(Some(Exc(INTR, 5, pc = 0x610)), Snap(priv = 1, mideleg = 1L << 5, stvec = 0x201, mstatus = 0x2))
      val mi = d.cycle(Some(Exc(INTR, 5, pc = 0x614)), Snap(priv = 1, mideleg = 0))
      Seq(
        chk(u.tw.exists(t => t.kind == code(K.TrapEntryS) && t.xepc == 0x600 && t.xcause == ecallU && t.mstatus == 0x20 && t.priv == 1) &&
          u.ar.exists(_.target == 0x200), "a delegated U-mode exception enters S at stvec (SPIE := SIE, SIE := 0, SPP := U)", s"${u.tw} ${u.ar}"),
        chk(s.tw.exists(t => t.kind == code(K.TrapEntryS) && t.xtval == 0xbad0 && t.mstatus == 0x100 && t.priv == 1),
          "a delegated S-mode exception records SPP = S", s"${s.tw}"),
        chk(sm.tw.exists(t => t.kind == code(K.TrapEntryM) && t.mstatus == 0x800) && mm.tw.exists(_.kind == code(K.TrapEntryM)),
          "undelegated from S (MPP := S), and never delegated when taken in M", s"${sm.tw} ${mm.tw}"),
        chk(si.tw.exists(t => t.kind == code(K.TrapEntryS) && t.xcause == 0x80000005L && t.mstatus == 0x120) &&
          si.ar.exists(_.target == 0x200 + 4 * 5), "a delegated interrupt uses mideleg and the vectored stvec", s"${si.tw} ${si.ar}"),
        chk(mi.tw.exists(_.kind == code(K.TrapEntryM)) && mi.ar.exists(_.target == 0x100), "an undelegated interrupt goes to M", s"${mi.tw}")
      )
    }
  }

  // ---- funcXRet ------------------------------------------------------------------------------------------

  val xret = new SpecTest("trap.xret", Seq("funcXRet")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val MPRV = 1L << 17
      // MRET to S: MIE := MPIE, MPIE := 1, MPP := U, MPRV := 0.
      val m1 = d.cycle(Some(Exc(SYSOP, sysOp = SysOp.Mret)), Snap(priv = 3, mstatus = MPRV | (1L << 11) | 0x80))
      // MRET to M keeps MPRV.
      val m2 = d.cycle(Some(Exc(SYSOP, sysOp = SysOp.Mret)), Snap(priv = 3, mstatus = MPRV | (3L << 11)))
      // SRET to S: SIE := SPIE (0), SPIE := 1, SPP := U, MPRV := 0.
      val s1 = d.cycle(Some(Exc(SYSOP, sysOp = SysOp.Sret)), Snap(priv = 1, mstatus = MPRV | 0x100 | 0x2))
      // SRET to U.
      val s2 = d.cycle(Some(Exc(SYSOP, sysOp = SysOp.Sret)), Snap(priv = 1, mstatus = 0x20))
      Seq(
        chk(m1.tw.exists(t => t.kind == code(K.MRet) && t.priv == 1 && t.mstatus == 0x88) &&
          m1.ar.exists(r => r.target == 0x3000 && r.cause == code(RecoveryCause.XRet)), "MRET: priv := MPP, MIE := MPIE, MPIE := 1, MPP := U, MPRV := 0; target mepc",
          s"${m1.tw} ${m1.ar}"),
        chk(m2.tw.exists(t => t.priv == 3 && t.mstatus == (MPRV | 0x80)), "MRET to M keeps MPRV", s"${m2.tw}"),
        chk(s1.tw.exists(t => t.kind == code(K.SRet) && t.priv == 1 && t.mstatus == 0x20) &&
          s1.ar.exists(r => r.target == 0x4000 && r.cause == code(RecoveryCause.XRet)),
          "SRET: priv := SPP, SIE := SPIE, SPIE := 1, SPP := U, MPRV := 0; target sepc", s"${s1.tw} ${s1.ar}"),
        chk(s2.tw.exists(t => t.priv == 0 && t.mstatus == 0x22), "SRET to U restores SIE from SPIE", s"${s2.tw}")
      )
    }
  }

  // ---- funcDebugCommitBoundary -------------------------------------------------------------------------------

  val debug = new SpecTest("trap.debug", Seq("funcDebugCommitBoundary")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val ebreakm = 1L << 15
      val o = d.cycle(Some(Exc(DBG, 3, pc = 0x7000, s = 9)), Snap(priv = 1, dcsr = (4L << 28) | ebreakm | 3))
      Seq(
        chk(o.tw.exists(t => t.kind == code(K.DebugEntry) && t.dpc == 0x7000 && t.priv == 3 &&
          t.dcsr == ((4L << 28) | ebreakm | (3L << 6) | 1)),
          "Exception{Debug}: DebugEntry {dpc = pc, dcsr.cause = 3, dcsr.prv = priv, other dcsr bits kept, priv M}", s"${o.tw}"),
        chk(o.ar.exists(r => r.cause == code(RecoveryCause.Debug) && r.target == p.debugEntryAddr && r.tag == tagOf(9)) && one(o),
          "the debug-entry ArchRedirect carries cause Debug (never Trap) and the debug entry PC", s"${o.ar}")
      )
    }
  }

  /** ADR-019E E-2/E-5: a non-default platform debugEntryAddr is honored; dpc stays the next
    * normal PC (never the entry address). */
  val pAlt = p.copy(debugEntryAddr = 0x40000800L)
  val debugEntryAddr = new SpecTest("trap.debugEntryAddr", Seq("funcDebugCommitBoundary")) {
    def run(): Seq[TCheck] = withDrv(this, pAlt) { d =>
      val o = d.cycle(Some(Exc(DBG, 3, pc = 0x7004, s = 2)), Snap(priv = 0))
      Seq(
        chk(o.ar.exists(r => r.target == 0x40000800L && r.cause == code(RecoveryCause.Debug)),
          "DebugEntry redirects to the configured (non-default) debugEntryAddr", s"${o.ar}"),
        chk(o.tw.exists(t => t.dpc == 0x7004 && t.dpc != pAlt.debugEntryAddr && (t.dcsr & 3) == 0),
          "dpc is the next normal PC (the head pc), not debugEntryAddr; dcsr.prv records U", s"${o.tw}")
      )
    }
  }

  // ---- funcXRet: DRET (ADR-019E E-3/E-5) ------------------------------------------------------------

  val dret = new SpecTest("trap.dret", Seq("funcXRet")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val toS = d.cycle(Some(Exc(SYSOP, pc = 0x800, s = 4, sysOp = SysOp.Dret)),
        Snap(priv = 3, dcsr = (4L << 28) | (3L << 6) | 1, dpc = 0x2468, mstatus = 0x1888))
      val toU = d.cycle(Some(Exc(SYSOP, pc = 0x804, sysOp = SysOp.Dret)), Snap(priv = 3, dcsr = (4L << 28) | 0, dpc = 0x1000))
      d.arReady = false
      val bp = d.cycle(Some(Exc(SYSOP, pc = 0x804, sysOp = SysOp.Dret)), Snap(dpc = 0x1000))
      d.arReady = true
      Seq(
        chk(one(toS) && toS.twFire && toS.tw.exists(t => t.kind == code(K.DRet) && t.priv == 1 && t.mstatus == 0x1888),
          "DRET: CSRTrapWrite{DRet, privNext = dcsr.prv}, mstatus unchanged", s"${toS.tw}"),
        chk(toS.ar.exists(r => r.target == 0x2468 && r.cause == code(RecoveryCause.XRet) && r.tag == tagOf(4)),
          "DRET redirects to dpc with cause XRet (not to debugEntryAddr)", s"${toS.ar}"),
        chk(toU.tw.exists(t => t.kind == code(K.DRet) && t.priv == 0) && toU.ar.exists(_.target == 0x1000),
          "DRET restores U from dcsr.prv", s"${toU.tw} ${toU.ar}"),
        chk(!bp.excReady && bp.tw.isEmpty, "DRET is one atomic transfer (no CSRTrapWrite without its ArchRedirect)", s"$bp")
      )
    }
  }

  // ---- propTrapSingleWriter -----------------------------------------------------------------------------------

  val atomic = new SpecTest("trap.singleWriter", Seq("propTrapSingleWriter")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val rnd = new scala.util.Random(0x7c)
      var bad = Seq.empty[String]
      var fires = 0
      val kinds = Seq(Exc(SYNC, 2), Exc(INTR, 11), Exc(DBG, 3), Exc(SYSOP, sysOp = SysOp.Mret), Exc(SYSOP, sysOp = SysOp.FenceI),
        Exc(SYSOP, sysOp = SysOp.Dret))
      for (step <- 0 until 300) {
        d.twReady = rnd.nextInt(3) > 0; d.arReady = rnd.nextInt(3) > 0
        val e = if (rnd.nextBoolean()) Some(kinds(rnd.nextInt(kinds.size))) else None
        val o = d.cycle(e)
        val excFire = e.nonEmpty && o.excReady
        val needsWrite = e.exists(x => x.source != SYSOP || x.sysOp == SysOp.Mret || x.sysOp == SysOp.Dret)
        if (o.twFire != (excFire && needsWrite)) bad :+= s"step $step: CSRTrapWrite fire ${o.twFire} vs hand-off $excFire"
        if (o.arFire != excFire) bad :+= s"step $step: ArchRedirect fire ${o.arFire} vs hand-off $excFire"
        if (e.isEmpty && (o.tw.nonEmpty || o.ar.nonEmpty)) bad :+= s"step $step: output without a hand-off"
        if (excFire) fires += 1
      }
      Seq(
        chk(bad.isEmpty, "every trap-driven write fires only with its hand-off and its ArchRedirect, at most one per cycle",
          bad.take(3).mkString("; ")),
        chk(fires > 50, "the stream exercised hand-offs", s"$fires")
      )
    }
  }

  val all: Seq[SpecTest] = Seq(entryM, refetch, delegation, xret, debug, debugEntryAddr, dret, atomic)
}
