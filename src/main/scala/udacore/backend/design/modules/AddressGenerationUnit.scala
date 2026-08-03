package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.AddressGenerationUnitSpecs

/** AddressGenerationUnit vertex shell described by AddressGenerationUnit.
  */
@LocalSpec(AddressGenerationUnitSpecs.contAddressGenerationUnit)
class AddressGenerationUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    // Route load and store address channels per AddressGenerationUnit.
  })
}
