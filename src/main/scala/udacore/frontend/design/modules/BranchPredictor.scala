package udacore.frontend.design.modules

import chisel3._
import chisel3.util._
import udacore.backend.design.shared.{CfiType, RecoveryEvent}
import framework.macros.LocalSpec
import udacore.frontend.design.shared._
import udacore.frontend.spec.modules.BranchPredictorSpecs._

/** BranchPredictor (spec: BranchPredictorSpecs; ADR-019 D-19.2/D-19.11, ADR-019G).
  *
  * One prediction per PredictReq, combinational from the request PC and the table/history
  * state: the request, the Prediction to the FTQ, and the NextPc to FetchPcGen transfer in one
  * cycle (atomic fork), so FetchPcGen can offer one block per cycle. BTB and TAGE are indexed
  * and tagged with blockBase = alignDown(fetchPc, FetchBytes) (ADR-019G E-3); only BTB entries
  * at or after startSlot are eligible; the fall-through is blockBase + FetchBytes (E-4); a
  * Call pushes blockBase + 4 * cfiSlot + 4 (E-5).
  *
  * Tables (BTB, TAGE, bimodal) are microarchitectural and written only by PredictorTrain at
  * commit. The speculative GHR and RAS are the only speculative state: updated when a
  * prediction transfers, overwritten by HistoryRestore after a RecoveryEvent. Between an event
  * and its restore (counted, one restore per event) PredictReq is held.
  */
@LocalSpec(contBranchPredictor)
class BranchPredictor(val params: FrontendParams) extends FrontendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfPredictReqIn)
    val predictReqIn = Flipped(Decoupled(new PredictReq(params.vAddrWidth)))

    @LocalSpec(intfPredictionOut)
    val predictionOut = Decoupled(new Prediction(params))

    @LocalSpec(intfNextPcOut)
    val nextPcOut = Decoupled(new PredictReq(params.vAddrWidth))

    @LocalSpec(intfHistoryRestoreIn)
    val historyRestoreIn = Flipped(Decoupled(new HistoryRestore(params)))

    @LocalSpec(intfPredictorTrainIn)
    val predictorTrainIn = Flipped(Decoupled(new PredictorTrain(params)))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params.backend))
  })

  private val t        = params.tuning
  private val ob       = params.offsetBits
  private val sW       = params.slotWidth
  private val nTables  = params.tageTables
  private val hist     = t.tageHistoryLengths
  private val tIdxBits = log2Ceil(t.tageTableEntries)
  private val tTagBits = t.tageTagBits
  private val bSets    = t.btbSets
  private val bWays    = t.btbWays
  private val bIdxBits = log2Ceil(bSets)
  private val bTagBits = vAddrWidth - ob - bIdxBits
  private val rasDepth = t.rasDepth

  // ---- State -------------------------------------------------------------------------------------

  class BtbEntry extends Bundle {
    val tag     = UInt(bTagBits.W)
    val slot    = UInt(sW.W)
    val cfiType = UInt(CfiType.width.W)
    val target  = UInt(vAddrWidth.W)
  }
  class TageEntry extends Bundle {
    val tag    = UInt(tTagBits.W)
    val ctr    = SInt(3.W)
    val useful = UInt(2.W)
  }

  val btbValid = RegInit(VecInit(Seq.fill(bSets)(VecInit(Seq.fill(bWays)(false.B)))))
  val btb      = Reg(Vec(bSets, Vec(bWays, new BtbEntry)))
  /** Per set, per way LRU rank (0 = most recently trained). */
  val btbRank  = RegInit(VecInit(Seq.fill(bSets)(VecInit((0 until bWays).map(_.U(log2Ceil(bWays max 2).W))))))
  val bimodal  = RegInit(VecInit(Seq.fill(t.bimodalEntries)(1.U(2.W)))) // weakly not-taken
  val tageValid = RegInit(VecInit(Seq.fill(nTables)(VecInit(Seq.fill(t.tageTableEntries)(false.B)))))
  val tage      = Reg(Vec(nTables, Vec(t.tageTableEntries, new TageEntry)))
  val ghr       = RegInit(0.U(ghrLength.W))
  val rasTop    = RegInit(0.U(params.rasTopWidth.W))
  val ras       = RegInit(VecInit(Seq.fill(rasDepth)(0.U(vAddrWidth.W))))
  val pendingRestores = RegInit(0.U(3.W))

  private val ev  = io.recoveryEventIn
  private val req = io.predictReqIn
  private val hr  = io.historyRestoreIn
  private val tr  = io.predictorTrainIn

  // ---- Index / tag functions (all on blockBase, ADR-019G E-3) --------------------------------------

  private def blockBits(pc: UInt): UInt = pc(vAddrWidth - 1, ob)
  private def btbIdx(pc: UInt): UInt = blockBits(pc)(bIdxBits - 1, 0)
  private def btbTag(pc: UInt): UInt = blockBits(pc)(vAddrWidth - ob - 1, bIdxBits)
  private def fold(h: UInt, len: Int, w: Int): UInt =
    (0 until len by w).map(i => h(math.min(i + w, len) - 1, i).pad(w)).reduce(_ ^ _)
  private def bimIdx(pc: UInt): UInt = blockBits(pc)(log2Ceil(t.bimodalEntries) - 1, 0)
  private def tageIdx(pc: UInt, g: UInt, i: Int): UInt =
    (blockBits(pc)(tIdxBits - 1, 0) ^ fold(g, hist(i), tIdxBits) ^ (i * 0x5b % t.tageTableEntries).U)(tIdxBits - 1, 0)
  private def tageTag(pc: UInt, g: UInt, i: Int): UInt = {
    val hi = blockBits(pc)(tIdxBits + tTagBits - 1, tIdxBits)
    (hi ^ Cat(fold(g, hist(i), tTagBits - 1), 0.U(1.W)) ^ fold(g, hist(i), tTagBits))(tTagBits - 1, 0)
  }
  private def satInc(c: SInt, up: Bool): SInt = Mux(up, Mux(c === 3.S, c, c + 1.S), Mux(c === -4.S, c, c - 1.S))

  /** TAGE lookup: (provider 0 = bimodal, provider counter, provider direction, alternate direction,
    * useAlt, hit mask) for block pc with history g. */
  private case class TageView(provider: UInt, provCtr: SInt, provPred: Bool, altPred: Bool, useAlt: Bool, hits: Seq[Bool],
      idx: Seq[UInt], bIdx: UInt) {
    def pred: Bool = Mux(useAlt, altPred, provPred)
  }
  private def tageLookup(pc: UInt, g: UInt): TageView = {
    val idx  = (0 until nTables).map(i => tageIdx(pc, g, i))
    val tag  = (0 until nTables).map(i => tageTag(pc, g, i))
    val hits = (0 until nTables).map(i => tageValid(i)(idx(i)) && tage(i)(idx(i)).tag === tag(i))
    val bI   = bimIdx(pc)
    val bimPred = bimodal(bI)(1)
    // Provider: the longest matching table; alternate: the next-longest match or the bimodal base.
    val provider = PriorityMux(hits.zipWithIndex.reverse.map { case (h, i) => h -> (i + 1).U } :+ (true.B -> 0.U))
    def dirOf(k: UInt): Bool = Mux(k === 0.U, bimPred, VecInit((0 until nTables).map(i => tage(i)(idx(i)).ctr >= 0.S))(k - 1.U))
    val altProvider = PriorityMux((0 until nTables).reverse.map(i => (hits(i) && (i + 1).U < provider) -> (i + 1).U) :+
      (true.B -> 0.U))
    val provCtr = Mux(provider === 0.U, 0.S(3.W), VecInit((0 until nTables).map(i => tage(i)(idx(i)).ctr))(provider - 1.U))
    val provUseful = Mux(provider === 0.U, 0.U, VecInit((0 until nTables).map(i => tage(i)(idx(i)).useful))(provider - 1.U))
    val weak = provCtr === 0.S || provCtr === -1.S
    TageView(provider, provCtr, dirOf(provider), dirOf(altProvider), provider =/= 0.U && weak && provUseful === 0.U, hits, idx, bI)
  }

  // ---- funcBtbLookup ------------------------------------------------------------------------------

  private val pc        = req.bits.fetchPc
  private val base      = FetchPc.blockBase(pc, ob)
  private val startSlot = FetchPc.startSlot(pc, ob)

  /** The lowest-slot eligible (slot >= startSlot, ADR-019G E-3) hitting way of the fetch block. */
  @LocalSpec(funcBtbLookup)
  val btbLookup: (Bool, UInt, BtbEntry) = {
    val set  = btbIdx(pc)
    val elig = (0 until bWays).map(w => btbValid(set)(w) && btb(set)(w).tag === btbTag(pc) && btb(set)(w).slot >= startSlot)
    val best = (0 until bWays).map { w =>
      elig(w) && (0 until bWays).filter(_ != w).map(o => !elig(o) || btb(set)(w).slot < btb(set)(o).slot ||
        (btb(set)(w).slot === btb(set)(o).slot && w.U < o.U)).foldLeft(true.B)(_ && _)
    }
    (elig.reduce(_ || _), OHToUInt(best), Mux1H(best, btb(set)))
  }
  private val (btbHit, btbWay, hitEntry) = btbLookup

  // ---- funcTageDirection ------------------------------------------------------------------------------

  private val tv = tageLookup(pc, ghr)

  /** Provider direction, or the alternate for a weak, not-yet-useful provider. */
  @LocalSpec(funcTageDirection)
  val tageDirection: Bool = tv.pred

  // ---- funcRasPredict / funcBlockExitSelect ------------------------------------------------------------

  private val isBranch = hitEntry.cfiType === CfiType.Branch
  private val isCall   = hitEntry.cfiType === CfiType.Call
  private val isRet    = hitEntry.cfiType === CfiType.Ret
  private val cfiPc    = FetchPc.slotPc(base, hitEntry.slot)

  @LocalSpec(funcRasPredict)
  val rasPredict: UInt = cfiPc + 4.U // the return address a Call pushes (blockBase + 4 * slot + 4)

  @LocalSpec(funcBlockExitSelect)
  val blockExitSelect: Prediction = {
    val p = Wire(new Prediction(params))
    p.fetchPc  := pc
    p.cfiValid := btbHit
    p.cfiSlot  := hitEntry.slot
    p.cfiType  := Mux(btbHit, hitEntry.cfiType, CfiType.None)
    p.taken    := btbHit && Mux(isBranch, tageDirection, true.B)
    p.target   := Mux(isRet, ras(rasTop), hitEntry.target)
    p.nextPc   := Mux(p.taken, p.target, base + params.fetchBytes.U)
    p.meta.btbHit      := btbHit
    p.meta.btbWay      := btbWay
    p.meta.provider    := tv.provider
    p.meta.providerCtr := tv.provCtr
    p.meta.altPred     := tv.altPred
    p.meta.useAlt      := tv.useAlt
    p.meta.hitMask     := VecInit(tv.hits).asUInt
    p.checkpoint.ghr        := ghr
    p.checkpoint.rasTop     := rasTop
    p.checkpoint.rasEntries := ras
    p
  }

  // ---- The atomic fork -----------------------------------------------------------------------------------

  private val canPredict = req.valid && pendingRestores === 0.U && !ev.valid
  io.predictionOut.valid := canPredict && io.nextPcOut.ready
  io.nextPcOut.valid     := canPredict && io.predictionOut.ready
  io.predictionOut.bits  := blockExitSelect
  io.nextPcOut.bits.fetchPc := blockExitSelect.nextPc
  req.ready := pendingRestores === 0.U && !ev.valid && io.predictionOut.ready && io.nextPcOut.ready
  private val fire = req.fire

  // ---- funcSpeculativeHistoryUpdate ---------------------------------------------------------------------

  private def push(top: UInt, entries: Vec[UInt], v: UInt): Unit = {
    val n = Mux(top === (rasDepth - 1).U, 0.U, top + 1.U)
    rasTop := n; ras := entries; ras(n) := v
  }
  private def pop(top: UInt, entries: Vec[UInt]): Unit = {
    rasTop := Mux(top === 0.U, (rasDepth - 1).U, top - 1.U); ras := entries
  }

  @LocalSpec(funcSpeculativeHistoryUpdate)
  val speculativeHistoryUpdate: Unit = when(fire) {
    val p = blockExitSelect
    when(p.cfiValid && isBranch) { ghr := Cat(ghr(ghrLength - 2, 0), p.taken) }
    when(p.cfiValid && isCall) { push(rasTop, ras, rasPredict) }
      .elsewhen(p.cfiValid && isRet) { pop(rasTop, ras) }
  }

  // ---- funcHistoryRestore -----------------------------------------------------------------------------------

  hr.ready := true.B

  @LocalSpec(funcHistoryRestore)
  val historyRestore: Unit = {
    when(hr.fire) {
      val cp = hr.bits.checkpoint
      val o  = hr.bits.outcome
      val ap = hr.bits.applyOutcome
      ghr := Mux(ap && o.cfiType === CfiType.Branch, Cat(cp.ghr(ghrLength - 2, 0), o.taken), cp.ghr)
      rasTop := cp.rasTop; ras := cp.rasEntries
      when(ap && o.cfiType === CfiType.Call) { push(cp.rasTop, cp.rasEntries, hr.bits.pc + 4.U) }
        .elsewhen(ap && o.cfiType === CfiType.Ret) { pop(cp.rasTop, cp.rasEntries) }
    }
    pendingRestores := pendingRestores + ev.valid.asUInt - hr.fire.asUInt
    assert(!(pendingRestores === 7.U && ev.valid && !hr.fire), "BranchPredictor: outstanding HistoryRestore counter overflow")
    assert(!hr.fire || pendingRestores =/= 0.U || ev.valid, "BranchPredictor: HistoryRestore without a RecoveryEvent")
  }

  // ---- funcPredictorTraining ----------------------------------------------------------------------------------

  tr.ready := true.B

  @LocalSpec(funcPredictorTraining)
  val predictorTraining: Unit = when(tr.fire) {
    val tpc = tr.bits.fetchPc // blockBase (ADR-019G E-7)
    val c   = tr.bits.committed
    val pr  = tr.bits.predicted
    // BTB: allocate or update the entry for the committed taken exit.
    when(c.cfiType =/= CfiType.None && c.taken) {
      val set   = btbIdx(tpc)
      val match_ = (0 until bWays).map(w => btbValid(set)(w) && btb(set)(w).tag === btbTag(tpc) && btb(set)(w).slot === c.slot)
      val free  = (0 until bWays).map(w => !btbValid(set)(w))
      val lru   = (0 until bWays).map(w => btbRank(set)(w) === (bWays - 1).U)
      val way   = Mux(match_.reduce(_ || _), OHToUInt(match_), Mux(free.reduce(_ || _), PriorityEncoder(free), OHToUInt(lru)))
      btbValid(set)(way) := true.B
      btb(set)(way).tag := btbTag(tpc); btb(set)(way).slot := c.slot
      btb(set)(way).cfiType := c.cfiType; btb(set)(way).target := c.target
      val r = btbRank(set)(way)
      for (w <- 0 until bWays) {
        when(w.U === way) { btbRank(set)(w) := 0.U }
          .elsewhen(btbRank(set)(w) < r) { btbRank(set)(w) := btbRank(set)(w) + 1.U }
      }
    }
    // TAGE: a committed taken Branch exit, or a tracked Branch that committed not-taken.
    val tracked  = pr.cfiValid && pr.cfiType === CfiType.Branch
    val takenBr  = c.cfiType === CfiType.Branch && c.taken
    val notTaken = tracked && (c.cfiType === CfiType.None || c.slot > pr.cfiSlot)
    when(takenBr || notTaken) {
      val out  = takenBr
      val v    = tageLookup(tpc, tr.bits.ghr)
      val predicted = tracked && pr.taken && (!takenBr || pr.cfiSlot === c.slot)
      when(v.provider === 0.U) {
        val b = bimodal(v.bIdx)
        bimodal(v.bIdx) := Mux(out, Mux(b === 3.U, b, b + 1.U), Mux(b === 0.U, b, b - 1.U))
      }
      for (i <- 0 until nTables) {
        val e = tage(i)(v.idx(i))
        when(v.provider === (i + 1).U) {
          e.ctr := satInc(e.ctr, out)
          when(v.provPred =/= v.altPred) {
            e.useful := Mux(v.provPred === out, Mux(e.useful === 3.U, e.useful, e.useful + 1.U),
              Mux(e.useful === 0.U, e.useful, e.useful - 1.U))
          }
        }
      }
      // Allocation on a misprediction: the shortest longer table with a free (useful 0) entry.
      when(predicted =/= out) {
        val cand = (0 until nTables).map(i => (i + 1).U > v.provider && (!tageValid(i)(v.idx(i)) || tage(i)(v.idx(i)).useful === 0.U))
        val any  = cand.reduce(_ || _)
        val pick = PriorityEncoderOH(cand)
        for (i <- 0 until nTables) {
          val e = tage(i)(v.idx(i))
          when(any && pick(i)) {
            tageValid(i)(v.idx(i)) := true.B
            e.tag := tageTag(tpc, tr.bits.ghr, i); e.ctr := Mux(out, 0.S, -1.S); e.useful := 0.U
          }.elsewhen(!any && (i + 1).U > v.provider && e.useful =/= 0.U) {
            e.useful := e.useful - 1.U
          }
        }
      }
    }
  }

  // ---- Properties (simulation assertions) -----------------------------------------------------------------------

  /** Structural (no instruction-data input exists) plus: a prediction transfers only with its own
    * request and names that request's PC. */
  @LocalSpec(propPredictFromPcOnly)
  val predictFromPcOnly: Unit =
    assert(!io.predictionOut.fire || (req.fire && io.predictionOut.bits.fetchPc === pc && io.nextPcOut.fire),
      "PredictFromPcOnly: a prediction transferred without its own request (or without its NextPc)")

  @LocalSpec(propOneTakenCfiPerBlock)
  val oneTakenCfiPerBlock: Unit = {
    val p = io.predictionOut.bits
    assert(!io.predictionOut.valid || ((!p.taken || p.cfiValid) && (!p.cfiValid || p.cfiSlot >= startSlot) &&
      p.nextPc === Mux(p.taken, p.target, base + params.fetchBytes.U)),
      "OneTakenCfiPerBlock: the prediction names an ineligible CFI or an inconsistent exit")
  }

  /** The state after a restore is the checkpoint plus exactly the recovering outcome, and no
    * prediction updates history in the restore cycle. */
  @LocalSpec(propHistoryRestoreExact)
  val historyRestoreExact: Unit = {
    val was  = RegNext(hr.fire, false.B)
    val cp   = RegEnable(hr.bits.checkpoint, hr.fire)
    val o    = RegEnable(hr.bits.outcome, hr.fire)
    val ap   = RegEnable(hr.bits.applyOutcome, hr.fire)
    val rpc  = RegEnable(hr.bits.pc, hr.fire)
    assert(!(hr.fire && fire), "HistoryRestoreExact: a prediction updated history in the restore cycle")
    val isB = ap && o.cfiType === CfiType.Branch
    val isC = ap && o.cfiType === CfiType.Call
    val isR = ap && o.cfiType === CfiType.Ret
    val expTop = Mux(isC, Mux(cp.rasTop === (rasDepth - 1).U, 0.U, cp.rasTop + 1.U),
      Mux(isR, Mux(cp.rasTop === 0.U, (rasDepth - 1).U, cp.rasTop - 1.U), cp.rasTop))
    val expGhr = Mux(isB, Cat(cp.ghr(ghrLength - 2, 0), o.taken), cp.ghr)
    val rasOk = (0 until rasDepth).map(i => ras(i) === Mux(isC && i.U === expTop, rpc + 4.U, cp.rasEntries(i))).reduce(_ && _)
    assert(!was || (ghr === expGhr && rasTop === expTop && rasOk),
      "HistoryRestoreExact: GHR/RAS after a restore differ from checkpoint plus the resolved outcome")
  }
}
