package udacore.memorysubsystem.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.memorysubsystem.spec.modules.LoadUnitSpecs._

/** LoadUnit vertex shell described by LoadUnit. */
@LocalSpec(contLoadUnit)
class LoadUnit extends Module {
  val io = IO(new Bundle {
    // Handle memory read requests per LoadUnit.
  })
}
