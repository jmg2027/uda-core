package udacore.frontend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.FetchBufferSpecs._

/** FetchBuffer vertex shell (spec: FetchBufferSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contFetchBuffer)
class FetchBuffer(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfFetchBlockIn)
    val fetchBlockIn = ???

    @LocalSpec(intfFetchPacketOut)
    val fetchPacketOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcFetchBufferEnqueue)
  val fetchBufferEnqueue = ???

  @LocalSpec(funcFetchPacketDequeue)
  val fetchPacketDequeue = ???

  @LocalSpec(funcFetchBufferRecovery)
  val fetchBufferRecovery = ???
}
