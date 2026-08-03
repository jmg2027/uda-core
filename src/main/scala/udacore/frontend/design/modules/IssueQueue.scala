package udacore.frontend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.IssueQueueSpecs._

/** IssueQueue vertex shell described by IssueQueue. */
@LocalSpec(contIssueQueue)
class IssueQueue(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    // Buffer aligned uops toward BackendTop per IssueQueue.
  })
}
