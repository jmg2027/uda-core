package udacore.core.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.DataCacheSpecs._

/** VIPT write-back DataCache (spec: DataCacheSpecs; ADR-019 D-19.5/D-19.12).
  *
  * Fill sources belong to individual MSHRs. A separate source serializes dirty
  * writebacks, and another serves one uncached request at a time. Committed
  * store bytes awaiting a fill stay in an MSHR overlay and merge before install.
  * Loads and PTE reads may join that fill; neither is canceled by recovery.
  */
@LocalSpec(contDataCache)
class DataCache(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDCacheLoadReqIn)
    val dCacheLoadReqIn = Flipped(Decoupled(new DCacheLoadReq(params.lsq.lqIdxWidth,
      params.lsq.generationWidth, params.vAddrWidth)))

    @LocalSpec(intfDCacheTranslationIn)
    val dCacheTranslationIn = Flipped(Decoupled(new Translation(params.lsq.reqIdWidth,
      params.pAddrWidth)))

    @LocalSpec(intfDCacheLoadRespOut)
    val dCacheLoadRespOut = Decoupled(new DCacheLoadResp(params.lsq.lqIdxWidth,
      params.lsq.generationWidth, params.pAddrWidth, params.dataWidth))

    @LocalSpec(intfStoreDrainReqIn)
    val storeDrainReqIn = Flipped(Decoupled(new StoreDrainReq(params.pAddrWidth, params.dataWidth)))

    @LocalSpec(intfStoreDrainRespOut)
    val storeDrainRespOut = Decoupled(new StoreDrainResp)

    @LocalSpec(intfPtwMemReqIn)
    val ptwMemReqIn = Flipped(Decoupled(new PtwMemReq(params.pAddrWidth)))

    @LocalSpec(intfPtwMemRespOut)
    val ptwMemRespOut = Decoupled(new PtwMemResp)

    @LocalSpec(intfDCacheCleanReqIn)
    val dCacheCleanReqIn = Flipped(Decoupled(new CacheMaintenance))

    @LocalSpec(intfDCacheCleanRespOut)
    val dCacheCleanRespOut = Decoupled(new CacheMaintenance)

    @LocalSpec(intfUncachedLoadReqIn)
    val uncachedLoadReqIn = Flipped(Decoupled(new UncachedLoadReq(params.lsq.lqIdxWidth,
      params.lsq.generationWidth, params.pAddrWidth)))

    @LocalSpec(intfUncachedLoadRespOut)
    val uncachedLoadRespOut = Decoupled(new UncachedLoadResp(params.lsq.lqIdxWidth,
      params.lsq.generationWidth, params.dataWidth))

    @LocalSpec(intfUncachedStoreReqIn)
    val uncachedStoreReqIn = Flipped(Decoupled(new UncachedStoreReq(params.lsq.sqIdxWidth,
      params.pAddrWidth, params.dataWidth)))

    @LocalSpec(intfUncachedStoreRespOut)
    val uncachedStoreRespOut = Decoupled(new UncachedStoreResp(params.lsq.sqIdxWidth))

    @LocalSpec(intfDataMemReqOut)
    val dataMemReqOut = Decoupled(new DataMemReq(params.pAddrWidth, params.dataWidth,
      params.dataBusParams.sourceBits, params.dataBusParams.sizeBits))

    @LocalSpec(intfDataMemRespIn)
    val dataMemRespIn = Flipped(Decoupled(new DataMemResp(params.dataWidth,
      params.dataBusParams.sourceBits)))
  })

  private val geo = params.contract.dcache
  private val sets = geo.sets
  private val ways = geo.ways
  private val mshrs = geo.mshrs
  private val targets = geo.targetsPerMshr
  private val beatBytes = params.dataWidth / 8
  private val beatShift = log2Ceil(beatBytes)
  private val lineBytes = geo.blockBytes
  private val lineWords = lineBytes / beatBytes
  private val offBits = log2Ceil(lineBytes)
  private val idxBits = log2Ceil(sets)
  private val setWidth = math.max(1, idxBits)
  private val wayWidth = math.max(1, log2Ceil(ways))
  private val mWidth = math.max(1, log2Ceil(mshrs))
  private val wordWidth = math.max(1, log2Ceil(lineWords))
  private val beatWidth = math.max(1, log2Ceil(lineWords + 1))
  private val rowWidth = math.max(1, log2Ceil(sets * ways))
  private val tagWidth = params.pAddrWidth - offBits - idxBits
  private val wbSource = mshrs.U(params.dataBusParams.sourceBits.W)
  private val ucSource = (mshrs + 1).U(params.dataBusParams.sourceBits.W)
  require(lineBytes % beatBytes == 0 && offBits + idxBits <= 12,
    "DataCache needs whole bus beats and a VIPT index inside the Sv32 page offset")
  require(tagWidth > 0 && mshrs >= 1 && targets >= 1)

  private def setOf(a: UInt): UInt =
    if (sets == 1) 0.U(setWidth.W) else a(offBits + idxBits - 1, offBits)
  private def tagOf(a: UInt): UInt = a(params.pAddrWidth - 1, offBits + idxBits)
  private def wordOf(a: UInt): UInt = a(offBits - 1, log2Ceil(beatBytes))
  private def lineOf(a: UInt): UInt = Cat(a(params.pAddrWidth - 1, offBits), 0.U(offBits.W))
  private def sameWord(a: UInt, b: UInt): Bool =
    a(params.pAddrWidth - 1, beatShift) === b(params.pAddrWidth - 1, beatShift)
  private def lineAddr(tag: UInt, set: UInt): UInt =
    if (sets == 1) Cat(tag, 0.U(offBits.W))
    else Cat(tag, set(idxBits - 1, 0), 0.U(offBits.W))
  private def rowOf(set: UInt, way: UInt): UInt =
    if (sets == 1 && ways == 1) 0.U(rowWidth.W)
    else if (sets == 1) way.pad(rowWidth)
    else if (ways == 1) set(idxBits - 1, 0).pad(rowWidth)
    else Cat(way(wayWidth - 1, 0), set(idxBits - 1, 0))
  private def mergeWord(old: UInt, fresh: UInt, mask: UInt): UInt =
    Cat((0 until beatBytes).reverse.map(i => Mux(mask(i),
      fresh(8 * i + 7, 8 * i), old(8 * i + 7, 8 * i))))

  val valid = RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.fill(ways)(false.B)))))
  val dirty = RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.fill(ways)(false.B)))))
  val tags = Mem(sets, Vec(ways, UInt(tagWidth.W)))
  val lines = Module(new CacheLineSram(sets * ways, lineWords, params.dataWidth))
  val replace = RegInit(VecInit(Seq.fill(sets)(0.U(wayWidth.W))))

  private val sFree :: sReadVictim :: sWriteback :: sFillReq :: sFillWait :: sComplete :: Nil = Enum(6)
  val mState = RegInit(VecInit(Seq.fill(mshrs)(sFree)))
  val mAddr = Reg(Vec(mshrs, UInt(params.pAddrWidth.W)))
  val mSet = Reg(Vec(mshrs, UInt(setWidth.W)))
  val mWay = Reg(Vec(mshrs, UInt(wayWidth.W)))
  val mOldTag = Reg(Vec(mshrs, UInt(tagWidth.W)))
  val mOldLine = Reg(Vec(mshrs, Vec(lineWords, UInt(params.dataWidth.W))))
  val mFill = Reg(Vec(mshrs, Vec(lineWords, UInt(params.dataWidth.W))))
  val mOverlay = Reg(Vec(mshrs, Vec(lineWords, UInt(params.dataWidth.W))))
  val mMask = RegInit(VecInit(Seq.fill(mshrs)(VecInit(Seq.fill(lineWords)(0.U(beatBytes.W))))))
  val mBeat = RegInit(VecInit(Seq.fill(mshrs)(0.U(beatWidth.W))))
  val mDenied = RegInit(VecInit(Seq.fill(mshrs)(false.B)))
  val mTValid = RegInit(VecInit(Seq.fill(mshrs)(VecInit(Seq.fill(targets)(false.B)))))
  val mTIdx = Reg(Vec(mshrs, Vec(targets, UInt(params.lsq.lqIdxWidth.W))))
  val mTGen = Reg(Vec(mshrs, Vec(targets, UInt(params.lsq.generationWidth.W))))
  val mTWord = Reg(Vec(mshrs, Vec(targets, UInt(wordWidth.W))))
  val mTPa = Reg(Vec(mshrs, Vec(targets, UInt(params.pAddrWidth.W))))
  val mPtw = RegInit(VecInit(Seq.fill(mshrs)(false.B)))
  val mPtwWord = Reg(Vec(mshrs, UInt(wordWidth.W)))

  val loadPending = RegInit(false.B)
  val pendingLoad = Reg(new DCacheLoadReq(params.lsq.lqIdxWidth,
    params.lsq.generationWidth, params.vAddrWidth))
  val loadHoldV = RegInit(false.B)
  val loadHold = Reg(new DCacheLoadResp(params.lsq.lqIdxWidth,
    params.lsq.generationWidth, params.pAddrWidth, params.dataWidth))
  val ptwBusy = RegInit(false.B)
  val ptwHoldV = RegInit(false.B)
  val ptwHold = Reg(new PtwMemResp)
  val storeHoldV = RegInit(false.B)
  val cleanHoldV = RegInit(false.B)
  val ucLoadHoldV = RegInit(false.B)
  val ucLoadHold = Reg(new UncachedLoadResp(params.lsq.lqIdxWidth,
    params.lsq.generationWidth, params.dataWidth))
  val ucStoreHoldV = RegInit(false.B)
  val ucStoreHold = Reg(new UncachedStoreResp(params.lsq.sqIdxWidth))

  private val uFree :: uRequest :: uWait :: Nil = Enum(3)
  val ucState = RegInit(uFree)
  val ucKind = Reg(UInt(2.W)) // 0: PTW, 1: load, 2: store
  val ucAddr = Reg(UInt(params.pAddrWidth.W))
  val ucSize = Reg(UInt(params.dataBusParams.sizeBits.W))
  val ucData = Reg(UInt(params.dataWidth.W))
  val ucMask = Reg(UInt(beatBytes.W))
  val ucIdx = Reg(UInt(math.max(params.lsq.lqIdxWidth, params.lsq.sqIdxWidth).W))
  val ucGen = Reg(UInt(params.lsq.generationWidth.W))

  val wbActive = RegInit(false.B)
  val wbWait = RegInit(false.B)
  val wbClean = RegInit(false.B)
  val wbOwner = Reg(UInt(mWidth.W))
  val wbAddr = Reg(UInt(params.pAddrWidth.W))
  val wbLine = Reg(Vec(lineWords, UInt(params.dataWidth.W)))
  val wbBeat = RegInit(0.U(beatWidth.W))
  val cleanActive = RegInit(false.B)
  val cleanCursor = RegInit(0.U(log2Ceil(sets * ways + 1).W))

  // One uncancelable SRAM read owns its identity until the line response transfers.
  // Cache contents and these local transaction registers are recovery-exempt.
  private val rLoad :: rPtw :: rVictim :: rClean :: Nil = Enum(4)
  val readPending = RegInit(false.B)
  val readKind = Reg(UInt(2.W))
  val readOwner = Reg(UInt(mWidth.W))
  val readLqIdx = Reg(UInt(params.lsq.lqIdxWidth.W))
  val readLqGen = Reg(UInt(params.lsq.generationWidth.W))
  val readPaddr = Reg(UInt(params.pAddrWidth.W))
  val readWord = Reg(UInt(wordWidth.W))
  val readWbAddr = Reg(UInt(params.pAddrWidth.W))
  private val arrayRead = lines.io.readReq
  private val arrayAnswer = lines.io.readResp
  private val arrayWrite = lines.io.writeReq
  private val readSlotFree = !readPending && arrayRead.ready

  private def requestRead(kind: UInt): Unit = { readKind := kind }

  private val lreq = io.dCacheLoadReqIn
  private val ltr = io.dCacheTranslationIn
  private val lresp = io.dCacheLoadRespOut
  private val sreq = io.storeDrainReqIn
  private val sresp = io.storeDrainRespOut
  private val preq = io.ptwMemReqIn
  private val presp = io.ptwMemRespOut
  private val creq = io.dCacheCleanReqIn
  private val cresp = io.dCacheCleanRespOut
  private val ulreq = io.uncachedLoadReqIn
  private val ulresp = io.uncachedLoadRespOut
  private val usreq = io.uncachedStoreReqIn
  private val usresp = io.uncachedStoreRespOut
  private val breq = io.dataMemReqOut
  private val bresp = io.dataMemRespIn

  private val loadFree = !loadHoldV || lresp.fire
  private val ptwFree = !ptwHoldV || presp.fire
  private val storeFree = !storeHoldV || sresp.fire
  private val ucLoadFree = !ucLoadHoldV || ulresp.fire
  private val ucStoreFree = !ucStoreHoldV || usresp.fire
  private val cleanFree = !cleanHoldV || cresp.fire
  private val fillLast = bresp.valid && bresp.bits.source < mshrs.U && bresp.bits.last
  private val arrayOpen = !cleanActive && !fillLast

  private val freeVec = VecInit((0 until mshrs).map(i => mState(i) === sFree))
  private val hasFree = freeVec.asUInt.orR
  private val freeIdx = PriorityEncoder(freeVec)
  private def hitWays(set: UInt, tag: UInt): Vec[Bool] =
    VecInit((0 until ways).map(i => valid(set)(i) && tags(set)(i) === tag))
  private def matchingMshr(pa: UInt): Vec[Bool] =
    VecInit((0 until mshrs).map(i => mState(i) =/= sFree && mState(i) =/= sComplete &&
      mAddr(i) === lineOf(pa)))
  private def writebackHazard(pa: UInt): Bool = VecInit((0 until mshrs).map(i =>
    (mState(i) === sReadVictim || mState(i) === sWriteback) &&
      lineAddr(mOldTag(i), mSet(i)) === lineOf(pa))).asUInt.orR
  private def availableWays(set: UInt): Vec[Bool] =
    VecInit((0 until ways).map(w => !(0 until mshrs).map(i =>
      mState(i) =/= sFree && mSet(i) === set && mWay(i) === w.U).reduce(_ || _)))
  private def canVictim(set: UInt): Bool = availableWays(set).asUInt.orR
  private def victimOf(set: UInt): UInt = {
    val available = availableWays(set)
    val invalid = VecInit((0 until ways).map(i => available(i) && !valid(set)(i)))
    Mux(invalid.asUInt.orR, PriorityEncoder(invalid),
      Mux(available(replace(set)), replace(set), PriorityEncoder(available)))
  }
  private def nextWay(w: UInt): UInt = Mux(w === (ways - 1).U, 0.U, w + 1.U)
  private def fillWord(m: UInt, word: UInt): UInt = Mux1H((0 until lineWords).map(i =>
    (word === i.U) -> mFill(m)(i)))

  private def allocate(m: UInt, pa: UInt, set: UInt, way: UInt): Unit = {
    val oldDirty = valid(set)(way) && dirty(set)(way)
    mState(m) := Mux(oldDirty, sReadVictim, sFillReq)
    mAddr(m) := lineOf(pa)
    mSet(m) := set
    mWay(m) := way
    mOldTag(m) := tags(set)(way)
    when(oldDirty) {
      requestRead(rVictim)
      readOwner := m
    }
    mBeat(m) := 0.U
    mDenied(m) := false.B
    for (i <- 0 until lineWords) {
      mMask(m)(i) := 0.U
      mOverlay(m)(i) := 0.U
    }
    for (i <- 0 until targets) mTValid(m)(i) := false.B
    mPtw(m) := false.B
    valid(set)(way) := false.B
    dirty(set)(way) := false.B
  }

  private val lSet = setOf(pendingLoad.vaddr)
  private val lTag = tagOf(ltr.bits.paddr)
  private val lHits = hitWays(lSet, lTag)
  private val lHit = lHits.asUInt.orR
  private val lWay = PriorityEncoder(lHits)
  private val lMatch = matchingMshr(ltr.bits.paddr)
  private val lMatchAny = lMatch.asUInt.orR
  private val lMatchIdx = PriorityEncoder(lMatch)
  private val lTargetFree = VecInit((0 until targets).map(i => !mTValid(lMatchIdx)(i)))
  private val doneTargets = VecInit((0 until mshrs).map(i =>
    mState(i) === sComplete && mTValid(i).asUInt.orR))
  private val doneTargetAny = doneTargets.asUInt.orR
  private val doneM = PriorityEncoder(doneTargets)
  private val doneT = PriorityEncoder(mTValid(doneM))
  private val lVictim = victimOf(lSet)
  private val lNeedsRead = ltr.bits.status === TranslationStatus.Hit && ltr.bits.cacheable &&
    !lMatchAny && (lHit || (hasFree && canVictim(lSet) &&
      valid(lSet)(lVictim) && dirty(lSet)(lVictim)))
  private val loadLookupBase = loadPending && arrayOpen && !preq.valid && !sreq.valid &&
    loadFree && !doneTargetAny && !(readPending && readKind === rLoad) &&
    (ltr.bits.status =/= TranslationStatus.Hit || !writebackHazard(ltr.bits.paddr))
  private val loadCanLookup = loadLookupBase && (!lNeedsRead || readSlotFree)

  @LocalSpec(funcDCacheViptLookup)
  val dCacheViptLookup: Unit = {
    lreq.ready := !loadPending || ltr.fire
    ltr.ready := loadCanLookup
    lresp.valid := loadHoldV
    lresp.bits := loadHold
    when(lresp.fire) { loadHoldV := false.B }
    when(lreq.fire) { loadPending := true.B; pendingLoad := lreq.bits }
      .elsewhen(ltr.fire) { loadPending := false.B }
    when(ltr.fire) {
      val expected = Cat(0.U(1.W), pendingLoad.lqIdx.pad(params.lsq.indexWidth),
        pendingLoad.lqGen)
      assert(ltr.bits.reqId === expected,
        "DataCache load translation does not match its oldest pending load request")
      assert(PopCount(lHits) <= 1.U, "DataCache has duplicate physical tags in one set")
      loadHold.lqIdx := pendingLoad.lqIdx
      loadHold.lqGen := pendingLoad.lqGen
      loadHold.paddr := ltr.bits.paddr
      loadHold.data := 0.U
      loadHold.status := MuxLookup(ltr.bits.status, LoadStatus.AccessFault)(Seq(
        TranslationStatus.Miss -> LoadStatus.TlbMiss,
        TranslationStatus.PageFault -> LoadStatus.PageFault,
        TranslationStatus.AccessFault -> LoadStatus.AccessFault))
      when(ltr.bits.status =/= TranslationStatus.Hit) {
        loadHoldV := true.B
      }.elsewhen(!ltr.bits.cacheable) {
        loadHoldV := true.B; loadHold.status := LoadStatus.Uncacheable
      }.elsewhen(lHit && !lMatchAny) {
        requestRead(rLoad)
        readLqIdx := pendingLoad.lqIdx
        readLqGen := pendingLoad.lqGen
        readPaddr := ltr.bits.paddr
        readWord := wordOf(ltr.bits.paddr)
        replace(lSet) := nextWay(lWay)
      }.elsewhen(lMatchAny) {
        when(lTargetFree.asUInt.orR) {
          val t = PriorityEncoder(lTargetFree)
          mTValid(lMatchIdx)(t) := true.B
          mTIdx(lMatchIdx)(t) := pendingLoad.lqIdx
          mTGen(lMatchIdx)(t) := pendingLoad.lqGen
          mTWord(lMatchIdx)(t) := wordOf(ltr.bits.paddr)
          mTPa(lMatchIdx)(t) := ltr.bits.paddr
        }.otherwise {
          loadHoldV := true.B; loadHold.status := LoadStatus.Replay
        }
      }.elsewhen(hasFree && canVictim(lSet)) {
        val way = victimOf(lSet)
        allocate(freeIdx, ltr.bits.paddr, lSet, way)
        mTValid(freeIdx)(0) := true.B
        mTIdx(freeIdx)(0) := pendingLoad.lqIdx
        mTGen(freeIdx)(0) := pendingLoad.lqGen
        mTWord(freeIdx)(0) := wordOf(ltr.bits.paddr)
        mTPa(freeIdx)(0) := ltr.bits.paddr
      }.otherwise {
        loadHoldV := true.B; loadHold.status := LoadStatus.Replay
      }
    }.elsewhen(doneTargetAny && loadFree) {
      loadHoldV := true.B
      loadHold.lqIdx := mTIdx(doneM)(doneT)
      loadHold.lqGen := mTGen(doneM)(doneT)
      loadHold.paddr := mTPa(doneM)(doneT)
      val filled = fillWord(doneM, mTWord(doneM)(doneT))
      loadHold.data := Mux(sreq.fire && sameWord(mTPa(doneM)(doneT), sreq.bits.paddr),
        mergeWord(filled, sreq.bits.data, sreq.bits.mask), filled)
      loadHold.status := Mux(mDenied(doneM), LoadStatus.AccessFault, LoadStatus.Data)
      mTValid(doneM)(doneT) := false.B
    }
  }

  private val fillReady = VecInit((0 until mshrs).map(i => mState(i) === sFillReq))
  private val fillReadyAny = fillReady.asUInt.orR
  private val fillIdx = PriorityEncoder(fillReady)
  private val busWrite = wbActive
  private val busFill = !busWrite && fillReadyAny
  private val busUncached = !busWrite && !busFill && ucState === uRequest
  private val dIsFill = bresp.bits.source < mshrs.U
  private val dFillIdx = bresp.bits.source(mWidth - 1, 0)
  private val dIsWb = bresp.bits.source === wbSource
  private val dIsUc = bresp.bits.source === ucSource
  private val ucAnswerFree = Mux(ucKind === 0.U, ptwFree,
    Mux(ucKind === 1.U, ucLoadFree, ucStoreFree))

  @LocalSpec(funcDCacheMshr)
  val dCacheMshr: Unit = {
    breq.valid := busWrite || busFill || busUncached
    breq.bits.op := Mux(busWrite, DataMemOp.PutLine,
      Mux(busFill, DataMemOp.GetLine,
        Mux(ucKind === 2.U, DataMemOp.PutUncached, DataMemOp.GetUncached)))
    breq.bits.source := Mux(busWrite, wbSource,
      Mux(busFill, fillIdx, ucSource))
    breq.bits.paddr := Mux(busWrite, wbAddr,
      Mux(busFill, mAddr(fillIdx), ucAddr))
    breq.bits.size := Mux(busWrite || busFill, offBits.U, ucSize)
    breq.bits.data := Mux(busWrite, wbLine(wbBeat(wordWidth - 1, 0)),
      Mux(busUncached, ucData, 0.U))
    breq.bits.mask := Mux(busUncached, ucMask, Fill(beatBytes, 1.U(1.W)))
    breq.bits.last := Mux(busWrite, wbBeat === (lineWords - 1).U, true.B)
    when(breq.fire && busFill) {
      mState(fillIdx) := sFillWait
      mBeat(fillIdx) := 0.U
      mDenied(fillIdx) := false.B
    }
    when(breq.fire && busUncached) { ucState := uWait }

    bresp.ready := Mux(dIsFill, mState(dFillIdx) === sFillWait &&
      (!bresp.bits.last || bresp.bits.denied || mDenied(dFillIdx) || arrayWrite.ready),
      Mux(dIsWb, wbWait, dIsUc && ucState === uWait && ucAnswerFree))
    when(bresp.valid) {
      assert(dIsFill || dIsWb || dIsUc, "DataCache bus response source is outside its range")
      when(dIsFill) {
        assert(mState(dFillIdx) === sFillWait, "DataCache fill response without a pending fill")
        assert(bresp.bits.last === (mBeat(dFillIdx) === (lineWords - 1).U),
          "DataCache fill last does not match the configured line size")
      }
    }
    when(bresp.fire && dIsFill) {
      val m = dFillIdx
      val beat = mBeat(m)
      mFill(m)(beat(wordWidth - 1, 0)) := bresp.bits.data
      mBeat(m) := beat + 1.U
      mDenied(m) := mDenied(m) || bresp.bits.denied
      when(bresp.bits.last) {
        val denied = mDenied(m) || bresp.bits.denied
        assert(!denied || !mMask(m).map(_.orR).reduce(_ || _),
          "A cacheable committed store fill was denied despite the PMA contract")
        val raw = VecInit((0 until lineWords).map(i =>
          Mux(beat === i.U, bresp.bits.data, mFill(m)(i))))
        val merged = VecInit((0 until lineWords).map(i =>
          mergeWord(raw(i), mOverlay(m)(i), mMask(m)(i))))
        for (i <- 0 until lineWords) mFill(m)(i) := merged(i)
        when(!denied) {
          val wayMask = UIntToOH(mWay(m), ways).asBools
          tags.write(mSet(m), VecInit(Seq.fill(ways)(tagOf(mAddr(m)))), wayMask)
          valid(mSet(m))(mWay(m)) := true.B
          dirty(mSet(m))(mWay(m)) := mMask(m).map(_.orR).reduce(_ || _)
          replace(mSet(m)) := nextWay(mWay(m))
        }
        mState(m) := sComplete
      }
    }
    for (i <- 0 until mshrs) {
      when(mState(i) === sComplete && !mTValid(i).asUInt.orR && !mPtw(i)) {
        mState(i) := sFree
      }
    }
  }

  private val sSet = setOf(sreq.bits.paddr)
  private val sHits = hitWays(sSet, tagOf(sreq.bits.paddr))
  private val sHit = sHits.asUInt.orR
  private val sWay = PriorityEncoder(sHits)
  private val sMatch = matchingMshr(sreq.bits.paddr)
  private val sMatchAny = sMatch.asUInt.orR
  private val sMatchIdx = PriorityEncoder(sMatch)
  private val sWord = wordOf(sreq.bits.paddr)
  private val sVictim = victimOf(sSet)
  private val sNeedsRead = !sMatchAny && !sHit && hasFree && canVictim(sSet) &&
    valid(sSet)(sVictim) && dirty(sSet)(sVictim)
  private val sNeedsWrite = sHit && !sMatchAny
  private val sWaitsForLoad = readPending && readKind === rLoad &&
    sameWord(readPaddr, sreq.bits.paddr)
  private val storeLookupBase = arrayOpen && !preq.valid && storeFree &&
    !sWaitsForLoad && !writebackHazard(sreq.bits.paddr) &&
    (sHit || sMatchAny || (hasFree && canVictim(sSet)))

  @LocalSpec(funcDCacheStoreWrite)
  val dCacheStoreWrite: Unit = {
    sreq.ready := storeLookupBase && (!sNeedsRead || readSlotFree) &&
      (!sNeedsWrite || arrayWrite.ready)
    sresp.valid := storeHoldV
    sresp.bits := (new StoreDrainResp).asTypeOf(sresp.bits)
    when(sresp.fire) { storeHoldV := false.B }
    when(sreq.fire) {
      assert(PopCount(sHits) <= 1.U, "DataCache store hits more than one way")
      storeHoldV := true.B
      when(sMatchAny) {
        val oldMask = mMask(sMatchIdx)(sWord)
        mMask(sMatchIdx)(sWord) := oldMask | sreq.bits.mask
        mOverlay(sMatchIdx)(sWord) := mergeWord(mOverlay(sMatchIdx)(sWord),
          sreq.bits.data, sreq.bits.mask)
      }.elsewhen(sHit) {
        dirty(sSet)(sWay) := true.B
        replace(sSet) := nextWay(sWay)
      }.otherwise {
        val way = victimOf(sSet)
        allocate(freeIdx, sreq.bits.paddr, sSet, way)
        mMask(freeIdx)(sWord) := sreq.bits.mask
        mOverlay(freeIdx)(sWord) := sreq.bits.data
      }
      // Completed fills still own bytes for their unanswered targets. Repair that
      // response copy together with SRAM, including targets blocked behind another answer.
      for (m <- 0 until mshrs) {
        when(mState(m) === sComplete && mAddr(m) === lineOf(sreq.bits.paddr)) {
          mFill(m)(sWord) := mergeWord(mFill(m)(sWord), sreq.bits.data, sreq.bits.mask)
        }
      }
      when(loadHoldV && !lresp.fire && loadHold.status === LoadStatus.Data &&
          sameWord(loadHold.paddr, sreq.bits.paddr)) {
        loadHold.data := mergeWord(loadHold.data, sreq.bits.data, sreq.bits.mask)
      }
    }
  }

  private val wbCandidates = VecInit((0 until mshrs).map(i => mState(i) === sWriteback))
  private val wbCandidateAny = wbCandidates.asUInt.orR
  private val wbCandidate = PriorityEncoder(wbCandidates)

  @LocalSpec(funcDCacheWriteback)
  val dCacheWriteback: Unit = {
    when(!wbActive && !wbWait && wbCandidateAny) {
      wbActive := true.B
      wbClean := false.B
      wbOwner := wbCandidate
      wbAddr := lineAddr(mOldTag(wbCandidate), mSet(wbCandidate))
      wbLine := mOldLine(wbCandidate)
      wbBeat := 0.U
    }
    when(breq.fire && busWrite) {
      wbBeat := wbBeat + 1.U
      when(wbBeat === (lineWords - 1).U) {
        wbActive := false.B
        wbWait := true.B
      }
    }
    when(bresp.fire && dIsWb) {
      assert(wbWait && bresp.bits.last, "DataCache writeback needs one final acknowledgement")
      assert(!bresp.bits.denied, "A cacheable dirty writeback was denied despite the PMA contract")
      wbWait := false.B
      when(wbClean) {
        val set = (cleanCursor / ways.U)(setWidth - 1, 0)
        val way = (cleanCursor % ways.U)(wayWidth - 1, 0)
        dirty(set)(way) := false.B
        cleanCursor := cleanCursor + 1.U
      }.otherwise {
        mState(wbOwner) := sFillReq
      }
    }
  }

  private val (pmaHit, pmaAttr) = PmaLookup(params.pma, preq.bits.paddr)
  private val pReadable = pmaHit && pmaAttr.readable
  private val pSet = setOf(preq.bits.paddr)
  private val pHits = hitWays(pSet, tagOf(preq.bits.paddr))
  private val pHit = pHits.asUInt.orR
  private val pWay = PriorityEncoder(pHits)
  private val pMatch = matchingMshr(preq.bits.paddr)
  private val pMatchAny = pMatch.asUInt.orR
  private val pMatchIdx = PriorityEncoder(pMatch)
  private val donePtw = VecInit((0 until mshrs).map(i =>
    mState(i) === sComplete && mPtw(i)))
  private val donePtwAny = donePtw.asUInt.orR
  private val donePtwIdx = PriorityEncoder(donePtw)
  private val pVictim = victimOf(pSet)
  private val pNeedsRead = pReadable && pmaAttr.cacheable && !pMatchAny &&
    (pHit || (hasFree && canVictim(pSet) && valid(pSet)(pVictim) && dirty(pSet)(pVictim)))
  private val ptwLookupBase = !ptwBusy && ptwFree && arrayOpen &&
    !writebackHazard(preq.bits.paddr) &&
    Mux(!pReadable, true.B,
      Mux(!pmaAttr.cacheable, ucState === uFree,
        Mux(pMatchAny, !mPtw(pMatchIdx), pHit || (hasFree && canVictim(pSet)))))

  @LocalSpec(funcDCachePhysicalRead)
  val dCachePhysicalRead: Unit = {
    preq.ready := ptwLookupBase && (!pNeedsRead || readSlotFree)
    presp.valid := ptwHoldV
    presp.bits := ptwHold
    when(presp.fire) { ptwHoldV := false.B; ptwBusy := false.B }
    when(preq.fire) {
      ptwBusy := true.B
      assert(PopCount(pHits) <= 1.U, "DataCache PTE read hits more than one way")
      when(!pReadable) {
        ptwHoldV := true.B
        ptwHold.pte := 0.U.asTypeOf(new Sv32Pte)
        ptwHold.accessFault := true.B
      }.elsewhen(!pmaAttr.cacheable) {
        ucState := uRequest
        ucKind := 0.U
        ucAddr := preq.bits.paddr
        ucSize := log2Ceil(beatBytes).U
        ucData := 0.U
        ucMask := Fill(beatBytes, 1.U(1.W))
      }.elsewhen(pMatchAny) {
        mPtw(pMatchIdx) := true.B
        mPtwWord(pMatchIdx) := wordOf(preq.bits.paddr)
      }.elsewhen(pHit) {
        requestRead(rPtw)
        readWord := wordOf(preq.bits.paddr)
        replace(pSet) := nextWay(pWay)
      }.otherwise {
        val way = victimOf(pSet)
        allocate(freeIdx, preq.bits.paddr, pSet, way)
        mPtw(freeIdx) := true.B
        mPtwWord(freeIdx) := wordOf(preq.bits.paddr)
      }
    }
    when(donePtwAny && ptwFree && !preq.fire) {
      ptwHoldV := true.B
      ptwHold.pte := fillWord(donePtwIdx, mPtwWord(donePtwIdx)).asTypeOf(new Sv32Pte)
      ptwHold.accessFault := mDenied(donePtwIdx)
      mPtw(donePtwIdx) := false.B
    }
  }

  @LocalSpec(funcDCacheUncached)
  val dCacheUncached: Unit = {
    ulreq.ready := !cleanActive && !preq.valid && ucState === uFree && ucLoadFree
    usreq.ready := !cleanActive && !preq.valid && !ulreq.valid &&
      ucState === uFree && ucStoreFree
    ulresp.valid := ucLoadHoldV
    ulresp.bits := ucLoadHold
    usresp.valid := ucStoreHoldV
    usresp.bits := ucStoreHold
    when(ulresp.fire) { ucLoadHoldV := false.B }
    when(usresp.fire) { ucStoreHoldV := false.B }
    when(ulreq.fire) {
      ucState := uRequest
      ucKind := 1.U
      ucAddr := ulreq.bits.paddr
      ucSize := ulreq.bits.size
      ucData := 0.U
      ucMask := Fill(beatBytes, 1.U(1.W))
      ucIdx := ulreq.bits.lqIdx
      ucGen := ulreq.bits.lqGen
    }.elsewhen(usreq.fire) {
      ucState := uRequest
      ucKind := 2.U
      ucAddr := usreq.bits.paddr
      ucSize := Mux(PopCount(usreq.bits.mask) === 1.U, 0.U,
        Mux(usreq.bits.mask === 3.U || usreq.bits.mask === 12.U, 1.U,
          log2Ceil(beatBytes).U))
      ucData := usreq.bits.data
      ucMask := usreq.bits.mask
      ucIdx := usreq.bits.sqIdx
      ucGen := 0.U
    }
    when(bresp.fire && dIsUc) {
      assert(ucState === uWait && bresp.bits.last,
        "DataCache uncached response must complete the single-beat access")
      ucState := uFree
      switch(ucKind) {
        is(0.U) {
          ptwHoldV := true.B
          ptwHold.pte := bresp.bits.data.asTypeOf(new Sv32Pte)
          ptwHold.accessFault := bresp.bits.denied
        }
        is(1.U) {
          ucLoadHoldV := true.B
          ucLoadHold.lqIdx := ucIdx
          ucLoadHold.lqGen := ucGen
          ucLoadHold.data := bresp.bits.data
          ucLoadHold.accessFault := bresp.bits.denied
        }
        is(2.U) {
          ucStoreHoldV := true.B
          ucStoreHold.sqIdx := ucIdx
          ucStoreHold.accessFault := bresp.bits.denied
        }
      }
    }
  }

  @LocalSpec(funcDCacheCleanAll)
  val dCacheCleanAll: Unit = {
    creq.ready := !cleanActive && !wbActive && !wbWait &&
      mState.map(_ === sFree).reduce(_ && _) && ucState === uFree &&
      !loadPending && !loadHoldV && !ptwBusy && !storeHoldV && cleanFree &&
      !readPending &&
      !preq.valid && !sreq.valid
    cresp.valid := cleanHoldV
    cresp.bits.op := MaintOp.DCacheCleanAll
    when(cresp.fire) { cleanHoldV := false.B }
    when(creq.valid) {
      assert(creq.bits.op === MaintOp.DCacheCleanAll,
        "DataCache clean request has the wrong maintenance operation")
    }
    when(creq.fire) {
      cleanActive := true.B
      cleanCursor := 0.U
    }
    when(cleanActive && !wbActive && !wbWait && !readPending) {
      when(cleanCursor === (sets * ways).U) {
        cleanActive := false.B
        cleanHoldV := true.B
      }.otherwise {
        val set = (cleanCursor / ways.U)(setWidth - 1, 0)
        val way = (cleanCursor % ways.U)(wayWidth - 1, 0)
        when(valid(set)(way) && dirty(set)(way)) {
          when(readSlotFree) {
            requestRead(rClean)
            readWbAddr := lineAddr(tags(set)(way), set)
          }
        }.otherwise {
          cleanCursor := cleanCursor + 1.U
        }
      }
    }
  }

  @LocalSpec(funcDCacheSramStorage)
  val dCacheSramStorage: Unit = {
    val cleanSet = (cleanCursor / ways.U)(setWidth - 1, 0)
    val cleanWay = (cleanCursor % ways.U)(wayWidth - 1, 0)
    val cleanReadOffer = cleanActive && !wbActive && !wbWait && !readPending &&
      cleanCursor < (sets * ways).U && valid(cleanSet)(cleanWay) && dirty(cleanSet)(cleanWay)
    val ptwReadOffer = preq.valid && ptwLookupBase && pNeedsRead && !readPending
    val storeReadOffer = sreq.valid && storeLookupBase && sNeedsRead && !readPending
    val loadReadOffer = ltr.valid && loadLookupBase && lNeedsRead && !readPending
    arrayRead.valid := cleanReadOffer || ptwReadOffer || storeReadOffer || loadReadOffer
    arrayRead.bits := Mux(cleanReadOffer, rowOf(cleanSet, cleanWay),
      Mux(ptwReadOffer, rowOf(pSet, Mux(pHit, pWay, pVictim)),
        Mux(storeReadOffer, rowOf(sSet, sVictim),
          rowOf(lSet, Mux(lHit, lWay, lVictim)))))

    val fillWriteOffer = bresp.valid && dIsFill && mState(dFillIdx) === sFillWait &&
      bresp.bits.last && !mDenied(dFillIdx) && !bresp.bits.denied
    val storeWriteOffer = sreq.valid && storeLookupBase && sNeedsWrite
    val fillData = VecInit((0 until lineWords).map(i => {
      val raw = Mux(mBeat(dFillIdx) === i.U, bresp.bits.data, mFill(dFillIdx)(i))
      mergeWord(raw, mOverlay(dFillIdx)(i), mMask(dFillIdx)(i))
    }))
    arrayWrite.valid := fillWriteOffer || storeWriteOffer
    arrayWrite.bits.row := Mux(fillWriteOffer, rowOf(mSet(dFillIdx), mWay(dFillIdx)), rowOf(sSet, sWay))
    arrayWrite.bits.data := Mux(fillWriteOffer, fillData, VecInit(Seq.fill(lineWords)(sreq.bits.data)))
    arrayWrite.bits.mask := Mux(fillWriteOffer,
      VecInit(Seq.fill(lineWords)(Fill(beatBytes, 1.U(1.W)))),
      VecInit((0 until lineWords).map(i => Mux(sWord === i.U, sreq.bits.mask, 0.U(beatBytes.W)))))

    arrayAnswer.ready := readPending && MuxLookup(readKind, true.B)(Seq(
      rLoad -> (loadFree && !doneTargetAny),
      rPtw -> (ptwFree && !donePtwAny)))
    when(arrayRead.fire) { readPending := true.B }
    when(arrayAnswer.fire) {
      assert(readPending, "DataCache SRAM answered without an owned read")
      readPending := false.B
      switch(readKind) {
        is(rLoad) {
          loadHoldV := true.B
          loadHold.lqIdx := readLqIdx
          loadHold.lqGen := readLqGen
          loadHold.paddr := readPaddr
          loadHold.status := LoadStatus.Data
          loadHold.data := arrayAnswer.bits(readWord)
        }
        is(rPtw) {
          ptwHoldV := true.B
          ptwHold.pte := arrayAnswer.bits(readWord).asTypeOf(new Sv32Pte)
          ptwHold.accessFault := false.B
        }
        is(rVictim) {
          assert(mState(readOwner) === sReadVictim,
            "DataCache SRAM victim snapshot lost its MSHR owner")
          mOldLine(readOwner) := arrayAnswer.bits
          mState(readOwner) := sWriteback
        }
        is(rClean) {
          wbActive := true.B
          wbClean := true.B
          wbAddr := readWbAddr
          wbLine := arrayAnswer.bits
          wbBeat := 0.U
        }
      }
    }
    assert(!arrayRead.fire || !readPending,
      "DataCache must retain its SRAM owner until the previous response transfers")
    assert(!(sreq.fire && sWaitsForLoad),
      "DataCache store must wait until an overlapping SRAM load reaches its response holder")
    assert(!arrayRead.fire || PopCount(Seq(cleanReadOffer, ptwReadOffer, storeReadOffer, loadReadOffer)) === 1.U,
      "DataCache SRAM read must have one selected operation owner")
    assert(!(fillWriteOffer && storeWriteOffer), "DataCache SRAM write must have one selected source")
  }

  @LocalSpec(funcDCachePortArbitrate)
  val dCachePortArbitrate: Unit = {
    assert(!preq.fire || !sreq.fire && !ltr.fire,
      "DataCache array arbitration must give a PTE read exclusive priority")
    assert(!sreq.fire || !ltr.fire,
      "DataCache array arbitration must give a store drain priority over a load")
  }
}
