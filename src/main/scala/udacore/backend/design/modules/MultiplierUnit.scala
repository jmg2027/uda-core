package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.MultiplierUnitSpecs._
import udacore.external.multiplier.design.{
  AccumMethod,
  Multiplier,
  MultiplierParams
}

/** MultiplierUnit vertex shell described by MultiplierUnit. */
@LocalSpec(contMultiplierUnit)
class MultiplierUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfMultiplierReqIn)
    val req = Flipped(Decoupled(new MultiplierReq(params)))

    @LocalSpec(intfMultiplierResultOut)
    val resp = Decoupled(new MultiplierResult(params))

    @LocalSpec(intfGlobalEpochIn)
    val globalEpoch = Input(UInt(epochWidth.W))
  })

  private val requestReg    = Reg(new MultiplierReq(params))
  private val requestActive = RegInit(false.B)

  // Production config is the single-cycle (32,1) point (ProductSpecs: "production ships
  // single-cycle"; lowest latency, no accumulator). The multi-cycle points (e.g. 16,1) run
  // the same corrected rvv_coprocessor core and are verified too (multiplier.csa16 green);
  // selecting a smaller/slower multiplier is a PPA choice (OQ-C, owner-gated), not a
  // correctness one.
  private val multiplierIp = Module(
    new Multiplier(
      MultiplierParams(
        dataWidth = xLen,
        sliceWidth = 32,
        slicesPerCycle = 1
      )
    )
  )

  private val staleRequest      =
    requestActive && (requestReg.epoch =/= io.globalEpoch)
  private val resultValid       = requestActive && multiplierIp.io.done
  private val releasingResponse = resultValid && io.resp.ready && !staleRequest
  private val canAccept         = !requestActive || releasingResponse
  private val launch            = io.req.valid && canAccept
  private val currentReq        = Wire(chiselTypeOf(requestReg))

  @LocalSpec(funcIntegrateExternalMultiplier)
  val connectMultiplierIp = {
    currentReq := requestReg
    when(launch) {
      requestReg := io.req.bits
      currentReq := io.req.bits
    }

    when(staleRequest) {
      requestActive := false.B
    }.elsewhen(releasingResponse) {
      requestActive := false.B
    }.elsewhen(launch) {
      requestActive := true.B
    }

    multiplierIp.io.start := launch
    multiplierIp.io.kill  := staleRequest
    multiplierIp.io.op    := currentReq.op
    multiplierIp.io.src1  := currentReq.lhs
    multiplierIp.io.src2  := currentReq.rhs

    val respWire = Wire(new MultiplierResult(params))
    respWire.uopId := requestReg.uopId
    respWire.data  := multiplierIp.io.result
    respWire.rd    := requestReg.rd
    respWire.seq   := requestReg.seq
    respWire.epoch := requestReg.epoch
    respWire.flags := 0.U

    io.req.ready  := canAccept
    io.resp.valid := resultValid && !staleRequest
    io.resp.bits  := respWire
  }
}
