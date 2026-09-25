package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.StoreBufferSpecs._

/** StoreBuffer vertex shell (spec: StoreBufferSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contStoreBuffer)
class StoreBuffer(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfCommittedStoreIn)
    val committedStoreIn = ???

    @LocalSpec(intfStoreDrainReqOut)
    val storeDrainReqOut = ???

    @LocalSpec(intfStoreDrainRespIn)
    val storeDrainRespIn = ???

    @LocalSpec(intfStoreForwardQueryIn)
    val storeForwardQueryIn = ???

    @LocalSpec(intfStoreForwardDataOut)
    val storeForwardDataOut = ???

    @LocalSpec(intfStoreBufferDrainReqIn)
    val storeBufferDrainReqIn = ???

    @LocalSpec(intfStoreBufferDrainRespOut)
    val storeBufferDrainRespOut = ???

    @LocalSpec(intfStoreBufferEmptyOut)
    val storeBufferEmptyOut = ???
  })

  @LocalSpec(funcCommitOrderDrain)
  val commitOrderDrain = ???

  @LocalSpec(funcCommittedForward)
  val committedForward = ???

  @LocalSpec(funcDrainFence)
  val drainFence = ???
}
