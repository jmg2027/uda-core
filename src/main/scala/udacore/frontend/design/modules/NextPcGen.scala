package udacore.frontend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.NextPcGenSpecs._

/** NextPcGen vertex shell described by NextPcGen. */
@LocalSpec(contNextPcGen)
class NextPcGen(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    // Produce next PC candidates per NextPcGen.
  })
}
