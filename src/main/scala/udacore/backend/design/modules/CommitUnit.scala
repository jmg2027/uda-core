package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.CommitUnitSpecs._
import udacore.core.design.shared.{CacheMaintenance, DebugReq, MaintOp, TlbFlush}

/** CommitUnit (spec: CommitUnitSpecs; ADR-019 D-19.7/D-19.8, ADR-019A, ADR-019B).
  *
  * Consumes the ROB head in program order. Exactly one of these acts on a presented head
  * in a cycle, in priority order:
  *  1. an interrupt/debug hand-off sampled at the boundary (funcInterruptSampling), held
  *     stable once offered;
  *  2. the precise hand-off of a head that carries an exception (funcPreciseTrapHandoff);
  *  3. the HeadMemGrant of an uncacheable head (funcHeadMemGrant);
  *  4. a maintenance sequence step of a FENCE / FENCE.I / SFENCE.VMA head;
  *  5. the retirement (funcCommitHead), atomic over its required views and, for a
  *     retiring redirect, its ExceptionOut (ADR-019B E-6).
  * After any ExceptionOut transfer the unit holds (trapPending) until the ArchRedirect that
  * names the hand-off robTag.
  *
  * Recovery stance: it only ever handles the oldest live uop, which no BranchMispredict can
  * kill; an ArchRedirect ends any sequence, grant, or latched hand-off it caused.
  */
@LocalSpec(contCommitUnit)
class CommitUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfRobHeadIn)
    val robHeadIn = Flipped(Decoupled(new RobHead(params)))

    @LocalSpec(intfRenameCommitOut)
    val renameCommitOut = Decoupled(new RenameCommit(params))

    @LocalSpec(intfStoreCommitOut)
    val storeCommitOut = Decoupled(new StoreCommit(params))

    @LocalSpec(intfFtqCommitOut)
    val ftqCommitOut = Decoupled(new FtqCommit(params))

    @LocalSpec(intfCommitGrantOut)
    val commitGrantOut = Output(new CommitGrant(params))

    @LocalSpec(intfExceptionOut)
    val exceptionOut = Decoupled(new ExceptionReq(params))

    @LocalSpec(intfInterruptCtrlIn)
    val interruptCtrlIn = Input(new InterruptCtrl)

    @LocalSpec(intfRetireStreamOut)
    val retireStreamOut = if (params.usingRvvi) Some(Decoupled(new RetireToken(params))) else None

    @LocalSpec(intfCommitPrfReadReqOut)
    val commitPrfReadReqOut = if (params.usingRvvi) Some(Decoupled(new CommitPrfReadReq(params))) else None

    @LocalSpec(intfCommitPrfReadRespIn)
    val commitPrfReadRespIn = if (params.usingRvvi) Some(Flipped(Decoupled(new CommitPrfReadResp(params)))) else None

    @LocalSpec(intfStoreBufferDrainReqOut)
    val storeBufferDrainReqOut = Decoupled(new HandshakeToken)

    @LocalSpec(intfStoreBufferDrainRespIn)
    val storeBufferDrainRespIn = Flipped(Decoupled(new HandshakeToken))

    @LocalSpec(intfICacheInvalidateOut)
    val iCacheInvalidateOut = Decoupled(new CacheMaintenance)

    @LocalSpec(intfDCacheCleanReqOut)
    val dCacheCleanReqOut = Decoupled(new CacheMaintenance)

    @LocalSpec(intfDCacheCleanRespIn)
    val dCacheCleanRespIn = Flipped(Decoupled(new CacheMaintenance))

    @LocalSpec(intfSfenceVmaOut)
    val sfenceVmaOut = Decoupled(new TlbFlush(vAddrWidth))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))

    @LocalSpec(intfHeadMemGrantOut)
    val headMemGrantOut = Decoupled(new HeadMemGrant(params))

    @LocalSpec(intfDebugReqIn)
    val debugReqIn = Input(new DebugReq)
  })

  private val hv   = io.robHeadIn.valid
  private val head = io.robHeadIn.bits.entry
  private val tag  = io.robHeadIn.bits.robTag
  private val ev   = io.recoveryEventIn
  private val archEv = ev.valid && ev.kind === RecoveryKind.ArchRedirect
  private val ic   = io.interruptCtrlIn
  private val exc  = io.exceptionOut

  // ---- Head classification ---------------------------------------------------------

  private val op          = head.sysOp
  private val isHx        = head.headExecute && !head.done
  private val isExc       = head.done && head.exception.valid
  private val isSeqOp     = op === SysOp.Fence || op === SysOp.FenceI || op === SysOp.SfenceVma
  /** Retiring architectural redirects (ADR-019B E-6). */
  private val redirecting = op === SysOp.FenceI || op === SysOp.SfenceVma || op === SysOp.CsrWrite ||
    op === SysOp.Mret || op === SysOp.Sret || head.predictionFault
  /** Intrinsic retiring redirects keep their sysOp in ExceptionOut; a redirect caused only by
    * predictionFault carries sysOp None (ADR-019B E-6). */
  private val intrinsicRedirect = op === SysOp.FenceI || op === SysOp.SfenceVma || op === SysOp.CsrWrite ||
    op === SysOp.Mret || op === SysOp.Sret
  /** A CSR uop: serialize with sysOp None (read-only) or CsrWrite (ADR-019A E-2). */
  private val isCsr       = head.serialize && (op === SysOp.None || op === SysOp.CsrWrite)

  // ---- State -------------------------------------------------------------------------

  val trapPending   = RegInit(false.B)
  val pendingTag    = Reg(new RobTag(params))
  val grantInFlight = RegInit(false.B)
  val grantTag      = Reg(new RobTag(params))
  val asyncLatched  = RegInit(false.B)
  val latchedSource = Reg(UInt(ExceptionSource.width.W))
  val latchedCause  = Reg(UInt(5.W))

  private object Step {
    val width = 3
    val Idle      = 0.U(width.W)
    val DrainReq  = 1.U(width.W)
    val DrainWait = 2.U(width.W)
    val CleanReq  = 3.U(width.W)
    val CleanWait = 4.U(width.W)
    val Inv       = 5.U(width.W)
    val Flush     = 6.U(width.W)
    val Final     = 7.U(width.W)
  }
  val seqState = RegInit(Step.Idle)

  private val hold = trapPending

  // ---- funcInterruptSampling -----------------------------------------------------------

  private val dbgTake = io.debugReqIn.debugReq && !ic.debugMode
  private val intTake = ic.interruptPending && !ic.debugMode

  /** An interrupt/debug hand-off is offered this cycle (sampled now, or latched earlier). A
    * presented serialize head suppresses sampling from its first presented cycle until it
    * retires or traps (ADR-019D E-5): a CSR write may already be staged in the CsrController. */
  @LocalSpec(funcInterruptSampling)
  val interruptSampling: Bool = {
    val sampleOk = hv && !head.serialize && !hold && !grantInFlight && seqState === Step.Idle
    !hold && (asyncLatched || (sampleOk && (dbgTake || intTake)))
  }
  private val takeAsync   = interruptSampling
  private val asyncSource = Mux(asyncLatched, latchedSource, Mux(dbgTake, ExceptionSource.Debug, ExceptionSource.Interrupt))
  private val asyncCause  = Mux(asyncLatched, latchedCause, Mux(dbgTake, 3.U(5.W), ic.interruptCause)) // 3 = haltreq
  when(takeAsync && !exc.ready) {
    asyncLatched  := true.B
    latchedSource := asyncSource
    latchedCause  := asyncCause
  }
  when(exc.fire || archEv) { asyncLatched := false.B }

  private val active = hv && !hold && !takeAsync

  // ---- funcPreciseTrapHandoff ------------------------------------------------------------

  @LocalSpec(funcPreciseTrapHandoff)
  val preciseTrapHandoff: Bool = active && isExc
  private val syncGo = preciseTrapHandoff

  // ---- funcHeadMemGrant --------------------------------------------------------------------

  @LocalSpec(funcHeadMemGrant)
  val headMemGrant: Unit = {
    io.headMemGrantOut.valid       := active && isHx && !grantInFlight
    io.headMemGrantOut.bits.robTag := tag
    when(io.headMemGrantOut.fire) {
      grantInFlight := true.B
      grantTag      := tag
    }
    // Cleared at that head's retirement or trap hand-off, or by the ArchRedirect.
    when((io.robHeadIn.fire && tag.asUInt === grantTag.asUInt) || archEv) { grantInFlight := false.B }
  }

  // ---- funcSystemOpSequencing ----------------------------------------------------------------

  private val retireEligible = active && head.done && !head.exception.valid

  @LocalSpec(funcSystemOpSequencing)
  val systemOpSequencing: Unit = {
    io.storeBufferDrainReqOut.valid := seqState === Step.DrainReq
    io.storeBufferDrainRespIn.ready := seqState === Step.DrainWait
    io.dCacheCleanReqOut.valid      := seqState === Step.CleanReq
    io.dCacheCleanReqOut.bits.op    := MaintOp.DCacheCleanAll
    io.dCacheCleanRespIn.ready      := seqState === Step.CleanWait
    io.iCacheInvalidateOut.valid    := seqState === Step.Inv
    io.iCacheInvalidateOut.bits.op  := MaintOp.ICacheInvalidateAll
    io.sfenceVmaOut.valid           := seqState === Step.Flush
    // v0 flushes every TLB entry; the rs1/rs2 operands are not available at commit.
    io.sfenceVmaOut.bits.vaddr      := 0.U
    io.sfenceVmaOut.bits.vaddrValid := false.B
    io.sfenceVmaOut.bits.asid       := 0.U
    io.sfenceVmaOut.bits.asidValid  := false.B
    switch(seqState) {
      is(Step.Idle)      { when(retireEligible && isSeqOp) { seqState := Step.DrainReq } }
      is(Step.DrainReq)  { when(io.storeBufferDrainReqOut.fire) { seqState := Step.DrainWait } }
      is(Step.DrainWait) {
        when(io.storeBufferDrainRespIn.fire) {
          seqState := Mux(op === SysOp.Fence, Step.Final, Mux(op === SysOp.FenceI, Step.CleanReq, Step.Flush))
        }
      }
      is(Step.CleanReq)  { when(io.dCacheCleanReqOut.fire) { seqState := Step.CleanWait } }
      is(Step.CleanWait) { when(io.dCacheCleanRespIn.fire) { seqState := Step.Inv } }
      is(Step.Inv)       { when(io.iCacheInvalidateOut.fire) { seqState := Step.Final } }
      is(Step.Flush)     { when(io.sfenceVmaOut.fire) { seqState := Step.Final } }
      is(Step.Final)     { when(io.robHeadIn.fire) { seqState := Step.Idle } }
    }
    when(archEv) { seqState := Step.Idle }
  }

  // ---- funcCommitHead (atomic over the required views, ADR-019B E-6) --------------------------

  private val retireGo = retireEligible && (!isSeqOp || seqState === Step.Final)
  private val rcOk  = io.renameCommitOut.ready
  private val scOk  = !head.isStore || io.storeCommitOut.ready
  private val fqOk  = !head.blockEnd || io.ftqCommitOut.ready
  private val exOk  = !redirecting || exc.ready

  @LocalSpec(funcCommitHead)
  val commitHead: Bool = {
    io.renameCommitOut.valid        := retireGo && scOk && fqOk && exOk
    io.renameCommitOut.bits.archRd  := head.archRd
    io.renameCommitOut.bits.newPrd  := head.newPrd
    io.renameCommitOut.bits.oldPrd  := head.oldPrd
    io.renameCommitOut.bits.hasDest := head.hasDest
    io.storeCommitOut.valid         := retireGo && head.isStore && rcOk && fqOk && exOk
    io.storeCommitOut.bits.robTag   := tag
    io.ftqCommitOut.valid           := retireGo && head.blockEnd && rcOk && scOk && exOk
    retireGo && rcOk && scOk && fqOk && exOk
  }
  private val retireFire = commitHead

  io.commitGrantOut.valid  := retireFire && isCsr
  io.commitGrantOut.robTag := tag

  // ---- funcBlockEndCommit ----------------------------------------------------------------------

  @LocalSpec(funcBlockEndCommit)
  val blockEndCommit: Unit = {
    io.ftqCommitOut.bits.ftqIdx := head.ftqIdx
    val takenCfi = head.isCfi && head.cfiOutcome.taken
    io.ftqCommitOut.bits.exit := Mux(takenCfi, head.cfiOutcome, 0.U.asTypeOf(head.cfiOutcome)) // cfiType None
  }

  // ---- ExceptionOut and the head transfer ---------------------------------------------------------

  private val retireExcValid = retireGo && redirecting && rcOk && scOk && fqOk
  exc.valid       := takeAsync || syncGo || retireExcValid
  exc.bits.source := Mux(takeAsync, asyncSource, Mux(syncGo, ExceptionSource.Sync, ExceptionSource.SysOp))
  exc.bits.cause  := Mux(takeAsync, asyncCause, Mux(syncGo, head.exception.cause, 0.U))
  exc.bits.tval   := Mux(!takeAsync && syncGo, head.exception.tval, 0.U)
  exc.bits.pc     := head.pc
  exc.bits.robTag := tag
  exc.bits.ftqIdx := head.ftqIdx
  exc.bits.sysOp  := Mux(!takeAsync && !syncGo && intrinsicRedirect, op, SysOp.None)

  when(exc.valid && exc.bits.source === ExceptionSource.SysOp) {
    assert(exc.bits.sysOp === SysOp.None || intrinsicRedirect,
      "SystemOpSequencing: ExceptionOut{SysOp} carries a non-redirect sysOp")
  }

  // The head leaves on a retirement, or is locked in the ROB by the trap hand-off.
  io.robHeadIn.ready := (syncGo && exc.ready) || retireFire

  // ---- funcTrapHold ---------------------------------------------------------------------------------

  @LocalSpec(funcTrapHold)
  val trapHold: Unit = {
    // A matching ArchRedirect in the transfer cycle (a zero-latency TrapController) satisfies
    // the hold at once.
    val releasedNow = archEv && ev.robTag.asUInt === tag.asUInt
    when(exc.fire && !releasedNow) {
      trapPending := true.B
      pendingTag  := tag
    }
    // Only the ArchRedirect naming the hand-off robTag releases the hold.
    when(trapPending && archEv && ev.robTag.asUInt === pendingTag.asUInt) { trapPending := false.B }
  }

  // ---- funcRetireStreamEmit (usingRvvi only; ADR-010 D-10.3, ADR-019B E-3/E-4/E-5) ---------------

  private val tokRetire = io.robHeadIn.fire && !isExc
  private val tokTrap   = exc.fire && (takeAsync || syncGo)

  @LocalSpec(funcRetireStreamEmit)
  val retireStreamEmit: Unit = if (params.usingRvvi) {
    val tok  = io.retireStreamOut.get
    val req  = io.commitPrfReadReqOut.get
    val resp = io.commitPrfReadRespIn.get
    val wen  = tokRetire && head.hasDest && head.archRd =/= 0.U
    req.valid     := wen
    req.bits.prd  := head.newPrd
    resp.ready    := true.B
    val retireOrder = RegInit(0.U(64.W))
    when(tok.valid) { retireOrder := retireOrder + 1.U }
    tok.valid       := tokRetire || tokTrap
    tok.bits.order  := retireOrder
    tok.bits.pc     := head.pc
    tok.bits.insn   := head.insn
    tok.bits.rd     := head.archRd
    tok.bits.wdata  := Mux(wen, resp.bits.data, 0.U)
    tok.bits.wen    := wen
    tok.bits.trap   := tokTrap
    tok.bits.source := Mux(tokTrap, exc.bits.source, ExceptionSource.Sync)
    tok.bits.cause  := Mux(tokTrap, exc.bits.cause, 0.U)
    tok.bits.tval   := Mux(tokTrap, exc.bits.tval, 0.U)
    tok.bits.priv   := ic.priv // the executing privilege, before any transition (E-4)
  }

  // ---- Properties (simulation assertions) ------------------------------------------------------------

  private val views = io.renameCommitOut.valid || io.storeCommitOut.valid || io.ftqCommitOut.valid ||
    io.commitGrantOut.valid

  @LocalSpec(propCommitInOrder)
  val commitInOrder: Unit = {
    val viewFires = io.renameCommitOut.fire || io.storeCommitOut.fire || io.ftqCommitOut.fire || io.commitGrantOut.valid
    assert(!viewFires || (io.robHeadIn.fire && !isExc), "CommitInOrder: a commit view fires without a retirement")
    assert(!(io.renameCommitOut.fire && head.isStore) || io.storeCommitOut.fire,
      "CommitInOrder: a store retired without its StoreCommit")
    // Head transfers follow program order; only an ArchRedirect moves the expectation.
    val expectTag = RegInit(0.U.asTypeOf(new RobTag(params)))
    when(io.robHeadIn.fire) {
      assert(tag.asUInt === expectTag.asUInt, "CommitInOrder: retired robTag is not the next in program order")
    }
    when(archEv) {
      expectTag := RobOrder.next(ev.robTag)
    }.elsewhen(io.robHeadIn.fire && !isExc) {
      expectTag := RobOrder.next(tag)
    }
  }

  @LocalSpec(propNoCommitPastException)
  val noCommitPastException: Unit = {
    val syncHeld = RegInit(false.B)
    val syncTag  = Reg(new RobTag(params))
    when(exc.fire && syncGo && !(archEv && ev.robTag.asUInt === tag.asUInt)) { syncHeld := true.B; syncTag := tag }
    when(syncHeld && archEv && ev.robTag.asUInt === syncTag.asUInt) { syncHeld := false.B }
    assert(!(syncGo && views), "NoCommitPastException: a commit view offered with a trapping head")
    assert(!(syncHeld && (views || io.robHeadIn.fire)), "NoCommitPastException: commit after an exception hand-off")
  }

  @LocalSpec(propTrapHoldUntilRedirect)
  val trapHoldUntilRedirect: Unit =
    when(trapPending) {
      assert(!io.robHeadIn.fire && !views && !exc.valid && !io.headMemGrantOut.valid,
        "TrapHoldUntilRedirect: activity during the trap hold")
    }

  @LocalSpec(propRetireNonBlocking)
  val retireNonBlocking: Unit = if (params.usingRvvi) {
    val tok = io.retireStreamOut.get
    val req = io.commitPrfReadReqOut.get
    assert(!tok.valid || tok.ready, "RetireNonBlocking: retire token not accepted")
    assert(!req.valid || (req.ready && io.commitPrfReadRespIn.get.valid),
      "RetireNonBlocking: commit PRF read not answered in the retire cycle")
  }
}
