package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared.{
  BackendModule,
  BackendParams
}
import udacore.backend.design.shared.{AluReqIn, Result}
import udacore.backend.spec.modules.AluUnitSpecs._
import udacore.external.alu.design.{Alu => ExternalAlu, AluParams}

/** Arithmetic Logic Unit vertex wrapping the external ALU compute engine.
  * @param params
  *   backend domain parameters
  */
@LocalSpec(contAluUnit)
class AluUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfAluReqIn)
    val req = Flipped(Decoupled(new AluReqIn))

    @LocalSpec(intfAluResultOut)
    val resp = Decoupled(new Result)
  })

  private val alu = Module(new ExternalAlu(AluParams(dataWidth = xLen)))

  @LocalSpec(funcIntegrateExternalAlu)
  val connectExternalAlu = {
    alu.io.ctrl := io.req.bits.ctrl
    alu.io.srcA := io.req.bits.A
    alu.io.srcB := io.req.bits.B

    val respWire = Wire(new Result)
    respWire.value        := alu.io.result
    respWire.tag          := io.req.bits.rd
    respWire.seq          := io.req.bits.seq
    respWire.epoch        := io.req.bits.epoch
    respWire.trap.hasTrap := false.B
    respWire.trap.cause   := 0.U
    respWire.trap.tval    := 0.U

    io.resp.bits := respWire
  }

  io.resp.valid := io.req.valid
  io.req.ready  := io.resp.ready
}
