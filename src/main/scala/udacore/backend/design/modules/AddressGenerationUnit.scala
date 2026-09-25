package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.AddressGenerationUnitSpecs._

/** AddressGenerationUnit vertex shell (spec: AddressGenerationUnitSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contAddressGenerationUnit)
class AddressGenerationUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfAddressGenerationReqIn)
    val addressGenerationReqIn = ???

    @LocalSpec(intfMemAddressOut)
    val memAddressOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcAddressGenerate)
  val addressGenerate = ???
}
