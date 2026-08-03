package udacore.core.design.top

import chisel3._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.top.CoreTopSpecs._

@LocalSpec(contCoreTop)
class CoreTop(val params: CoreParams) extends CoreModule {

  val io = IO(new Bundle {
    @LocalSpec(intfBootAddrIn)
    val bootAddrIn = ???

    @LocalSpec(intfHartEnIn)
    val hartEnIn = ???

    @LocalSpec(intfInterruptIn)
    val interruptIn = ???

    @LocalSpec(intfDebugReqIn)
    val debugReqIn = ???

    // ADR-016: the external memory boundary is two standard TileLink links
    // (master view; new TLBundle(params.instBusParams) / (params.dataBusParams)
    // once the bus adapter vertices land).
    @LocalSpec(intfInstBus)
    val instBus = ???

    @LocalSpec(intfDataBus)
    val dataBus = ???
  })

  @LocalSpec(funcBootSequencing)
  val bootSequencing = ???

  @LocalSpec(funcGlobalEpochManagement)
  val globalEpochManagement = ???

  @LocalSpec(funcFrontendBackendDataflow)
  val frontendBackendDataflow = ???

  @LocalSpec(funcMemorySubsystemIntegration)
  val memorySubsystemIntegration = ???

  @LocalSpec(funcExternalMemoryBridge)
  val externalMemoryBridge = ???

  @LocalSpec(funcInterruptDebugIntegration)
  val interruptDebugIntegration = ???
}
