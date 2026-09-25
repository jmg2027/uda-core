package udacore.frontend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.BranchPredictorSpecs._

/** BranchPredictor vertex shell (spec: BranchPredictorSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contBranchPredictor)
class BranchPredictor(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfPredictReqIn)
    val predictReqIn = ???

    @LocalSpec(intfPredictionOut)
    val predictionOut = ???

    @LocalSpec(intfNextPcOut)
    val nextPcOut = ???

    @LocalSpec(intfHistoryRestoreIn)
    val historyRestoreIn = ???

    @LocalSpec(intfPredictorTrainIn)
    val predictorTrainIn = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcBtbLookup)
  val btbLookup = ???

  @LocalSpec(funcTageDirection)
  val tageDirection = ???

  @LocalSpec(funcRasPredict)
  val rasPredict = ???

  @LocalSpec(funcBlockExitSelect)
  val blockExitSelect = ???

  @LocalSpec(funcSpeculativeHistoryUpdate)
  val speculativeHistoryUpdate = ???

  @LocalSpec(funcHistoryRestore)
  val historyRestore = ???

  @LocalSpec(funcPredictorTraining)
  val predictorTraining = ???
}
