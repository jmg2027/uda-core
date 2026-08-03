package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.RenameUnitSpecs._

@LocalSpec(contRenameUnit)
class RenameUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    // Allocate physical resources for decoded uops per RenameUnit.
  })
}
