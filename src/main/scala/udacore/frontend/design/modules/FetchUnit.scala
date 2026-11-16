package udacore.frontend.design.modules

import chisel3._
import chisel3.util._
import udacore.backend.design.shared.RecoveryEvent
import udacore.core.design.shared.{
  AccessType,
  ICacheReq,
  ICacheResp,
  TranslateReq
}
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.FetchUnitSpecs._

/** FetchUnit (spec: FetchUnitSpecs; ADR-019 D-19.3, D-19.4 and ADR-019G E-6).
  *
  * One outstanding I-cache lookup bounds the metadata needed to assemble a
  * response. A recovery marks that lookup stale but lets the uncancelable
  * cache/TLB work drain. No new lookup is issued until the old response
  * arrives, so even 16 recoveries cannot alias an old reqId with a new
  * transaction. A completed block is held until the FetchBuffer accepts it or
  * recovery drops it.
  */
@LocalSpec(contFetchUnit)
class FetchUnit(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfFetchRequestIn)
    val fetchRequestIn = Flipped(Decoupled(new FetchRequest(params)))

    @LocalSpec(intfITlbReqOut)
    val iTlbReqOut =
      Decoupled(new TranslateReq(params.fetchGenWidth, params.vAddrWidth))

    @LocalSpec(intfICacheReqOut)
    val iCacheReqOut =
      Decoupled(new ICacheReq(params.fetchGenWidth, params.vAddrWidth))

    @LocalSpec(intfICacheRespIn)
    val iCacheRespIn = Flipped(
      Decoupled(new ICacheResp(params.fetchGenWidth, params.fetchWidth))
    )

    @LocalSpec(intfFetchBlockOut)
    val fetchBlockOut = Decoupled(new FetchBlock(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params.backend))
  })

  private val ev           = io.recoveryEventIn.valid
  private val gen          = RegInit(0.U(params.fetchGenWidth.W))
  private val pendingValid = RegInit(false.B)
  private val pendingLive  = RegInit(false.B)
  private val pendingReq   = Reg(new FetchRequest(params))
  private val pendingId    = Reg(UInt(params.fetchGenWidth.W))
  private val holdValid    = RegInit(false.B)
  private val hold         = Reg(new FetchBlock(params))
  private val holdGen      = Reg(UInt(params.fetchGenWidth.W))

  private val canIssue  = !ev && !pendingValid && !holdValid
  private val bothReady = io.iTlbReqOut.ready && io.iCacheReqOut.ready
  private val offer     = io.fetchRequestIn.valid && canIssue && bothReady

  @LocalSpec(funcParallelFetchLookup)
  val parallelFetchLookup: Unit = {
    io.fetchRequestIn.ready    := canIssue && bothReady
    io.iTlbReqOut.valid        := offer
    io.iTlbReqOut.bits.reqId   := gen
    io.iTlbReqOut.bits.vaddr   := io.fetchRequestIn.bits.fetchPc
    io.iTlbReqOut.bits.access  := AccessType.Fetch
    io.iCacheReqOut.valid      := offer
    io.iCacheReqOut.bits.reqId := gen
    io.iCacheReqOut.bits.vaddr := FetchPc.blockBase(
      io.fetchRequestIn.bits.fetchPc,
      params.offsetBits
    )
    when(io.fetchRequestIn.fire) {
      pendingValid := true.B
      pendingLive  := true.B
      pendingReq   := io.fetchRequestIn.bits
      pendingId    := gen
    }
    assert(
      io.iTlbReqOut.fire === io.iCacheReqOut.fire,
      "FetchUnitAtomic: ITLB and I-cache must accept together"
    )
  }

  @LocalSpec(funcFetchGeneration)
  val fetchGeneration: Unit = {
    io.iCacheRespIn.ready := pendingValid
    when(ev) {
      gen         := gen + 1.U
      pendingLive := false.B
      holdValid   := false.B
    }
    when(io.iCacheRespIn.fire) {
      assert(
        io.iCacheRespIn.bits.reqId === pendingId,
        "FetchUnitResponse: reqId does not echo the accepted request"
      )
      pendingValid := false.B
    }
  }

  @LocalSpec(funcFetchBlockAssembly)
  val fetchBlockAssembly: Unit = {
    io.fetchBlockOut.valid := holdValid && !ev
    io.fetchBlockOut.bits  := hold
    when(io.fetchBlockOut.fire) { holdValid := false.B }
    when(io.iCacheRespIn.fire && pendingLive && !ev) {
      val r     = io.iCacheRespIn.bits
      val start =
        if (params.fetchWidth == 1) 0.U(params.slotWidth.W)
        else FetchPc.startSlot(pendingReq.fetchPc, params.offsetBits)
      val fault = r.fault =/= FetchFault.None
      hold.ftqIdx := pendingReq.ftqIdx
      hold.basePc := FetchPc.blockBase(pendingReq.fetchPc, params.offsetBits)
      hold.insts  := r.data
      for (i <- 0 until params.fetchWidth) {
        hold.slotValid(i) := (i.U >= start) && Mux(
          fault,
          i.U === start,
          i.U <= pendingReq.lastSlot
        )
      }
      hold.exitTaken := pendingReq.exitTaken && !fault
      hold.exitTarget := Mux(fault, 0.U, pendingReq.exitTarget)
      hold.fault      := r.fault
      holdGen         := pendingId
      holdValid       := true.B
    }
  }

  @LocalSpec(propFetchInOrder)
  val fetchInOrder: Unit = {
    val seenOut = RegInit(false.B)
    val lastIdx = Reg(UInt(params.ftqIdxWidth.W))
    when(ev) { seenOut := false.B }
    when(io.fetchBlockOut.fire) {
      when(seenOut) {
        assert(
          io.fetchBlockOut.bits.ftqIdx === (lastIdx + 1.U)(
            params.ftqIdxWidth - 1,
            0
          ),
          "FetchInOrder: consecutive accepted FTQ requests must leave in order"
        )
      }
      lastIdx := io.fetchBlockOut.bits.ftqIdx
      seenOut := true.B
    }
    assert(
      !(pendingValid && holdValid),
      "FetchInOrder: more than one fetch is in flight"
    )
  }

  @LocalSpec(propNoStaleFetchBlock)
  val noStaleFetchBlock: Unit = {
    val staleDrainPrev =
      RegNext(io.iCacheRespIn.fire && (!pendingLive || ev), false.B)
    assert(
      !(ev && io.fetchBlockOut.valid),
      "NoStaleFetchBlock: block offered during recovery"
    )
    when(io.fetchBlockOut.valid) {
      assert(
        holdGen === gen,
        "NoStaleFetchBlock: held block belongs to an old generation"
      )
    }
    when(staleDrainPrev) {
      assert(
        !io.fetchBlockOut.valid,
        "NoStaleFetchBlock: stale response became a fetch block"
      )
    }
  }
}
