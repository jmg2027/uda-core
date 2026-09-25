package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.PhysicalRegisterFileSpecs._

/** PhysicalRegisterFile (spec: PhysicalRegisterFileSpecs; ADR-019 D-19.7/D-19.8, ADR-019B E-3).
  *
  * IntegerPrfEntries registers; p0 reads zero and is never written. One write per cycle
  * (PublishWidth 1, always ready). Reads are combinational in the request cycle with a
  * same-cycle write bypass: IssueWidth operand ports (two operands each) toward the RS, and
  * CommitWidth commit-time ports toward the CommitUnit that exist only when usingRvvi.
  *
  * Recovery stance: recovery-exempt. It holds no program-order state and observes no
  * RecoveryEvent; values of killed producers become unreachable when rename restores its
  * map, and their prds return through the free list.
  */
@LocalSpec(contPhysicalRegisterFile)
class PhysicalRegisterFile(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfPhysicalRegWriteIn)
    val physicalRegWriteIn = Flipped(Decoupled(new PhysicalRegWrite(params)))

    @LocalSpec(intfRegisterFileReadReqIn)
    val registerFileReadReqIn = Flipped(Decoupled(new RegisterFileReadReq(params)))

    @LocalSpec(intfRegisterFileReadRespOut)
    val registerFileReadRespOut = Decoupled(new RegisterFileReadResp(params))

    @LocalSpec(intfCommitPrfReadReqIn)
    val commitPrfReadReqIn = if (params.usingRvvi) Some(Flipped(Decoupled(new CommitPrfReadReq(params)))) else None

    @LocalSpec(intfCommitPrfReadRespOut)
    val commitPrfReadRespOut = if (params.usingRvvi) Some(Decoupled(new CommitPrfReadResp(params))) else None
  })

  // Storage for p1..p(N-1) only: p0 has no register, so it can never hold a stale value.
  private val regs = Reg(Vec(params.prfEntries - 1, UInt(xLen.W)))
  private def slot(prd: UInt): UInt = (prd - 1.U)(physRegIdWidth - 1, 0)
  private val w    = io.physicalRegWriteIn

  w.ready := true.B
  when(w.valid && w.bits.prd =/= 0.U) { regs(slot(w.bits.prd)) := w.bits.data }
  when(w.valid) { assert(w.bits.prd =/= 0.U, "ReadAtSelect: p0 is never written") }

  /** Read with the same-cycle write bypass; p0 is zero. */
  private def readPort(prd: UInt): UInt =
    Mux(prd === 0.U, 0.U,
      Mux(w.valid && w.bits.prd === prd, w.bits.data, regs(slot(prd))))

  @LocalSpec(funcReadAtSelect)
  val readAtSelect: Unit = {
    val q = io.registerFileReadReqIn
    val a = io.registerFileReadRespOut
    // Combinational answer: the request is taken exactly when the answer is.
    a.valid          := q.valid
    q.ready          := a.ready
    a.bits.src1      := readPort(q.bits.prs1)
    a.bits.src2      := readPort(q.bits.prs2)
    if (params.usingRvvi) {
      val cq = io.commitPrfReadReqIn.get
      val ca = io.commitPrfReadRespOut.get
      cq.ready       := true.B // never backpressures retirement (ADR-019B E-3)
      ca.valid       := cq.valid
      ca.bits.data   := readPort(cq.bits.prd)
    }
  }

  /** Port counts derive from the widths only (never from PRF or ROB depth). */
  @LocalSpec(propPrfPortsFixed)
  val prfPortsFixed: Unit = {
    val t = params.tuning
    require(t.issueWidth == 1 && t.publishWidth == 1 && t.commitWidth == 1,
      "PrfPortsFixed: v0 elaborates one operand port pair, one write port, and one commit read port")
    val readPorts = 2 * t.issueWidth + (if (params.usingRvvi) t.commitWidth else 0)
    require(readPorts == 2 + (if (params.usingRvvi) 1 else 0), "PrfPortsFixed: read port count is not width-derived")
  }
}
