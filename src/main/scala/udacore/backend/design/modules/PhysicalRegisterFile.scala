package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.PhysicalRegisterFileSpecs._

/** Unified physical register file for speculative and committed register state. */
@LocalSpec(contPhysicalRegisterFile)
class PhysicalRegisterFile(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    // Unified PRF storage for architectural and speculative registers per PhysicalRegisterFile.
  })
}
