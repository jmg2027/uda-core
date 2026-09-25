package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.DispatchUnitSpecs._

/** DispatchUnit (spec: DispatchUnitSpecs; ADR-019, ADR-019C E-1/E-2).
  *
  * Stateless router: the presented IssuedUop is offered on exactly one request edge,
  * selected by fuType, and IssuedUopIn.ready is that edge's ready. It holds no token, so
  * FuAvailability is exactly the per-class downstream request readiness, which every
  * execution wrapper derives from its own registered state (never from this request).
  *
  * Recovery stance (rawSpeculativeHolder): it holds no entries; a presented token that the
  * same-cycle RecoveryEvent kills (funcRecoveryKills) is not routed.
  */
@LocalSpec(contDispatchUnit)
class DispatchUnit(val params: BackendParams) extends BackendModule {
  private val CSRControlWidth = udacore.common.ControlSignal.CSRControl.getWidth
  val io = IO(new Bundle {
    @LocalSpec(intfIssuedUopIn)
    val issuedUopIn = Flipped(Decoupled(new IssuedUop(params)))

    @LocalSpec(intfAluReqOut)
    val aluReqOut = Decoupled(new IssuedUop(params))

    @LocalSpec(intfBitAluReqOut)
    val bitAluReqOut = if (params.enableBitAlu) Some(Decoupled(new IssuedUop(params))) else None

    @LocalSpec(intfMultiplierReqOut)
    val multiplierReqOut = Decoupled(new IssuedUop(params))

    @LocalSpec(intfDividerReqOut)
    val dividerReqOut = Decoupled(new IssuedUop(params))

    @LocalSpec(intfBranchUnitReqOut)
    val branchUnitReqOut = Decoupled(new IssuedUop(params))

    @LocalSpec(intfAddressGenerationReqOut)
    val addressGenerationReqOut = Decoupled(new IssuedUop(params))

    @LocalSpec(intfCsrReqOut)
    val csrReqOut = Decoupled(new CsrReq(params))

    @LocalSpec(intfFuAvailabilityOut)
    val fuAvailabilityOut = Output(new FuAvailability(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))
  })

  private val in    = io.issuedUopIn
  private val alive = in.valid && !RobOrder.recoveryKills(io.recoveryEventIn, in.bits.robTag)
  private def is(t: UInt): Bool = in.bits.fuType === t

  /** The IssuedUop request edges by class. */
  private val edges: Seq[(UInt, DecoupledIO[IssuedUop])] = Seq(
    FuType.Alu -> io.aluReqOut, FuType.Mul -> io.multiplierReqOut, FuType.Div -> io.dividerReqOut,
    FuType.Branch -> io.branchUnitReqOut, FuType.Mem -> io.addressGenerationReqOut)

  // ---- funcFuAvailability (ADR-019C E-2: downstream readiness only) -----------------------

  @LocalSpec(funcFuAvailability)
  val fuAvailability: FuAvailability = {
    val a = io.fuAvailabilityOut
    a.alu    := io.aluReqOut.ready
    a.mul    := io.multiplierReqOut.ready
    a.div    := io.dividerReqOut.ready
    a.branch := io.branchUnitReqOut.ready
    a.mem    := io.addressGenerationReqOut.ready
    a.csr    := io.csrReqOut.ready
    a.bitAlu.foreach(_ := false.B) // the BitAluUnit edge is added with that extension
    a
  }

  // ---- funcFuRoute -------------------------------------------------------------------------

  @LocalSpec(funcFuRoute)
  val fuRoute: Unit = {
    for ((t, e) <- edges) {
      e.valid := alive && is(t)
      e.bits  := in.bits
    }
    io.bitAluReqOut.foreach { e => e.valid := false.B; e.bits := in.bits }
    // CSR edge (legacy bndCsrReq shape until the CSR rewrite): address in imm, operand in src1.
    io.csrReqOut.valid         := alive && is(FuType.Csr)
    io.csrReqOut.bits.csr      := in.bits.imm(11, 0)
    io.csrReqOut.bits.op       := in.bits.op(CSRControlWidth - 1, 0).asTypeOf(io.csrReqOut.bits.op)
    io.csrReqOut.bits.data     := in.bits.src1
    io.csrReqOut.bits.meta.rd    := 0.U
    io.csrReqOut.bits.meta.epoch := 0.U
    // IssuedUopIn.ready equals the availability bit of the presented class (ADR-019C E-2).
    in.ready := fuAvailability.of(in.bits.fuType)
    val offered = edges.map(_._2.valid) :+ io.csrReqOut.valid
    assert(PopCount(offered) <= 1.U, "FuRoute: a uop was offered on more than one edge")
  }
}
