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
        "Services CSRRW/CSRRS/CSRRC(I) for M- and S-mode CSRs, applies the TrapController's " +
        "CSRTrapWrite verbatim, and publishes three committed-state views: the trap snapshot, " +
        "the pending-and-enabled interrupt view, and the translation context used by the MMU. " +
        "It computes no exception routing and no trap encoding (ADR-004)."
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
        funcCsrAccessCheck,
        funcSupervisorCsrs,
        funcTranslationContextPublish,
        funcCsrMapContribution,
        propNoSpeculativeCsrWrite
      )
      .uses(rawExtensionContribution)
      .note(
        "Recovery stance: a CSR uop is renamed only into an empty ROB (funcSerializeGate), so it " +
        "is never younger than a live branch and is never killed by a BranchMispredict; an " +
        "ArchRedirect can only follow its own commit."
      )
      .note(
        "The owner-protected csr/CSR.scala (OQ-E) still carries an epoch input and meta field " +
        "from the superseded machine; the ADR-019 contract does not use them."
      )
      .build()
  }

  val intfCsrReqIn = spec {
    INTERFACE("CSRReqIn")
      .desc("CSR operations from DispatchUnit.")
      .uses(bndCsrReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrResultOut = spec {
    INTERFACE("CSRResultOut")
      .desc("Old CSR value as the rd writeback (or an illegal-instruction exception) toward PublishMux.")
      .uses(bndCsrResult)
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
      .desc("Raw interrupt lines feeding mip.")
      .uses(bndInterrupt)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 2.")
      .build()
  }

  val intfInterruptCtrlOut = spec {
    INTERFACE("InterruptCtrlOut")
      .desc("Pending-and-enabled interrupt view to the CommitUnit.")
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
        "A CSR uop reads the architectural value when it executes (it is the only live uop) and " +
        "returns the old value as its result; its write is staged and applied only on the " +
        "CommitGrant for its robTag. Every committed CSR write is followed by a Refetch."
      )
      .uses(intfCsrReqIn, intfCsrResultOut, intfCommitGrantIn)
      .build()
  }

  val funcCsrAccessCheck = spec {
    FUNCTION("CsrAccessCheck")
      .desc(
        "An access to a CSR that does not exist, whose privilege field exceeds the current " +
        "privilege, that writes a read-only CSR, or that touches satp in S-mode with " +
        "mstatus.TVM set, completes with an illegal-instruction exception and no staged write."
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
      .note("Simulation assert, paired when the CSR body is rewritten (OQ-E).")
      .build()
  }
}
