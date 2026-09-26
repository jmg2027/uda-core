package udacore.core.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.InstructionTlbSpecs._

/** InstructionTlb vertex shell (spec: InstructionTlbSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contInstructionTlb)
class InstructionTlb(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfITlbReqIn)
    val iTlbReqIn = ???

    @LocalSpec(intfICacheTranslationOut)
    val iCacheTranslationOut = ???

    @LocalSpec(intfItlbWalkReqOut)
    val itlbWalkReqOut = ???

    @LocalSpec(intfItlbWalkRespIn)
    val itlbWalkRespIn = ???

    @LocalSpec(intfItlbFlushIn)
    val itlbFlushIn = ???

    @LocalSpec(intfTranslationContextIn)
    val translationContextIn = ???
  })

  @LocalSpec(funcItlbTranslate)
  val itlbTranslate = ???

  @LocalSpec(funcItlbMissWalk)
  val itlbMissWalk = ???

  @LocalSpec(funcItlbRefill)
  val itlbRefill = ???

  @LocalSpec(funcItlbFlush)
  val itlbFlush = ???
}
