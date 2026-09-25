package udacore.backend.design.top

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.top.BackendTopSpecs._

/** BackendTop vertex shell (spec: BackendTopSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contBackendTop)
class BackendTop(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfFetchPacketIn)
    val fetchPacketIn = ???

    @LocalSpec(intfInterruptIn)
    val interruptIn = ???

    @LocalSpec(intfDebugReqIn)
    val debugReqIn = ???

    @LocalSpec(intfDtlbStoreRespIn)
    val dtlbStoreRespIn = ???

    @LocalSpec(intfDtlbRefillIn)
    val dtlbRefillIn = ???

    @LocalSpec(intfDCacheLoadRespIn)
    val dCacheLoadRespIn = ???

    @LocalSpec(intfUncachedStoreRespIn)
    val uncachedStoreRespIn = ???

    @LocalSpec(intfUncachedLoadRespIn)
    val uncachedLoadRespIn = ???

    @LocalSpec(intfStoreDrainRespIn)
    val storeDrainRespIn = ???

    @LocalSpec(intfDCacheCleanRespIn)
    val dCacheCleanRespIn = ???

    @LocalSpec(intfRecoveryEventOut)
    val recoveryEventOut = ???

    @LocalSpec(intfFtqCommitOut)
    val ftqCommitOut = ???

    @LocalSpec(intfDtlbReqOut)
    val dtlbReqOut = ???

    @LocalSpec(intfDCacheLoadReqOut)
    val dCacheLoadReqOut = ???

    @LocalSpec(intfUncachedStoreReqOut)
    val uncachedStoreReqOut = ???

    @LocalSpec(intfUncachedLoadReqOut)
    val uncachedLoadReqOut = ???

    @LocalSpec(intfStoreDrainReqOut)
    val storeDrainReqOut = ???

    @LocalSpec(intfTranslationContextOut)
    val translationContextOut = ???

    @LocalSpec(intfSfenceVmaOut)
    val sfenceVmaOut = ???

    @LocalSpec(intfICacheInvalidateOut)
    val iCacheInvalidateOut = ???

    @LocalSpec(intfDCacheCleanReqOut)
    val dCacheCleanReqOut = ???

    @LocalSpec(intfRetireStreamOut)
    val retireStreamOut = ???
  })
}
