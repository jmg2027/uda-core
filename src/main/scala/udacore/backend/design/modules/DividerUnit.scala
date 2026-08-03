package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.DividerUnitSpecs._
import udacore.common.ControlSignal.DividerControl
import udacore.external.divider.design.{Divider, DividerParams}

/** DividerUnit vertex shell described by DividerUnit. */
@LocalSpec(contDividerUnit)
class DividerUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDividerReqIn)
    val req = Flipped(Decoupled(new DividerReq(params)))

    @LocalSpec(intfDividerResultOut)
    val resp = Decoupled(new DividerResult(params))

    @LocalSpec(intfGlobalEpochIn)
    val globalEpoch = Input(UInt(epochWidth.W))
  })

  private val requestReg    = Reg(new DividerReq(params))
  private val requestActive = RegInit(false.B)

  private val dividerIp = Module(
    new Divider(
      DividerParams(
        dataWidth = xLen,
        useRestoring = false,
        useClz = true
      )
    )
  )

  private val staleRequest       = requestActive && (requestReg.epoch =/= io.globalEpoch)
  private val resultValid        = requestActive && dividerIp.io.done
  private val releasingResponse  = resultValid && io.resp.ready && !staleRequest
  private val canAccept          = !requestActive || releasingResponse
  private val launch             = io.req.valid && canAccept
  private val currentReq         = Wire(chiselTypeOf(requestReg))

  @LocalSpec(funcIntegrateExternalDivider)
  val connectDividerIp = {
    currentReq := requestReg
    when(launch) {
      requestReg := io.req.bits
      currentReq := io.req.bits
    }

    when(staleRequest) {
      requestActive := false.B
    }

    when(releasingResponse) {
      requestActive := false.B
    }

    when(launch) {
      requestActive := true.B
    }

    dividerIp.io.start := launch
    dividerIp.io.kill := staleRequest
    dividerIp.io.op := currentReq.op
    dividerIp.io.src1 := currentReq.dividend
    dividerIp.io.src2 := currentReq.divisor

    val respWire = Wire(new DividerResult(params))
    val isDiv = requestReg.op === DividerControl.DIV || requestReg.op === DividerControl.DIVU
    val isRem = requestReg.op === DividerControl.REM || requestReg.op === DividerControl.REMU

    respWire.uopId := requestReg.uopId
    respWire.quotient := Mux(isDiv, dividerIp.io.result, 0.U)
    respWire.remainder := Mux(isRem, dividerIp.io.result, 0.U)
    respWire.rd := requestReg.rd
    respWire.seq := requestReg.seq
    respWire.epoch := requestReg.epoch
    respWire.flags := (requestReg.divisor === 0.U).asUInt

    io.req.ready := canAccept
    io.resp.valid := resultValid && !staleRequest
    io.resp.bits := respWire
  }
}
