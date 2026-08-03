package udacore.memorysubsystem.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.memorysubsystem.spec.modules.MemoryDispatcherSpecs._

/** MemoryDispatcher vertex shell described by MemoryDispatcher.
  */
@LocalSpec(contMemoryDispatcher)
class MemoryDispatcher extends Module {
  val io = IO(new Bundle {
    // Steer memory requests toward LoadUnit and StoreUnit per MemoryDispatcher.
  })
}
