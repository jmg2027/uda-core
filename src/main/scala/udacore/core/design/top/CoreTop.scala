package udacore.core.design.top

import chisel3._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.top.CoreTopSpecs._

/** CoreTop vertex shell (spec: CoreTopSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contCoreTop)
class CoreTop(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBootAddrIn)
    val bootAddrIn = ???

    @LocalSpec(intfHartEnIn)
    val hartEnIn = ???

    @LocalSpec(intfInterruptIn)
    val interruptIn = ???

    @LocalSpec(intfDebugReqIn)
    val debugReqIn = ???

    @LocalSpec(intfInstBus)
    val instBus = ???

    @LocalSpec(intfDataBus)
    val dataBus = ???

    @LocalSpec(intfRetireStreamOut)
    val retireStreamOut = ???
  })
}
