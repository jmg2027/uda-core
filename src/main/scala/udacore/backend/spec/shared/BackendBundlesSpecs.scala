package udacore.backend.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._

object BackendBundlesSpecs {
  val bndInstructionIssue = spec {
    BUNDLE("InstructionIssue")
      .desc("Issue payload to backend decoder.")
      .note("Fields: instBytes, pc, meta")
      .build()
  }

  val bndInterrupt = spec {
    BUNDLE("Interrupt")
      .desc("Aggregated interrupt indicators.")
      .note("Fields: mie, mtip, msip, meip, privilege")
      .build()
  }

  val bndMemoryOpResp = spec {
    BUNDLE("MemoryOpResp")
      .desc("Memory response from memory subsystem.")
      .note("Fields: data, meta, fault")
      .note(
        "meta carries the canonical seqTag / txnId reassociation key (ADR-003 D-3.13), not a 32-bit uopId."
      )
      .build()
  }

  val bndDecodedUop = spec {
    BUNDLE("DecodedUop")
      .desc("Decoded uop payload.")
      .note("Fields: seqTag, opcode, operands, immediates, serializing, meta")
      .note(
        "serializing bit set by Decode for CSR/mret/dret/wfi/fence/fence.i/ecall/ebreak (ADR-004 D-4.2)."
      )
      .build()
  }

  val bndRenamedUop = spec {
    BUNDLE("RenamedUop")
      .desc("Renamed uop payload.")
      .note(
        "Fields: seqTag, prs1, prs2, prd (physRegIdWidth physical register IDs), dependencies, epoch, meta"
      )
      .build()
  }

  val bndRegisterFileReadReq = spec {
    BUNDLE("RegisterFileReadReq")
      .desc("Physical register read request.")
      .note("Fields: prs1, prs2 (physical register IDs), meta, epochTag")
      .build()
  }

  val bndRegisterFileReadResp = spec {
    BUNDLE("RegisterFileReadResp")
      .desc("Physical register read response.")
      .note("Fields: data1, data2 (register values), epochTag")
      .build()
  }

  val bndDispatchedUop = spec {
    BUNDLE("DispatchedUop")
      .desc("Dispatch-ready uop.")
      .note("Fields: seqTag, operands, fuMask, serializing, meta")
      .build()
  }

  val bndDecodedUopAlloc = spec {
    BUNDLE("DecodedUopAlloc")
      .desc("Allocation notification for commit bookkeeping (the alloc-FIFO token, ADR-002 D-2.1).")
      .note("Fields: seqTag, archRd, oldPrd, newPrd, flags, epoch")
      .note(
        "One token per uop in program order; CommitUnit retires strictly from the FIFO head."
      )
      .build()
  }

  val bndAluReq = spec {
    BUNDLE("ALUReq")
      .desc("Integer ALU execution request.")
      .note("Fields: seqTag, op, lhs, rhs, epoch, meta")
      .build()
  }

  val bndBitAluReq = spec {
    BUNDLE("BitAluReq")
      .desc("Bit-manipulation execution request.")
      .note("Fields: seqTag, op, lhs, rhs, mask, epoch")
      .build()
  }

  val bndMultiplierReq = spec {
    BUNDLE("MultiplierReq")
      .desc("Multiplier unit request.")
      .note("Fields: seqTag, lhs, rhs, op, rd, epoch")
      .build()
  }

  val bndDividerReq = spec {
    BUNDLE("DividerReq")
      .desc("Divider unit request.")
      .note("Fields: seqTag, dividend, divisor, op, rd, epoch")
      .build()
  }

  val bndBranchUnitReq = spec {
    BUNDLE("BranchUnitReq")
      .desc("Branch evaluation request.")
      .note("Fields: seqTag, lhs, rhs, pc, target, prediction, epoch")
      .build()
  }

  val bndCsrReq = spec {
    BUNDLE("CSRReq")
      .desc("CSR operation request.")
      .note("Fields: seqTag, csr, op, data, epoch, meta")
      .build()
  }

  val bndAddressGenerationReq = spec {
    BUNDLE("AddressGenerationReq")
      .desc("Address generation request.")
      .note("Fields: seqTag, base, offset, attrs, epoch")
      .build()
  }

  val bndAluResult = spec {
    BUNDLE("ALUResult")
      .desc("Integer ALU result.")
      .note("Fields: seqTag, result, flags")
      .build()
  }

  val bndBitAluResult = spec {
    BUNDLE("BitALUResult")
      .desc("Bit ALU result.")
      .note("Fields: seqTag, result, flags")
      .build()
  }

  val bndMultiplierResult = spec {
    BUNDLE("MultiplierResult")
      .desc("Multiplier result.")
      .note("Fields: seqTag, data, rd, epoch, flags")
      .build()
  }

  val bndDividerResult = spec {
    BUNDLE("DividerResult")
      .desc("Divider result.")
      .note("Fields: seqTag, quotient, remainder, rd, epoch, flags")
      .build()
  }

  val bndBranchUnitResult = spec {
    BUNDLE("BranchUnitResult")
      .desc("Branch resolution result.")
      .note("Fields: seqTag, pc, target, taken, epochTag")
      .note(
        "Resolved direction/target is commit-pending payload; the FU never redirects (ADR-011 D-11.1)."
      )
      .build()
  }

  val bndCsrResult = spec {
    BUNDLE("CSRResult")
      .desc("CSR execution result.")
      .note("Fields: seqTag, oldValue, wIntent{addr,wdata}, meta")
      .note(
        "FU visit is pure read plus staged write intent; no CSR mutation before commitGrant (ADR-004 D-4.1)."
      )
      .build()
  }

  val bndPublishResult = spec {
    BUNDLE("PublishResult")
      .desc("Aggregated writeback result.")
      .note("Fields: seqTag, data, flags")
      .build()
  }

  val bndCommitResult = spec {
    BUNDLE("CommitResult")
      .desc("Writeback record for commit unit.")
      .note("Fields: seqTag, data, flags, epochTag")
      .build()
  }

  val bndWriteBack = spec {
    BUNDLE("WriteBack")
      .desc("Retire writeback to register file.")
      .note("Fields: prs, data, flags")
      .build()
  }

  // ADR-004: complete trap/return application packet.
  val bndCsrTrapRead = spec {
    BUNDLE("CSRTrapRead")
      .desc("Live trap-CSR snapshot read by TrapController at the commit head.")
      .note(
        "Fields: mstatus, mepc, mcause, mtval, mtvec, mie, mip, priv, dpc, dcsr"
      )
      .note(
        "Pure combinational read of live CSR state; TrapController computes the trap application from it (ADR-004 D-4.3)."
      )
      .build()
  }

  val bndCsrTrapWrite = spec {
    BUNDLE("CSRTrapWrite")
      .desc("Single trap/return application packet driven only by TrapController (ADR-004 D-4.3).")
      .markdownTable(
        List("field", "meaning"),
        List(
          List("kind", "TrapEntry | MRet | DRet | DebugEntry"),
          List("mepc", "faulting/return PC written on TrapEntry/DebugEntry"),
          List("mcause", "trap cause (sync or interrupt)"),
          List("mtval", "trap value (fault address / instruction bits)"),
          List("mstatusNext", "mstatus after push/pop of mie/mpie and priv"),
          List("privNext", "privilege after the transition"),
          List("dpc", "debug PC (DebugEntry/DRet)"),
          List("dcsrNext", "dcsr after the transition (DebugEntry/DRet)")
        )
      )
      .note(
        "Sole producer of trap-CSR and privilege transitions; the CSR-internal trap writer is deleted (ADR-004 D-4.3)."
      )
      .build()
  }

  // ADR-004 D-4.5: unified sync/async exception edge from the commit head.
  val bndException = spec {
    BUNDLE("Exception")
      .desc("Unified exception notification raised by CommitUnit at the commit head.")
      .markdownTable(
        List("field", "meaning"),
        List(
          List("source", "Sync | Interrupt"),
          List("cause", "trap/interrupt cause code"),
          List("pc", "architectural PC of the trapping / next-uncommitted uop"),
          List("epoch", "epoch of the retiring uop (cross-check)")
        )
      )
      .note(
        "Async interrupts join program order only at the commit boundary; source distinguishes them (ADR-004 D-4.5)."
      )
      .build()
  }

  val bndRedirect = spec {
    BUNDLE("Redirect")
      .desc("Redirect request from trap controller.")
      .note("Fields: pc, target, reason, epochTag")
      .build()
  }

  val bndMispredict = spec {
    BUNDLE("Mispredict")
      .desc("Branch misprediction feedback.")
      .note("Fields: pc, target, prediction, epochTag")
      .build()
  }

  val bndMemoryOpReq = spec {
    BUNDLE("MemoryOpReq")
      .desc("Memory operation request.")
      .note("Fields: addr, size, write, data, seqTag, meta")
      .build()
  }

  val bndRedirectOut = spec {
    BUNDLE("RedirectOut")
      .desc("Backend redirect output.")
      .note("Fields: pc, target, reason, epochTag")
      .build()
  }

  // Physical Register File bundles for Unified PRF architecture
  val bndPhysicalRegWrite = spec {
    BUNDLE("PhysicalRegWrite")
      .desc("Physical register write command.")
      .note("Fields: prd (physRegIdWidth physical register destination), data, valid, epochTag")
      .build()
  }

  val bndWakeupBroadcast = spec {
    BUNDLE("WakeupBroadcast")
      .desc("Wakeup broadcast for dependency resolution.")
      .note("Fields: prd (physical register destination), valid, epochTag")
      .build()
  }

  val bndMapTableUpdate = spec {
    BUNDLE("MapTableUpdate")
      .desc("Map table update command.")
      .note("Fields: archReg (architectural register ID), prd (physical register ID), valid")
      .build()
  }

  val bndPhysicalRegFree = spec {
    BUNDLE("PhysicalRegFree")
      .desc("Physical register free command.")
      .note("Fields: prd (physical register ID), valid")
      .build()
  }

  // ------------------------------------------------------------------------
  // ADR-001 / ADR-002 / ADR-004 / ADR-010 / ADR-012 canonical shared bundles.
  // Owned here by WP-A; consumed read-only by WP-B/WP-C/WP-D via import.
  // ------------------------------------------------------------------------

  // ADR-001 D-1.1 / ADR-012 D-12.4 (map-update+free view source).
  val bndArchMapSnapshot = spec {
    BUNDLE("ArchMapSnapshot")
      .desc("Full architectural (retirement) register->physical map plus the free-set mask.")
      .note(
        "Fields: mapPhys[RegNum] (physical id per arch reg, width physRegIdWidth), freeMask[33+N]"
      )
      .note(
        "Broadcast combinationally with the epoch (non-Decoupled); consumed only in the epoch-change cycle (ADR-001 D-1.2)."
      )
      .build()
  }

  // ADR-012 D-12.4: ONE unified commit event with field-projected views.
  val bndCommitBroadcast = spec {
    BUNDLE("CommitBroadcast")
      .desc(
        "The single in-order commit event, owned and driven by CommitUnit, keyed by canonical seqTag (ADR-012 D-12.4)."
      )
      .note("Key: seqTag (seqWidth). All consumers key on the SAME seqTag.")
      .markdownTable(
        List("projected view", "fields", "consumer"),
        List(
          List("StoreCommit", "seqTag, epoch", "StoreBuffer (ADR-003 D-3.3)"),
          List("commitGrant", "seqTag, epoch, valid", "CSR (ADR-004 D-4.1)"),
          List("mapUpdateFree", "archRd, oldPrd, newPrd", "RenameUnit free list (ADR-001/002)"),
          List("retireTrigger", "seqTag, valid", "retire stream (ADR-010, verif-gated)")
        )
      )
      .note(
        "The store and CSR paths cannot disagree about which uop retired because they share seqTag (closes M4/V-MA-5)."
      )
      .build()
  }

  // ADR-004 D-4.5: interrupt pending/enable view sampled at the commit boundary.
  val bndInterruptCtrl = spec {
    BUNDLE("InterruptCtrl")
      .desc("Interrupt pending/enable control view consumed by CommitUnit at a retire boundary.")
      .note("Fields: mip, mie, priv, debugMode")
      .note(
        "Pending/enable compute stays in the CSR; the decision to take moves to CommitUnit (ADR-004 D-4.5)."
      )
      .build()
  }

  // ADR-010 D-10.1: verification-only retire stream token.
  val bndRetireToken = spec {
    BUNDLE("RetireToken")
      .desc("Per-retirement verification token emitted by CommitUnit (ADR-010 D-10.1).")
      .markdownTable(
        List("field", "meaning"),
        List(
          List("order", "64b monotone RVVI sequence (verif-only, gated by usingRvvi)"),
          List("pc", "architectural PC of the retiring instruction"),
          List("insn", "RVC-expanded instruction bits"),
          List("rd", "architectural destination register"),
          List("wdata", "committed value from a commit-time PRF read"),
          List("wen", "register-write-enable"),
          List("trap", "1 for a trap-entry retire token (ADR-010 D-10.4)"),
          List("cause", "trap cause"),
          List("epoch", "cross-check only; EXCLUDED from the N-equivalence compare (ADR-010 D-10.4)")
        )
      )
      .note(
        "Emitted only for tokens that actually retire; observation-only, ready tied high (ADR-010 D-10.2)."
      )
      .note(
        "The entire path INCLUDING the 64b order counter folds away at usingRvvi=false (ADR-010 D-10.3)."
      )
      .build()
  }
}
