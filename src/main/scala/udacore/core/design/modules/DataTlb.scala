package udacore.core.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.DataTlbSpecs._

/** DataTlb vertex shell (spec: DataTlbSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contDataTlb)
class DataTlb(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDtlbReqIn)
    val dtlbReqIn = ???

    @LocalSpec(intfDCacheTranslationOut)
    val dCacheTranslationOut = ???

    @LocalSpec(intfDtlbStoreRespOut)
    val dtlbStoreRespOut = ???

    @LocalSpec(intfDtlbRefillOut)
    val dtlbRefillOut = ???

    @LocalSpec(intfDtlbWalkReqOut)
    val dtlbWalkReqOut = ???

    @LocalSpec(intfDtlbWalkRespIn)
    val dtlbWalkRespIn = ???

    @LocalSpec(intfDtlbFlushIn)
    val dtlbFlushIn = ???

    @LocalSpec(intfTranslationContextIn)
    val translationContextIn = ???
  })

  @LocalSpec(funcDtlbTranslate)
  val dtlbTranslate = ???

  @LocalSpec(funcDtlbMissNonBlocking)
  val dtlbMissNonBlocking = ???

  @LocalSpec(funcDtlbRefill)
  val dtlbRefill = ???

  @LocalSpec(funcDtlbFlush)
  val dtlbFlush = ???
}
