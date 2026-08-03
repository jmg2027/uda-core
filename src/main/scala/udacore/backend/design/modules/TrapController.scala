package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.TrapControllerSpecs

/** TrapController vertex shell described by TrapController. */
@LocalSpec(TrapControllerSpecs.contTrapController)
class TrapController(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    // Route trap requests and status updates per TrapController.
  })
}
