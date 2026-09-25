package udacore.frontend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.FetchTargetQueueSpecs._

/** FetchTargetQueue vertex shell (spec: FetchTargetQueueSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contFetchTargetQueue)
class FetchTargetQueue(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfPredictionIn)
    val predictionIn = ???

    @LocalSpec(intfFetchRequestOut)
    val fetchRequestOut = ???

    @LocalSpec(intfFtqCommitIn)
    val ftqCommitIn = ???

    @LocalSpec(intfHistoryRestoreOut)
    val historyRestoreOut = ???

    @LocalSpec(intfPredictorTrainOut)
    val predictorTrainOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcFtqAllocate)
  val ftqAllocate = ???

  @LocalSpec(funcFtqFetchIssue)
  val ftqFetchIssue = ???

  @LocalSpec(funcFtqRecovery)
  val ftqRecovery = ???

  @LocalSpec(funcFtqCommitTrain)
  val ftqCommitTrain = ???
}
