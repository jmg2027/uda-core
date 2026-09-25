package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.DecodeUnitSpecs._

/** DecodeUnit vertex shell (spec: DecodeUnitSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contDecodeUnit)
class DecodeUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfFetchPacketIn)
    val fetchPacketIn = ???

    @LocalSpec(intfDecodedPacketOut)
    val decodedPacketOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcDecodeRv32im)
  val decodeRv32im = ???

  @LocalSpec(funcExtensionDecodeContribution)
  val extensionDecodeContribution = ???

  @LocalSpec(funcSerializingTag)
  val serializingTag = ???

  @LocalSpec(funcPredictionCheck)
  val predictionCheck = ???

  @LocalSpec(funcDecodeRecovery)
  val decodeRecovery = ???
}
