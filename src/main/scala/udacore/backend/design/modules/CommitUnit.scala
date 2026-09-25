package udacore.backend.design.modules

import chisel3._
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
    val robHeadIn = ???

    @LocalSpec(intfRenameCommitOut)
    val renameCommitOut = ???

    @LocalSpec(intfStoreCommitOut)
    val storeCommitOut = ???

    @LocalSpec(intfFtqCommitOut)
    val ftqCommitOut = ???

    @LocalSpec(intfCommitGrantOut)
    val commitGrantOut = ???

    @LocalSpec(intfExceptionOut)
    val exceptionOut = ???

    @LocalSpec(intfInterruptCtrlIn)
    val interruptCtrlIn = ???

    @LocalSpec(intfRetireStreamOut)
    val retireStreamOut = ???

    @LocalSpec(intfStoreBufferDrainReqOut)
    val storeBufferDrainReqOut = ???

    @LocalSpec(intfStoreBufferDrainRespIn)
    val storeBufferDrainRespIn = ???

    @LocalSpec(intfICacheInvalidateOut)
    val iCacheInvalidateOut = ???

    @LocalSpec(intfDCacheCleanReqOut)
    val dCacheCleanReqOut = ???

    @LocalSpec(intfDCacheCleanRespIn)
    val dCacheCleanRespIn = ???

    @LocalSpec(intfSfenceVmaOut)
    val sfenceVmaOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcCommitHead)
  val commitHead = ???

  @LocalSpec(funcTrapHold)
  val trapHold = ???

  @LocalSpec(funcUncacheableStoreAtHead)
  val uncacheableStoreAtHead = ???

  @LocalSpec(funcPreciseTrapHandoff)
  val preciseTrapHandoff = ???

  @LocalSpec(funcInterruptSampling)
  val interruptSampling = ???

  @LocalSpec(funcBlockEndCommit)
  val blockEndCommit = ???

  @LocalSpec(funcSystemOpSequencing)
  val systemOpSequencing = ???
}
