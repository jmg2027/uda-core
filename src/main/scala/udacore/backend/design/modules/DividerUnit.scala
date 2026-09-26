package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import udacore.common.ControlSignal.DividerControl
import udacore.external.divider.design.{Divider, DividerParams}
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.DividerUnitSpecs._

/** DividerUnit (spec: DividerUnitSpecs; ADR-019, ADR-019C E-2).
  *
  * Wraps the external Divider (non-restoring with CLZ) with one in-flight operation and a
  * one-entry result holder.
  *
  * Recovery stance (rawSpeculativeHolder): an operation killed by funcRecoveryKills while in
  * flight is marked killed; the core finishes and its result is discarded. A held result is
  * dropped in the event cycle. Older operations always complete and publish.
  */
@LocalSpec(contDividerUnit)
class DividerUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDividerReqIn)
    val dividerReqIn = Flipped(Decoupled(new IssuedUop(params)))

    @LocalSpec(intfDividerResultOut)
    val dividerResultOut = Decoupled(new FuResult(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))
  })

  private val req = io.dividerReqIn
  private val ev  = io.recoveryEventIn
  private val holder = new ResultHolder(new FuResult(params), (r: FuResult) => r.robTag, ev, io.dividerResultOut)

  // The in-flight operation: the core cannot be canceled, so a killed operation keeps
  // running with `killed` set and its eventual result is discarded (never published).
  val busy    = RegInit(false.B)
  val killed  = Reg(Bool())
  val flight  = Reg(new FuResult(params))

  @LocalSpec(funcIntegrateExternalDivider)
  val integrateExternalDivider: Unit = {
    val core = Module(new Divider(DividerParams(dataWidth = xLen, useRestoring = false, useClz = true)))
    core.io.kill  := false.B // ADR-017: external kill ports are not a UDACore recovery path
    // The core expects its operation and operands stable until done: hold them while busy.
    val heldOp = Reg(UInt(UopOp.width.W))
    val heldA  = Reg(UInt(xLen.W))
    val heldB  = Reg(UInt(xLen.W))
    when(req.fire) { heldOp := req.bits.op; heldA := req.bits.src1; heldB := req.bits.src2 }
    core.io.op    := Mux(busy, heldOp, req.bits.op)(DividerControl.getWidth - 1, 0).asTypeOf(DividerControl())
    core.io.src1  := Mux(busy, heldA, req.bits.src1)
    core.io.src2  := Mux(busy, heldB, req.bits.src2)
    // canAccept: registered state (busy, holder) and the held result's drain only.
    req.ready     := !busy && holder.canAccept
    core.io.start := req.fire

    val meta = Wire(new FuResult(params))
    meta.robTag     := req.bits.robTag
    meta.prd        := req.bits.prd
    meta.wen        := req.bits.hasDest
    meta.data       := 0.U
    meta.exception  := 0.U.asTypeOf(meta.exception)
    meta.cfiOutcome := 0.U.asTypeOf(meta.cfiOutcome)

    val cur       = Mux(busy, flight, meta)
    val curKilled = Mux(busy, killed, false.B) || RobOrder.recoveryKills(ev, cur.robTag)
    when(req.fire) {
      flight := meta
      killed := RobOrder.recoveryKills(ev, meta.robTag)
      busy   := !core.io.done
    }
    when(busy && RobOrder.recoveryKills(ev, flight.robTag)) { killed := true.B }
    val result = Wire(new FuResult(params))
    result      := cur
    result.data := core.io.result
    val finish  = core.io.done && (busy || req.fire)
    when(busy && core.io.done) { busy := false.B }
    holder.load(finish && !curKilled, result)
  }
}
