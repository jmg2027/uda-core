package udacore.core.design.top

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.top.BackendTop
import udacore.backend.design.shared.{BackendContractParams, BackendParams, Interrupt, RetireToken}
import udacore.common.tilelink.TLBundle
import udacore.core.design.modules._
import udacore.core.design.shared._
import udacore.core.spec.top.CoreTopSpecs._
import udacore.frontend.design.shared.FrontendParams
import udacore.frontend.design.top.FrontendTop

/** CoreTop rawTop wiring (spec: CoreTopSpecs; ADR-019). */
@LocalSpec(contCoreTop)
class CoreTop(val params: CoreParams) extends CoreModule {
  val backendParams = BackendParams(
    contract = BackendContractParams(xLen = params.dataWidth, hartId = params.hartId),
    usingRvvi = params.usingRvvi,
    debugEntryAddr = params.debugEntryAddr)
  val frontendParams = FrontendParams(backend = backendParams)
  require(params.fetch.fetchBytes == frontendParams.fetchBytes &&
    params.fetch.fetchGenWidth == frontendParams.fetchGenWidth &&
    params.vAddrWidth == frontendParams.vAddrWidth,
    "CoreFetchView must equal the FrontendParams fetch geometry")
  require(params.lsq.loadQueueDepth == backendParams.tuning.loadQueueDepth &&
    params.lsq.storeQueueDepth == backendParams.tuning.storeQueueDepth &&
    params.lsq.generationWidth == backendParams.lsqGenWidth,
    "CoreLsqView must equal the BackendParams LSQ geometry")
  val io = IO(new Bundle {
    @LocalSpec(intfBootAddrIn)
    val bootAddrIn = Input(UInt(params.vAddrWidth.W))

    @LocalSpec(intfHartEnIn)
    val hartEnIn = Input(Bool())

    @LocalSpec(intfInterruptIn)
    val interruptIn = Input(new Interrupt)

    @LocalSpec(intfDebugReqIn)
    val debugReqIn = Input(new DebugReq)

    @LocalSpec(intfInstBus)
    val instBus = new TLBundle(params.instBusParams)

    @LocalSpec(intfDataBus)
    val dataBus = new TLBundle(params.dataBusParams)

    @LocalSpec(intfRetireStreamOut)
    val retireStreamOut = if (params.usingRvvi) Some(Decoupled(new RetireToken(backendParams))) else None
  })

  val boot = Module(new BootSequencer(params.priv.bootCycles, params))
  val frontend = Module(new FrontendTop(frontendParams))
  val backend = Module(new BackendTop(backendParams))
  val itlb = Module(new InstructionTlb(params))
  val icache = Module(new InstructionCache(params))
  val dtlb = Module(new DataTlb(params))
  val dcache = Module(new DataCache(params))
  val ptw = Module(new PageTableWalker(params))
  val instAdapter = Module(new InstBusAdapter(params))
  val dataAdapter = Module(new DataBusAdapter(params))

  boot.io.bootAddrIn := io.bootAddrIn
  boot.io.hartEnIn := io.hartEnIn
  frontend.io.bootAddrIn :<>= boot.io.bootOut
  backend.io.fetchPacketIn :<>= frontend.io.fetchPacketOut
  frontend.io.ftqCommitIn :<>= backend.io.ftqCommitOut
  frontend.io.recoveryEventIn := backend.io.recoveryEventOut
  backend.io.interruptIn := io.interruptIn
  backend.io.debugReqIn := io.debugReqIn

  itlb.io.iTlbReqIn :<>= frontend.io.iTlbReqOut
  icache.io.iCacheReqIn :<>= frontend.io.iCacheReqOut
  icache.io.iCacheTranslationIn :<>= itlb.io.iCacheTranslationOut
  frontend.io.iCacheRespIn :<>= icache.io.iCacheRespOut
  icache.io.iCacheInvalidateIn :<>= backend.io.iCacheInvalidateOut

  dtlb.io.dtlbReqIn :<>= backend.io.dtlbReqOut
  dcache.io.dCacheLoadReqIn :<>= backend.io.dCacheLoadReqOut
  dcache.io.dCacheTranslationIn :<>= dtlb.io.dCacheTranslationOut
  backend.io.dCacheLoadRespIn :<>= dcache.io.dCacheLoadRespOut
  backend.io.dtlbStoreRespIn :<>= dtlb.io.dtlbStoreRespOut
  backend.io.dtlbRefillIn :<>= dtlb.io.dtlbRefillOut
  dcache.io.uncachedLoadReqIn :<>= backend.io.uncachedLoadReqOut
  backend.io.uncachedLoadRespIn :<>= dcache.io.uncachedLoadRespOut
  dcache.io.uncachedStoreReqIn :<>= backend.io.uncachedStoreReqOut
  backend.io.uncachedStoreRespIn :<>= dcache.io.uncachedStoreRespOut
  dcache.io.storeDrainReqIn :<>= backend.io.storeDrainReqOut
  backend.io.storeDrainRespIn :<>= dcache.io.storeDrainRespOut
  dcache.io.dCacheCleanReqIn :<>= backend.io.dCacheCleanReqOut
  backend.io.dCacheCleanRespIn :<>= dcache.io.dCacheCleanRespOut

  ptw.io.itlbWalkReqIn :<>= itlb.io.itlbWalkReqOut
  itlb.io.itlbWalkRespIn :<>= ptw.io.itlbWalkRespOut
  ptw.io.dtlbWalkReqIn :<>= dtlb.io.dtlbWalkReqOut
  dtlb.io.dtlbWalkRespIn :<>= ptw.io.dtlbWalkRespOut
  dcache.io.ptwMemReqIn :<>= ptw.io.ptwMemReqOut
  ptw.io.ptwMemRespIn :<>= dcache.io.ptwMemRespOut
  ptw.io.sfenceVmaIn :<>= backend.io.sfenceVmaOut
  itlb.io.itlbFlushIn :<>= ptw.io.itlbFlushOut
  dtlb.io.dtlbFlushIn :<>= ptw.io.dtlbFlushOut
  itlb.io.translationContextIn := backend.io.translationContextOut
  dtlb.io.translationContextIn := backend.io.translationContextOut

  instAdapter.io.instMemReqIn :<>= icache.io.instMemReqOut
  icache.io.instMemRespIn :<>= instAdapter.io.instMemRespOut
  dataAdapter.io.dataMemReqIn :<>= dcache.io.dataMemReqOut
  dcache.io.dataMemRespIn :<>= dataAdapter.io.dataMemRespOut
  io.instBus :<>= instAdapter.io.instBus
  io.dataBus :<>= dataAdapter.io.dataBus
  if (params.usingRvvi) io.retireStreamOut.get :<>= backend.io.retireStreamOut.get
}
