package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import udacore.common.ControlSignal.AluControl
import udacore.external.alu.design.{Alu, AluParams}
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.AluUnitSpecs._

/** AluUnit (spec: AluUnitSpecs; ADR-019, ADR-019C E-2).
  *
  * Single-cycle integer ALU around the external Alu core with a one-entry result holder:
  * operand select by the AluOp layout (register or immediate B, pc or zero A for
  * AUIPC/LUI), result registered with the uop's robTag and prd.
  *
  * Recovery stance (rawSpeculativeHolder): the only state is the held result, dropped in
  * the event cycle when funcRecoveryKills selects it; request ready = !held || drained.
  */
@LocalSpec(contAluUnit)
class AluUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfAluReqIn)
    val aluReqIn = Flipped(Decoupled(new IssuedUop(params)))

    @LocalSpec(intfAluResultOut)
    val aluResultOut = Decoupled(new FuResult(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))
  })

  private val req = io.aluReqIn
  private val ev  = io.recoveryEventIn
  private val holder = new ResultHolder(new FuResult(params), (r: FuResult) => r.robTag, ev, io.aluResultOut)

  @LocalSpec(funcIntegrateExternalAlu)
  val integrateExternalAlu: Unit = {
    val core = Module(new Alu(AluParams(dataWidth = xLen)))
    val op   = req.bits.op
    core.io.srcA := Mux(op(7), 0.U, Mux(op(6), req.bits.pc, req.bits.src1))
    core.io.srcB := Mux(op(5), req.bits.imm, req.bits.src2)
    core.io.ctrl := op(4, 0).asTypeOf(AluControl())
    val r = Wire(new FuResult(params))
    r.robTag     := req.bits.robTag
    r.prd        := req.bits.prd
    r.wen        := req.bits.hasDest
    r.data       := core.io.result
    r.exception  := 0.U.asTypeOf(r.exception)
    r.cfiOutcome := 0.U.asTypeOf(r.cfiOutcome)
    req.ready := holder.canAccept
    holder.load(req.fire, r)
  }
}
