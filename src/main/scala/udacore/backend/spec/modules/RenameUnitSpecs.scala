package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._

object RenameUnitSpecs {
  val contRenameUnit = spec {
    CONTRACT("RenameUnit")
      .desc(
        "Rename unit maintains map table and free list, assigns physical registers."
      )
      .has(
        intfDecodedUopIn,
        intfRenamedUopOut,
        intfDecodedUopAllocOut,
        intfMapTableUpdateIn,
        intfPhysicalRegFreeIn,
        intfArchMapRestoreIn,
        funcRenameRecovery
      )
      .note("Map Table: architectural register -> physical register mapping")
      .note("Free List: pool of available physical registers for allocation")
      .note("Allocates physical register for destination on rename")
      .note("Receives map updates and freed registers from CommitUnit")
      .build()
  }

  // ADR-001 D-1.2: architectural-map snapshot bus, rides with the epoch.
  val intfArchMapRestoreIn = spec {
    INTERFACE("ArchMapRestoreIn")
      .desc(
        "Architectural (retirement) map snapshot from CommitUnit, applied to the speculative map on redirect."
      )
      .uses(bndArchMapSnapshot)
      .is(rawNoDecoupled)
      .note("Consumed only in the cycle the global epoch changes; ignored otherwise (ADR-001 D-1.2).")
      .build()
  }

  // ADR-001 D-1.1: same-cycle bulk recovery.
  val funcRenameRecovery = spec {
    FUNCTION("RenameRecovery")
      .desc(
        "On global-epoch change, overwrite the speculative map with the architectural map and reconstruct the free list as its complement plus the retirement free set."
      )
      .note("No per-uop rollback walk: squashed uops self-filter by epoch (ADR-011); free reclamation is bulk.")
      .note("Recovery completes in the same cycle the epoch changes (ADR-001 D-1.1).")
      .uses(intfArchMapRestoreIn, paramSpeculativeRegNum)
      .build()
  }

  // ADR-001 / ADR-008: N=1 degeneracy - the restore mux elaborates away.
  val propN1MapDegenerate = spec {
    PROPERTY("N1MapDegenerate")
      .desc(
        "At N=1 the speculative map always equals the architectural map, so rename recovery is a no-op and the restore mux elaborates away."
      )
      .note("Pair with a design assert (ADR-015 D-15.3); a surviving multi-entry map / restore mux at N=1 is an ADR-008 forbidden structure.")
      .uses(paramSpeculativeRegNum)
      .build()
  }

  val intfDecodedUopIn = spec {
    INTERFACE("DecodedUopIn")
      .desc("Decoded uop input.")
      .uses(bndDecodedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRenamedUopOut = spec {
    INTERFACE("RenamedUopOut")
      .desc("Renamed uop output.")
      .uses(bndRenamedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDecodedUopAllocOut = spec {
    INTERFACE("DecodedUopAllocOut")
      .desc("Allocation bookkeeping output to commit unit.")
      .uses(bndDecodedUopAlloc)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMapTableUpdateIn = spec {
    INTERFACE("MapTableUpdateIn")
      .desc("Map table update input from commit unit.")
      .uses(bndMapTableUpdate)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPhysicalRegFreeIn = spec {
    INTERFACE("PhysicalRegFreeIn")
      .desc("Physical register free input from commit unit.")
      .uses(bndPhysicalRegFree)
      .is(rawReadyValidIntf)
      .build()
  }
}
