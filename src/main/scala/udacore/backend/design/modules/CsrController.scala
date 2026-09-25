package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.CsrControllerSpecs._

/** CsrController vertex shell (spec: CsrControllerSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contCsrController)
class CsrController(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfCsrReqIn)
    val csrReqIn = ???

    @LocalSpec(intfCsrResultOut)
    val csrResultOut = ???

    @LocalSpec(intfCsrTrapReadOut)
    val csrTrapReadOut = ???

    @LocalSpec(intfCsrTrapWriteIn)
    val csrTrapWriteIn = ???

    @LocalSpec(intfCommitGrantIn)
    val commitGrantIn = ???

    @LocalSpec(intfInterruptIn)
    val interruptIn = ???

    @LocalSpec(intfInterruptCtrlOut)
    val interruptCtrlOut = ???

    @LocalSpec(intfTranslationContextOut)
    val translationContextOut = ???
  })

  @LocalSpec(funcCsrExecuteAtCommit)
  val csrExecuteAtCommit = ???

  @LocalSpec(funcCsrAccessCheck)
  val csrAccessCheck = ???

  @LocalSpec(funcSupervisorCsrs)
  val supervisorCsrs = ???

  @LocalSpec(funcTranslationContextPublish)
  val translationContextPublish = ???

  @LocalSpec(funcCsrMapContribution)
  val csrMapContribution = ???
}
