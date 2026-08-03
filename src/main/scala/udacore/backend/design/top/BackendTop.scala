package udacore.backend.design.top

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.spec.top.BackendTopSpecs

/** Raw backend vertex described by BackendTop. */
@LocalSpec(BackendTopSpecs.contBackendTop)
class BackendTop extends Module {
  val io = IO(new Bundle {
    // Wire instruction issue, redirect, memory, and epoch edges per BackendTop.
  })
}
