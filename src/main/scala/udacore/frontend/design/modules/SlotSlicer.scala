package udacore.frontend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.SlotSlicerSpecs._

/** SlotSlicer vertex shell described by SlotSlicer. */
@LocalSpec(contSlotSlicer)
class SlotSlicer(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    // Align fetch responses toward the issue queue per SlotSlicer.
  })
}
