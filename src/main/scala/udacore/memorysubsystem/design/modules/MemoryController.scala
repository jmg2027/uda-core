package udacore.memorysubsystem.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.memorysubsystem.spec.modules.MemoryControllerSpecs._

/** MemoryController vertex shell described by MemoryController.
  */
@LocalSpec(contMemoryController)
class MemoryController extends Module {
  val io = IO(new Bundle {
    // Coordinate memory bus arbitration per MemoryController.
  })
}
