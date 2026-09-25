package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.TrapControllerSpecs._

/** TrapController vertex shell (spec: TrapControllerSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contTrapController)
class TrapController(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfExceptionIn)
    val exceptionIn = ???

    @LocalSpec(intfCsrTrapReadIn)
    val csrTrapReadIn = ???

    @LocalSpec(intfCsrTrapWriteOut)
    val csrTrapWriteOut = ???

    @LocalSpec(intfArchRedirectOut)
    val archRedirectOut = ???
  })

  @LocalSpec(funcTrapSingleOwner)
  val trapSingleOwner = ???

  @LocalSpec(funcTrapDelegation)
  val trapDelegation = ???

  @LocalSpec(funcXRet)
  val xRet = ???

  @LocalSpec(funcDebugCommitBoundary)
  val debugCommitBoundary = ???
}
