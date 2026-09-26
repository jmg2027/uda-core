package udacore.core.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.PageTableWalkerSpecs._

/** PageTableWalker vertex shell (spec: PageTableWalkerSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contPageTableWalker)
class PageTableWalker(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfItlbWalkReqIn)
    val itlbWalkReqIn = ???

    @LocalSpec(intfItlbWalkRespOut)
    val itlbWalkRespOut = ???

    @LocalSpec(intfDtlbWalkReqIn)
    val dtlbWalkReqIn = ???

    @LocalSpec(intfDtlbWalkRespOut)
    val dtlbWalkRespOut = ???

    @LocalSpec(intfPtwMemReqOut)
    val ptwMemReqOut = ???

    @LocalSpec(intfPtwMemRespIn)
    val ptwMemRespIn = ???

    @LocalSpec(intfSfenceVmaIn)
    val sfenceVmaIn = ???

    @LocalSpec(intfItlbFlushOut)
    val itlbFlushOut = ???

    @LocalSpec(intfDtlbFlushOut)
    val dtlbFlushOut = ???
  })

  @LocalSpec(funcPtwArbitrate)
  val ptwArbitrate = ???

  @LocalSpec(funcSv32Walk)
  val sv32Walk = ???

  @LocalSpec(funcPtwPhysicalAccess)
  val ptwPhysicalAccess = ???

  @LocalSpec(funcPtwFlush)
  val ptwFlush = ???
}
