package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.RedirectUnitSpecs

/** RedirectUnit vertex shell described by RedirectUnit. */
@LocalSpec(RedirectUnitSpecs.contRedirectUnit)
class RedirectUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    // Emit redirect decisions toward the frontend per RedirectUnit.
  })
}
