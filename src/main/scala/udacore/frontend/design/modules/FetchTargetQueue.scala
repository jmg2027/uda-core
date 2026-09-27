package udacore.frontend.design.modules

import chisel3._
import chisel3.util._
import udacore.backend.design.shared.{CfiOutcome, FtqCommit, RecoveryEvent, RecoveryKind}
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.FetchTargetQueueSpecs._

/** FetchTargetQueue (spec: FetchTargetQueueSpecs; ADR-019 D-19.2/D-19.11, ADR-019G, ADR-019H).
  *
  * A circular queue of FtqDepth fetch blocks. Each entry keeps the whole Prediction (the requested
  * fetch PC, possibly mid-block, the PredictorMeta, and the full HistoryCheckpoint), a fetched bit,
  * and the resolved exit recorded by a BranchMispredict. Three identity pointers, all ftqIdx =
  * {wrap, idx} and ordered only by the funcRobOlder rule (ftqOlder): head (oldest uncommitted),
  * tail (next allocation), fetchPtr (oldest live entry without a FetchRequest).
  *
  * Recovery stance (rawSpeculativeHolder): a BranchMispredict keeps e.ftqIdx and every older entry,
  * invalidates the younger ones, rewinds tail to e.ftqIdx + 1 and fetchPtr to at most that point;
  * an ArchRedirect discards every entry and rewinds tail and fetchPtr to the head. The queue
  * bookkeeping is recovered in the event cycle. The HistoryRestore descriptor of each event is
  * captured in that cycle into a separate ordered restore FIFO (BranchCheckpointCount + 1 entries,
  * the ADR-019H E-4 bound), which is transaction bookkeeping and is never discarded by a later
  * event: exactly one restore per RecoveryEvent, in event order (ADR-019H E-5).
  */
@LocalSpec(contFetchTargetQueue)
class FetchTargetQueue(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfPredictionIn)
    val predictionIn = Flipped(Decoupled(new Prediction(params)))

    @LocalSpec(intfFetchRequestOut)
    val fetchRequestOut = Decoupled(new FetchRequest(params))

    @LocalSpec(intfFtqCommitIn)
    val ftqCommitIn = Flipped(Decoupled(new FtqCommit(params.backend)))

    @LocalSpec(intfHistoryRestoreOut)
    val historyRestoreOut = Decoupled(new HistoryRestore(params))

    @LocalSpec(intfPredictorTrainOut)
    val predictorTrainOut = Decoupled(new PredictorTrain(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params.backend))
  })

  private val idxBits = log2Ceil(ftqDepth)
  private val iw      = params.ftqIdxWidth
  private val ob      = params.offsetBits

  // ---- ftqIdx identity ({wrap, idx}) ----------------------------------------------------------------------
  private def wrapOf(a: UInt): Bool = a(iw - 1)
  private def idxOf(a: UInt): UInt  = a(idxBits - 1, 0)
  /** funcRobOlder on the fetch-block order domain: a is older than b. */
  private def ftqOlder(a: UInt, b: UInt): Bool =
    Mux(wrapOf(a) === wrapOf(b), idxOf(a) < idxOf(b), idxOf(a) > idxOf(b))
  /** The identity allocated after a: idx wraps at FtqDepth (a power of two) and toggles wrap. */
  private def next(a: UInt): UInt = (a + 1.U)(iw - 1, 0)
  /** Window distance from base to a (a - base modulo 2 * FtqDepth): 0 = base, FtqDepth = one full
    * window. Unlike ftqOlder it is defined for the tail sentinel, so window bounds use it. */
  private def dist(a: UInt, base: UInt): UInt = (a - base)(iw - 1, 0)

  // ---- State ----------------------------------------------------------------------------------------------
  val valid         = RegInit(VecInit(Seq.fill(ftqDepth)(false.B)))
  /** The slot has been allocated at least once (its Prediction register holds a real block). */
  val written       = RegInit(VecInit(Seq.fill(ftqDepth)(false.B)))
  val fetched       = RegInit(VecInit(Seq.fill(ftqDepth)(false.B)))
  val resolvedValid = RegInit(VecInit(Seq.fill(ftqDepth)(false.B)))
  val resolved      = Reg(Vec(ftqDepth, new CfiOutcome(params.backend)))
  /** Prediction per entry: fetchPc (requested), exit, meta, and the full HistoryCheckpoint. */
  val pred          = Reg(Vec(ftqDepth, new Prediction(params)))
  val head          = RegInit(0.U(iw.W))
  val tail          = RegInit(0.U(iw.W))
  val fetchPtr      = RegInit(0.U(iw.W))

  private val ev      = io.recoveryEventIn
  private val isMis   = ev.valid && ev.kind === RecoveryKind.BranchMispredict
  private val isArch  = ev.valid && ev.kind === RecoveryKind.ArchRedirect
  private val r       = ev.ftqIdx
  private val empty   = head === tail
  private val full    = idxOf(head) === idxOf(tail) && wrapOf(head) =/= wrapOf(tail)
  /** Identity of physical slot i while live (slots at or after head.idx carry head's wrap). */
  private def idOf(i: Int): UInt = Cat(Mux(i.U >= idxOf(head), wrapOf(head), !wrapOf(head)), i.U(idxBits.W))
  private val ids     = VecInit((0 until ftqDepth).map(idOf))
  private def live(a: UInt): Bool = valid(idxOf(a)) && ids(idxOf(a)) === a
  /** Slots this cycle's RecoveryEvent discards (funcRecoveryKills in the fetch-block domain). */
  private val killed  = VecInit((0 until ftqDepth).map(i => valid(i) && (isArch || (isMis && ftqOlder(r, ids(i))))))

  // ---- funcFtqAllocate ------------------------------------------------------------------------------------
  // Full, or a RecoveryEvent this cycle, holds PredictionIn; a pending HistoryRestore does not.
  io.predictionIn.ready := !full && !ev.valid

  @LocalSpec(funcFtqAllocate)
  val ftqAllocate: Unit = when(io.predictionIn.fire) {
    val t = idxOf(tail)
    valid(t) := true.B; written(t) := true.B; fetched(t) := false.B; resolvedValid(t) := false.B
    pred(t)  := io.predictionIn.bits
  }

  // ---- funcFtqFetchIssue ----------------------------------------------------------------------------------
  private val fIdx     = idxOf(fetchPtr)
  private val fEntry   = pred(fIdx)
  private val fPending = fetchPtr =/= tail && valid(fIdx) && !fetched(fIdx)

  @LocalSpec(funcFtqFetchIssue)
  val ftqFetchIssue: Unit = {
    val q = io.fetchRequestOut
    // A request whose entry this cycle's RecoveryEvent discards never transfers.
    q.valid           := fPending && !killed(fIdx)
    q.bits.ftqIdx     := fetchPtr
    q.bits.fetchPc    := fEntry.fetchPc
    q.bits.lastSlot   := Mux(fEntry.taken, fEntry.cfiSlot, (fetchWidth - 1).U)
    q.bits.exitTaken  := fEntry.taken
    q.bits.exitTarget := fEntry.target
    when(q.fire) { fetched(fIdx) := true.B }
  }

  // ---- funcFtqCommitTrain ---------------------------------------------------------------------------------
  private val hIdx   = idxOf(head)
  private val hEntry = pred(hIdx)
  private val commitFire = io.ftqCommitIn.fire

  @LocalSpec(funcFtqCommitTrain)
  val ftqCommitTrain: Unit = {
    val t = io.predictorTrainOut
    // FtqCommit and PredictorTrain transfer together: each side's handshake is the other's.
    t.valid              := io.ftqCommitIn.valid
    io.ftqCommitIn.ready := t.ready
    t.bits.fetchPc       := FetchPc.blockBase(hEntry.fetchPc, ob)
    t.bits.ghr           := hEntry.checkpoint.ghr
    t.bits.meta          := hEntry.meta
    t.bits.predicted     := hEntry
    t.bits.committed     := Mux(resolvedValid(hIdx), resolved(hIdx), io.ftqCommitIn.bits.exit)
    when(commitFire) { valid(hIdx) := false.B }
  }

  // ---- funcFtqRecovery ------------------------------------------------------------------------------------
  private val rqDepth = params.backend.checkpointCount + 1
  val rq      = Reg(Vec(rqDepth, new HistoryRestore(params)))
  val rqHead  = RegInit(0.U(log2Ceil(rqDepth).W))
  val rqCount = RegInit(0.U(log2Ceil(rqDepth + 1).W))
  /** p modulo rqDepth for p < 2 * rqDepth; callers widen sums (+&) so the carry reaches the compare. */
  private def rqWrap(p: UInt): UInt = Mux(p >= rqDepth.U, p - rqDepth.U, p)

  /** The descriptor captured in the event cycle (checkpoint, outcome, pc of the chosen entry).
    * ArchRedirect: e.ftqIdx if live, else the tail slot's stored checkpoint, or the reset
    * checkpoint (the BranchPredictor's reset history, all zero) if that slot was never written. */
  private val restoreNow: HistoryRestore = {
    val rIdx  = idxOf(r)
    val src   = Mux(isMis || live(r), rIdx, idxOf(tail))
    val init  = written(src)
    val d     = Wire(new HistoryRestore(params))
    d.checkpoint   := Mux(init, pred(src).checkpoint, 0.U.asTypeOf(new HistoryCheckpoint(params)))
    d.applyOutcome := isMis
    d.outcome      := ev.cfiOutcome
    d.pc           := FetchPc.slotPc(FetchPc.blockBase(Mux(init, pred(src).fetchPc, 0.U), ob), ev.cfiOutcome.slot)
    d
  }

  @LocalSpec(funcFtqRecovery)
  val ftqRecovery: Unit = {
    // Restore FIFO: one enqueue per RecoveryEvent, dequeued in order through HistoryRestoreOut.
    val o = io.historyRestoreOut
    o.valid := rqCount =/= 0.U
    o.bits  := rq(rqHead)
    when(ev.valid) { rq(rqWrap(rqHead +& rqCount)) := restoreNow }
    when(o.fire) { rqHead := rqWrap(rqHead +& 1.U) }
    rqCount := rqCount + ev.valid.asUInt - o.fire.asUInt
    assert(!(ev.valid && rqCount === rqDepth.U && !o.fire),
      "FetchTargetQueue: HistoryRestore FIFO overflow (more than BranchCheckpointCount + 1 pending restores)")

    // Queue bookkeeping, recovered in the event cycle.
    for (i <- 0 until ftqDepth) when(killed(i)) { valid(i) := false.B }
    when(isMis) {
      resolved(idxOf(r)) := ev.cfiOutcome; resolvedValid(idxOf(r)) := true.B
      assert(live(r), "FetchTargetQueue: BranchMispredict names an FTQ entry that is not live")
    }
  }

  // ---- Pointers -------------------------------------------------------------------------------------------
  private val headNext = Mux(commitFire, next(head), head)
  head := headNext
  when(isArch) {
    tail := headNext; fetchPtr := headNext
  }.elsewhen(isMis) {
    // Window arithmetic from the pre-event head: keep e.ftqIdx and older (keepCount entries); the
    // fetch position (possibly the tail sentinel, after a surviving request's same-cycle transfer)
    // is clamped to that bound.
    val fpAfter   = Mux(io.fetchRequestOut.fire, next(fetchPtr), fetchPtr)
    val keepCount = dist(r, head) +& 1.U
    val fetchDist = dist(fpAfter, head)
    val newDist   = Mux(fetchDist < keepCount, fetchDist, keepCount)
    tail := next(r)
    fetchPtr := (head + newDist)(iw - 1, 0)
  }.otherwise {
    when(io.predictionIn.fire) { tail := next(tail) }
    when(io.fetchRequestOut.fire) { fetchPtr := next(fetchPtr) }
  }

  // ---- Properties -----------------------------------------------------------------------------------------
  @LocalSpec(propFtqInOrderRelease)
  val ftqInOrderRelease: Unit = {
    assert(!io.ftqCommitIn.valid || (!empty && valid(hIdx) && io.ftqCommitIn.bits.ftqIdx === head),
      "FtqInOrderRelease: FtqCommit names an entry other than the live head")
    assert(!commitFire || io.predictorTrainOut.fire,
      "FtqInOrderRelease: the head was released without its PredictorTrain transfer")
    val prevHead   = RegNext(head, 0.U)
    val prevCommit = RegNext(commitFire, false.B)
    assert(head === prevHead || (prevCommit && head === next(prevHead)),
      "FtqInOrderRelease: the head moved other than by one FtqCommit")
    // Window integrity: exactly the slots between head and tail are live.
    val occ = tail - head
    for (i <- 0 until ftqDepth)
      assert(valid(i) === ((i.U(iw.W) - idxOf(head))(idxBits - 1, 0) < occ),
        "FtqInOrderRelease: a live entry lies outside [head, tail)")
  }

  @LocalSpec(propFtqRecoveryKeepsOlder)
  val ftqRecoveryKeepsOlder: Unit = {
    // Checked on the state after a BranchMispredict against a snapshot taken in the event cycle.
    val was      = RegNext(isMis, false.B)
    val keep     = RegNext(VecInit((0 until ftqDepth).map(i => valid(i) && !ftqOlder(r, ids(i)))))
    val younger  = RegNext(VecInit((0 until ftqDepth).map(i => valid(i) && ftqOlder(r, ids(i)))))
    val released = RegNext(VecInit((0 until ftqDepth).map(i => commitFire && i.U === hIdx)))
    val recIdx   = RegNext(idxOf(r))
    val fetchedB = RegNext(fetched)
    val resV     = RegNext(resolvedValid)
    val res      = RegNext(resolved)
    val predB    = RegNext(pred)
    val rOut     = RegNext(ev.cfiOutcome)
    val tailExp  = RegNext(next(r))
    when(was) {
      for (i <- 0 until ftqDepth) {
        val k = keep(i) && !released(i)
        assert(!k || (valid(i) && pred(i).asUInt === predB(i).asUInt && (fetched(i) || !fetchedB(i))),
          "FtqRecoveryKeepsOlder: a BranchMispredict discarded or modified an entry at or older than e.ftqIdx")
        assert(!k || i.U === recIdx || (resolvedValid(i) === resV(i) && resolved(i).asUInt === res(i).asUInt),
          "FtqRecoveryKeepsOlder: a resolved exit changed on an entry other than e.ftqIdx")
        assert(!younger(i) || !valid(i), "FtqRecoveryKeepsOlder: an entry younger than e.ftqIdx survived a BranchMispredict")
      }
      assert(released(recIdx) || (resolvedValid(recIdx) && resolved(recIdx).asUInt === rOut.asUInt),
        "FtqRecoveryKeepsOlder: the recovering entry did not record e.cfiOutcome")
      assert(tail === tailExp, "FtqRecoveryKeepsOlder: the tail did not rewind to e.ftqIdx + 1")
    }
  }
}
