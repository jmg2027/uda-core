package udacore.frontend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.FetchUnitSpecs._

/** FetchUnit vertex shell described by FetchUnit. */
@LocalSpec(contFetchUnit)
class FetchUnit(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    // Interface with instruction memory per FetchUnit.
  })
}
