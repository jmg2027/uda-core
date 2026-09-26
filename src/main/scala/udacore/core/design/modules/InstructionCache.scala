package udacore.core.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.InstructionCacheSpecs._

/** InstructionCache vertex shell (spec: InstructionCacheSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contInstructionCache)
class InstructionCache(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfICacheReqIn)
    val iCacheReqIn = ???

    @LocalSpec(intfICacheTranslationIn)
    val iCacheTranslationIn = ???

    @LocalSpec(intfICacheRespOut)
    val iCacheRespOut = ???

    @LocalSpec(intfICacheInvalidateIn)
    val iCacheInvalidateIn = ???

    @LocalSpec(intfInstMemReqOut)
    val instMemReqOut = ???

    @LocalSpec(intfInstMemRespIn)
    val instMemRespIn = ???
  })

  @LocalSpec(funcICacheViptLookup)
  val iCacheViptLookup = ???

  @LocalSpec(funcICacheMissFill)
  val iCacheMissFill = ???

  @LocalSpec(funcICacheUncachedFetch)
  val iCacheUncachedFetch = ???

  @LocalSpec(funcICacheInvalidate)
  val iCacheInvalidate = ???
}
