package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.LoadStoreQueueSpecs._

/** LoadStoreQueue vertex shell (spec: LoadStoreQueueSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contLoadStoreQueue)
class LoadStoreQueue(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfLsqAllocIn)
    val lsqAllocIn = ???

    @LocalSpec(intfMemAddressIn)
    val memAddressIn = ???

    @LocalSpec(intfDtlbReqOut)
    val dtlbReqOut = ???

    @LocalSpec(intfDtlbStoreRespIn)
    val dtlbStoreRespIn = ???

    @LocalSpec(intfDtlbRefillIn)
    val dtlbRefillIn = ???

    @LocalSpec(intfDCacheLoadReqOut)
    val dCacheLoadReqOut = ???

    @LocalSpec(intfDCacheLoadRespIn)
    val dCacheLoadRespIn = ???

    @LocalSpec(intfStoreForwardQueryOut)
    val storeForwardQueryOut = ???

    @LocalSpec(intfStoreForwardDataIn)
    val storeForwardDataIn = ???

    @LocalSpec(intfMemResultOut)
    val memResultOut = ???

    @LocalSpec(intfStoreCommitIn)
    val storeCommitIn = ???

    @LocalSpec(intfCommittedStoreOut)
    val committedStoreOut = ???

    @LocalSpec(intfRobStatusIn)
    val robStatusIn = ???

    @LocalSpec(intfHeadMemGrantIn)
    val headMemGrantIn = ???

    @LocalSpec(intfUncachedStoreReqOut)
    val uncachedStoreReqOut = ???

    @LocalSpec(intfUncachedStoreRespIn)
    val uncachedStoreRespIn = ???

    @LocalSpec(intfStoreBufferEmptyIn)
    val storeBufferEmptyIn = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcLsqAllocate)
  val lsqAllocate = ???

  @LocalSpec(funcAddressCapture)
  val addressCapture = ???

  @LocalSpec(funcTranslationWait)
  val translationWait = ???

  @LocalSpec(funcConservativeDisambig)
  val conservativeDisambig = ???

  @LocalSpec(funcLoadIssue)
  val loadIssue = ???

  @LocalSpec(funcStoreToLoadForward)
  val storeToLoadForward = ???

  @LocalSpec(funcLoadComplete)
  val loadComplete = ???

  @LocalSpec(funcStoreComplete)
  val storeComplete = ???

  @LocalSpec(funcUncacheableAtHead)
  val uncacheableAtHead = ???

  @LocalSpec(funcStoreCommitHandoff)
  val storeCommitHandoff = ???

  @LocalSpec(funcLsqRecovery)
  val lsqRecovery = ???
}
