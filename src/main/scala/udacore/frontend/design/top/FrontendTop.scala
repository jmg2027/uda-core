package udacore.frontend.design.top

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.top.FrontendTopSpecs._

/** Raw frontend vertex described by FrontendTop. */
@LocalSpec(contFrontendTop)
class FrontendTop(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    // Wire boot, redirect, epoch, and memory edges exactly as FrontendTop dictates.
  })
}
