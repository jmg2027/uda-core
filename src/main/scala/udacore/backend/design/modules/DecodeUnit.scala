package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import udacore.frontend.design.shared.{FetchFault, FetchInst, FetchPacket}
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.DecodeUnitSpecs._
import udacore.common.ControlSignal.{BranchControl, DividerControl, IllegalInstruction, LoadControl, MultiplierControl, StoreControl}
import udacore.common.enums.OperandType

/** DecodeUnit (spec: DecodeUnitSpecs; ADR-019 D-19.1/D-19.3, ADR-019A E-2, ADR-019E E-3/E-4, ADR-019F E-5).
  *
  * Each lane decodes one fixed 32-bit instruction: the integer/branch/memory/M rows come from
  * the assembled decode table of DecodeCore (base rows plus each enabled extension's
  * contribution), the system and CSR rows and their privilege legality from SystemOpDecode
  * over the committed DecodePrivView. The decoded packet is held in one register until the
  * RenameUnit takes it. Op layouts follow the unit-local encodings (AluOp, MulDivOp, BranchOp,
  * MemOp, CsrOp = funct3). Registers an instruction does not read are reported as x0 so the
  * RenameUnit creates no false dependence (CSR immediate forms carry uimm in insn only).
  *
  * Recovery stance (rawSpeculativeHolder): the held packet is younger than every renamed uop,
  * so any RecoveryEvent discards it, and no packet is accepted in the event cycle.
  */
@LocalSpec(contDecodeUnit)
class DecodeUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfFetchPacketIn)
    val fetchPacketIn = Flipped(Decoupled(new FetchPacket(params.decodeWidth, vAddrWidth, ftqIdxWidth, fetchSlotWidth)))

    @LocalSpec(intfDecodedPacketOut)
    val decodedPacketOut = Decoupled(new DecodedPacket(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))

    @LocalSpec(intfDecodePrivViewIn)
    val decodePrivViewIn = Input(new DecodePrivView)
  })

  require(!params.enableBitAlu, "the v0 DecodeUnit has no BitAlu fuType (the extension is not elaborated)")

  private val dw   = params.decodeWidth
  private val view = io.decodePrivViewIn
  private val ev   = io.recoveryEventIn

  // ---- funcExtensionDecodeContribution: one assembled table per lane --------------------------------

  /** DecodeCore assembles RV32I plus the enabled M (and B) contributions; a disabled
    * extension's rows are absent, so its encodings take the illegal default. */
  @LocalSpec(funcExtensionDecodeContribution)
  val cores: Seq[DecodeCore] = Seq.fill(dw)(Module(new DecodeCore(params)))

  private def isLink(r: UInt): Bool = r === 1.U || r === 5.U

  /** Decode one fetched instruction into a DecodedUop. */
  private def decodeLane(fi: FetchInst, core: DecodeCore): DecodedUop = {
    core.io.inst := fi.inst
    val c    = core.io.out
    val inst = fi.inst
    val rdF  = inst(11, 7); val rs1F = inst(19, 15); val rs2F = inst(24, 20); val f3 = inst(14, 12)
    val sys  = SystemOpDecode.classify(inst)

    val isBranch = c.branchCtrl =/= BranchControl.None
    val isJal    = c.branchCtrl === BranchControl.JAL
    val isJalr   = c.branchCtrl === BranchControl.JALR
    val isLoad   = c.loadCtrl =/= LoadControl.None
    val isStore  = c.storeCtrl =/= StoreControl.None
    val isMul    = c.multiplierCtrl =/= MultiplierControl.None
    val isDiv    = c.divCtrl =/= DividerControl.None
    val isSys    = sys.isSystem
    val isCsr    = sys.isCsr
    val isEcall  = inst === "h00000073".U
    val isEbreak = inst === "h00100073".U

    // ---- funcDecodeRv32im: legality and exception payloads (fetch fault first) ------------------
    val compressed = inst(1, 0) =/= "b11".U
    val known      = c.illegal === IllegalInstruction.normal || isSys || isCsr
    val privOk     = systemPrivLegality(inst)
    val illegal    = compressed || !known || !privOk
    val fetchFault = fi.fault =/= FetchFault.None
    val u = Wire(new DecodedUop(params))
    u.exception.valid := fetchFault || illegal || isEcall || isEbreak
    u.exception.cause := MuxCase(0.U, Seq(
      (fi.fault === FetchFault.InstPageFault)   -> 12.U,
      (fi.fault === FetchFault.InstAccessFault) -> 1.U,
      illegal                                   -> 2.U,
      isEbreak                                  -> 3.U,
      isEcall                                   -> MuxLookup(view.priv, 11.U)(Seq(Priv.U -> 8.U, Priv.S -> 9.U))))
    u.exception.tval := MuxCase(0.U, Seq(fetchFault -> fi.pc, illegal -> inst, isEbreak -> fi.pc))
    val exc = u.exception.valid

    // ---- Classification and operands ----------------------------------------------------------
    val opA = c.operandSelect.a; val opB = c.operandSelect.b
    val isAlu = !isBranch && !isLoad && !isStore && !isMul && !isDiv && !isSys && !isCsr
    u.fuType := MuxCase(FuType.Alu, Seq(
      exc -> FuType.System, isSys -> FuType.System, isCsr -> FuType.Csr, isBranch -> FuType.Branch,
      (isLoad || isStore) -> FuType.Mem, isMul -> FuType.Mul, isDiv -> FuType.Div))
    val cfiType = MuxCase(CfiType.Branch, Seq(
      (isJal && isLink(rdF))                    -> CfiType.Call,
      isJal                                     -> CfiType.Jal,
      (isJalr && isLink(rdF))                   -> CfiType.Call,
      (isJalr && isLink(rs1F))                  -> CfiType.Ret,
      isJalr                                    -> CfiType.Jalr))
    val memSize = Mux(isLoad,
      MuxLookup(c.loadCtrl.asUInt, 2.U)(Seq(LoadControl.LB.asUInt -> 0.U, LoadControl.LBU.asUInt -> 0.U,
        LoadControl.LH.asUInt -> 1.U, LoadControl.LHU.asUInt -> 1.U)),
      MuxLookup(c.storeCtrl.asUInt, 2.U)(Seq(StoreControl.SB.asUInt -> 0.U, StoreControl.SH.asUInt -> 1.U)))
    val memUnsigned = c.loadCtrl === LoadControl.LBU || c.loadCtrl === LoadControl.LHU
    val aluOp = c.aluCtrl.asUInt.pad(5) | ((opB =/= OperandType.Reg) << 5).asUInt | ((opA === OperandType.PC) << 6).asUInt |
      ((opA === OperandType.None) << 7).asUInt
    u.op := MuxCase(aluOp, Seq(
      (exc || isSys) -> 0.U,
      isCsr          -> f3.pad(UopOp.width),
      isBranch       -> (c.branchCtrl.asUInt.pad(4) | (cfiType << 4)),
      (isLoad || isStore) -> (memSize | (memUnsigned << 2).asUInt | (isStore << 3).asUInt),
      isMul          -> c.multiplierCtrl.asUInt,
      isDiv          -> c.divCtrl.asUInt))
    u.imm := MuxCase(0.U, Seq(
      exc                             -> 0.U,
      isJal                           -> c.imm.j.asUInt,
      (isBranch && !isJalr)           -> c.imm.b.asUInt,
      isStore                         -> c.imm.s.asUInt,
      (isLoad || isJalr)              -> c.imm.i.asUInt,
      (isAlu && opB === OperandType.UImmediate) -> c.imm.u.asUInt,
      (isAlu && opB === OperandType.IImmediate) -> c.imm.i.asUInt))
    val writesRd = !exc && (isAlu || isMul || isDiv || isLoad || isJal || isJalr || isCsr)
    val readsRs1 = !exc && ((isAlu && opA === OperandType.Reg) || (isBranch && !isJal) || isLoad || isStore || isMul || isDiv ||
      (isCsr && !f3(2)))
    val readsRs2 = !exc && ((isAlu && opB === OperandType.Reg) || (isBranch && !isJal && !isJalr) || isStore || isMul || isDiv)
    u.rd  := Mux(writesRd, rdF, 0.U)
    u.rs1 := Mux(readsRs1, rs1F, 0.U)
    u.rs2 := Mux(readsRs2, rs2F, 0.U)
    u.pc      := fi.pc
    u.insn    := inst
    u.isCfi   := !exc && isBranch
    u.isLoad  := !exc && isLoad
    u.isStore := !exc && isStore

    // ---- funcSerializingTag -----------------------------------------------------------------
    u.serialize := !exc && sys.serialize
    u.sysOp     := Mux(exc, SysOp.None, sys.sysOp)

    // ---- funcPredictionCheck ---------------------------------------------------------------------
    u.prediction.predictedTaken  := fi.predictedTaken
    u.prediction.predictedTarget := fi.predictedTarget
    u.prediction.ftqIdx          := fi.ftqIdx
    u.prediction.slot            := fi.slot
    u.prediction.blockEnd        := fi.blockEnd
    u.predictionFault            := predictionCheck(fi.predictedTaken, u.isCfi, exc)
    u
  }

  /** ADR-019E E-4 / ADR-019F E-5: privileged-instruction legality over the committed DecodePrivView
    * (a Debug Mode ECALL is illegal, so the environment-call cause never sees debugMode). */
  @LocalSpec(funcSystemPrivLegality)
  def systemPrivLegality(inst: UInt): Bool = SystemOpDecode.privLegal(inst, view)

  /** A predicted-taken slot that decodes as a non-CFI executes normally and refetches at commit. */
  @LocalSpec(funcPredictionCheck)
  def predictionCheck(predictedTaken: Bool, isCfi: Bool, exc: Bool): Bool = predictedTaken && !isCfi && !exc

  @LocalSpec(funcSerializingTag)
  val serializingTag: String = "SystemOpDecode.classify (sysOp, serialize); sysOp None for any excepting uop"

  // ---- funcDecodeRv32im: the packet ------------------------------------------------------------

  private val in  = io.fetchPacketIn
  private val out = io.decodedPacketOut
  val heldValid = RegInit(false.B)
  val held      = Reg(new DecodedPacket(params))

  @LocalSpec(funcDecodeRv32im)
  val decodeRv32im: DecodedPacket = {
    val pkt = Wire(new DecodedPacket(params))
    for (l <- 0 until dw) {
      pkt.lanes(l).valid := in.bits.valid(l)
      pkt.lanes(l).uop   := decodeLane(in.bits.insts(l), cores(l))
    }
    pkt
  }

  // ---- funcDecodeRecovery -----------------------------------------------------------------------

  @LocalSpec(funcDecodeRecovery)
  val decodeRecovery: Unit = {
    in.ready  := !ev.valid && (!heldValid || out.ready)
    out.valid := heldValid && !ev.valid
    out.bits  := held
    when(out.fire || ev.valid) { heldValid := false.B }
    when(in.fire) { heldValid := true.B; held := decodeRv32im }
    assert(!in.valid || in.bits.valid(0) &&
      (1 until dw).map(l => !in.bits.valid(l) || in.bits.valid(l - 1)).foldLeft(true.B)(_ && _),
      "DecodeUnit: FetchPacket lanes are not contiguous from lane 0")
  }

  // ---- Properties (simulation assertions) ----------------------------------------------------------

  @LocalSpec(propNoCompressedDecode)
  val noCompressedDecode: Unit = for (l <- 0 until dw) {
    val u = held.lanes(l)
    assert(!(heldValid && u.valid && u.uop.insn(1, 0) =/= "b11".U) || u.uop.exception.valid,
      "NoCompressedDecode: a 16-bit encoding decoded without an exception")
  }

  @LocalSpec(propDisabledExtensionTraps)
  val disabledExtensionTraps: Unit = if (!params.enableMulDiv) for (l <- 0 until dw) {
    val u = held.lanes(l)
    assert(!(heldValid && u.valid) || (u.uop.fuType =/= FuType.Mul && u.uop.fuType =/= FuType.Div),
      "DisabledExtensionTraps: a disabled M instruction decoded to an execution unit")
  }
}
