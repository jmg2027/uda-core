package udacore.memorysubsystem.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.memorysubsystem.spec.modules.StoreUnitSpecs._

/** StoreUnit vertex shell described by StoreUnit. */
@LocalSpec(contStoreUnit)
class StoreUnit extends Module {
  val io = IO(new Bundle {
    // Handle memory write requests per StoreUnit.
  })
}
