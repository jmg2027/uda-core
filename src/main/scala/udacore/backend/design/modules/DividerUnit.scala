package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.DividerUnitSpecs._

/** DividerUnit vertex shell (spec: DividerUnitSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contDividerUnit)
class DividerUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDividerReqIn)
    val dividerReqIn = ???

    @LocalSpec(intfDividerResultOut)
    val dividerResultOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcIntegrateExternalDivider)
  val integrateExternalDivider = ???
}
