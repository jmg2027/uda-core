package udacore.core.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.InstBusAdapterSpecs._

/** InstBusAdapter vertex shell (spec: InstBusAdapterSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contInstBusAdapter)
class InstBusAdapter(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfInstMemReqIn)
    val instMemReqIn = ???

    @LocalSpec(intfInstMemRespOut)
    val instMemRespOut = ???

    @LocalSpec(intfInstBus)
    val instBus = ???
  })

  @LocalSpec(funcInstBusGet)
  val instBusGet = ???
}
