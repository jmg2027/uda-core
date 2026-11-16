package udacore.frontend.design.top

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared.{FtqCommit, RecoveryEvent}
import udacore.core.design.shared.{ICacheReq, ICacheResp, TranslateReq}
import udacore.frontend.design.modules.{BranchPredictor, FetchBuffer, FetchPcGen, FetchTargetQueue, FetchUnit}
import udacore.frontend.design.shared._
import udacore.frontend.spec.top.FrontendTopSpecs._

/** FrontendTop rawTop wiring (spec: FrontendTopSpecs; ADR-019 D-19.2). */
@LocalSpec(contFrontendTop)
class FrontendTop(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBootAddrIn)
    val bootAddrIn = Flipped(Decoupled(UInt(params.vAddrWidth.W)))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params.backend))

    @LocalSpec(intfFtqCommitIn)
    val ftqCommitIn = Flipped(Decoupled(new FtqCommit(params.backend)))

    @LocalSpec(intfICacheRespIn)
    val iCacheRespIn = Flipped(Decoupled(new ICacheResp(params.fetchGenWidth, params.fetchWidth)))

    @LocalSpec(intfFetchPacketOut)
    val fetchPacketOut = Decoupled(new FetchPacket(params.decodeWidth, params.vAddrWidth,
      params.ftqIdxWidth, params.slotWidth))

    @LocalSpec(intfITlbReqOut)
    val iTlbReqOut = Decoupled(new TranslateReq(params.fetchGenWidth, params.vAddrWidth))

    @LocalSpec(intfICacheReqOut)
    val iCacheReqOut = Decoupled(new ICacheReq(params.fetchGenWidth, params.vAddrWidth))
  })

  val pcg = Module(new FetchPcGen(params))
  val predictor = Module(new BranchPredictor(params))
  val ftq = Module(new FetchTargetQueue(params))
  val fetch = Module(new FetchUnit(params))
  val buffer = Module(new FetchBuffer(params))

  pcg.io.bootAddrIn :<>= io.bootAddrIn
  predictor.io.predictReqIn :<>= pcg.io.predictReqOut
  pcg.io.nextPcIn :<>= predictor.io.nextPcOut
  ftq.io.predictionIn :<>= predictor.io.predictionOut
  predictor.io.historyRestoreIn :<>= ftq.io.historyRestoreOut
  predictor.io.predictorTrainIn :<>= ftq.io.predictorTrainOut
  fetch.io.fetchRequestIn :<>= ftq.io.fetchRequestOut
  buffer.io.fetchBlockIn :<>= fetch.io.fetchBlockOut
  ftq.io.ftqCommitIn :<>= io.ftqCommitIn
  fetch.io.iCacheRespIn :<>= io.iCacheRespIn
  io.fetchPacketOut :<>= buffer.io.fetchPacketOut
  io.iTlbReqOut :<>= fetch.io.iTlbReqOut
  io.iCacheReqOut :<>= fetch.io.iCacheReqOut

  pcg.io.recoveryEventIn := io.recoveryEventIn
  predictor.io.recoveryEventIn := io.recoveryEventIn
  ftq.io.recoveryEventIn := io.recoveryEventIn
  fetch.io.recoveryEventIn := io.recoveryEventIn
  buffer.io.recoveryEventIn := io.recoveryEventIn
}
