package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared.{
  BackendModule,
  BackendParams
}
import udacore.backend.design.shared.{BitAluReqIn, Result}
import udacore.backend.spec.modules.BitAluUnitSpecs._
import udacore.external.bitalu.design.{BitAlu => ExternalBitAlu, BitAluParams}

/** Bit manipulation ALU vertex wrapping the external bitmanip engine.
  * @param params
  *   backend domain parameters
  */
@LocalSpec(contBitAluUnit)
class BitAluUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfBitAluReqIn)
    val req = Flipped(Decoupled(new BitAluReqIn))

    @LocalSpec(intfBitAluResultOut)
    val resp = Decoupled(new Result)
  })

  private val bitAlu = Module(
    new ExternalBitAlu(
      BitAluParams(
        dataWidth = xLen,
        enableZba = params.enableBitAlu,
        enableZbb = params.enableBitAlu,
        enableZbs = params.enableBitAlu,
        enableZbc = params.enableBitAlu
      )
    )
  )

  @LocalSpec(funcIntegrateExternalBitAlu)
  val connectExternalBitAlu = {
    bitAlu.io.ctrl := io.req.bits.ctrl
    bitAlu.io.srcA := io.req.bits.A
    bitAlu.io.srcB := io.req.bits.B
    bitAlu.io.start := io.req.valid

    val respWire = Wire(new Result)
    respWire.value        := bitAlu.io.result
    respWire.tag          := io.req.bits.rd
    respWire.seq          := io.req.bits.seq
    respWire.epoch        := io.req.bits.epoch
    respWire.trap.hasTrap := false.B
    respWire.trap.cause   := 0.U
    respWire.trap.tval    := 0.U

    io.resp.bits := respWire
  }

  io.resp.valid := bitAlu.io.done
  io.req.ready := io.resp.ready
}
