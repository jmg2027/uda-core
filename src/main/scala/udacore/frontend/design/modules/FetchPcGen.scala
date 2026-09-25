package udacore.frontend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.FetchPcGenSpecs._

/** FetchPcGen vertex shell (spec: FetchPcGenSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contFetchPcGen)
class FetchPcGen(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBootAddrIn)
    val bootAddrIn = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???

    @LocalSpec(intfNextPcIn)
    val nextPcIn = ???

    @LocalSpec(intfPredictReqOut)
    val predictReqOut = ???
  })

  @LocalSpec(funcFetchPcSelect)
  val fetchPcSelect = ???

  @LocalSpec(funcFetchPcRecovery)
  val fetchPcRecovery = ???
}
