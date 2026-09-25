package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.RenameUnitSpecs._

/** Free-list pointer: {wrap, idx} over the free-list slots, ordered like a RobTag. */
class FreeListPtr(slots: Int) extends Bundle {
  val wrap = Bool()
  val idx  = UInt(log2Ceil(slots).max(1).W)
}

/** RenameUnit (spec: RenameUnitSpecs, ADR-019 D-19.8).
  *
  * State:
  *  - robTagPtr: the program-order allocation pointer.
  *  - sRAT / rRAT: speculative and retirement maps (x0 -> p0 is structural).
  *  - freeBuf: the free-list circular FIFO of FreeSlots = PrfEntries - ArchRegNum
  *    prds. [archHead, specHead) holds the destinations of live uncommitted uops,
  *    [specHead, tail) the free prds, and tail = archHead + FreeSlots is implicit:
  *    because the rRAT always names 31 prds besides p0, the FIFO always holds
  *    exactly FreeSlots entries, so a commit writes its oldPrd into the slot its
  *    newPrd leaves (the tail slot equals the archHead slot).
  *  - checkpoints: a free-bitmask pool of {owner robTag, sRAT, specHead}.
  *  - ready: the busy table, one ready bit per prd.
  *  - serBlock: set when a serializing uop renames, cleared by RobStatus.empty.
  *
  * Recovery stance (rawSpeculativeHolder): the recover rules are
  * funcBranchRecoveryRestore and funcArchRecoveryRestore below; rRAT, archHead,
  * and the tail are committed state and survive every RecoveryEvent.
  *
  * Fork (funcAllocateAtomic): each output's valid is the conjunction of the
  * rename condition and the OTHER outputs' ready, never its own, so all offered
  * tokens fire in the same cycle and no output fires alone.
  */
@LocalSpec(contRenameUnit)
class RenameUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDecodedPacketIn)
    val decodedPacketIn = Flipped(Decoupled(new DecodedPacket(params)))

    @LocalSpec(intfRobAllocOut)
    val robAllocOut = Decoupled(new RenameAllocation(params))

    @LocalSpec(intfRsAllocOut)
    val rsAllocOut = Decoupled(new RenameAllocation(params))

    @LocalSpec(intfLsqAllocOut)
    val lsqAllocOut = Decoupled(new LsqAllocation(params))

    @LocalSpec(intfRenameCommitIn)
    val renameCommitIn = Flipped(Decoupled(new RenameCommit(params)))

    @LocalSpec(intfCheckpointReleaseIn)
    val checkpointReleaseIn = Flipped(Decoupled(new CheckpointRelease(params)))

    @LocalSpec(intfWakeupBroadcastIn)
    val wakeupBroadcastIn = Input(new WakeupBroadcast(params))

    @LocalSpec(intfRobStatusIn)
    val robStatusIn = Input(new RobStatus(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))
  })

  private val prdW      = physRegIdWidth
  private val freeSlots = params.prfEntries - regNum
  private val ckCount   = params.checkpointCount
  private val lanes     = params.decodeWidth
  require(freeSlots >= 1, "the PRF must hold at least one prd beyond the architectural map")

  private def nextPtr(p: FreeListPtr): FreeListPtr = {
    val n    = Wire(new FreeListPtr(freeSlots))
    val last = p.idx === (freeSlots - 1).U
    n.idx  := Mux(last, 0.U, p.idx + 1.U)
    n.wrap := p.wrap ^ last
    n
  }
  private def identityMap = VecInit(Seq.tabulate(regNum)(i => i.U(prdW.W)))

  // ---- State -----------------------------------------------------------------

  val robTagPtr = RegInit(0.U.asTypeOf(new RobTag(params)))
  val laneIdx   = RegInit(0.U(log2Ceil(lanes).max(1).W))
  val srat      = RegInit(identityMap)
  val rrat      = RegInit(identityMap)
  val freeBuf   = RegInit(VecInit(Seq.tabulate(freeSlots)(i => (regNum + i).U(prdW.W))))
  val specHead  = RegInit(0.U.asTypeOf(new FreeListPtr(freeSlots)))
  val archHead  = RegInit(0.U.asTypeOf(new FreeListPtr(freeSlots)))
  val ckValid   = RegInit(VecInit(Seq.fill(ckCount)(false.B)))
  val ckOwner   = Reg(Vec(ckCount, new RobTag(params)))
  val ckSrat    = Reg(Vec(ckCount, Vec(regNum, UInt(prdW.W))))
  val ckHead    = Reg(Vec(ckCount, new FreeListPtr(freeSlots)))
  val ready     = RegInit(VecInit(Seq.fill(params.prfEntries)(true.B)))
  val serBlock  = RegInit(false.B)

  // ---- Current lane and event decode -------------------------------------------

  private val pkt       = io.decodedPacketIn
  private val cur       = pkt.bits.lanes(laneIdx)
  private val uop       = cur.uop
  private val nextValid = VecInit(pkt.bits.lanes.map(_.valid).tail :+ false.B)(laneIdx)
  private val laneValid = pkt.valid && cur.valid
  private val isLast    = !nextValid

  private val ev       = io.recoveryEventIn
  private val branchEv = ev.valid && ev.kind === RecoveryKind.BranchMispredict
  private val archEv   = ev.valid && ev.kind === RecoveryKind.ArchRedirect

  private val hasDest = uop.rd =/= 0.U
  private val isMem   = uop.isLoad || uop.isStore

  private val cm      = io.renameCommitIn
  private val cmFire  = cm.valid && cm.bits.hasDest
  private val rel     = io.checkpointReleaseIn
  private val relEff  = rel.valid && !RobOrder.recoveryKills(ev, rel.bits.robTag)

  // ---- funcFreeListAllocate: pop at the speculative head, push at the tail ------

  private val freeEmpty = specHead.idx === archHead.idx && specHead.wrap =/= archHead.wrap
  private val ckFree    = VecInit(ckValid.map(!_))
  private val ckAvail   = ckFree.asUInt.orR
  private val ckAllocId = PriorityEncoder(ckFree)

  // ---- funcSerializeGate --------------------------------------------------------

  @LocalSpec(funcSerializeGate)
  val serializeGate: Bool =
    Mux(uop.serialize, io.robStatusIn.empty, !serBlock || io.robStatusIn.empty)

  // ---- funcAllocateAtomic ---------------------------------------------------------

  private val canGo =
    laneValid && (!hasDest || !freeEmpty) && (!uop.isCfi || ckAvail) && serializeGate && !ev.valid

  @LocalSpec(funcAllocateAtomic)
  val allocateAtomic: Bool = {
    val lsqOk = !isMem || io.lsqAllocOut.ready
    io.robAllocOut.valid := canGo && io.rsAllocOut.ready && lsqOk
    io.rsAllocOut.valid  := canGo && io.robAllocOut.ready && lsqOk
    io.lsqAllocOut.valid := canGo && isMem && io.robAllocOut.ready && io.rsAllocOut.ready
    canGo && io.robAllocOut.ready && io.rsAllocOut.ready && lsqOk
  }
  private val fire = allocateAtomic

  // Packet consumption: the transfer completes when its last valid lane renames. A
  // packet whose current lane is empty carries nothing and is taken as is. Nothing
  // is accepted in an event cycle (DecodeUnit discards the offered token itself).
  pkt.ready := !ev.valid && ((fire && isLast) || (pkt.valid && !cur.valid))
  when(ev.valid) {
    laneIdx := 0.U
  }.elsewhen(fire) {
    laneIdx := Mux(isLast, 0.U, laneIdx + 1.U)
  }

  // ---- funcRobTagAllocate -----------------------------------------------------------

  @LocalSpec(funcRobTagAllocate)
  val robTagAllocate: Unit =
    when(ev.valid) {
      robTagPtr := RobOrder.next(ev.robTag)
    }.elsewhen(fire) {
      robTagPtr := RobOrder.next(robTagPtr)
    }

  // ---- funcRenameMap ----------------------------------------------------------------

  private val newPrd = freeBuf(specHead.idx)
  private def mapSrc(r: UInt): UInt = Mux(r === 0.U, 0.U, srat(r))

  /** The sRAT and speculative head as they stand after renaming this lane. */
  private val sratAfter     = WireDefault(srat)
  private val specHeadAfter = WireDefault(specHead)

  @LocalSpec(funcRenameMap)
  val renameMap: RenameAllocation = {
    val a = Wire(new RenameAllocation(params))
    a.robTag            := robTagPtr
    a.uop               := uop
    a.prs1              := mapSrc(uop.rs1)
    a.prs2              := mapSrc(uop.rs2)
    a.prs1Ready         := DontCare
    a.prs2Ready         := DontCare
    a.hasDest           := hasDest
    a.newPrd            := Mux(hasDest, newPrd, 0.U)
    a.oldPrd            := Mux(hasDest, srat(uop.rd), 0.U)
    a.checkpointId.id   := Mux(uop.isCfi, ckAllocId, 0.U)
    when(fire && hasDest) {
      sratAfter(uop.rd) := newPrd
      specHeadAfter     := nextPtr(specHead)
    }
    srat     := sratAfter
    specHead := specHeadAfter
    a
  }

  // ---- funcBusyTable -----------------------------------------------------------------

  private val wk = io.wakeupBroadcastIn
  private def readyNow(prd: UInt): Bool = ready(prd) || (wk.valid && wk.prd === prd)

  @LocalSpec(funcBusyTable)
  val busyTable: Unit = {
    renameMap.prs1Ready := readyNow(renameMap.prs1)
    renameMap.prs2Ready := readyNow(renameMap.prs2)
    when(wk.valid) { ready(wk.prd) := true.B }
    // Allocation wins over a same-cycle wakeup of the same prd: that wakeup can only be
    // stale (a killed producer's), because a free-list prd has no live producer.
    when(fire && hasDest) { ready(newPrd) := false.B }
  }

  io.robAllocOut.bits := renameMap
  io.rsAllocOut.bits  := renameMap
  io.lsqAllocOut.bits.robTag  := robTagPtr
  io.lsqAllocOut.bits.isLoad  := uop.isLoad
  io.lsqAllocOut.bits.isStore := uop.isStore
  // Access size and load sign come from the RV32 load/store funct3 (insn[14:12]).
  io.lsqAllocOut.bits.size    := uop.insn(13, 12)
  io.lsqAllocOut.bits.signed  := !uop.insn(14)
  io.lsqAllocOut.bits.prd     := renameMap.newPrd

  // ---- funcSerializeGate state ------------------------------------------------------

  when(io.robStatusIn.empty) { serBlock := false.B }
  when(fire && uop.serialize) { serBlock := true.B }

  // ---- funcRetirementMapUpdate -------------------------------------------------------

  /** rRAT and architectural head including this cycle's commit (the ArchRedirect source). */
  private val rratNext     = WireDefault(rrat)
  private val archHeadNext = WireDefault(archHead)

  @LocalSpec(funcRetirementMapUpdate)
  val retirementMapUpdate: Unit = {
    cm.ready := true.B
    when(cmFire) {
      rratNext(cm.bits.archRd)  := cm.bits.newPrd
      freeBuf(archHead.idx)     := cm.bits.oldPrd
      archHeadNext              := nextPtr(archHead)
    }
    rrat     := rratNext
    archHead := archHeadNext
  }

  // ---- funcBranchCheckpoint ----------------------------------------------------------

  /** Checkpoints freed this cycle (release, own mispredict, kill, ArchRedirect). */
  private val ckFreeMask = Wire(Vec(ckCount, Bool()))

  @LocalSpec(funcBranchCheckpoint)
  val branchCheckpoint: Unit = {
    rel.ready := true.B
    for (i <- 0 until ckCount) {
      val relHit    = relEff && rel.bits.checkpointId.id === i.U
      val ownEvent  = branchEv && ev.checkpointId.id === i.U
      val killed    = ckValid(i) && RobOrder.recoveryKills(ev, ckOwner(i))
      ckFreeMask(i) := relHit || ownEvent || killed
      val alloc     = fire && uop.isCfi && ckAllocId === i.U
      ckValid(i)    := (ckValid(i) && !ckFreeMask(i)) || alloc
      when(alloc) {
        ckOwner(i) := robTagPtr
        ckSrat(i)  := sratAfter
        ckHead(i)  := specHeadAfter
      }
    }
  }

  // ---- funcBranchRecoveryRestore -------------------------------------------------------

  @LocalSpec(funcBranchRecoveryRestore)
  val branchRecoveryRestore: Unit =
    when(branchEv) {
      srat     := ckSrat(ev.checkpointId.id)
      specHead := ckHead(ev.checkpointId.id)
    }

  // ---- funcArchRecoveryRestore ---------------------------------------------------------

  @LocalSpec(funcArchRecoveryRestore)
  val archRecoveryRestore: Unit =
    when(archEv) {
      srat     := rratNext
      specHead := archHeadNext
      for (r <- 0 until regNum) ready(rratNext(r)) := true.B
    }

  // ---- Properties (simulation assertions) ----------------------------------------------

  @LocalSpec(propPhysRegConservation)
  val physRegConservation: Unit = {
    // {p0} + rRAT image (x1..x31) + free-list FIFO (live destinations and free prds)
    // cover the PRF; with 1 + 31 + FreeSlots == PrfEntries members, full coverage also
    // means no prd is in two sets.
    val members = Seq(0.U(prdW.W)) ++ rrat.drop(1) ++ freeBuf
    val cover   = members.map(m => UIntToOH(m, params.prfEntries)).reduce(_ | _)
    assert(cover.andR, "PhysRegConservation: a prd is leaked or doubly mapped")
    assert(rrat(0) === 0.U && srat(0) === 0.U, "PhysRegConservation: x0 must map to p0")
    // A committing destination is the oldest live allocation, and the prd it frees is
    // its rRAT predecessor.
    when(cmFire) {
      assert(archHead.asUInt =/= specHead.asUInt, "PhysRegConservation: commit with no live destination")
      assert(cm.bits.newPrd === freeBuf(archHead.idx), "PhysRegConservation: commit newPrd is not the oldest allocation")
      assert(cm.bits.oldPrd === rrat(cm.bits.archRd), "PhysRegConservation: commit oldPrd is not the rRAT mapping")
    }
  }

  @LocalSpec(propCheckpointReleasedOnce)
  val checkpointReleasedOnce: Unit = {
    val relId = rel.bits.checkpointId.id
    when(relEff) {
      assert(ckValid(relId), "CheckpointReleasedOnce: release of a free checkpoint")
      assert(ckOwner(relId).asUInt === rel.bits.robTag.asUInt, "CheckpointReleasedOnce: release by a non-owner")
    }
    when(branchEv) {
      assert(ckValid(ev.checkpointId.id), "CheckpointReleasedOnce: BranchMispredict names a free checkpoint")
      assert(ckOwner(ev.checkpointId.id).asUInt === ev.robTag.asUInt,
        "CheckpointReleasedOnce: BranchMispredict robTag is not the checkpoint owner")
      assert(!(relEff && relId === ev.checkpointId.id), "CheckpointReleasedOnce: released and recovered in one cycle")
    }
  }
}
