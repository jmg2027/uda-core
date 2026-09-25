package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.ReorderBufferSpecs._

/** ReorderBuffer vertex shell (spec: ReorderBufferSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contReorderBuffer)
class ReorderBuffer(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfRobAllocIn)
    val robAllocIn = ???

    @LocalSpec(intfRobCompletionIn)
    val robCompletionIn = ???

    @LocalSpec(intfRobHeadOut)
    val robHeadOut = ???

    @LocalSpec(intfRobStatusOut)
    val robStatusOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcRobAllocate)
  val robAllocate = ???

  @LocalSpec(funcRobComplete)
  val robComplete = ???

  @LocalSpec(funcRobHeadOffer)
  val robHeadOffer = ???

  @LocalSpec(funcRobRecovery)
  val robRecovery = ???
}
