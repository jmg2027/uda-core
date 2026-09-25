package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.RecoveryControllerSpecs._

/** RecoveryController vertex shell (spec: RecoveryControllerSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contRecoveryController)
class RecoveryController(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBranchResolutionIn)
    val branchResolutionIn = ???

    @LocalSpec(intfArchRedirectIn)
    val archRedirectIn = ???

    @LocalSpec(intfRecoveryEventOut)
    val recoveryEventOut = ???
  })

  @LocalSpec(funcRecoverySelect)
  val recoverySelect = ???

  @LocalSpec(funcRecoveryPublish)
  val recoveryPublish = ???
}
