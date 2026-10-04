package udacore.frontend.design.modules

import chisel3._
import chisel3.util._
import udacore.backend.design.shared.{
  CfiOutcome,
  FtqCommit,
  RecoveryEvent,
  RecoveryKind
}
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.FetchTargetQueueSpecs._

/** Prediction-to-commit circular FTQ (ADR-019, ADR-019G/H).
  *
  * Recovery stance: the live window is [head, tail), with a separate fetch
  * cursor. Branch recovery truncates that window after the recovering entry;
  * architectural recovery empties it. Restore snapshots have independent,
  * bounded ownership and survive every subsequent recovery until the predictor
  * accepts them.
  */
@LocalSpec(contFetchTargetQueue)
class FetchTargetQueue(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfPredictionIn)
    val predictionIn      = Flipped(Decoupled(new Prediction(params)))
    @LocalSpec(intfFetchRequestOut)
    val fetchRequestOut   = Decoupled(new FetchRequest(params))
    @LocalSpec(intfFtqCommitIn)
    val ftqCommitIn       = Flipped(Decoupled(new FtqCommit(params.backend)))
    @LocalSpec(intfHistoryRestoreOut)
    val historyRestoreOut = Decoupled(new HistoryRestore(params))
    @LocalSpec(intfPredictorTrainOut)
    val predictorTrainOut = Decoupled(new PredictorTrain(params))
    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn   = Input(new RecoveryEvent(params.backend))
  })

  private val depth                            = params.ftqDepth
  private val pointerWidth                     = log2Ceil(2 * depth)
  private def index(tag: UInt): UInt           =
    if (depth == 1) 0.U else tag(log2Ceil(depth) - 1, 0)
  private def next(tag: UInt): UInt            = (tag + 1.U)(pointerWidth - 1, 0)
  private def distance(a: UInt, b: UInt): UInt = (a - b)(pointerWidth - 1, 0)

  // The distance from head gives the same live-window ordering as funcRobOlder,
  // including index wrap, without comparing the full-queue tail sentinel.
  private val head            = RegInit(0.U(pointerWidth.W))
  private val tail            = RegInit(0.U(pointerWidth.W))
  private val fetch           = RegInit(0.U(pointerWidth.W))
  private val predictions     = Reg(Vec(depth, new Prediction(params)))
  private val resolved        = Reg(Vec(depth, new CfiOutcome(params.backend)))
  private val valid           = RegInit(VecInit(Seq.fill(depth)(false.B)))
  private val tailCheckpoint  = RegInit(
    0.U.asTypeOf(new HistoryCheckpoint(params))
  )
  private val predictionsNext = WireDefault(predictions)
  private val resolvedNext    = WireDefault(resolved)
  private val validNext       = WireDefault(valid)
  predictions := predictionsNext
  resolved    := resolvedNext
  valid       := validNext

  private val count                 = distance(tail, head)
  private def live(tag: UInt): Bool = distance(tag, head) < count
  private val ev                    = io.recoveryEventIn
  private val branchEvent           =
    ev.valid && ev.kind === RecoveryKind.BranchMispredict
  private val archEvent             = ev.valid && ev.kind === RecoveryKind.ArchRedirect
  private val eventLive             = live(ev.ftqIdx)
  private val commit                = io.ftqCommitIn
  private val train                 = io.predictorTrainOut

  @LocalSpec(funcFtqRecovery)
  private val restores = Module(
    new Queue(
      new HistoryRestore(params),
      params.backend.checkpointCount + 1,
      pipe = true
    )
  )
  io.historyRestoreOut :<>= restores.io.deq

  @LocalSpec(funcFtqAllocate)
  val ftqAllocate: Unit = {
    io.predictionIn.ready := count < depth.U && !ev.valid && !restores.io.deq.valid
    when(io.predictionIn.fire) {
      predictionsNext(index(tail)) := io.predictionIn.bits
      resolvedNext(index(tail))    := 0.U.asTypeOf(new CfiOutcome(params.backend))
      validNext(index(tail))       := true.B
      tailCheckpoint               := io.predictionIn.bits.checkpoint
      tail                         := next(tail)
    }
  }

  @LocalSpec(funcFtqFetchIssue)
  val ftqFetchIssue: Unit = {
    val p   = predictions(index(fetch))
    val out = io.fetchRequestOut
    out.valid           := fetch =/= tail && !ev.valid && !restores.io.deq.valid
    out.bits.ftqIdx     := fetch
    out.bits.fetchPc    := p.fetchPc
    out.bits.lastSlot   := Mux(
      p.cfiValid && p.taken,
      p.cfiSlot,
      (params.fetchWidth - 1).U
    )
    out.bits.exitTaken  := p.cfiValid && p.taken
    out.bits.exitTarget := p.target
    when(out.fire) { fetch := next(fetch) }
  }

  @LocalSpec(funcFtqCommitTrain)
  val ftqCommitTrain: Unit = {
    val p = predictions(index(head))
    // Do not gate this edge with RecoveryEvent: retiring redirects are generated
    // by the same atomic CommitUnit transaction that consumes this readiness.
    commit.ready         := count =/= 0.U && train.ready
    train.valid          := commit.valid && count =/= 0.U
    train.bits.fetchPc   := FetchPc.blockBase(p.fetchPc, params.offsetBits)
    train.bits.ghr       := p.checkpoint.ghr
    train.bits.meta      := p.meta
    train.bits.predicted := p
    train.bits.committed := commit.bits.exit
    when(commit.fire) {
      validNext(index(head)) := false.B
      head                   := next(head)
    }
  }

  @LocalSpec(funcFtqRecovery)
  val ftqRecovery: Unit = {
    val restore = restores.io.enq
    val p       = predictions(index(ev.ftqIdx))
    restore.valid             := ev.valid
    restore.bits.checkpoint   := Mux(eventLive, p.checkpoint, tailCheckpoint)
    restore.bits.applyOutcome := branchEvent
    restore.bits.outcome      := ev.cfiOutcome
    restore.bits.pc           := FetchPc.slotPc(
      FetchPc.blockBase(p.fetchPc, params.offsetBits),
      ev.cfiOutcome.slot
    )
    when(ev.valid) {
      assert(
        restore.ready,
        "FetchTargetQueue: recovery burst exceeds ADR-019H bound"
      )
      assert(
        branchEvent || archEvent,
        "FetchTargetQueue: unsupported recovery kind"
      )
    }
    when(branchEvent) {
      assert(eventLive, "FtqRecoveryKeepsOlder: recovering entry must be live")
      for (i <- 0 until depth) {
        // Rebuild the tag at physical index i from the head's wrap and position.
        val tag =
          if (depth == 1) head
          else
            Cat(
              head(pointerWidth - 1) ^ (i.U < index(head)),
              i.U(log2Ceil(depth).W)
            )
        when(valid(i) && distance(tag, head) > distance(ev.ftqIdx, head)) {
          validNext(i) := false.B
        }
      }
      resolvedNext(index(ev.ftqIdx)) := ev.cfiOutcome
      tail := next(ev.ftqIdx)
      // Preserve unissued survivors; rewind only a cursor beyond the new tail.
      val keepCount = distance(ev.ftqIdx, head) +& 1.U
      when(distance(fetch, head) > keepCount) {
        fetch := next(ev.ftqIdx)
      }
    }
    when(archEvent) {
      val emptyAt = Mux(commit.fire, next(head), head)
      validNext.foreach(_ := false.B)
      tail := emptyAt
      fetch := emptyAt
    }
  }

  @LocalSpec(propFtqInOrderRelease)
  val ftqInOrderRelease: Unit = {
    assert(
      count <= depth.U && PopCount(valid) === count,
      "FtqInOrderRelease: live window corrupted"
    )
    when(commit.valid) {
      assert(
        count =/= 0.U && commit.bits.ftqIdx === head && valid(index(head)),
        "FtqInOrderRelease: commit must name the live head"
      )
    }
    assert(
      commit.fire === train.fire,
      "FtqInOrderRelease: commit and training must be atomic"
    )
  }

  @LocalSpec(propFtqRecoveryKeepsOlder)
  val ftqRecoveryKeepsOlder: Unit = {
    for (i <- 0 until depth) {
      val tag      =
        if (depth == 1) head
        else
          Cat(
            head(pointerWidth - 1) ^ (i.U < index(head)),
            i.U(log2Ceil(depth).W)
          )
      val survivor = branchEvent && valid(i) && distance(tag, head) <= distance(
        ev.ftqIdx,
        head
      )
      when(survivor) {
        assert(
          validNext(i) === !(commit.fire && index(head) === i.U),
          "FtqRecoveryKeepsOlder: a survivor was discarded"
        )
        assert(
          predictionsNext(i).asUInt === predictions(i).asUInt,
          "FtqRecoveryKeepsOlder: prediction or checkpoint changed"
        )
        when(tag =/= ev.ftqIdx) {
          assert(
            resolvedNext(i).asUInt === resolved(i).asUInt,
            "FtqRecoveryKeepsOlder: older outcome changed"
          )
        }
      }
    }
  }
}
