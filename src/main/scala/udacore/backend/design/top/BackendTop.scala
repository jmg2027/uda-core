package udacore.backend.design.top

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.modules._
import udacore.backend.design.shared._
import udacore.backend.spec.top.BackendTopSpecs._
import udacore.core.design.shared._
import udacore.frontend.design.shared.FetchPacket

/** BackendTop (spec: BackendTopSpecs; ADR-019 D-19.7..D-19.9). rawTop: vertex instantiation and
  * wiring only, one connection per edge of the BackendTop graph. The RecoveryController's
  * RecoveryEvent is the only squash fact; it fans out to every speculative holder and the
  * boundary. BitAluUnit and its edges are absent in the v0 configuration (enableBitAlu off).
  */
@LocalSpec(contBackendTop)
class BackendTop(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfFetchPacketIn)
    val fetchPacketIn = Flipped(Decoupled(new FetchPacket(params.decodeWidth, vAddrWidth, ftqIdxWidth, fetchSlotWidth)))

    @LocalSpec(intfInterruptIn)
    val interruptIn = Input(new Interrupt)

    @LocalSpec(intfDebugReqIn)
    val debugReqIn = Input(new DebugReq)

    @LocalSpec(intfDtlbStoreRespIn)
    val dtlbStoreRespIn = Flipped(Decoupled(new Translation(params.lsqReqIdWidth, pAddrWidth)))

    @LocalSpec(intfDtlbRefillIn)
    val dtlbRefillIn = Flipped(Decoupled(new WalkResp))

    @LocalSpec(intfDCacheLoadRespIn)
    val dCacheLoadRespIn = Flipped(Decoupled(new DCacheLoadResp(params.lqIdxWidth, params.lsqGenWidth, pAddrWidth, xLen)))

    @LocalSpec(intfUncachedStoreRespIn)
    val uncachedStoreRespIn = Flipped(Decoupled(new UncachedStoreResp(params.sqIdxWidth)))

    @LocalSpec(intfUncachedLoadRespIn)
    val uncachedLoadRespIn = Flipped(Decoupled(new UncachedLoadResp(params.lqIdxWidth, params.lsqGenWidth, xLen)))

    @LocalSpec(intfStoreDrainRespIn)
    val storeDrainRespIn = Flipped(Decoupled(new StoreDrainResp))

    @LocalSpec(intfDCacheCleanRespIn)
    val dCacheCleanRespIn = Flipped(Decoupled(new CacheMaintenance))

    @LocalSpec(intfRecoveryEventOut)
    val recoveryEventOut = Output(new RecoveryEvent(params))

    @LocalSpec(intfFtqCommitOut)
    val ftqCommitOut = Decoupled(new FtqCommit(params))

    @LocalSpec(intfDtlbReqOut)
    val dtlbReqOut = Decoupled(new TranslateReq(params.lsqReqIdWidth, vAddrWidth))

    @LocalSpec(intfDCacheLoadReqOut)
    val dCacheLoadReqOut = Decoupled(new DCacheLoadReq(params.lqIdxWidth, params.lsqGenWidth, vAddrWidth))

    @LocalSpec(intfUncachedStoreReqOut)
    val uncachedStoreReqOut = Decoupled(new UncachedStoreReq(params.sqIdxWidth, pAddrWidth, xLen))

    @LocalSpec(intfUncachedLoadReqOut)
    val uncachedLoadReqOut = Decoupled(new UncachedLoadReq(params.lqIdxWidth, params.lsqGenWidth, pAddrWidth))

    @LocalSpec(intfStoreDrainReqOut)
    val storeDrainReqOut = Decoupled(new StoreDrainReq(pAddrWidth, xLen))

    @LocalSpec(intfTranslationContextOut)
    val translationContextOut = Output(new TranslationContext)

    @LocalSpec(intfSfenceVmaOut)
    val sfenceVmaOut = Decoupled(new TlbFlush(vAddrWidth))

    @LocalSpec(intfICacheInvalidateOut)
    val iCacheInvalidateOut = Decoupled(new CacheMaintenance)

    @LocalSpec(intfDCacheCleanReqOut)
    val dCacheCleanReqOut = Decoupled(new CacheMaintenance)

    @LocalSpec(intfRetireStreamOut)
    val retireStreamOut = if (params.usingRvvi) Some(Decoupled(new RetireToken(params))) else None
  })

  require(!params.enableBitAlu, "the v0 BackendTop does not elaborate the BitAluUnit")

  // ---- Vertices ----------------------------------------------------------------------------------
  val dec  = Module(new DecodeUnit(params))
  val rn   = Module(new RenameUnit(params))
  val rob  = Module(new ReorderBuffer(params))
  val rs   = Module(new ReservationStation(params))
  val prf  = Module(new PhysicalRegisterFile(params))
  val dis  = Module(new DispatchUnit(params))
  val alu  = Module(new AluUnit(params))
  val mul  = Module(new MultiplierUnit(params))
  val div  = Module(new DividerUnit(params))
  val bru  = Module(new BranchUnit(params))
  val agu  = Module(new AddressGenerationUnit(params))
  val csr  = Module(new CsrController(params))
  val pub  = Module(new PublishMux(params))
  val lsq  = Module(new LoadStoreQueue(params))
  val sb   = Module(new StoreBuffer(params))
  val com  = Module(new CommitUnit(params))
  val trap = Module(new TrapController(params))
  val rc   = Module(new RecoveryController(params))

  private val ev = rc.io.recoveryEventOut

  // ---- Decode, rename, allocation --------------------------------------------------------------------
  dec.io.fetchPacketIn    <> io.fetchPacketIn
  dec.io.decodePrivViewIn := csr.io.decodePrivViewOut
  rn.io.decodedPacketIn   <> dec.io.decodedPacketOut
  rob.io.robAllocIn       <> rn.io.robAllocOut
  rs.io.rsAllocIn         <> rn.io.rsAllocOut
  lsq.io.lsqAllocIn       <> rn.io.lsqAllocOut
  rn.io.renameCommitIn    <> com.io.renameCommitOut
  rn.io.checkpointReleaseIn <> bru.io.checkpointReleaseOut
  rn.io.robStatusIn       := rob.io.robStatusOut

  // ---- Select, operand read, dispatch ----------------------------------------------------------------
  prf.io.registerFileReadReqIn <> rs.io.registerFileReadReqOut
  rs.io.registerFileReadRespIn <> prf.io.registerFileReadRespOut
  dis.io.issuedUopIn           <> rs.io.issuedUopOut
  rs.io.fuAvailabilityIn       := dis.io.fuAvailabilityOut
  alu.io.aluReqIn              <> dis.io.aluReqOut
  mul.io.multiplierReqIn       <> dis.io.multiplierReqOut
  div.io.dividerReqIn          <> dis.io.dividerReqOut
  bru.io.branchUnitReqIn       <> dis.io.branchUnitReqOut
  agu.io.addressGenerationReqIn <> dis.io.addressGenerationReqOut
  csr.io.csrReqIn              <> dis.io.csrReqOut
  lsq.io.memAddressIn          <> agu.io.memAddressOut

  // ---- Publication ---------------------------------------------------------------------------------
  pub.io.aluResultIn        <> alu.io.aluResultOut
  pub.io.multiplierResultIn <> mul.io.multiplierResultOut
  pub.io.dividerResultIn    <> div.io.dividerResultOut
  pub.io.branchResultIn     <> bru.io.branchResultOut
  pub.io.csrResultIn        <> csr.io.csrResultOut
  pub.io.memResultIn        <> lsq.io.memResultOut
  prf.io.physicalRegWriteIn <> pub.io.physicalRegWriteOut
  rs.io.wakeupBroadcastIn   := pub.io.wakeupBroadcastOut
  rn.io.wakeupBroadcastIn   := pub.io.wakeupBroadcastOut
  rob.io.robCompletionIn    <> pub.io.robCompletionOut

  // ---- Memory: LSQ, StoreBuffer, boundary ----------------------------------------------------------------
  io.dtlbReqOut             <> lsq.io.dtlbReqOut
  lsq.io.dtlbStoreRespIn    <> io.dtlbStoreRespIn
  lsq.io.dtlbRefillIn       <> io.dtlbRefillIn
  io.dCacheLoadReqOut       <> lsq.io.dCacheLoadReqOut
  lsq.io.dCacheLoadRespIn   <> io.dCacheLoadRespIn
  io.uncachedLoadReqOut     <> lsq.io.uncachedLoadReqOut
  lsq.io.uncachedLoadRespIn <> io.uncachedLoadRespIn
  io.uncachedStoreReqOut    <> lsq.io.uncachedStoreReqOut
  lsq.io.uncachedStoreRespIn <> io.uncachedStoreRespIn
  sb.io.storeForwardQueryIn <> lsq.io.storeForwardQueryOut
  lsq.io.storeForwardDataIn <> sb.io.storeForwardDataOut
  sb.io.committedStoreIn    <> lsq.io.committedStoreOut
  lsq.io.storeCommitIn      <> com.io.storeCommitOut
  lsq.io.robStatusIn        := rob.io.robStatusOut
  lsq.io.headMemGrantIn     <> com.io.headMemGrantOut
  lsq.io.storeBufferEmptyIn := sb.io.storeBufferEmptyOut
  sb.io.storeBufferDrainReqIn   <> com.io.storeBufferDrainReqOut
  com.io.storeBufferDrainRespIn <> sb.io.storeBufferDrainRespOut
  io.storeDrainReqOut       <> sb.io.storeDrainReqOut
  sb.io.storeDrainRespIn    <> io.storeDrainRespIn

  // ---- Commit, CSR, trap, recovery ------------------------------------------------------------------------
  com.io.robHeadIn       <> rob.io.robHeadOut
  com.io.interruptCtrlIn := csr.io.interruptCtrlOut
  com.io.debugReqIn      := io.debugReqIn
  io.ftqCommitOut        <> com.io.ftqCommitOut
  io.iCacheInvalidateOut <> com.io.iCacheInvalidateOut
  io.dCacheCleanReqOut   <> com.io.dCacheCleanReqOut
  com.io.dCacheCleanRespIn <> io.dCacheCleanRespIn
  io.sfenceVmaOut        <> com.io.sfenceVmaOut
  csr.io.commitGrantIn   := com.io.commitGrantOut
  csr.io.interruptIn     := io.interruptIn
  io.translationContextOut := csr.io.translationContextOut
  trap.io.exceptionIn    <> com.io.exceptionOut
  trap.io.csrTrapReadIn  := csr.io.csrTrapReadOut
  csr.io.csrTrapWriteIn  <> trap.io.csrTrapWriteOut
  rc.io.archRedirectIn   <> trap.io.archRedirectOut
  rc.io.branchResolutionIn <> bru.io.branchResolutionOut
  // usingRvvi: the retire stream and its commit-time PRF read (ADR-019B E-3).
  io.retireStreamOut.zip(com.io.retireStreamOut).foreach { case (o, i) => o <> i }
  prf.io.commitPrfReadReqIn.zip(com.io.commitPrfReadReqOut).foreach { case (i, o) => i <> o }
  com.io.commitPrfReadRespIn.zip(prf.io.commitPrfReadRespOut).foreach { case (i, o) => i <> o }

  // ---- The RecoveryEvent broadcast ------------------------------------------------------------------------
  Seq(dec.io.recoveryEventIn, rn.io.recoveryEventIn, rob.io.recoveryEventIn, rs.io.recoveryEventIn,
    dis.io.recoveryEventIn, com.io.recoveryEventIn, alu.io.recoveryEventIn, mul.io.recoveryEventIn,
    div.io.recoveryEventIn, bru.io.recoveryEventIn, agu.io.recoveryEventIn, lsq.io.recoveryEventIn).foreach(_ := ev)
  io.recoveryEventOut := ev
}
