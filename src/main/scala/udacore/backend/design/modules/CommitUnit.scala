package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import udacore.core.design.shared.{CacheMaintenance, DebugReq, TlbFlush}
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.CommitUnitSpecs._

/** CommitUnit vertex shell (spec: CommitUnitSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
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

  @LocalSpec(funcCommitHead)
  val commitHead = ???

  @LocalSpec(funcTrapHold)
  val trapHold = ???

  @LocalSpec(funcHeadMemGrant)
  val headMemGrant = ???

  @LocalSpec(funcPreciseTrapHandoff)
  val preciseTrapHandoff = ???

  @LocalSpec(funcInterruptSampling)
  val interruptSampling = ???

  @LocalSpec(funcBlockEndCommit)
  val blockEndCommit = ???

  @LocalSpec(funcSystemOpSequencing)
  val systemOpSequencing = ???

  @LocalSpec(funcRetireStreamEmit)
  val retireStreamEmit = ???
}
