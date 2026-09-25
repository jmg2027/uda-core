package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.DispatchUnitSpecs._

/** DispatchUnit vertex shell (spec: DispatchUnitSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contDispatchUnit)
class DispatchUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfIssuedUopIn)
    val issuedUopIn = ???

    @LocalSpec(intfAluReqOut)
    val aluReqOut = ???

    @LocalSpec(intfBitAluReqOut)
    val bitAluReqOut = ???

    @LocalSpec(intfMultiplierReqOut)
    val multiplierReqOut = ???

    @LocalSpec(intfDividerReqOut)
    val dividerReqOut = ???

    @LocalSpec(intfBranchUnitReqOut)
    val branchUnitReqOut = ???

    @LocalSpec(intfAddressGenerationReqOut)
    val addressGenerationReqOut = ???

    @LocalSpec(intfCsrReqOut)
    val csrReqOut = ???

    @LocalSpec(intfFuAvailabilityOut)
    val fuAvailabilityOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcFuRoute)
  val fuRoute = ???

  @LocalSpec(funcFuAvailability)
  val fuAvailability = ???
}
