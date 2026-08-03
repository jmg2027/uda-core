# ADR-001: Rename Recovery on Redirect

Status: **accepted**.

Depends on: ADR-011 (redirect at commit head), ADR-002 (CommitUnit owns the
architectural map), ADR-012 (canonical tag).

## Context

The map-table + free-list rename is specified (`RenameUnitSpecs.scala:10-27`)
and commit frees the old physical register of committed uops
(`CommitUnitSpecs.scala:22-26`), but nothing reclaims the physical registers of
renamed-then-squashed uops or repairs the speculative map after a redirect. As
written, the first mispredict leaks free-list entries and leaves the rename map
pointing at physical registers that are never written. The deleted reference
cleared the whole map on `epochSwitch` - correct only because that map was a
VirtualGPR valid-bitmap, not a unified-PRF pointer map. Under a unified PRF the
map MUST be *restored to the architectural mapping*, not cleared
(`document/architecture-team/01-backend-ooo.md` s1.1).

The correctness critique (B1.2) established that bulk restore is exact ONLY if
no older-uncommitted uop exists at redirect time. ADR-011 guarantees exactly
that: redirect fires at the commit head, so every surviving in-flight rename is
younger wrong-path. This ADR is therefore sound precisely because ADR-011 holds.

## Decision

**D-1.1 (normative).** Rename recovery = **architectural-map overwrite of the
speculative map + free-list reconstruction from the retirement map**, completed
in the same cycle the global epoch changes. On epoch change:

1. The RenameUnit speculative map is overwritten by the architectural
   (retirement) map owned by CommitUnit (ADR-002).
2. The free list is reconstructed as the complement of the physical registers
   named by the retirement map plus the retirement free set (RAA "free in all
   error paths").
3. In-flight speculative uops self-filter by epoch; no per-uop unwind walk is
   required for correctness. Free reclamation is bulk, derived from the restored
   map.

CommitUnit is the single owner of the architectural map and the single source of
recovery truth.

**D-1.2 (normative).** Recovery MUST complete in the same cycle the epoch
changes so the next-fetch rename sees a consistent map. The snapshot bus is a
non-Decoupled combinational broadcast (it rides with the epoch, per ADR-006's
commit/redirect path which is 0-stage forever).

Spec to write (`RenameUnitSpecs.scala`, `CommitUnitSpecs.scala`,
`BackendBundlesSpecs.scala`) - see work-orders WP-A:

```scala
val intfArchMapRestoreIn = spec {
  INTERFACE("ArchMapRestoreIn")
    .desc("Architectural (retirement) map snapshot from CommitUnit, applied to the speculative map on redirect.")
    .uses(bndArchMapSnapshot).is(rawNoDecoupled)
    .note("Consumed only in the cycle the global epoch changes; ignored otherwise.")
    .build()
}
val funcRenameRecovery = spec {
  FUNCTION("RenameRecovery")
    .desc("On global-epoch change, overwrite the speculative map with the architectural map and reconstruct the free list as its complement plus the retirement free set.")
    .note("No per-uop rollback walk: squashed uops self-filter by epoch (ADR-011); free reclamation is bulk.")
    .note("Recovery completes in the same cycle the epoch changes.")
    .uses(intfArchMapRestoreIn, paramSpeculativeRegNum).build()
}
val bndArchMapSnapshot = spec {
  BUNDLE("ArchMapSnapshot")
    .desc("Full architectural register->physical map plus the free-set mask.")
    .note("Fields: mapPhys[RegNum] (physical id per arch reg, width physRegIdWidth), freeMask[33+N]")
    .build()
}
```

## Alternatives rejected

- **Per-branch checkpointing (RAT snapshots keyed by branch tag).** Rejected for
  the base: a single global epoch is kill-everything (ADR-011), one live redirect
  context at a time, so a checkpoint bank buys nothing and costs
  N x RegNum x physRegIdWidth flops. Forward-compatible: if ADR-011 D-11.4
  (multi-context epochs) is ever adopted, "keep K arch-map snapshots" reuses
  `bndArchMapSnapshot`.
- **Alloc-FIFO backward walk (unwind youngest-first).** Rejected as the primary
  mechanism: O(in-flight) cycles, violates same-cycle recovery, re-introduces the
  sequential unwind the epoch model exists to avoid. The alloc FIFO is retained
  for commit ordering (ADR-002), not recovery.
- **Clear the whole map (deleted-reference behavior).** Rejected: under unified
  PRF this discards architectural mappings and is functionally wrong.

## Consequences

- Recovery is exact and O(1) in time, at the cost of one RegNum-wide restore mux
  and a (33+N)-bit free-mask recompute, both combinational, both gated by the
  epoch-change pulse. At N=1 the speculative map always equals the architectural
  map, so the restore is a no-op and elaborates away (ADR-008).
- Couples tightly to ADR-002: CommitUnit MUST maintain the architectural map in
  program order at retirement.

## Verification obligations

- PROPERTY `propFreeListConservation` bound to a design assert: after any
  epoch-change cycle, `union(map image) + freeMask == all physical registers`
  (no leaked, no double-owned register). Runtime simulation monitor (not
  elaboration - it is a temporal fact; see ADR-015 on assertion typing).
- Directed test: rename K uops writing the same arch reg, fire a redirect
  mid-flight, assert the free-list count returns to its pre-burst value and the
  map equals the retirement map. Run at N=1, 2, 8, 32.
- Random test: interleave allocations and redirects; the free-list conservation
  monitor must never fire.
