package udacore.backend.design.modules.csr

import chisel3._
import chisel3.util._
import udacore.common.system.CSRFields.MControl6
import udacore.common.system.{CSRAddresses => CSRAddr}
import udacore.common.system.CSRCollection
import udacore.backend.design.shared._
import udacore.backend.design.shared.{BackendBundle, BackendModule, BackendParams}
import udacore.common._
import udacore.common.ControlSignal._
import udacore.common.system.SystemConstants
import udacore.common.Causes

import udacore.backend.spec.modules.CsrControllerSpecs._

// Missing Bundle definitions
class CSRBreakPoint(val params: BackendParams) extends BackendBundle {
  val valid = Bool()
  val cause = UInt(4.W)
  val exe   = Bool()
  val load  = Bool()
  val store = Bool()
}

class CSRTriggerFire(val params: BackendParams) extends BackendBundle {
  val debugMode  = Bool()
  val loadData   = Bool()
  val breakPoint = new CSRBreakPoint(params)
}

class CSRTrapSource(val params: BackendParams) extends BackendBundle {
  val exception = Bool()
  val cause     = UInt(log2Ceil(Causes.all.length).W)
  val pc        = UInt(xLen.W)
}

class CSRDebug(val params: BackendParams) extends BackendBundle {
  val fire = Bool()
  val mode = Bool()
  val dpc  = UInt(xLen.W)
}

class CSRTriggerSource(val params: BackendParams) extends BackendBundle {
  val pc          = UInt(xLen.W)
  val instruction = UInt(32.W)
  val loadAddr    = UInt(xLen.W)
  val storeAddr   = UInt(xLen.W)
  val loadData    = UInt(xLen.W)
  val storeData   = UInt(xLen.W)
}

class CSRStatus(val params: BackendParams) extends BackendBundle {
  val mtvec    = UInt(xLen.W)
  val mcause   = UInt(xLen.W)
  val mstatus  = UInt(xLen.W)
  val mstatush = UInt(xLen.W)
  val mepc     = UInt(xLen.W)
  val mtval    = UInt(xLen.W)
  val mie      = UInt(xLen.W)
  val mip      = UInt(xLen.W)
}

class CSRSystemInstruction(val params: BackendParams) extends BackendBundle {
  val ecall  = Ecall()
  val ebreak = Ebreak()
}

class CSRReturnInstruction(val params: BackendParams) extends BackendBundle {
  val mret = MRet()
  val dret = DRet()
}

class CSRInterruptPending(val params: BackendParams) extends BackendBundle {
  val pending = Bool()
  val cause   = UInt(4.W)
}

class CSRResetHartRequest(val params: BackendParams) extends BackendBundle {
  val booting  = Bool()
  val bootAddr = UInt(xLen.W)
}

class InterruptCtrl(val params: BackendParams) extends BackendBundle {
  val mie = UInt(xLen.W)
  val mip = UInt(xLen.W)
}

class CSRIO(val params: BackendParams) extends BackendBundle {
  val req  = Flipped(Decoupled(new CsrReq(params)))
  val resp = Decoupled(new CsrResult(params))

  val ie_inst_valid = Input(Bool())

  val trap             = Input(new CSRTrapSource(params))
  val interrupt        = Input(new Interrupt)
  val interruptPending = Output(new CSRInterruptPending(params))
  val debugReq         = Input(Bool())
  val debug            = Output(new CSRDebug(params))
  val trigSrc          = Input(new CSRTriggerSource(params))
  val trigFire         = Output(new CSRTriggerFire(params))

  val csr = Output(new CSRStatus(params))

  val sys = Input(new CSRSystemInstruction(params))
  val ret = Input(new CSRReturnInstruction(params))

  val evec = Output(UInt(xLen.W))

  val resetReq = Input(new CSRResetHartRequest(params))

  val wfi           = Input(WFI())
  val wfiOut        = Output(Bool())
  val globalEpochIn = Input(UInt(epochWidth.W))
  val trapWrite     = Flipped(Decoupled(new CsrTrapWrite(params)))
  val trapRead      = Output(new CsrTrapRead(params))
  val interruptCtrl = Output(new InterruptCtrl(params))
}

// Dummy wrapper for function unit template
class CSR(val params: BackendParams) extends BackendModule {
  val io = IO(new CSRIO(params))

  val core = Module(new CSRCore(params))

  // Request handshake (Decoupled semantics)
  core.io.req.valid := io.req.valid
  io.req.ready      := core.io.req.ready
  core.io.req.bits  := io.req.bits

  // Response handshake mirrors legacy core output
  io.resp.valid      := core.io.resp.valid
  core.io.resp.ready := io.resp.ready
  io.resp.bits       := core.io.resp.bits

  // Side-band control and state mirrors
  core.io.ie_inst_valid := io.ie_inst_valid
  core.io.trap          := io.trap
  core.io.interrupt     := io.interrupt
  io.interruptPending   := core.io.interruptPending
  core.io.debugReq      := io.debugReq
  io.debug              := core.io.debug
  core.io.trigSrc       := io.trigSrc
  io.trigFire           := core.io.trigFire
  io.csr                := core.io.csr
  core.io.sys           := io.sys
  core.io.ret           := io.ret
  io.evec               := core.io.evec
  core.io.resetReq      := io.resetReq
  core.io.wfi           := io.wfi
  io.wfiOut             := core.io.wfiOut
  core.io.globalEpochIn := io.globalEpochIn
  core.io.trapWrite <> io.trapWrite
  io.trapRead           := core.io.trapRead
  io.interruptCtrl      := core.io.interruptCtrl
}

// Legacy core logic of the superseded machine. The owner waived its protection (OQ-E) for
// the ADR-019 migration; it is replaced by the M/S/U + TranslationContext rewrite.
class CSRCore(val params: BackendParams) extends BackendModule {
  import udacore.common.system.CSR._
  import CSRControl._

  private val mxLen          = xLen
  private val pcWidth        = xLen
  private val causeWidth     = log2Ceil(Causes.all.length)
  private val usingTrigger   = true
  private val usingDebug     = true
  private val usingCompressed = false

  val io = IO(new CSRIO(params))

  // Proper interface implementation - no stubs
  io.req.ready       := true.B       // CSR requests are handled in single cycle
  io.resp.valid      := io.req.valid // Response valid when request is valid
  io.trapWrite.ready := true.B

  val csr       = new CSRCollection
  val exception = WireInit(false.B)

  // From core contract parameter -> backend parameter
  csr.mhartid.write(hartId.U)

  var csrMap = Map(
    CSRAddr.mstatus    -> csr.mstatus.reg,
    CSRAddr.mstatush   -> csr.mstatush.reg,
    CSRAddr.misa       -> csr.misa.reg,
    CSRAddr.medeleg    -> csr.medeleg.reg,
    CSRAddr.mideleg    -> csr.mideleg.reg,
    CSRAddr.mie        -> csr.mie.reg,
    CSRAddr.mtvec      -> csr.mtvec.reg,
    CSRAddr.mvendorid  -> csr.mvendorid.reg,
    CSRAddr.marchid    -> csr.marchid.reg,
    CSRAddr.mimpid     -> csr.mimpid.reg,
    CSRAddr.mhartid    -> csr.mhartid.reg,
    CSRAddr.mscratch   -> csr.mscratch.reg,
    CSRAddr.mepc       -> csr.mepc.reg,
    CSRAddr.mcause     -> csr.mcause.reg,
    CSRAddr.mtval      -> csr.mtval.reg,
    CSRAddr.mip        -> csr.mip.reg,
    CSRAddr.mconfigptr -> csr.mconfigptr.reg,
    CSRAddr.menvcfg    -> csr.menvcfg.reg,
    CSRAddr.menvcfgh   -> csr.menvcfgh.reg,
    CSRAddr.mseccfg    -> csr.mseccfg.reg,
    // Debug CSRs
    CSRAddr.dcsr       -> csr.dcsr.reg,
    CSRAddr.dpc        -> csr.dpc.reg,
    CSRAddr.dscratch0  -> csr.dscratch0.reg,
    CSRAddr.dscratch1  -> csr.dscratch1.reg,
    CSRAddr.tselect    -> csr.tselect.reg,
    CSRAddr.tdata1     -> csr.tdata1.reg,
    CSRAddr.tdata2     -> csr.tdata2.reg,
    CSRAddr.tdata3     -> csr.tdata3.reg,
    CSRAddr.tinfo      -> csr.tinfo.reg
  )

  // HPM Counters
  csrMap += CSRAddr.mcountinhibit -> csr.mcounterinhibit.reg

  csrMap += CSRAddr.cycle    -> csr.mcycle.reg
  csrMap += CSRAddr.instret  -> csr.minstret.reg
  csrMap += CSRAddr.mcycle   -> csr.mcycle.reg
  csrMap += CSRAddr.minstret -> csr.minstret.reg
  if (xLen == 32) {
    csrMap += CSRAddr.cycleh    -> csr.mcycleh.reg
    csrMap += CSRAddr.instreth  -> csr.minstreth.reg
    csrMap += CSRAddr.mcycleh   -> csr.mcycleh.reg
    csrMap += CSRAddr.minstreth -> csr.minstreth.reg
  }

  for (
    ((event, counter), i) <- (csr.mhpmevent zip csr.mhpmcounter).zipWithIndex
  ) {
    csrMap += (i + CSRAddr.mhpmevent3)   -> event.reg
    csrMap += (i + CSRAddr.mhpmcounter3) -> counter.reg
    csrMap += (i + CSRAddr.hpmcounter3)  -> counter.reg
    // Platform dependent implementation
    counter.count(event.reg.asUInt, csr.mcounterinhibit.reg.asUInt(i + 3))
  }

  if (xLen == 32) {
    for ((counter, i) <- csr.mhpmcounterh.zipWithIndex) {
      csrMap += (i + CSRAddr.mhpmcounter3h) -> (counter.reg)
      csrMap += (i + CSRAddr.hpmcounter3h)  -> (counter.reg)
    }
  }

  // Essential Counters
  csr.mcycle.count(!io.wfiOut, csr.mcounterinhibit.reg.asUInt(0))
  csr.minstret.count(
    io.ie_inst_valid && !exception,
    csr.mcounterinhibit.reg.asUInt(2)
  )
  // When lower 32-bit counter overflows, high 32-bit register increases
  if (xLen == 32) {
    csr.mcycleh.count(csr.mcycle.reg.asUInt(xLen - 1), false.B)
    csr.minstreth.count(csr.minstret.reg.asUInt(xLen - 1), false.B)
  }

  val csrAddr      = csrMap map { case (k, v) =>
    k -> (io.req.bits.csr === k.U)
  }
  val csrAddrValid = csrAddr.values.reduce((a: Bool, b: Bool) => a || b)

  val a = for ((k, v) <- csrMap) yield (k)
  val b = for ((k, v) <- csrMap) yield (csrAddr(k) -> v)
  io.resp.bits.data := Mux1H(
    for ((k, v) <- csrMap) yield (csrAddr(k) -> v.asUInt)
  )

  // CSR illegal instruction check
  val illegalAccess = io.req.valid && !csrAddrValid
  val wen           =
    (io.req.bits.op === RW) || (io.req.bits.op === RS) || (io.req.bits.op === RC)
  val wdata         = Mux1H(
    Seq(
      (io.req.bits.op === RW) -> io.req.bits.data,
      (io.req.bits.op === RS) -> (io.req.bits.data | io.resp.bits.data),
      (io.req.bits.op === RC) -> ((io.req.bits.data | io.resp.bits.data) & (~io.req.bits.data).asUInt)
    )
  )

  when(wen) {
    when(csrAddr(CSRAddr.mstatus)) {
      csr.mstatus.write(wdata)
    }
    when(csrAddr(CSRAddr.mstatush)) {
      csr.mstatush.write(wdata)
    }
    // when (csrAddr(CSRAddr.misa)) {}
    when(csrAddr(CSRAddr.medeleg)) {
      csr.medeleg.write(wdata)
    }
    when(csrAddr(CSRAddr.mideleg)) {
      csr.mideleg.write(wdata)
    }
    when(csrAddr(CSRAddr.mie)) {
      csr.mie.write(wdata)
    }
    when(csrAddr(CSRAddr.mtvec)) {
      csr.mtvec.write(wdata)
    }
    when(csrAddr(CSRAddr.mscratch)) {
      csr.mscratch.write(wdata)
    }
    when(csrAddr(CSRAddr.mepc)) {
      val alignedMepc = if (usingCompressed) {
        Cat(wdata(pcWidth - 1, 1), 0.U(1.W))
      } else {
        Cat(wdata(pcWidth - 1, 2), 0.U(2.W))
      }
      csr.mepc.write(alignedMepc)
    }
    // If not bigint wrapped, compile error for overflow occurs
    when(csrAddr(CSRAddr.mcause)) {
      csr.mcause.write(
        wdata & ((BigInt(1) << (mxLen - 1)) | ((BigInt(1) << causeWidth) - 1)).U
      )
    }
    when(csrAddr(CSRAddr.mtval)) {
      csr.mtval.write(wdata)
    }
    when(csrAddr(CSRAddr.mip)) {
      csr.mip.write(wdata)
    }
    when(csrAddr(CSRAddr.menvcfg)) {
      // Support S-mode or satp.MODE is read-only zero then don't write
      csr.menvcfg.write(wdata.asTypeOf((new Envcfg).field).fiom)
    }

    when(csrAddr(CSRAddr.mcountinhibit)) {
      csr.mcounterinhibit.write(wdata)
    }

    // for Debugger
    when(csrAddr(CSRAddr.dscratch0)) {
      csr.dscratch0.write(wdata)
    }
    when(csrAddr(CSRAddr.dscratch1)) {
      csr.dscratch1.write(wdata)
    }
    when(csrAddr(CSRAddr.dcsr)) {
      csr.dcsr.write(wdata)
    }
    when(csrAddr(CSRAddr.dpc)) {
      if (usingCompressed) {
        csr.dpc.write(Cat(wdata(mxLen - 1, 1), 0.U(1.W)))
      } else {
        csr.dpc.write(Cat(wdata(mxLen - 1, 2), 0.U(2.W)))
      }
    }
    when(csrAddr(CSRAddr.tdata1)) {
      csr.tdata1.write(wdata)
    }
    when(csrAddr(CSRAddr.tdata2)) {
      csr.tdata2.write(wdata)
    }
    when(csrAddr(CSRAddr.tdata3)) {
      csr.tdata3.write(wdata)
    }
    when(csrAddr(CSRAddr.tinfo)) {
      csr.tinfo.write(wdata)
    }
    when(csrAddr(CSRAddr.tselect)) {
      csr.tselect.write(wdata)
    }
  }

  val priv = RegInit(SystemConstants.Privilege.MMode)

  // Debug and Trigger modules
  val mcontrol6 = WireInit(0.U.asTypeOf(new MControl6))
  if (usingTrigger) {
    csr.tdata1.read(mcontrol6)
  }

  val triggerUnit = Module(new TriggerUnit(params))
  triggerUnit.io.priv      := priv
  triggerUnit.io.mcontrol6 := mcontrol6
  triggerUnit.io.tdata2    := csr.tdata2.reg.data
  triggerUnit.io.trigSrc   := io.trigSrc

  val debugUnit = Module(new DebugUnit(params))
  debugUnit.io.debugReq    := io.debugReq
  debugUnit.io.sysEbreak   := io.sys.ebreak.asUInt.orR
  debugUnit.io.ieInstValid := io.ie_inst_valid
  debugUnit.io.dret        := io.ret.dret.asUInt.orR
  debugUnit.io.step        := csr.dcsr.reg.step
  debugUnit.io.ebreaks     := csr.dcsr.reg.ebreaks
  debugUnit.io.ebreaku     := csr.dcsr.reg.ebreaku
  debugUnit.io.ebreakm     := csr.dcsr.reg.ebreakm
  debugUnit.io.trigDebug   := triggerUnit.io.trigFire.debugMode
  debugUnit.io.priv        := priv

  triggerUnit.io.regDebugMode := debugUnit.io.regDebugMode

  io.trigFire := triggerUnit.io.trigFire

  val trigFire          = triggerUnit.io.fire
  val trigLdDataEn      = triggerUnit.io.trigLdDataEn
  val regDebugMode      = debugUnit.io.regDebugMode
  val debugFire         = debugUnit.io.debugFire
  val singleStep        = debugUnit.io.singleStep
  val ebreakToDebugMode = debugUnit.io.ebreakToDebugMode

  exception     := io.sys.ecall.asUInt.orR ||
    (io.sys.ebreak.asUInt.orR && !ebreakToDebugMode) ||
    illegalAccess ||
    io.trap.exception
  io.debug.fire := debugFire
  io.debug.mode := regDebugMode

  // Exception
  // tval
  // Overview: breakpoint, address-misaligned, access-fault, or
  // page-fault exception occurs on an instruction prefetchReq, load, or store --> faulting virtual address
  // 1. misaligned load or store causes an access-fault or page-fault exception --> virtual address of the portion of the access
  // 2. instruction access-fault or page-fault exception occurs on a system
  // with variable-length instructions --> virtual address of the portion of the instruction
  // 3. illegal instruction exception --> faulting instruction bits
  val tval = WireDefault(0.U(xLen.W))
  when(
    io.trap.cause === Causes.breakpoint.U ||
      io.trap.cause === Causes.machine_ecall.U
  ) {
    tval := io.trap.pc
  }.elsewhen(io.trap.cause === Causes.illegal_instruction.U) {
    tval := io.trigSrc.instruction
  }.elsewhen(
    io.trap.cause === Causes.misaligned_fetch.U ||
      io.trap.cause === Causes.fetch_page_fault.U ||
      io.trap.cause === Causes.fetch_guest_page_fault.U ||
      io.trap.cause === Causes.fetch_access.U ||
      io.trap.cause === Causes.misaligned_store.U ||
      io.trap.cause === Causes.store_page_fault.U ||
      io.trap.cause === Causes.store_guest_page_fault.U ||
      io.trap.cause === Causes.store_access.U
  ) {
    // Store fault address in tval register
    tval := 0.U
  }.elsewhen(
    io.trap.cause === Causes.misaligned_load.U ||
      io.trap.cause === Causes.load_page_fault.U ||
      io.trap.cause === Causes.load_guest_page_fault.U ||
      io.trap.cause === Causes.load_access.U
  ) {
    tval := io.trigSrc.loadAddr
  }.otherwise {
    tval := 0.U
  }
  // TVAL register mapping for fault addresses and instruction values

  // Interrupt
  val mip = WireInit(0.U.asTypeOf(csr.mip.reg))
  mip.meip := io.interrupt.e
  mip.mtip := io.interrupt.t
  mip.msip := io.interrupt.s

  // MIP register is read-only for software interrupts
  csr.mip.reg.meip  := mip.meip
  csr.mip.reg.mtip  := mip.mtip
  csr.mip.reg.msip  := mip.msip
  csr.mip.reg.debug := mip.debug

  // Interrupt enable and pending matching logic
  // mstatus.mie shows interrupts are to be taken or not
  // When not taken, interrupt will resume its original program flow by local interrupt enables
  // When taken, interrupt will jump to ISR
  val mInterruptPending   = (csr.mie.reg.asUInt & mip.asUInt).orR
  val mInterruptToBeTaken = mInterruptPending && csr.mstatus.reg.mie
  io.interruptPending.pending := mInterruptToBeTaken
  io.interruptPending.cause   := Mux1H(
    Seq(
      (mip.debug && csr.mie.reg.debug) -> 14.U,
      (mip.meip && csr.mie.reg.meip)   -> 11.U,
      (mip.mtip && csr.mie.reg.mtip)   -> 7.U,
      (mip.msip && csr.mie.reg.msip)   -> 3.U
    )
  )

  // CSR control when interrupt/exception occurs
  val trapWriteFire = io.trapWrite.fire

  io.evec := 0.U
  when(trapWriteFire) {
    val trapMepc = if (usingCompressed) {
      Cat(io.trapWrite.bits.mepc(pcWidth - 1, 1), 0.U(1.W))
    } else {
      Cat(io.trapWrite.bits.mepc(pcWidth - 1, 2), 0.U(2.W))
    }
    csr.mepc.write(trapMepc)
    csr.mcause.write(io.trapWrite.bits.mcause)
    csr.mtval.write(io.trapWrite.bits.mtval)
    csr.mstatus.write(io.trapWrite.bits.mstatus)
  }.elsewhen(exception) {
    csr.mstatus.reg.mpie := csr.mstatus.reg.mie
    csr.mstatus.reg.mpp  := priv
    csr.mstatus.reg.mie  := false.B
    csr.mepc.write(io.trap.pc)
    csr.mcause.write(io.trap.cause)
    csr.mtval.write(tval)
    io.evec              := Mux1H(
      Seq(
        (csr.mtvec.reg.mode === 0.U) -> (csr.mtvec.reg.base << 2.U),
        (csr.mtvec.reg.mode === 1.U) -> ((csr.mtvec.reg.base << 2.U).asUInt + (io.trap.cause << 2.U).asUInt)
      )
    )
  }.elsewhen(io.ret.mret.asUInt.orR) {
    csr.mstatus.reg.mie  := csr.mstatus.reg.mpie
    csr.mstatus.reg.mpie := true.B
    csr.mepc.read(io.evec)
    priv                 := csr.mstatus.reg.mpp
  }

  when(trapWriteFire) {
    priv := SystemConstants.Privilege.MMode
  }.elsewhen(exception) {
    priv := SystemConstants.Privilege.MMode
  }.elsewhen(debugFire) {
    priv := SystemConstants.Privilege.MMode
  }.elsewhen(io.ret.mret.asUInt.orR) {
    priv := csr.mstatus.reg.mpp
  }.elsewhen(io.ret.dret.asUInt.orR) {
    priv := csr.dcsr.reg.prv
  }

  // dcsr update for debugger
  csr.dcsr.reg.prv := Mux(debugFire, priv, csr.dcsr.reg.prv)
  val dbgCause = WireInit(0.U(3.W))
  // Priority of dcsr.cause
  // resethaltreq -> haltgroup -> haltreq -> trigger -> ebreak -> step
  dbgCause := PriorityMux(
    Seq(
      (!singleStep && debugFire && io.resetReq.booting)  -> SystemConstants.DebugCause.ResetHartReq,
      (!singleStep && debugFire && !io.resetReq.booting) -> SystemConstants.DebugCause.HartReq,
      trigFire                                           -> SystemConstants.DebugCause.Trigger,
      ebreakToDebugMode                                  -> SystemConstants.DebugCause.EBreak,
      csr.dcsr.reg.step                                  -> SystemConstants.DebugCause.SingleStep
    )
  )
  when(debugFire) {
    csr.dcsr.reg.cause := dbgCause
  }

  // dpc update for debugger
  val dpcComb = WireInit(0.U(xLen.W))
  dpcComb      := Mux1H(
    Seq(
      (dbgCause === SystemConstants.DebugCause.ResetHartReq)              -> io.resetReq.bootAddr,
      ((dbgCause === SystemConstants.DebugCause.Trigger) && trigLdDataEn) -> (io.trigSrc.pc + Mux(
        io.trigSrc.pc(1),
        2.U,
        4.U
      )),
      (dbgCause === SystemConstants.DebugCause.SingleStep)                -> (io.trigSrc.pc + Mux(
        io.trigSrc.pc(1),
        2.U,
        4.U
      )),
      (dbgCause === SystemConstants.DebugCause.EBreak)                    -> io.trigSrc.pc,
      (dbgCause === SystemConstants.DebugCause.HartReq)                   -> io.trigSrc.pc,
      (dbgCause === SystemConstants.DebugCause.GroupHartReq)              -> io.trigSrc.pc
    )
  )
  when(debugFire) {
    csr.dpc.reg.data := dpcComb
  }
  io.debug.dpc := Mux(usingDebug.B, csr.dpc.reg.data, 0.U)

  // WFI
  // WFI instruction handling with clock gating optimization
  val wfi = RegInit(false.B)
  when(debugFire || mInterruptPending || exception || trapWriteFire) {
    wfi := false.B
  }.elsewhen(io.wfi.asUInt.orR && !wfi) {
    wfi := true.B
  }
  io.wfiOut := wfi

  // To romiControl
  csr.mtvec.read(io.csr.mtvec)
  csr.mcause.read(io.csr.mcause)
  csr.mstatus.read(io.csr.mstatus)
  csr.mstatush.read(io.csr.mstatush)
  csr.mepc.read(io.csr.mepc)
  csr.mtval.read(io.csr.mtval)
  io.csr.mie := csr.mie.reg.asUInt
  io.csr.mip := csr.mip.reg.asUInt

  io.trapRead.mtvec    := io.csr.mtvec
  io.trapRead.mstatus  := io.csr.mstatus
  io.interruptCtrl.mie := io.csr.mie
  io.interruptCtrl.mip := io.csr.mip

  io.resp.bits.csr      := io.req.bits.csr
  io.resp.bits.traps    := 0.U.asTypeOf(io.resp.bits.traps)
  io.resp.bits.traps.exception := exception
  io.resp.bits.traps.cause     := io.trap.cause
  io.resp.bits.meta.rd        := io.req.bits.meta.rd
  io.resp.bits.meta.epoch     := io.req.bits.meta.epoch
}
