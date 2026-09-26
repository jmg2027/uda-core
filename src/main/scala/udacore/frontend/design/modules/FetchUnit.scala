package udacore.frontend.design.modules

import chisel3._
import chisel3.util._
import udacore.backend.design.shared.RecoveryEvent
import udacore.core.design.shared.{ICacheReq, ICacheResp, TranslateReq}
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
    val fetchRequestIn = Flipped(Decoupled(new FetchRequest(params)))

    @LocalSpec(intfITlbReqOut)
    val iTlbReqOut = Decoupled(new TranslateReq(params.fetchGenWidth, params.vAddrWidth))

    @LocalSpec(intfICacheReqOut)
    val iCacheReqOut = Decoupled(new ICacheReq(params.fetchGenWidth, params.vAddrWidth))

    @LocalSpec(intfICacheRespIn)
    val iCacheRespIn = Flipped(Decoupled(new ICacheResp(params.fetchGenWidth, params.fetchWidth)))

    @LocalSpec(intfFetchBlockOut)
    val fetchBlockOut = Decoupled(new FetchBlock(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params.backend))
  })

  @LocalSpec(funcParallelFetchLookup)
  val parallelFetchLookup = ???

  @LocalSpec(funcFetchGeneration)
  val fetchGeneration = ???

  @LocalSpec(funcFetchBlockAssembly)
  val fetchBlockAssembly = ???
}
