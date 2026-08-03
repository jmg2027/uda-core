package udacore.memorysubsystem.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.memorysubsystem.spec.modules.ResponseArbiterSpecs._

/** ResponseArbiter vertex shell described by ResponseArbiter.
  */
@LocalSpec(contResponseArbiter)
class ResponseArbiter extends Module {
  val io = IO(new Bundle {
    // Arbitrate memory response channels per ResponseArbiter.
  })
}
