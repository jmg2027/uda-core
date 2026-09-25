package udacore.core.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.DataCacheSpecs._

/** DataCache vertex shell (spec: DataCacheSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contDataCache)
class DataCache(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDCacheLoadReqIn)
    val dCacheLoadReqIn = ???

    @LocalSpec(intfDCacheTranslationIn)
    val dCacheTranslationIn = ???

    @LocalSpec(intfDCacheLoadRespOut)
    val dCacheLoadRespOut = ???

    @LocalSpec(intfStoreDrainReqIn)
    val storeDrainReqIn = ???

    @LocalSpec(intfStoreDrainRespOut)
    val storeDrainRespOut = ???

    @LocalSpec(intfPtwMemReqIn)
    val ptwMemReqIn = ???

    @LocalSpec(intfPtwMemRespOut)
    val ptwMemRespOut = ???

    @LocalSpec(intfDCacheCleanReqIn)
    val dCacheCleanReqIn = ???

    @LocalSpec(intfDCacheCleanRespOut)
    val dCacheCleanRespOut = ???

    @LocalSpec(intfUncachedStoreReqIn)
    val uncachedStoreReqIn = ???

    @LocalSpec(intfUncachedStoreRespOut)
    val uncachedStoreRespOut = ???

    @LocalSpec(intfDataMemReqOut)
    val dataMemReqOut = ???

    @LocalSpec(intfDataMemRespIn)
    val dataMemRespIn = ???
  })

  @LocalSpec(funcDCacheViptLookup)
  val dCacheViptLookup = ???

  @LocalSpec(funcDCacheMshr)
  val dCacheMshr = ???

  @LocalSpec(funcDCacheStoreWrite)
  val dCacheStoreWrite = ???

  @LocalSpec(funcDCacheWriteback)
  val dCacheWriteback = ???

  @LocalSpec(funcDCachePhysicalRead)
  val dCachePhysicalRead = ???

  @LocalSpec(funcDCacheUncached)
  val dCacheUncached = ???

  @LocalSpec(funcDCacheCleanAll)
  val dCacheCleanAll = ???

  @LocalSpec(funcDCachePortArbitrate)
  val dCachePortArbitrate = ???
}
