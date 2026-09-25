package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.MultiplierUnitSpecs._

/** MultiplierUnit vertex shell (spec: MultiplierUnitSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contMultiplierUnit)
class MultiplierUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfMultiplierReqIn)
    val multiplierReqIn = ???

    @LocalSpec(intfMultiplierResultOut)
    val multiplierResultOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcIntegrateExternalMultiplier)
  val integrateExternalMultiplier = ???
}
