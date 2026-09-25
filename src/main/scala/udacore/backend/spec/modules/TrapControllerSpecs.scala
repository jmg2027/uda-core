package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

/** TrapController: single owner of trap-CSR/privilege transitions and the only
  * producer of commit-head architectural redirects (ADR-004, ADR-019 D-19.9).
  */
object TrapControllerSpecs {
  val contTrapController = spec {
    CONTRACT("TrapController")
      .desc(
        "Consumes commit-head hand-offs (synchronous exceptions, interrupts, xRET, Refetch) " +
        "and the live trap-CSR snapshot, and produces exactly one CSRTrapWrite (when " +
        "architectural trap state changes) and exactly one ArchRedirect per hand-off. It " +
        "implements M/S/U trap routing with medeleg/mideleg delegation."
      )
      .has(
        intfExceptionIn,
        intfCsrTrapReadIn,
        intfCsrTrapWriteOut,
        intfArchRedirectOut,
        funcTrapSingleOwner,
        funcTrapDelegation,
        funcXRet,
        funcDebugCommitBoundary,
        propPreciseCommit,
        propTrapSingleWriter
      )
      .note(
        "Recovery stance: holds at most one hand-off, which always concerns the oldest uop; it " +
        "is never killed by a BranchMispredict."
      )
      .build()
  }

  val intfExceptionIn = spec {
    INTERFACE("ExceptionIn")
      .desc("Commit-head hand-offs from the CommitUnit.")
      .uses(bndException)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrTrapReadIn = spec {
    INTERFACE("CSRTrapReadIn")
      .desc("Live trap-CSR snapshot from the CsrController (combinational view).")
      .uses(bndCsrTrapRead)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4: a committed-state view read at the commit head.")
      .build()
  }

  val intfCsrTrapWriteOut = spec {
    INTERFACE("CSRTrapWriteOut")
      .desc("The single trap/return application packet to the CsrController.")
      .uses(bndCsrTrapWrite)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfArchRedirectOut = spec {
    INTERFACE("ArchRedirectOut")
      .desc("Architectural redirect request to the RecoveryController; fires together with CSRTrapWriteOut when both are needed.")
      .uses(bndArchRedirect)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcTrapSingleOwner = spec {
    FUNCTION("TrapSingleOwner")
      .desc(
        "For each hand-off produce the ArchRedirect target and, when trap state changes, the " +
        "CSRTrapWrite: Sync/Interrupt -> TrapEntryM or TrapEntryS (xepc = pc, xcause, xtval, " +
        "status push, privNext), target = xtvec base (direct) or base + 4 * cause (vectored " +
        "interrupts); XRet -> MRet/SRet; Refetch -> no CSR write, target = pc + 4."
      )
      .uses(intfExceptionIn, intfCsrTrapReadIn, intfCsrTrapWriteOut, intfArchRedirectOut)
      .note("ADR-004 D-4.3: the CSR node performs no trap encoding; this vertex is the only trap-CSR writer.")
      .build()
  }

  val funcTrapDelegation = spec {
    FUNCTION("TrapDelegation")
      .desc(
        "A synchronous exception with cause c taken in S or U mode is delegated to S-mode when " +
        "medeleg[c] is set; an interrupt is delegated when mideleg bit is set and the current " +
        "privilege is S or U. Traps taken in M-mode are never delegated. Delegated traps write " +
        "sepc/scause/stval, SPP, SPIE, clear SIE, and enter S-mode at stvec."
      )
      .uses(intfCsrTrapReadIn)
      .build()
  }

  val funcXRet = spec {
    FUNCTION("XRet")
      .desc(
        "MRET: priv := MPP, MIE := MPIE, MPIE := 1, MPP := U, and MPRV := 0 when leaving " +
        "M-mode; target = mepc. SRET: priv := SPP, SIE := SPIE, SPIE := 1, SPP := U, MPRV := 0; " +
        "target = sepc. Illegal xRET was already turned into an exception by decode."
      )
      .uses(intfCsrTrapWriteOut)
      .build()
  }

  val funcDebugCommitBoundary = spec {
    FUNCTION("DebugCommitBoundary")
      .desc(
        "A debug hand-off (Exception{Debug} from the CommitUnit, which alone samples the debug " +
        "request at a precise retire boundary) and trigger/step/ebreak effects qualified at " +
        "the commit head are emitted as CSRTrapWrite(DebugEntry) plus ArchRedirect through this " +
        "single owner. The TrapController itself never decides when a boundary is safe."
      )
      .uses(intfExceptionIn, intfCsrTrapWriteOut, intfArchRedirectOut)
      .note("ADR-004 D-4.7: TriggerUnit/DebugUnit remain pure-function leaf IP.")
      .build()
  }

  val propPreciseCommit = spec {
    PROPERTY("PreciseCommit")
      .desc(
        "At a trap on uop U every uop older than U has applied its architectural state and no " +
        "uop younger than U has applied any, so the trap observes exactly the state after U-1; " +
        "this holds for instruction, load, and store page faults."
      )
      .uses(intfExceptionIn, intfCsrTrapWriteOut)
      .note("Simulation monitor on the retire stream plus the ADR-019 precise page-fault directed cases.")
      .build()
  }

  val propTrapSingleWriter = spec {
    PROPERTY("TrapSingleWriter")
      .desc("Each trap CSR and the privilege register receive at most one trap-driven write per cycle, and every trap-driven write originates from CSRTrapWrite.fire.")
      .uses(intfCsrTrapWriteOut)
      .note("Structural single-writer argument plus a simulation monitor (ADR-015 D-15.3).")
      .build()
  }
}
