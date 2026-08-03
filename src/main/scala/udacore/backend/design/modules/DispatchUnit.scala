package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.DispatchUnitSpecs

/** DispatchUnit vertex shell described by DispatchUnit. */
@LocalSpec(DispatchUnitSpecs.contDispatchUnit)
class DispatchUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    // Fan out renamed uops toward execution units per DispatchUnit.
  })
}
