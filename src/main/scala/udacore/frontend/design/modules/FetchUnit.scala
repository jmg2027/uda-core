package udacore.frontend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.FetchUnitSpecs._

/** FetchUnit vertex shell (spec: FetchUnitSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contFetchUnit)
class FetchUnit(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfFetchRequestIn)
    val fetchRequestIn = ???

    @LocalSpec(intfITlbReqOut)
    val iTlbReqOut = ???

    @LocalSpec(intfICacheReqOut)
    val iCacheReqOut = ???

    @LocalSpec(intfICacheRespIn)
    val iCacheRespIn = ???

    @LocalSpec(intfFetchBlockOut)
    val fetchBlockOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcParallelFetchLookup)
  val parallelFetchLookup = ???

  @LocalSpec(funcFetchGeneration)
  val fetchGeneration = ???

  @LocalSpec(funcFetchBlockAssembly)
  val fetchBlockAssembly = ???
}
