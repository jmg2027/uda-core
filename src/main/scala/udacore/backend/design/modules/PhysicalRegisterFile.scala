package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.PhysicalRegisterFileSpecs._

/** PhysicalRegisterFile vertex shell (spec: PhysicalRegisterFileSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contPhysicalRegisterFile)
class PhysicalRegisterFile(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfPhysicalRegWriteIn)
    val physicalRegWriteIn = ???

    @LocalSpec(intfRegisterFileReadReqIn)
    val registerFileReadReqIn = ???

    @LocalSpec(intfRegisterFileReadRespOut)
    val registerFileReadRespOut = ???
  })

  @LocalSpec(funcReadAtSelect)
  val readAtSelect = ???
}
