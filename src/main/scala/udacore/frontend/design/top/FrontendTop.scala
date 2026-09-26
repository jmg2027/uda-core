package udacore.frontend.design.top

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.top.FrontendTopSpecs._

/** FrontendTop vertex shell (spec: FrontendTopSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contFrontendTop)
class FrontendTop(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBootAddrIn)
    val bootAddrIn = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???

    @LocalSpec(intfFtqCommitIn)
    val ftqCommitIn = ???

    @LocalSpec(intfICacheRespIn)
    val iCacheRespIn = ???

    @LocalSpec(intfFetchPacketOut)
    val fetchPacketOut = ???

    @LocalSpec(intfITlbReqOut)
    val iTlbReqOut = ???

    @LocalSpec(intfICacheReqOut)
    val iCacheReqOut = ???
  })
}
