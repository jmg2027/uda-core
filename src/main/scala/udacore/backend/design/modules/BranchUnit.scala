package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.BranchUnitSpecs._

/** BranchUnit vertex implementing UDA interface-edge pattern */
@LocalSpec(contBranchUnit)
class BranchUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBranchUnitReqIn)
    val req = Flipped(Decoupled(new BranchUnitReq))
    
    @LocalSpec(intfBranchUnitResultOut)
    val resp = Decoupled(new BranchUnitResult)
    
    @LocalSpec(intfMispredictOut)
    val mispredict = Decoupled(new Mispredict)
  })
  
  // TODO: Expose branch prediction feedback per BranchUnit.
}
