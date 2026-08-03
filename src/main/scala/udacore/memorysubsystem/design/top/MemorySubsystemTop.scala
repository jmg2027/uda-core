package udacore.memorysubsystem.design.top

import chisel3._
import framework.macros.LocalSpec
import udacore.memorysubsystem.spec.top.MemorySubsystemSpecs._

/** Raw memory subsystem vertex described by MemorySubsystemTop. */
@LocalSpec(contMemorySubsystem)
class MemorySubsystemTop extends Module {
  val io = IO(new Bundle {
    // Wire memory request, response, external memory, and epoch edges per MemorySubsystemTop.
  })
}
