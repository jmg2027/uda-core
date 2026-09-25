package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.ReorderBufferSpecs._

/** ReorderBuffer (spec: ReorderBufferSpecs, ADR-019 D-19.7).
  *
  * A circular buffer of RobDepth data-less entries indexed by robTag.idx, with a head and
  * a tail RobTag pointer. The live window is [head, tail); every entry in it is valid, so
  * RobStatus.empty is head == tail and "full" is equal idx with opposite wrap. Both are
  * computed from registers: an allocation accepted in cycle t is visible (empty low) in
  * cycle t + 1.
  *
  * Recovery stance (rawSpeculativeHolder): funcRobRecovery below applies
  * funcRecoveryKills per entry; surviving entries keep done/exception/outcome state; the
  * tail rewinds to e.robTag + 1 (ArchRedirect also moves the head there and releases
  * headLocked). A completion accepted in the event cycle whose uop the same event kills
  * is dropped (the entry is invalidated in that cycle).
  */
@LocalSpec(contReorderBuffer)
class ReorderBuffer(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfRobAllocIn)
    val robAllocIn = Flipped(Decoupled(new RenameAllocation(params)))

    @LocalSpec(intfRobCompletionIn)
    val robCompletionIn = Flipped(Decoupled(new RobCompletion(params)))

    @LocalSpec(intfRobHeadOut)
    val robHeadOut = Decoupled(new RobHead(params))

    @LocalSpec(intfRobStatusOut)
    val robStatusOut = Output(new RobStatus(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))
  })

  private val depth = params.robDepth

  // ---- State -------------------------------------------------------------------

  val entries    = Reg(Vec(depth, new RobEntry(params)))
  val entryValid = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val head       = RegInit(0.U.asTypeOf(new RobTag(params)))
  val tail       = RegInit(0.U.asTypeOf(new RobTag(params)))
  val headLocked = RegInit(false.B)

  private val empty = head.asUInt === tail.asUInt
  private val full  = head.idx === tail.idx && head.wrap =/= tail.wrap

  /** The tag an index carries while it is in the live window. */
  private def tagAt(i: Int): RobTag = {
    val t = Wire(new RobTag(params))
    t.idx  := i.U
    t.wrap := Mux(i.U >= head.idx, head.wrap, !head.wrap)
    t
  }
  /** t is in [head, tail). {wrap, idx} is a counter modulo 2 * RobDepth, so the window test
    * is a distance compare; robOlder alone cannot place the tail, which is RobDepth away
    * from the head when the ROB is full. */
  private def live(t: RobTag): Bool =
    (t.asUInt - head.asUInt)(robTagWidth - 1, 0) < (tail.asUInt - head.asUInt)(robTagWidth - 1, 0)

  private val ev       = io.recoveryEventIn
  private val branchEv = ev.valid && ev.kind === RecoveryKind.BranchMispredict
  private val archEv   = ev.valid && ev.kind === RecoveryKind.ArchRedirect

  /** Next-state of the entry array; every function below edits these wires. */
  private val entriesNext = WireDefault(entries)
  private val validNext   = WireDefault(entryValid)
  entries    := entriesNext
  entryValid := validNext

  // ---- funcRobAllocate ------------------------------------------------------------

  private val al = io.robAllocIn

  @LocalSpec(funcRobAllocate)
  val robAllocate: Unit = {
    al.ready := !full
    when(al.fire) {
      val u = al.bits.uop
      val e = entriesNext(al.bits.robTag.idx)
      // Execution-free uops (ADR-019A E-1) are done at allocation: a fetch/decode exception,
      // and every fuType System uop (performed by CommitUnit at the head).
      e.done            := u.exception.valid || u.fuType === FuType.System
      e.pc              := u.pc
      e.insn            := u.insn
      e.archRd          := u.rd
      e.hasDest         := al.bits.hasDest
      e.newPrd          := al.bits.newPrd
      e.oldPrd          := al.bits.oldPrd
      e.exception       := u.exception
      e.isCfi           := u.isCfi
      e.checkpointId    := al.bits.checkpointId
      e.cfiOutcome      := 0.U.asTypeOf(e.cfiOutcome)
      e.ftqIdx          := u.prediction.ftqIdx
      e.blockEnd        := u.prediction.blockEnd
      e.isLoad          := u.isLoad
      e.isStore         := u.isStore
      e.headExecute     := false.B
      e.serialize       := u.serialize
      e.sysOp           := u.sysOp // ADR-019A E-2: explicit, never derived from op
      e.predictionFault := u.predictionFault
      e.valid           := true.B
      validNext(al.bits.robTag.idx) := true.B
      tail := RobOrder.next(tail)
    }
    when(al.fire) {
      assert(al.bits.robTag.asUInt === tail.asUInt, "RobAllocate: allocation robTag is not the tail")
      assert(!ev.valid, "RobAllocate: allocation in a RecoveryEvent cycle")
    }
  }

  // ---- funcRobComplete ----------------------------------------------------------------

  private val cp = io.robCompletionIn

  @LocalSpec(funcRobComplete)
  val robComplete: Unit = {
    cp.ready := true.B
    val effective = cp.valid && !RobOrder.recoveryKills(ev, cp.bits.robTag)
    when(effective) {
      val e = entriesNext(cp.bits.robTag.idx)
      when(cp.bits.headExecute) {
        e.headExecute := true.B
      }.otherwise {
        e.done        := true.B
        e.headExecute := false.B
        e.exception   := cp.bits.exception
        when(entries(cp.bits.robTag.idx).isCfi) { e.cfiOutcome := cp.bits.cfiOutcome }
      }
    }
  }

  // ---- funcRobHeadOffer -----------------------------------------------------------------

  private val headEntry = entries(head.idx)

  @LocalSpec(funcRobHeadOffer)
  val robHeadOffer: Unit = {
    io.robHeadOut.valid :=
      !empty && entryValid(head.idx) && !headLocked && (headEntry.done || headEntry.headExecute)
    io.robHeadOut.bits.robTag      := head
    io.robHeadOut.bits.entry       := headEntry
    io.robHeadOut.bits.entry.valid := entryValid(head.idx)
    when(io.robHeadOut.fire) {
      when(headEntry.exception.valid) {
        headLocked := true.B // trap hand-off: the head stays until the ArchRedirect
      }.otherwise {
        validNext(head.idx) := false.B
        head := RobOrder.next(head)
      }
    }
    io.robStatusOut.empty   := empty
    io.robStatusOut.headTag := head
  }

  // ---- funcRobRecovery ------------------------------------------------------------------

  /** Entries this cycle's RecoveryEvent kills. */
  private val killMask = Wire(Vec(depth, Bool()))

  @LocalSpec(funcRobRecovery)
  val robRecovery: Unit = {
    for (i <- 0 until depth)
      killMask(i) := entryValid(i) && RobOrder.recoveryKills(ev, tagAt(i))
    when(branchEv) {
      for (i <- 0 until depth) when(killMask(i)) { validNext(i) := false.B }
      entriesNext(ev.robTag.idx).blockEnd := true.B
      tail := RobOrder.next(ev.robTag)
    }
    when(archEv) {
      for (i <- 0 until depth) validNext(i) := false.B
      head       := RobOrder.next(ev.robTag)
      tail       := RobOrder.next(ev.robTag)
      headLocked := false.B
    }
  }

  // ---- Properties (simulation assertions) --------------------------------------------------

  @LocalSpec(propRobRetireInOrder)
  val robRetireInOrder: Unit = {
    // Window integrity: exactly the entries in [head, tail) are valid.
    for (i <- 0 until depth)
      assert(entryValid(i) === (!empty && live(tagAt(i))), "RobRetireInOrder: valid entries are not the window [head, tail)")
    when(io.robHeadOut.fire) {
      assert(!headLocked, "RobRetireInOrder: transfer while headLocked")
      assert(headEntry.done, "RobRetireInOrder: transfer of a head that is not done")
      assert(!empty && entryValid(head.idx), "RobRetireInOrder: transfer of an empty head")
    }
  }

  @LocalSpec(propOlderSurvivesRecovery)
  val olderSurvivesRecovery: Unit = {
    val cpHit = VecInit(Seq.tabulate(depth)(i => cp.valid && cp.bits.robTag.idx === i.U))
    when(branchEv) {
      assert(live(ev.robTag) && !empty, "OlderSurvivesRecovery: BranchMispredict names a robTag that is not live")
      for (i <- 0 until depth) {
        val survivor = entryValid(i) && !RobOrder.robOlder(ev.robTag, tagAt(i))
        when(survivor) {
          assert(!killMask(i), "OlderSurvivesRecovery: a survivor is killed")
          val retiring = io.robHeadOut.fire && !headEntry.exception.valid && head.idx === i.U
          assert(validNext(i) || retiring, "OlderSurvivesRecovery: a survivor is invalidated")
          // done/exception/outcome change only through this cycle's own completion.
          when(!cpHit(i)) {
            assert(entriesNext(i).done === entries(i).done &&
              entriesNext(i).exception.asUInt === entries(i).exception.asUInt &&
              entriesNext(i).cfiOutcome.asUInt === entries(i).cfiOutcome.asUInt,
              "OlderSurvivesRecovery: survivor state changed by the recovery")
          }
        }
      }
    }
  }

  @LocalSpec(propRobCompletionTargetsLive)
  val robCompletionTargetsLive: Unit =
    when(cp.valid) {
      val e = entries(cp.bits.robTag.idx)
      assert(live(cp.bits.robTag) && entryValid(cp.bits.robTag.idx) && !e.done && !(cp.bits.headExecute && e.headExecute),
        "RobCompletionTargetsLive: completion names an entry that is not live and not done")
    }
}
