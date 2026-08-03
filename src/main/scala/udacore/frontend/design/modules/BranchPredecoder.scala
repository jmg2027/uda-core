package udacore.frontend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.BranchPredecoderSpecs._

/** BranchPredecoder vertex shell described by BranchPredecoder.
  */
@LocalSpec(contBranchPredecoder)
class BranchPredecoder(val params: FrontendParams)
    extends FrontendModule {
  val io = IO(new Bundle {
    // Provide early branch predictions per BranchPredecoder.
  })
}
