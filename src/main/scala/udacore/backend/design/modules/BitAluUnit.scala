package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.BitAluUnitSpecs._

/** BitAluUnit vertex shell (spec: BitAluUnitSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contBitAluUnit)
class BitAluUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBitAluReqIn)
    val bitAluReqIn = ???

    @LocalSpec(intfBitAluResultOut)
    val bitAluResultOut = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcIntegrateExternalBitAlu)
  val integrateExternalBitAlu = ???
}
