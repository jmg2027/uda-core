package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.ReservationStationSpecs._

/** One RS entry (the RenameAllocation fields the issue needs, plus source ready bits). */
class RsEntry(val params: BackendParams) extends BackendBundle {
  val robTag       = new RobTag(params)
  val fuType       = UInt(FuType.width.W)
  val op           = UInt(UopOp.width.W)
  val imm          = UInt(xLen.W)
  val pc           = UInt(vAddrWidth.W)
  val prs1         = UInt(physRegIdWidth.W)
  val prs2         = UInt(physRegIdWidth.W)
  val r1           = Bool()
  val r2           = Bool()
  val prd          = UInt(physRegIdWidth.W)
  val hasDest      = Bool()
  val checkpointId = new BranchCheckpointId(params)
  val prediction   = new PredictionView(params)
  val insn         = UInt(iLen.W)        // ADR-019D E-2
  val sysOp        = UInt(SysOp.width.W) // ADR-019D E-2
}

/** ReservationStation (spec: ReservationStationSpecs; ADR-019, ADR-019A E-1, ADR-019C E-1/E-3).
  *
  * IntegerRsEntries unordered entries, each holding its robTag, operation, source prds with
  * ready bits, and destination. Only needsRs uops are allocated. Each cycle the oldest
  * entry (funcRobOlder) whose sources are ready and whose FU class is available is offered
  * with its PRF operands; the entry is released exactly at the issue transfer. An offer
  * that is not accepted is held unchanged until it transfers or a RecoveryEvent kills it.
  *
  * Recovery stance (rawSpeculativeHolder): funcRecoveryKills per entry and per held offer in
  * the event cycle; older entries and their ready bits survive; killed entries are freed
  * in that cycle.
  */
@LocalSpec(contReservationStation)
class ReservationStation(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfRsAllocIn)
    val rsAllocIn = Flipped(Decoupled(new RenameAllocation(params)))

    @LocalSpec(intfWakeupBroadcastIn)
    val wakeupBroadcastIn = Input(new WakeupBroadcast(params))

    @LocalSpec(intfRegisterFileReadReqOut)
    val registerFileReadReqOut = Decoupled(new RegisterFileReadReq(params))

    @LocalSpec(intfRegisterFileReadRespIn)
    val registerFileReadRespIn = Flipped(Decoupled(new RegisterFileReadResp(params)))

    @LocalSpec(intfIssuedUopOut)
    val issuedUopOut = Decoupled(new IssuedUop(params))

    @LocalSpec(intfFuAvailabilityIn)
    val fuAvailabilityIn = Input(new FuAvailability(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))
  })

  private val n  = params.tuning.integerRsEntries
  private val ev = io.recoveryEventIn
  private val wk = io.wakeupBroadcastIn
  private val av = io.fuAvailabilityIn
  private val al = io.rsAllocIn


  val valid     = RegInit(VecInit(Seq.fill(n)(false.B)))
  val entries   = Reg(Vec(n, new RsEntry(params)))
  val heldValid = RegInit(false.B)
  val heldIdx   = Reg(UInt(log2Ceil(n).max(1).W))

  private val killed = VecInit(Seq.tabulate(n)(i => valid(i) && RobOrder.recoveryKills(ev, entries(i).robTag)))

  // ---- funcRsWakeup ---------------------------------------------------------------------

  private def woken(prs: UInt): Bool = wk.valid && wk.prd === prs

  @LocalSpec(funcRsWakeup)
  val rsWakeup: Unit =
    for (i <- 0 until n) {
      when(woken(entries(i).prs1)) { entries(i).r1 := true.B }
      when(woken(entries(i).prs2)) { entries(i).r2 := true.B }
    }

  // ---- funcSelectOldestReady (ADR-019C E-1/E-3) ------------------------------------------

  private val eligible = VecInit(Seq.tabulate(n)(i =>
    valid(i) && entries(i).r1 && entries(i).r2 && av.of(entries(i).fuType) && !killed(i)))

  /** Oldest eligible index by funcRobOlder (pairwise reduction). */
  private val (anyEligible, oldestIdx) = {
    val cands = Seq.tabulate(n)(i => (eligible(i), i.U(log2Ceil(n).max(1).W)))
    cands.reduce { (a, b) =>
      val takeB = b._1 && (!a._1 || RobOrder.robOlder(entries(b._2).robTag, entries(a._2).robTag))
      (a._1 || b._1, Mux(takeB, b._2, a._2))
    }
  }

  private val holdAlive = heldValid && !killed(heldIdx)
  private val offerIdx  = Mux(holdAlive, heldIdx, oldestIdx)
  private val offer     = holdAlive || anyEligible
  private val sel       = entries(offerIdx)
  private val q         = io.registerFileReadReqOut
  private val r         = io.registerFileReadRespIn
  private val iu        = io.issuedUopOut

  @LocalSpec(funcSelectOldestReady)
  val selectOldestReady: Bool = {
    // The PRF read, the issue transfer, and the entry release coincide.
    q.valid     := offer
    q.bits.prs1 := sel.prs1
    q.bits.prs2 := sel.prs2
    r.ready     := iu.ready
    iu.valid    := offer && r.valid
    iu.bits.robTag       := sel.robTag
    iu.bits.fuType       := sel.fuType
    iu.bits.op           := sel.op
    iu.bits.src1         := r.bits.src1
    iu.bits.src2         := r.bits.src2
    iu.bits.imm          := sel.imm
    iu.bits.pc           := sel.pc
    iu.bits.prd          := sel.prd
    iu.bits.hasDest      := sel.hasDest
    iu.bits.checkpointId := sel.checkpointId
    iu.bits.prediction   := sel.prediction
    iu.bits.insn         := sel.insn
    iu.bits.sysOp        := sel.sysOp
    heldValid := iu.valid && !iu.ready
    heldIdx   := offerIdx
    iu.fire
  }
  private val issueFire = selectOldestReady

  // ---- Allocation (only needsRs uops, ADR-019A E-1) ------------------------------------------

  private val free    = VecInit(valid.map(!_))
  private val freeIdx = PriorityEncoder(free)
  al.ready := free.asUInt.orR

  // ---- funcRsRecovery ---------------------------------------------------------------------------

  @LocalSpec(funcRsRecovery)
  val rsRecovery: Unit =
    for (i <- 0 until n) {
      when(killed(i) || (issueFire && offerIdx === i.U)) { valid(i) := false.B }
    }

  when(al.fire) {
    val u = al.bits.uop
    val e = entries(freeIdx)
    valid(freeIdx)  := true.B
    e.robTag        := al.bits.robTag
    e.fuType        := u.fuType
    e.op            := u.op
    e.imm           := u.imm
    e.pc            := u.pc
    e.prs1          := al.bits.prs1
    e.prs2          := al.bits.prs2
    e.r1            := al.bits.prs1Ready || woken(al.bits.prs1)
    e.r2            := al.bits.prs2Ready || woken(al.bits.prs2)
    e.prd           := al.bits.newPrd
    e.hasDest       := al.bits.hasDest
    e.checkpointId  := al.bits.checkpointId
    e.prediction    := u.prediction
    e.insn          := u.insn
    e.sysOp         := u.sysOp
    assert(!u.exception.valid && u.fuType =/= FuType.System, "ReservationStation: only needsRs uops are allocated")
    assert(!ev.valid, "ReservationStation: allocation in a RecoveryEvent cycle")
  }

  // ---- Properties (simulation assertions) ----------------------------------------------------------

  @LocalSpec(propRsIssueOnlyReady)
  val rsIssueOnlyReady: Unit =
    when(issueFire) {
      assert(valid(offerIdx) && sel.r1 && sel.r2, "RsIssueOnlyReady: issue of an entry with a busy source")
      assert(!killed(offerIdx), "RsIssueOnlyReady: issue of a killed entry")
    }

  @LocalSpec(propRsRecoveryKeepsOlder)
  val rsRecoveryKeepsOlder: Unit =
    when(ev.valid && ev.kind === RecoveryKind.BranchMispredict) {
      for (i <- 0 until n)
        when(valid(i) && !RobOrder.robOlder(ev.robTag, entries(i).robTag)) {
          assert(!killed(i), "RsRecoveryKeepsOlder: an entry at or older than the recovering branch is killed")
        }
    }

  @LocalSpec(propRsIssueStable)
  val rsIssueStable: Unit = {
    val prevHeld = RegNext(iu.valid && !iu.ready, false.B)
    val prevBits = RegNext(iu.bits)
    val prevIdx  = RegNext(offerIdx)
    when(prevHeld && !killed(prevIdx)) {
      assert(iu.valid && offerIdx === prevIdx && iu.bits.asUInt === prevBits.asUInt,
        "RsIssueStable: a held offer changed before its transfer")
    }
    // An entry leaves only by its issue transfer or a kill.
    val gone = VecInit(Seq.tabulate(n)(i => valid(i) && !(al.fire && freeIdx === i.U) &&
      (killed(i) || (issueFire && offerIdx === i.U))))
    val prevValid = RegNext(valid)
    val prevGone  = RegNext(gone)
    for (i <- 0 until n)
      when(prevValid(i) && !valid(i)) { assert(prevGone(i), "RsIssueStable: an entry was freed without issue or kill") }
  }
}
