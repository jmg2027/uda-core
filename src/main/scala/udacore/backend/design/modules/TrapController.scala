package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.TrapControllerSpecs._

/** TrapController (spec: TrapControllerSpecs; ADR-004, ADR-019 D-19.9, ADR-019B E-1, ADR-019D).
  *
  * Computes, for the one commit-head hand-off it is offered, the trap/return transition from
  * the CSRTrapRead snapshot and emits it as one CSRTrapWrite (when trap state changes) plus one
  * ArchRedirect. It owns no CSR register (the CsrController applies the packet, ADR-019D E-7).
  * The hand-off, the CSRTrapWrite, and the ArchRedirect transfer in the same cycle: each valid
  * waits for the other consumer's ready (never for its own), and ExceptionIn.ready is the
  * conjunction of the readies the hand-off needs.
  *
  * Recovery stance: it holds no token (a hand-off is combinational through this vertex) and
  * always concerns the oldest uop, which no BranchMispredict can kill.
  */
@LocalSpec(contTrapController)
class TrapController(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfExceptionIn)
    val exceptionIn = Flipped(Decoupled(new ExceptionReq(params)))

    @LocalSpec(intfCsrTrapReadIn)
    val csrTrapReadIn = Input(new CsrTrapRead(params))

    @LocalSpec(intfCsrTrapWriteOut)
    val csrTrapWriteOut = Decoupled(new CsrTrapWrite(params))

    @LocalSpec(intfArchRedirectOut)
    val archRedirectOut = Decoupled(new ArchRedirect(params))
  })

  private val e    = io.exceptionIn.bits
  private val r    = io.csrTrapReadIn
  private val m    = r.mstatus
  private val priv = r.priv

  private val isSync    = e.source === ExceptionSource.Sync
  private val isInt     = e.source === ExceptionSource.Interrupt
  private val isDebug   = e.source === ExceptionSource.Debug
  private val isSysOp   = e.source === ExceptionSource.SysOp
  private val isMret    = isSysOp && e.sysOp === SysOp.Mret
  private val isSret    = isSysOp && e.sysOp === SysOp.Sret
  private val isDret    = isSysOp && e.sysOp === SysOp.Dret // ADR-019E E-3
  private val isXRet    = isMret || isSret || isDret
  private val isRefetch = isSysOp && !isXRet

  /** mstatus bit positions (RV32). */
  private object St { val SIE = 1; val MIE = 3; val SPIE = 5; val MPIE = 7; val SPP = 8; val MPP = 11; val MPRV = 17 }
  private def put(v: UInt, i: Int, b: Bool): UInt = { val w = WireInit(VecInit(v.asBools)); w(i) := b; w.asUInt }
  private def putMpp(v: UInt, p: UInt): UInt = put(put(v, St.MPP, p(0)), St.MPP + 1, p(1))

  // ---- funcTrapDelegation -----------------------------------------------------------------------

  @LocalSpec(funcTrapDelegation)
  val toS: Bool = (isSync || isInt) && priv =/= Priv.M && Mux(isInt, r.mideleg(e.cause), r.medeleg(e.cause))

  // ---- funcXRet ------------------------------------------------------------------------------------

  /** (mstatusNext, privNext, target) of MRET / SRET / DRET. DRET restores dcsr.prv, resumes
    * at dpc (never at debugEntryAddr), and leaves mstatus unchanged (ADR-019E E-3/E-5). */
  @LocalSpec(funcXRet)
  val xRet: (UInt, UInt, UInt) = {
    val mpp   = m(12, 11)
    val mret0 = put(put(putMpp(m, Priv.U), St.MIE, m(St.MPIE)), St.MPIE, true.B)
    val mret  = Mux(mpp =/= Priv.M, put(mret0, St.MPRV, false.B), mret0)
    val spp   = m(St.SPP)
    val sret  = put(put(put(put(m, St.SIE, m(St.SPIE)), St.SPIE, true.B), St.SPP, false.B), St.MPRV, false.B)
    (MuxCase(sret, Seq(isMret -> mret, isDret -> m)),
     MuxCase(Cat(0.U(1.W), spp), Seq(isMret -> mpp, isDret -> r.dcsr(1, 0))),
     MuxCase(r.sepc, Seq(isMret -> r.mepc, isDret -> r.dpc)))
  }

  // ---- funcDebugCommitBoundary ---------------------------------------------------------------------

  /** DebugEntry dcsr: cause := the hand-off cause (3 = haltreq), prv := priv, other bits kept. */
  @LocalSpec(funcDebugCommitBoundary)
  val debugCommitBoundary: UInt = (r.dcsr & ~0x1c3L.U(32.W)) | (e.cause(2, 0).pad(32) << 6) | priv.pad(32)

  // ---- funcTrapSingleOwner --------------------------------------------------------------------------

  @LocalSpec(funcTrapSingleOwner)
  val needsWrite: Bool = {
    val tw    = io.csrTrapWriteOut
    val ar    = io.archRedirectOut
    val needs = !isRefetch

    val trapM      = putMpp(put(put(m, St.MPIE, m(St.MIE)), St.MIE, false.B), priv)
    val trapS      = put(put(put(m, St.SPIE, m(St.SIE)), St.SIE, false.B), St.SPP, priv(0))
    val tvec       = Mux(toS, r.stvec, r.mtvec)
    val base       = Cat(tvec(31, 2), 0.U(2.W))
    val trapTarget = Mux(tvec(0) && isInt, base + (e.cause.pad(32) << 2), base)

    val w = tw.bits
    w.kind := MuxCase(TrapWriteKind.TrapEntryM, Seq(
      isDebug -> TrapWriteKind.DebugEntry, isMret -> TrapWriteKind.MRet, isSret -> TrapWriteKind.SRet,
      isDret -> TrapWriteKind.DRet,
      toS -> TrapWriteKind.TrapEntryS))
    w.xepc        := e.pc
    w.xcause      := Cat(isInt, 0.U(26.W), e.cause)
    w.xtval       := Mux(isInt, 0.U, e.tval)
    w.mstatusNext := Mux(isXRet, xRet._1, Mux(toS, trapS, trapM))
    w.privNext    := MuxCase(Priv.M, Seq(isXRet -> xRet._2, toS -> Priv.S))
    w.dpc         := e.pc
    w.dcsrNext    := debugCommitBoundary

    val a = ar.bits
    a.robTag := e.robTag
    a.ftqIdx := e.ftqIdx
    a.target := MuxCase(trapTarget, Seq(
      isDebug -> params.debugEntryAddr.U(vAddrWidth.W), isXRet -> xRet._3, isRefetch -> (e.pc + 4.U)))
    a.cause := MuxCase(RecoveryCause.Trap, Seq(
      isInt -> RecoveryCause.Interrupt, isDebug -> RecoveryCause.Debug, isXRet -> RecoveryCause.XRet,
      isRefetch -> RecoveryCause.Refetch))

    // One atomic transfer: each valid waits only for the other consumer's ready.
    tw.valid := io.exceptionIn.valid && needs && ar.ready
    ar.valid := io.exceptionIn.valid && (!needs || tw.ready)
    io.exceptionIn.ready := ar.ready && (!needs || tw.ready)
    needs
  }

  // ---- propTrapSingleWriter (simulation assertion) ----------------------------------------------------

  @LocalSpec(propTrapSingleWriter)
  val trapSingleWriter: Unit = {
    assert(io.csrTrapWriteOut.fire === (io.exceptionIn.fire && needsWrite),
      "TrapSingleWriter: a CSRTrapWrite fired without its hand-off")
    assert(io.archRedirectOut.fire === io.exceptionIn.fire,
      "TrapSingleWriter: the ArchRedirect and the hand-off are not one transfer")
    assert(!io.exceptionIn.valid || priv =/= 2.U, "TrapSingleWriter: reserved committed privilege")
  }
}
