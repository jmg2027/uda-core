package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.PublishMuxSpecs._

/** PublishMux vertex shell (spec: PublishMuxSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contPublishMux)
class PublishMux(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfAluResultIn)
    val aluResultIn = ???

    @LocalSpec(intfBitAluResultIn)
    val bitAluResultIn = ???

    @LocalSpec(intfMultiplierResultIn)
    val multiplierResultIn = ???

    @LocalSpec(intfDividerResultIn)
    val dividerResultIn = ???

    @LocalSpec(intfBranchResultIn)
    val branchResultIn = ???

    @LocalSpec(intfCsrResultIn)
    val csrResultIn = ???

    @LocalSpec(intfMemResultIn)
    val memResultIn = ???

    @LocalSpec(intfPhysicalRegWriteOut)
    val physicalRegWriteOut = ???

    @LocalSpec(intfWakeupBroadcastOut)
    val wakeupBroadcastOut = ???

    @LocalSpec(intfRobCompletionOut)
    val robCompletionOut = ???
  })

  @LocalSpec(funcPublishArbitrate)
  val publishArbitrate = ???

  @LocalSpec(funcPublishFanout)
  val publishFanout = ???
}
