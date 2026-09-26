package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.CsrControllerSpecs._

/** One CSR address-map entry: its address, read view, and legalizing write (a no-op for a
  * read-only or WARL-constant CSR). */
case class CsrMapEntry(addr: Int, read: UInt, write: UInt => Unit = _ => ())

/** An extension's CSR-map contribution (ADR-019E E-1): declarative descriptors, built inside
  * the CsrController when it elaborates. */
trait CsrMapContribution {
  def entries(): Seq[CsrMapEntry]
}

/** CsrController (spec: CsrControllerSpecs; ADR-019D E-1..E-7).
  *
  * The sole owner of committed CSR state, the privilege, and debug mode. A CSR IssuedUop is
  * checked and answered with one FuResult (old value, or an illegal-instruction exception); a
  * legal write is staged and applied only by the CommitGrant naming its robTag. The only other
  * mutation path is CSRTrapWrite.fire. InterruptCtrl, CSRTrapRead, and TranslationContext are
  * combinational views of the state.
  *
  * Recovery stance: holds one execution context (CSR uops are serialized: renamed only into
  * an empty ROB, never younger than a live branch, and the CommitUnit takes no interrupt or
  * debug request while a serialize head is presented, ADR-019D E-5), so a held result or a
  * staged write is never killed; it has no RecoveryEvent input.
  *
  * The shared CsrAccess.readFromCsr library path is not used: it infers write intent from the
  * runtime operand value and writes at access time, which ADR-019D E-2/E-4 forbid.
  */
@LocalSpec(contCsrController)
class CsrController(val params: BackendParams, contributions: Seq[CsrMapContribution] = Nil) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfCsrReqIn)
    val csrReqIn = Flipped(Decoupled(new IssuedUop(params)))

    @LocalSpec(intfCsrResultOut)
    val csrResultOut = Decoupled(new FuResult(params))

    @LocalSpec(intfCsrTrapReadOut)
    val csrTrapReadOut = Output(new CsrTrapRead(params))

    @LocalSpec(intfCsrTrapWriteIn)
    val csrTrapWriteIn = Flipped(Decoupled(new CsrTrapWrite(params)))

    @LocalSpec(intfCommitGrantIn)
    val commitGrantIn = Input(new CommitGrant(params))

    @LocalSpec(intfInterruptIn)
    val interruptIn = Input(new Interrupt)

    @LocalSpec(intfInterruptCtrlOut)
    val interruptCtrlOut = Output(new InterruptCtrl)

    @LocalSpec(intfTranslationContextOut)
    val translationContextOut = Output(new TranslationContext)

    @LocalSpec(intfDecodePrivViewOut)
    val decodePrivViewOut = Output(new DecodePrivView)
  })

  require(xLen == 32, "the v0 CSR map is RV32")

  // ---- v0 WARL masks and constants ---------------------------------------------------------------

  private val MstatusMask = 0x007e19aaL // SIE MIE SPIE MPIE SPP MPP MPRV SUM MXR TVM TW TSR
  private val SstatusMask = 0x000c0122L // SIE SPIE SPP SUM MXR
  private val MedelegMask = 0x0000b3ffL // causes 0-9, 12, 13, 15
  private val MidelegMask = 0x00000222L // SSI STI SEI
  private val MieMask     = 0x00000aaaL
  private val MipSoftMask = 0x00000222L // SEIP STIP SSIP software bits
  private val MisaValue   = 0x40141100L // RV32 I M S U
  private val DcsrSwMask  = 0x0000b007L // ebreakm/s/u, step, prv
  private val DcsrTwMask  = 0x0000b1c7L // plus cause (debug entry)
  private val DcsrVersion = 4L << 28
  private val IllegalInstruction = 2

  // ---- Committed state (ADR-019D E-7: the only owner) ----------------------------------------------

  val priv      = RegInit(Priv.M)
  val debugMode = RegInit(false.B)
  val mstatus   = RegInit(0.U(32.W))
  val medeleg   = RegInit(0.U(32.W))
  val mideleg   = RegInit(0.U(32.W))
  val mie       = RegInit(0.U(32.W))
  val mipSoft   = RegInit(0.U(32.W))
  val mtvec     = RegInit(0.U(32.W))
  val mscratch  = RegInit(0.U(32.W))
  val mepc      = RegInit(0.U(32.W))
  val mcause    = RegInit(0.U(32.W))
  val mtval     = RegInit(0.U(32.W))
  val stvec     = RegInit(0.U(32.W))
  val sscratch  = RegInit(0.U(32.W))
  val sepc      = RegInit(0.U(32.W))
  val scause    = RegInit(0.U(32.W))
  val stval     = RegInit(0.U(32.W))
  val satp      = RegInit(0.U(32.W))
  val dcsr      = RegInit((DcsrVersion | 3L).U(32.W))
  val dpc       = RegInit(0.U(32.W))
  val dscratch0 = RegInit(0.U(32.W))

  private def lit(v: Long): UInt = v.U(32.W)
  private def legalMstatus(v: UInt, old: UInt): UInt = {
    val m   = v & lit(MstatusMask)
    val mpp = Mux(v(12, 11) === 2.U, old(12, 11), v(12, 11)) // MPP is WARL {U, S, M}
    Cat(m(31, 13), mpp, m(10, 0))
  }
  private def legalTvec(v: UInt): UInt = Cat(v(31, 2), 0.U(1.W), v(0)) // MODE 0 or 1
  private def legalEpc(v: UInt): UInt  = Cat(v(31, 2), 0.U(2.W))

  /** The raw lines ORed with the software-writable supervisor bits (funcInterruptCtrlPublish). */
  private val raw = io.interruptIn
  private def bitAt(b: Int, c: Bool): UInt = Mux(c, lit(1L << b), 0.U(32.W))
  val mip: UInt = Seq(11 -> raw.meip, 7 -> raw.mtip, 3 -> raw.msip, 9 -> raw.seip, 5 -> raw.stip, 1 -> raw.ssip)
    .map { case (b, c) => bitAt(b, c) }.reduce(_ | _) | mipSoft

  // ---- funcSupervisorCsrs + funcCsrMapContribution: the one address map --------------------------

  @LocalSpec(funcSupervisorCsrs)
  val supervisorCsrs: Seq[CsrMapEntry] = Seq(
    CsrMapEntry(0x100, mstatus & lit(SstatusMask), v => mstatus := (mstatus & ~lit(SstatusMask)) | (v & lit(SstatusMask))),
    CsrMapEntry(0x104, mie & mideleg, v => mie := (mie & ~mideleg) | (v & mideleg)),
    CsrMapEntry(0x105, stvec, v => stvec := legalTvec(v)),
    CsrMapEntry(0x106, 0.U(32.W)), // scounteren: no counters in v0
    CsrMapEntry(0x140, sscratch, v => sscratch := v),
    CsrMapEntry(0x141, sepc, v => sepc := legalEpc(v)),
    CsrMapEntry(0x142, scause, v => scause := v),
    CsrMapEntry(0x143, stval, v => stval := v),
    CsrMapEntry(0x144, mip & mideleg, v => {
      val ssip = mideleg & lit(2)
      mipSoft := (mipSoft & ~ssip) | (v & ssip)
    }),
    CsrMapEntry(0x180, satp, v => satp := v)
  )

  private val machineCsrs: Seq[CsrMapEntry] = Seq(
    CsrMapEntry(0x300, mstatus, v => mstatus := legalMstatus(v, mstatus)),
    CsrMapEntry(0x301, lit(MisaValue)), // WARL, writes ignored
    CsrMapEntry(0x302, medeleg, v => medeleg := v & lit(MedelegMask)),
    CsrMapEntry(0x303, mideleg, v => mideleg := v & lit(MidelegMask)),
    CsrMapEntry(0x304, mie, v => mie := v & lit(MieMask)),
    CsrMapEntry(0x305, mtvec, v => mtvec := legalTvec(v)),
    CsrMapEntry(0x306, 0.U(32.W)), // mcounteren: no counters in v0
    CsrMapEntry(0x310, 0.U(32.W)), // mstatush: all fields zero
    CsrMapEntry(0x340, mscratch, v => mscratch := v),
    CsrMapEntry(0x341, mepc, v => mepc := legalEpc(v)),
    CsrMapEntry(0x342, mcause, v => mcause := v),
    CsrMapEntry(0x343, mtval, v => mtval := v),
    CsrMapEntry(0x344, mip, v => mipSoft := v & lit(MipSoftMask)),
    CsrMapEntry(0xf11, 0.U(32.W)), CsrMapEntry(0xf12, 0.U(32.W)), CsrMapEntry(0xf13, 0.U(32.W)), // mvendorid marchid mimpid
    CsrMapEntry(0xf14, lit(params.hartId)), CsrMapEntry(0xf15, 0.U(32.W))                   // mhartid mconfigptr
  )

  private val debugCsrs: Seq[CsrMapEntry] = Seq(
    CsrMapEntry(0x7b0, dcsr, v => dcsr := (v & lit(DcsrSwMask)) | (dcsr & ~lit(DcsrSwMask))),
    CsrMapEntry(0x7b1, dpc, v => dpc := legalEpc(v)),
    CsrMapEntry(0x7b2, dscratch0, v => dscratch0 := v)
  )

  /** Descriptors contributed by enabled extensions (ADR-019E E-1), built here so their storage
    * belongs to this vertex; v0 enables no CSR-contributing extension. */
  private val contributed: Seq[CsrMapEntry] = contributions.flatMap(_.entries())

  /** The one map: base machine + supervisor + debug descriptors plus the contributed ones. A
    * descriptor supplies address, read source, and legalizing write target only; this vertex
    * alone classifies intent, stages, and applies at the matching CommitGrant. */
  @LocalSpec(funcCsrMapContribution)
  val csrMap: Seq[CsrMapEntry] = {
    val m = machineCsrs ++ supervisorCsrs ++ debugCsrs ++ contributed
    require(m.map(_.addr).distinct.size == m.size, "CsrMapContribution: duplicate CSR address")
    m
  }

  // ---- The request ----------------------------------------------------------------------------------

  private val in      = io.csrReqIn
  private val insn    = in.bits.insn
  private val addr    = insn(31, 20)
  private val hits    = csrMap.map(e => addr === e.addr.U)
  private val oldVal  = Mux1H(hits, csrMap.map(_.read))
  private val isWrite = in.bits.sysOp === SysOp.CsrWrite

  // ---- funcCsrAccessCheck -----------------------------------------------------------------------------

  @LocalSpec(funcCsrAccessCheck)
  val csrAccessCheck: Bool = {
    val exists   = hits.reduce(_ || _)
    val effPriv  = Mux(debugMode, Priv.M, priv)
    val privOk   = addr(9, 8) <= effPriv
    val debugOk  = !(addr(11, 4) === 0x7b.U) || debugMode
    val roOk     = !(isWrite && addr(11, 10) === 3.U)
    val tvmOk    = !(addr === 0x180.U && priv === Priv.S && !debugMode && mstatus(20))
    exists && privOk && debugOk && roOk && tvmOk
  }
  private val legal = csrAccessCheck

  // ---- funcCsrOpSemantics ------------------------------------------------------------------------------

  @LocalSpec(funcCsrOpSemantics)
  val csrOpSemantics: UInt = {
    val op      = in.bits.op
    val operand = Mux(op(2), insn(19, 15).pad(32), in.bits.src1)
    MuxLookup(op(1, 0), operand)(Seq(
      CsrOp.RW.U -> operand,
      CsrOp.RS.U -> (oldVal | operand),
      CsrOp.RC.U -> (oldVal & ~operand)
    ))
  }

  // ---- funcCsrExecuteAtCommit ---------------------------------------------------------------------------

  val resValid     = RegInit(false.B)
  val resBits      = Reg(new FuResult(params))
  val stagedValid  = RegInit(false.B)
  val stagedTag    = Reg(new RobTag(params))
  val stagedAddr   = Reg(UInt(12.W))
  val stagedValue  = Reg(UInt(32.W))

  private val grant   = io.commitGrantIn
  private val grantOk = grant.valid && stagedValid && grant.robTag.asUInt === stagedTag.asUInt
  private val trapW   = io.csrTrapWriteIn
  private val trapFire = trapW.fire

  @LocalSpec(funcCsrExecuteAtCommit)
  val swApply: Bool = {
    // ADR-019C E-2: ready from registered state only (no staged write, result slot free or draining).
    in.ready := !stagedValid && (!resValid || io.csrResultOut.ready)
    io.csrResultOut.valid := resValid
    io.csrResultOut.bits  := resBits
    when(io.csrResultOut.fire) { resValid := false.B }
    when(in.fire) {
      val r = Wire(new FuResult(params))
      r.robTag              := in.bits.robTag
      r.prd                 := in.bits.prd
      r.wen                 := in.bits.hasDest && legal
      r.data                := Mux(legal, oldVal, 0.U)
      r.exception.valid     := !legal
      r.exception.cause     := IllegalInstruction.U
      r.exception.tval      := insn
      r.cfiOutcome          := 0.U.asTypeOf(r.cfiOutcome)
      resValid := true.B
      resBits  := r
      when(legal && isWrite) {
        stagedValid := true.B
        stagedTag   := in.bits.robTag
        stagedAddr  := addr
        stagedValue := csrOpSemantics
      }
    }
    assert(!(grant.valid && stagedValid && !grantOk), "CsrExecuteAtCommit: CommitGrant does not name the staged write")
    assert(!in.valid || in.bits.fuType === FuType.Csr, "CsrExecuteAtCommit: a non-CSR uop reached the CsrController")
    when(grantOk) {
      csrMap.foreach(e => when(stagedAddr === e.addr.U) { e.write(stagedValue) })
      stagedValid := false.B
    }
    grantOk
  }

  // ---- funcCsrTrapWriteApply ------------------------------------------------------------------------------

  @LocalSpec(funcCsrTrapWriteApply)
  val csrTrapWriteApply: Unit = {
    val t = trapW.bits
    trapW.ready := true.B
    when(trapFire) {
      priv := t.privNext
      switch(t.kind) {
        is(TrapWriteKind.TrapEntryM) {
          mepc := legalEpc(t.xepc); mcause := t.xcause; mtval := t.xtval; mstatus := legalMstatus(t.mstatusNext, mstatus)
        }
        is(TrapWriteKind.TrapEntryS) {
          sepc := legalEpc(t.xepc); scause := t.xcause; stval := t.xtval; mstatus := legalMstatus(t.mstatusNext, mstatus)
        }
        is(TrapWriteKind.MRet) { mstatus := legalMstatus(t.mstatusNext, mstatus) }
        is(TrapWriteKind.SRet) { mstatus := legalMstatus(t.mstatusNext, mstatus) }
        is(TrapWriteKind.DebugEntry) {
          dpc := legalEpc(t.dpc); dcsr := (t.dcsrNext & lit(DcsrTwMask)) | lit(DcsrVersion); debugMode := true.B
        }
        is(TrapWriteKind.DRet) { debugMode := false.B }
      }
    }
    assert(!trapFire || t.privNext =/= 2.U, "CsrTrapWriteApply: reserved privilege")
    assert(!trapFire || t.kind <= TrapWriteKind.DebugEntry, "CsrTrapWriteApply: unknown kind")
  }

  // ---- funcInterruptCtrlPublish (and the CSRTrapRead view) ---------------------------------------------------

  @LocalSpec(funcInterruptCtrlPublish)
  val interruptCtrlPublish: Unit = {
    val pend  = mip & mie
    val order = Seq(11, 3, 7, 9, 1, 5) // MEI MSI MTI SEI SSI STI
    val mTake = order.map(i => pend(i) && !mideleg(i) && (priv =/= Priv.M || mstatus(3)))
    val sTake = order.map(i => pend(i) && mideleg(i) && (priv === Priv.U || (priv === Priv.S && mstatus(1))))
    val c = io.interruptCtrlOut
    c.interruptPending := (mTake ++ sTake).reduce(_ || _)
    c.interruptCause   := PriorityMux((mTake ++ sTake) :+ true.B, (order ++ order).map(_.U(5.W)) :+ 0.U(5.W))
    c.debugMode        := debugMode
    c.priv             := priv

    val r = io.csrTrapReadOut
    r.mstatus := mstatus; r.mepc := mepc; r.mcause := mcause; r.mtval := mtval; r.mtvec := mtvec
    r.medeleg := medeleg; r.mideleg := mideleg; r.mie := mie; r.mip := mip
    r.sepc := sepc; r.scause := scause; r.stval := stval; r.stvec := stvec
    r.priv := priv; r.dpc := dpc; r.dcsr := dcsr
  }

  // ---- funcTranslationContextPublish -------------------------------------------------------------------------

  @LocalSpec(funcTranslationContextPublish)
  val translationContextPublish: Unit = {
    val t = io.translationContextOut
    t.satpMode := satp(31)
    t.asid     := satp(30, 22)
    t.rootPpn  := satp(21, 0)
    t.priv     := priv
    t.dataPriv := Mux(mstatus(17), mstatus(12, 11), priv)
    t.sum      := mstatus(18)
    t.mxr      := mstatus(19)
  }

  // ---- funcDecodePrivViewPublish (ADR-019E E-4) ---------------------------------------------------------------

  @LocalSpec(funcDecodePrivViewPublish)
  val decodePrivViewPublish: Unit = {
    val v = io.decodePrivViewOut
    v.priv      := priv
    v.debugMode := debugMode
    v.tvm       := mstatus(20)
    v.tw        := mstatus(21)
    v.tsr       := mstatus(22)
  }

  // ---- Properties (simulation assertions) --------------------------------------------------------------------

  private val stateVec = Cat(priv, debugMode, mstatus, medeleg, mideleg, mie, mipSoft, mtvec, mscratch, mepc, mcause,
    mtval, stvec, sscratch, sepc, scause, stval, satp, dcsr, dpc, dscratch0)
  private val started  = RegNext(true.B, false.B)
  private val prevState = RegNext(stateVec)

  @LocalSpec(propCsrWriteIntent)
  val csrWriteIntent: Unit = {
    val f3     = insn(14, 12)
    val intent = f3(1, 0) === 1.U || insn(19, 15) =/= 0.U
    val csrWrite = in.bits.sysOp === SysOp.CsrWrite
    when(in.fire) {
      assert(csrWrite === intent, "CsrWriteIntent: sysOp disagrees with the encoding's write intent")
      assert(in.bits.sysOp === SysOp.None || csrWrite, "CsrWriteIntent: a CSR uop carries a non-CSR sysOp")
      assert(in.bits.op(2, 0) === f3, "CsrWriteIntent: CsrOp disagrees with the encoding")
    }
  }

  @LocalSpec(propNoSpeculativeCsrWrite)
  val noSpeculativeCsrWrite: Unit =
    assert(!started || stateVec === prevState || RegNext(trapFire, false.B) || RegNext(swApply, false.B),
      "NoSpeculativeCsrWrite: CSR state changed without a matching CommitGrant or CSRTrapWrite")

  @LocalSpec(propCsrSingleOwner)
  val csrSingleOwner: Unit = {
    assert(!(swApply && trapFire), "CsrSingleOwner: a software write and a CSRTrapWrite in the same cycle")
    // A contributed descriptor owns no mutation path (ADR-019E E-1): its value moves only in the
    // cycle after one of the two sanctioned paths fired.
    val pathFired = RegNext(swApply || trapFire, false.B)
    contributed.foreach { e =>
      assert(!started || e.read === RegNext(e.read) || pathFired,
        "CsrMapContribution: a contributed CSR changed outside its CommitGrant")
    }
  }
}
