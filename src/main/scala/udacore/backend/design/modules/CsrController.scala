package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.CsrControllerSpecs

/** CSRController vertex shell described by CSRController. */
@LocalSpec(CsrControllerSpecs.contCsrController)
class CSRController(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    // Connect CSR request, epoch, and trap edges per CSRController.
  })
}
