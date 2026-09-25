package udacore.core.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.DataBusAdapterSpecs._

/** DataBusAdapter vertex shell (spec: DataBusAdapterSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contDataBusAdapter)
class DataBusAdapter(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDataMemReqIn)
    val dataMemReqIn = ???

    @LocalSpec(intfDataMemRespOut)
    val dataMemRespOut = ???

    @LocalSpec(intfDataBus)
    val dataBus = ???
  })

  @LocalSpec(funcDataBusTransaction)
  val dataBusTransaction = ???
}
