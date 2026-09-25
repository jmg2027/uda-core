package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.BranchUnitSpecs._

/** BranchUnit vertex shell (spec: BranchUnitSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contBranchUnit)
class BranchUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBranchUnitReqIn)
    val branchUnitReqIn = ???

    @LocalSpec(intfBranchResultOut)
    val branchResultOut = ???

    @LocalSpec(intfBranchResolutionOut)
    val branchResolutionOut = ???

    @LocalSpec(intfCheckpointReleaseOut)
    val checkpointReleaseOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcBranchResolve)
  val branchResolve = ???

  @LocalSpec(funcMispredictDetect)
  val mispredictDetect = ???

  @LocalSpec(funcBranchRecoveryRequest)
  val branchRecoveryRequest = ???
}
