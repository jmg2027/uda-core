package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.shared.{CfiOutcome, CfiType, RecoveryCause, RecoveryEvent, RecoveryKind}
import udacore.frontend.design.shared._

/** Shared poke/peek helpers for the frontend L1 SpecTests (BranchPredictor, FetchTargetQueue,
  * FetchUnit). Every value is a plain Scala record so the drivers never build hardware.
  */
object FrontendTestKit {
  val fp = FrontendParams()

  val NONE = 0; val BRANCH = 1; val JAL = 2; val JALR = 3; val CALL = 4; val RET = 5
  def cfiName(t: Int): String = Seq("None", "Branch", "Jal", "Jalr", "Call", "Ret").lift(t).getOrElse(s"?$t")

  case class Outcome(cfiType: Int = NONE, slot: Int = 0, taken: Boolean = false, target: Long = 0)
  case class Checkpoint(ghr: BigInt = 0, rasTop: Int = 0, ras: Seq[Long] = Seq.fill(fp.tuning.rasDepth)(0L))
  case class Meta(btbHit: Boolean = false, btbWay: Int = 0, provider: Int = 0, providerCtr: Int = 0, altPred: Boolean = false,
      useAlt: Boolean = false, hitMask: Int = 0)
  case class Pred(fetchPc: Long, cfiValid: Boolean = false, cfiSlot: Int = 0, cfiType: Int = NONE, taken: Boolean = false,
      target: Long = 0, nextPc: Long = 0, meta: Meta = Meta(), cp: Checkpoint = Checkpoint()) {
    override def toString: String =
      f"Pred(pc 0x$fetchPc%x cfi ${if (cfiValid) s"${cfiName(cfiType)}@$cfiSlot" else "-"} taken $taken target 0x$target%x " +
        f"next 0x$nextPc%x ghr ${cp.ghr.toString(16)} ras ${cp.rasTop}:${cp.ras.map(x => f"$x%x").mkString(",")})"
  }
  case class Event(kind: UInt = RecoveryKind.BranchMispredict, target: Long = 0, ftqIdx: Int = 0, outcome: Outcome = Outcome(),
      cause: UInt = RecoveryCause.DirectionMispredict)

  def b(x: Bool): Boolean = x.peek().litToBoolean
  def l(x: UInt): Long    = x.peek().litValue.toLong

  def pokeOutcome(o: CfiOutcome, v: Outcome): Unit = {
    o.cfiType.poke(v.cfiType.U); o.slot.poke(v.slot.U); o.taken.poke(v.taken.B); o.target.poke(v.target.U)
  }
  def peekOutcome(o: CfiOutcome): Outcome = Outcome(l(o.cfiType).toInt, l(o.slot).toInt, b(o.taken), l(o.target))

  def pokeEvent(e: RecoveryEvent, v: Option[Event]): Unit = {
    e.valid.poke(v.nonEmpty.B)
    val x = v.getOrElse(Event())
    e.kind.poke(x.kind); e.robTag.wrap.poke(false.B); e.robTag.idx.poke(0.U); e.checkpointId.id.poke(0.U)
    e.target.poke(x.target.U); e.ftqIdx.poke(x.ftqIdx.U); pokeOutcome(e.cfiOutcome, x.outcome); e.cause.poke(x.cause)
  }

  def pokeCheckpoint(c: HistoryCheckpoint, v: Checkpoint): Unit = {
    c.ghr.poke(v.ghr.U); c.rasTop.poke(v.rasTop.U); c.rasEntries.zip(v.ras).foreach { case (r, x) => r.poke(x.U) }
  }
  def peekCheckpoint(c: HistoryCheckpoint): Checkpoint =
    Checkpoint(c.ghr.peek().litValue, l(c.rasTop).toInt, c.rasEntries.map(l))

  def pokeMeta(m: PredictorMeta, v: Meta): Unit = {
    m.btbHit.poke(v.btbHit.B); m.btbWay.poke(v.btbWay.U); m.provider.poke(v.provider.U); m.providerCtr.poke(v.providerCtr.S)
    m.altPred.poke(v.altPred.B); m.useAlt.poke(v.useAlt.B); m.hitMask.poke(v.hitMask.U)
  }
  def peekMeta(m: PredictorMeta): Meta = Meta(b(m.btbHit), l(m.btbWay).toInt, l(m.provider).toInt,
    m.providerCtr.peek().litValue.toInt, b(m.altPred), b(m.useAlt), l(m.hitMask).toInt)

  def pokePred(p: Prediction, v: Pred): Unit = {
    p.fetchPc.poke(v.fetchPc.U); p.cfiValid.poke(v.cfiValid.B); p.cfiSlot.poke(v.cfiSlot.U); p.cfiType.poke(v.cfiType.U)
    p.taken.poke(v.taken.B); p.target.poke(v.target.U); p.nextPc.poke(v.nextPc.U)
    pokeMeta(p.meta, v.meta); pokeCheckpoint(p.checkpoint, v.cp)
  }
  def peekPred(p: Prediction): Pred = Pred(l(p.fetchPc), b(p.cfiValid), l(p.cfiSlot).toInt, l(p.cfiType).toInt, b(p.taken),
    l(p.target), l(p.nextPc), peekMeta(p.meta), peekCheckpoint(p.checkpoint))

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)
  def hx(v: Long): String = f"0x$v%x"
}
