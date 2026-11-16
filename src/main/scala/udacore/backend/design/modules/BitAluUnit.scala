package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.BitAluUnitSpecs._

/** Unimplemented optional bit-manipulation wrapper (spec: BitAluUnitSpecs).
  *
  * Excluded from the RV32IM v0 configuration. Its documented placeholders raise
  * NotImplementedError if elaborated; the ADR-018 runner reports them as
  * PENDING.
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
