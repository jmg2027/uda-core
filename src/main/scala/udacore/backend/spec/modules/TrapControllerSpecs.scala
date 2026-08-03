package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.core.spec.shared.CoreBundlesSpecs.bndDebugReq

object TrapControllerSpecs {
  val contTrapController = spec {
    CONTRACT("TrapController")
      .desc(
        "TrapController is the single owner of trap-CSR and privilege transitions. It consumes commit-detected exceptions and the live CSR snapshot and drives exactly one CSRTrapWrite and one Redirect per accepted trap or return."
      )
      .has(
        intfInterruptIn,
        intfDebugReqIn,
        intfCsrTrapReadIn,
        intfExceptionIn,
        intfCsrTrapWriteOut,
        intfRedirectOut,
        funcTrapSingleOwner,
        funcSerializingOps,
        funcDebugCommitBoundary,
        propPreciseCommit,
        propTrapSingleWriter
      )
      .note(
        "ADR-004 D-4.3: CommitUnit detects the trap at the in-order commit head and emits Exception; TrapController encodes the trap and is the SOLE producer of trap-CSR writes and privilege transitions."
      )
      .build()
  }

  val intfInterruptIn = spec {
    INTERFACE("InterruptIn")
      .desc("Interrupt summary input.")
      .uses(bndInterrupt)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDebugReqIn = spec {
    INTERFACE("DebugReqIn")
      .desc("Debug request signal input.")
      .uses(bndDebugReq)
      .note("Debug entry is emitted as a CSRTrapWrite(kind=DebugEntry) plus Redirect through the single trap-write owner (ADR-004 D-4.7).")
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrTrapReadIn = spec {
    INTERFACE("CSRTrapReadIn")
      .desc("Live CSR control snapshot input used to encode the trap application packet.")
      .uses(bndCsrTrapRead)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfExceptionIn = spec {
    INTERFACE("ExceptionIn")
      .desc("Exception notification input from CommitUnit (synchronous or interrupt source).")
      .uses(bndException)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrTrapWriteOut = spec {
    INTERFACE("CSRTrapWriteOut")
      .desc("The single trap/return application packet emitted to the CSR controller.")
      .uses(bndCsrTrapWrite)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRedirectOut = spec {
    INTERFACE("RedirectOut")
      .desc("Redirect request output accompanying every trap entry, return, and debug entry.")
      .uses(bndRedirect)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcTrapSingleOwner = spec {
    FUNCTION("TrapSingleOwner")
      .desc(
        "TrapController is the sole producer of trap-CSR and privilege transitions. It consumes Exception plus CSRTrapRead and produces one CSRTrapWrite and one Redirect per accepted trap or return, covering TrapEntry, MRet, DRet, and DebugEntry."
      )
      .uses(intfExceptionIn, intfCsrTrapReadIn, intfCsrTrapWriteOut, intfRedirectOut)
      .note(
        "ADR-004 D-4.3/D-4.5: the CSR node performs no trap encoding and computes no exception signal. mret/dret privilege restore and debug entry are also TrapController-emitted CSRTrapWrite+Redirect pairs."
      )
      .markdownTable(
        List("kind", "redirect target"),
        List(
          List("TrapEntry", "mtvec.base<<2 (direct) or +cause<<2 (vectored)"),
          List("MRet", "mepc"),
          List("DRet", "dpc"),
          List("DebugEntry", "debug handler / park PC")
        )
      )
      .build()
  }

  val funcSerializingOps = spec {
    FUNCTION("SerializingOps")
      .desc(
        "mret, dret, fence, fence.i, and wfi retire one at a time at the commit head; fence and fence.i gate retire on the memory subsystem StoreBuffer.empty predicate; fence.i and mret/dret emit a Redirect."
      )
      .uses(intfExceptionIn, intfRedirectOut)
      .note(
        "ADR-004 D-4.6: fence.i target is pc+4 after older stores drain, forcing a refetch. wfi parks the commit head, mcycle continues, waking on any mip & mie or debug request; interrupts are masked in debug mode. The StoreBuffer.empty drain predicate is a memory-subsystem backpressure condition (WP-B)."
      )
      .build()
  }

  val funcDebugCommitBoundary = spec {
    FUNCTION("DebugCommitBoundary")
      .desc(
        "Trigger, step, and ebreak fire is qualified by the commit head; debug entry is emitted as a CSRTrapWrite(kind=DebugEntry) plus a Redirect through the single trap-write owner."
      )
      .uses(intfDebugReqIn, intfCsrTrapReadIn, intfCsrTrapWriteOut, intfRedirectOut)
      .note(
        "ADR-004 D-4.7: TriggerUnit/DebugUnit remain pure-function leaf IP; only their effect is commit-gated, keeping dpc/dcsr/priv under one-writer discipline."
      )
      .build()
  }

  val propPreciseCommit = spec {
    PROPERTY("PreciseCommit")
      .desc(
        "At a trap on uop U every uop older than U has applied architectural state and no uop younger than U has applied any, so the trap observes exactly the state after U-1."
      )
      .uses(intfExceptionIn, intfCsrTrapWriteOut)
      .note(
        "ADR-004 C4.1: guaranteed by commit-gated CSR writes, drain-at-commit stores, and commit-time map update. Verified by a simulation monitor on commit ordering per ADR-015 D-15.3, paired with an @LocalSpec design assert."
      )
      .build()
  }

  val propTrapSingleWriter = spec {
    PROPERTY("TrapSingleWriter")
      .desc(
        "Each trap CSR has at most one write-enable per cycle and any trap-driven enable originates from CSRTrapWrite.fire; the deleted CSR-internal trap path does not exist."
      )
      .uses(intfCsrTrapWriteOut)
      .note(
        "ADR-004 D-4.4 / ADR-015 D-15.3: a STRUCTURAL single-writer argument (machine check 2 single-writer grep) plus a simulation monitor; not a formal proof. Paired with an @LocalSpec design assert."
      )
      .build()
  }
}
