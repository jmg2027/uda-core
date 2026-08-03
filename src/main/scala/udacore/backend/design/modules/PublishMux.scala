package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.PublishMuxSpecs

/** PublishMux vertex shell described by PublishMux. */
@LocalSpec(PublishMuxSpecs.contPublishMux)
class PublishMux(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    // Merge execution results toward CommitUnit per PublishMux.
  })
}
