package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.core.spec.shared.CoreBundlesSpecs.bndInterrupt

/** CsrController: Zicsr datapath, M/S/U CSR state, and the committed views it
  * publishes (ADR-004 as re-based by ADR-019; ADR-017 CSR map contributions).
  */
object CsrControllerSpecs {
  val contCsrController = spec {
    CONTRACT("CsrController")
      .desc(
        "Executes CSRRW/CSRRS/CSRRC(I) IssuedUops for M-, S-, and debug-mode CSRs and returns a " +
        "FuResult, stages a legal write until the matching CommitGrant, applies the " +
        "TrapController's CSRTrapWrite verbatim, and publishes three committed-state views: the " +
        "trap snapshot, the pending-and-enabled interrupt view, and the translation context used " +
        "by the MMU. It is the sole owner of committed CSR state, privilege, and debug mode " +
        "(ADR-019D E-7); it computes no exception routing and no trap encoding (ADR-004)."
      )
      .has(
        intfCsrReqIn,
        intfCsrResultOut,
        intfCsrTrapReadOut,
        intfCsrTrapWriteIn,
        intfCommitGrantIn,
        intfInterruptIn,
        intfInterruptCtrlOut,
        intfTranslationContextOut,
        funcCsrExecuteAtCommit,
        funcCsrOpSemantics,
        funcCsrAccessCheck,
        funcSupervisorCsrs,
        funcCsrTrapWriteApply,
        funcInterruptCtrlPublish,
        funcTranslationContextPublish,
        funcCsrMapContribution,
        propNoSpeculativeCsrWrite,
        propCsrWriteIntent,
        propCsrSingleOwner
      )
      .uses(rawExtensionContribution)
      .note(
        "Recovery stance: a CSR uop is renamed only into an empty ROB (funcSerializeGate), so it " +
        "is never younger than a live branch and is never killed by a BranchMispredict; an " +
        "ArchRedirect can only follow its own commit."
      )
      .note(
        "ADR-019D: the legacy epoch-based CSRReq/CSRResult boundary and csr/CSR.scala are deleted; " +
        "the CSR execution edge is IssuedUop in, FuResult out. A staged write is never killed: " +
        "the CommitUnit takes no interrupt or debug request while a serialize head is presented " +
        "(ADR-019D E-5), and an illegal access stages nothing."
      )
      .build()
  }

  val intfCsrReqIn = spec {
    INTERFACE("CSRReqIn")
      .desc("CSR IssuedUops from DispatchUnit (ADR-019D E-1).")
      .note("ADR-019C E-2: ready derives only from registered state, never from this request's valid or payload: no staged write and the one-entry result slot free or draining.")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrResultOut = spec {
    INTERFACE("CSRResultOut")
      .desc("FuResult toward PublishMux (ADR-019D E-4): robTag and prd of the uop, wen = hasDest && legal, data = the old CSR value, illegal-instruction exception, cfiOutcome None.")
      .note("While valid and not ready the payload is stable.")
      .uses(bndFuResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrTrapReadOut = spec {
    INTERFACE("CSRTrapReadOut")
      .desc("Live trap-CSR snapshot to the TrapController.")
      .uses(bndCsrTrapRead)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4.")
      .build()
  }

  val intfCsrTrapWriteIn = spec {
    INTERFACE("CSRTrapWriteIn")
      .desc("Trap/return application packets from the TrapController; the only trap-driven mutation path.")
      .uses(bndCsrTrapWrite)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCommitGrantIn = spec {
    INTERFACE("CommitGrantIn")
      .desc("Commit strobe from the CommitUnit qualifying every software CSR write.")
      .uses(bndCommitBroadcast)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4.")
      .build()
  }

  val intfInterruptIn = spec {
    INTERFACE("InterruptIn")
      .desc("Raw interrupt lines (meip, mtip, msip, seip, stip, ssip) feeding mip.")
      .uses(bndInterrupt)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 2.")
      .build()
  }

  val intfInterruptCtrlOut = spec {
    INTERFACE("InterruptCtrlOut")
      .desc("Pending-and-enabled interrupt view, debugMode, and the committed privilege (ADR-019B E-4) to the CommitUnit.")
      .uses(bndInterruptCtrl)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 2/4.")
      .build()
  }

  val intfTranslationContextOut = spec {
    INTERFACE("TranslationContextOut")
      .desc("Committed satp/priv/MPRV/SUM/MXR view to the ITLB, DTLB, and PTW.")
      .uses(bndTranslationContext)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4: changes only at a commit that is followed by an ArchRedirect.")
      .build()
  }

  val funcCsrExecuteAtCommit = spec {
    FUNCTION("CsrExecuteAtCommit")
      .desc(
        "On an accepted IssuedUop (the only live uop, CSR uops are serialized) check legality, " +
        "read the committed value, and return one FuResult {robTag, prd, wen = hasDest && legal, " +
        "data = old value, exception, cfiOutcome None}. A legal write (sysOp CsrWrite) stages " +
        "{robTag, csrAddr, newValue} without mutating state; it applies only when CommitGrant.valid " +
        "and CommitGrant.robTag equals the staged robTag, exactly once. A CommitGrant naming a " +
        "different robTag while a write is staged is an assertion failure. A read-only access " +
        "(sysOp None) stages nothing and its CommitGrant writes nothing. Every committed CSR " +
        "write is followed by a Refetch."
      )
      .uses(intfCsrReqIn, intfCsrResultOut, intfCommitGrantIn)
      .note("ADR-019D E-4.")
      .build()
  }

  val funcCsrOpSemantics = spec {
    FUNCTION("CsrOpSemantics")
      .desc(
        "CsrOp (a UopOp layout set by the DecodeUnit) is RW, RS, RC, RWI, RSI, or RCI. The " +
        "operand is src1 for the register forms and zext(insn[19:15]) for the immediate forms; " +
        "the CSR address is insn[31:20]. newValue = operand (RW/RWI), old | operand (RS/RSI), " +
        "old & ~operand (RC/RCI), then the CSR's WARL legalization. Write intent is sysOp == " +
        "CsrWrite and is never inferred from the operand value; rd = x0 CSRRW[I] still writes."
      )
      .uses(intfCsrReqIn)
      .note("ADR-019D E-2/E-3.")
      .build()
  }

  val funcCsrAccessCheck = spec {
    FUNCTION("CsrAccessCheck")
      .desc(
        "An access to a CSR that does not exist, whose privilege field exceeds the current " +
        "privilege, that writes a read-only CSR, or that touches satp in S-mode with " +
        "mstatus.TVM set, completes with an illegal-instruction exception (cause 2, tval = insn), " +
        "wen = 0, and no staged write. A write attempt is sysOp == CsrWrite. Debug-mode CSRs " +
        "(dcsr, dpc, dscratch0) exist only in debug mode, which accesses as M."
      )
      .uses(intfCsrResultOut)
      .build()
  }

  val funcSupervisorCsrs = spec {
    FUNCTION("SupervisorCsrs")
      .desc(
        "Implement the S-mode CSRs for v0: sstatus and sie/sip as restricted views of " +
        "mstatus and mie/mip, stvec, sscratch, sepc, scause, stval, scounteren, satp, and the " +
        "M-mode medeleg, mideleg, mcounteren; mstatus MPRV, SUM, MXR, TVM, TW, TSR, MPP, SPP are " +
        "WARL fields with the v0 legal values."
      )
      .build()
  }

  val funcCsrTrapWriteApply = spec {
    FUNCTION("CsrTrapWriteApply")
      .desc(
        "Apply each CSRTrapWrite in its fire cycle by kind: TrapEntryM writes mepc/mcause/mtval, " +
        "TrapEntryS writes sepc/scause/stval, both write mstatus := mstatusNext and priv := " +
        "privNext; MRet/SRet write mstatus and priv; DebugEntry writes dpc, dcsr := dcsrNext, " +
        "priv := privNext and enters debug mode; DRet writes priv and leaves debug mode. " +
        "CSRTrapWriteIn is always ready."
      )
      .uses(intfCsrTrapWriteIn)
      .note("ADR-019D E-6.")
      .build()
  }

  val funcInterruptCtrlPublish = spec {
    FUNCTION("InterruptCtrlPublish")
      .desc(
        "Drive InterruptCtrl and CSRTrapRead every cycle as combinational views of the committed " +
        "state. mip = raw machine lines | (raw supervisor lines OR the software-writable " +
        "SEIP/STIP/SSIP). An interrupt i is takeable when mip[i] & mie[i] and, for a " +
        "non-delegated i, priv < M or MIE; for a delegated i (mideleg), priv < S or (priv == S " +
        "and SIE). interruptCause is the highest-priority takeable cause in the order MEI, MSI, " +
        "MTI, SEI, SSI, STI; debugMode is the committed debug-mode bit; priv the committed privilege."
      )
      .uses(intfInterruptIn, intfInterruptCtrlOut, intfCsrTrapReadOut)
      .note("ADR-019D E-7.")
      .build()
  }

  val funcTranslationContextPublish = spec {
    FUNCTION("TranslationContextPublish")
      .desc(
        "Drive TranslationContext every cycle from the committed satp, privilege, and mstatus " +
        "fields; dataPriv = MPRV ? MPP : priv. The view changes only when a committed CSR write " +
        "or trap/xRET application changes those fields."
      )
      .uses(intfTranslationContextOut)
      .build()
  }

  val funcCsrMapContribution = spec {
    FUNCTION("CsrMapContribution")
      .desc(
        "The Zicsr address map is the base machine and supervisor map plus each enabled " +
        "extension's Map[Int, Csr] contribution, merged into the one CsrAccess.readFromCsr " +
        "call this vertex owns. Contributions add addresses, never a second write path."
      )
      .build()
  }

  val propNoSpeculativeCsrWrite = spec {
    PROPERTY("NoSpeculativeCsrWrite")
      .desc("An architectural CSR register write-enable asserts only in the cycle CommitGrant names the writing uop's robTag.")
      .uses(intfCommitGrantIn)
      .note("ADR-019D E-4: simulation assertion on the committed-state write enables.")
      .build()
  }

  val propCsrWriteIntent = spec {
    PROPERTY("CsrWriteIntent")
      .desc(
        "Every accepted CSR IssuedUop has sysOp == CsrWrite exactly when its encoding writes: " +
        "CSRRW/CSRRWI always; CSRRS/CSRRC iff the encoded rs1 != x0; CSRRSI/CSRRCI iff " +
        "uimm != 0. The rs1 = x0 / uimm = 0 forms are read-only (sysOp None)."
      )
      .uses(intfCsrReqIn)
      .note("ADR-019D E-3: simulation assertion on the accepted request.")
      .build()
  }

  val propCsrSingleOwner = spec {
    PROPERTY("CsrSingleOwner")
      .desc(
        "Committed CSR state, privilege, and debug mode change only through a software write " +
        "applied by the matching CommitGrant or through CSRTrapWrite.fire; the two never occur " +
        "in the same cycle in v0 (no retiring transition produces both)."
      )
      .uses(intfCommitGrantIn, intfCsrTrapWriteIn)
      .note("ADR-019D E-7: structural (one register set, two enables) plus a simulation assertion.")
      .build()
  }
}
