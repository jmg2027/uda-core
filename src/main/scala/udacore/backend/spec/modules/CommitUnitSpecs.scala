package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._

object CommitUnitSpecs {
  val contCommitUnit = spec {
    CONTRACT("CommitUnit")
      .desc(
        "Commit unit tracks in-order retirement, updates map table, frees old physical registers."
      )
      .has(
        intfDecodedUopAllocIn,
        intfCommitResultIn,
        intfMapTableUpdateOut,
        intfPhysicalRegFreeOut,
        intfExceptionOut,
        intfInterruptCtrlIn,
        intfArchMapRestoreOut,
        intfCommitBroadcastOut,
        intfRetireStreamOut,
        funcArchMapMaintain,
        funcCompletionScoreboard,
        funcInterruptSampling,
        propCommitInOrder,
        propRetireNonBlocking,
        propFreeListConservation
      )
      .note("Tracks instruction completion in program order")
      .note("On commit: updates architectural map table in rename unit")
      .note("Frees old physical register to free list")
      .note("No data writeback - mapping update only")
      .note("CommitUnit is the single owner of the architectural map and the unified commit broadcast (ADR-002 D-2.2, ADR-012 D-12.4).")
      .build()
  }

  val intfDecodedUopAllocIn = spec {
    INTERFACE("DecodedUopAllocIn")
      .desc("Allocation bookkeeping input.")
      .uses(bndDecodedUopAlloc)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCommitResultIn = spec {
    INTERFACE("CommitResultIn")
      .desc("Commit result input.")
      .uses(bndCommitResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMapTableUpdateOut = spec {
    INTERFACE("MapTableUpdateOut")
      .desc("Map table update output to rename unit.")
      .uses(bndMapTableUpdate)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPhysicalRegFreeOut = spec {
    INTERFACE("PhysicalRegFreeOut")
      .desc("Physical register free output to rename unit.")
      .uses(bndPhysicalRegFree)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfExceptionOut = spec {
    INTERFACE("ExceptionOut")
      .desc("Exception notification output.")
      .uses(bndException)
      .is(rawReadyValidIntf)
      .build()
  }

  // ADR-004 D-4.5: interrupt control view sampled at the commit boundary.
  val intfInterruptCtrlIn = spec {
    INTERFACE("InterruptCtrlIn")
      .desc("Interrupt pending/enable control view from the CSR, sampled at a retire boundary.")
      .uses(bndInterruptCtrl)
      .is(rawNoDecoupled)
      .build()
  }

  // ADR-001 D-1.1: CommitUnit is the source of recovery truth.
  val intfArchMapRestoreOut = spec {
    INTERFACE("ArchMapRestoreOut")
      .desc("Architectural (retirement) map snapshot broadcast to RenameUnit for same-cycle recovery.")
      .uses(bndArchMapSnapshot)
      .is(rawNoDecoupled)
      .note("Rides combinationally with the epoch; 0-stage forever (ADR-006 commit/redirect path).")
      .build()
  }

  // ADR-012 D-12.4: the single unified commit broadcast, driven by CommitUnit.
  val intfCommitBroadcastOut = spec {
    INTERFACE("CommitBroadcastOut")
      .desc("The single in-order commit event, fanned to all consumers as field-projected views.")
      .uses(bndCommitBroadcast)
      .note("Drives StoreCommit -> StoreBuffer, commitGrant -> CSR, map-update+free -> Rename, retireTrigger -> retire stream (ADR-012 D-12.4).")
      .is(rawReadyValidIntf)
      .build()
  }

  // ADR-010 D-10.1/D-10.3: verification retire stream, gated by usingRvvi.
  val intfRetireStreamOut = spec {
    INTERFACE("RetireStreamOut")
      .desc("One verification retire token per in-order retirement (ADR-010 D-10.1).")
      .uses(bndRetireToken)
      .is(rawReadyValidIntf)
      .note("Gated by the usingRvvi elaboration knob (WP-D CoreParams, default false); the port, the commit-time wdata PRF read, and the 64b order counter all fold away when false (ADR-010 D-10.3).")
      .note("Observation-only: ready tied high in the harness, commit progress independent of it (ADR-010 D-10.2).")
      .build()
  }

  // ADR-002 D-2.2: CommitUnit maintains the architectural map in program order.
  val funcArchMapMaintain = spec {
    FUNCTION("ArchMapMaintain")
      .desc("Maintain the architectural (retirement) map: on head retire, point the retiring arch reg at its new prd and free the old prd.")
      .note("The architectural map is the ADR-001 recovery source of truth; updated only at the alloc-FIFO head (ADR-002 D-2.2/D-2.4).")
      .uses(intfDecodedUopAllocIn, intfCommitResultIn, intfArchMapRestoreOut)
      .build()
  }

  // ADR-002 D-2.4: architectural state changes only at the FIFO head, in order.
  val propCommitInOrder = spec {
    PROPERTY("CommitInOrder")
      .desc("Architectural map updates, physical-register frees, CSR side effects, and store visibility occur only at the alloc-FIFO head, in FIFO order.")
      .note("Precise exceptions follow: the head is the precise architectural point (ADR-002 D-2.4). Pair with a design assert.")
      .uses(intfDecodedUopAllocIn, intfCommitResultIn, intfExceptionOut)
      .build()
  }

  // ADR-002 D-2.3: the only per-uop state commit needs.
  val funcCompletionScoreboard = spec {
    FUNCTION("CompletionScoreboard")
      .desc("Per-in-flight-uop done bit set by PublishResult, cleared at retire; head retires when done and epoch-matched.")
      .note("Size = SpeculativeRegNum (N); degenerates to a single valid bit at N=1 (ADR-002 D-2.3).")
      .uses(paramSpeculativeRegNum)
      .build()
  }

  // ADR-004 D-4.5: interrupts join program order at the commit boundary.
  val funcInterruptSampling = spec {
    FUNCTION("InterruptSampling")
      .desc("Sample enabled+pending interrupts at a retire boundary with no synchronous exception and not in debug mode; emit Exception{source=Interrupt, cause, pc=next-uncommitted PC}.")
      .note("Consumed only at the head, so epoch===globalEpoch holds by construction; interrupts never alias across the epoch wrap (ADR-004 D-4.5).")
      .uses(intfInterruptCtrlIn, intfCommitResultIn, intfExceptionOut)
      .build()
  }

  // ADR-010 D-10.2: the retire port never stalls commit.
  val propRetireNonBlocking = spec {
    PROPERTY("RetireNonBlocking")
      .desc("The retire stream is observation-only: whenever the retire token is valid its sink is always ready, so commit progress never depends on the retire port draining.")
      .note("Pair with a design assert (ADR-010 D-10.2, ADR-015 D-15.3).")
      .uses(intfRetireStreamOut)
      .build()
  }

  // ADR-001 D-1.1/D-1.2: CommitUnit is the sole owner of the free list, so the
  // architectural map image and the free mask must partition the PRF exactly.
  val propFreeListConservation = spec {
    PROPERTY("FreeListConservation")
      .desc(
        "After any epoch-change (recovery) cycle, the union of the restored architectural map image (the physical registers named by mapPhys) and the free mask covers every physical register in the PRF exactly once: no physical register is both mapped and free (no double-free) and none is neither mapped nor free (no leak)."
      )
      .note(
        "CommitUnit is the single free-list owner: the architectural map is the ADR-001 recovery source of truth, restored combinationally with the epoch on redirect (ADR-001 D-1.1/D-1.2, ADR-012 D-12.4)."
      )
      .note(
        "Verification obligation: pair with a design assert evaluated on the recovery cycle that the mapPhys image and freeMask are disjoint and jointly cover the 33+N PRF slots. At N=1 the map/free partition is the trivial single-slot case."
      )
      .uses(intfArchMapRestoreOut, intfPhysicalRegFreeOut)
      .build()
  }
}
