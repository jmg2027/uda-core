# ADR-000: Architecture Decision Record Index

UDACore rebuild core, branch `claude/rebuild-architecture-review-jg0grl`.
These ADRs are the chief architect's binding rulings synthesized from the six
position papers (`document/architecture-team/01..06-*.md`) and the three
adversarial critiques (`critique-correctness.md`, `critique-ppa-scaling.md`,
`critique-verifiability.md`).

Normative language: MUST / MUST NOT / SHOULD / MAY per RFC-2119.

Status legend:
- **accepted** - binding contract; write the spec now, fill shell RTL against it.
- **proposed** - a real conflict the critics exposed that a measurement, not an
  argument, must settle. The ADR states the experiment and the interim default
  that ships until the experiment returns.

## The current root ruling

For new implementation work, **ADR-019: Conventional OoO Root Architecture** is
the root ruling. It supersedes the earlier commit-head/global-epoch substrate
where explicitly stated: the new machine has an explicit data-less ROB,
PC-indexed BTB+TAGE+RAS frontend, execute-time selective branch recovery,
VIPT L1 caches, and Sv32 ITLB/DTLB/PTW.

ADR-011 remains historical rationale for why the old single-global-epoch machine
required commit-head redirect. Do not apply that constraint to ADR-019 work.

## Index

| ADR | Title | Status | Settles (paper / critique) |
|-----|-------|--------|----------------------------|
| [001](ADR-001-rename-recovery.md) | Rename recovery on redirect | accepted | P01 s1; crit-correctness B1.2 |
| [002](ADR-002-commit-retirement.md) | Commit and retirement without a ROB | **superseded by 019** | P01 s2; P06 |
| [003](ADR-003-store-lifecycle-load-ordering.md) | Store lifecycle, load ordering, disambiguation | accepted / **proposed** | P02 M1/M2; crit B2, BL-1 |
| [004](ADR-004-csr-serialization.md) | CSR serialization and single-owner trap | accepted | P04 s1-5; crit M2 |
| [005](ADR-005-epoch-wrap-policy.md) | Epoch wrap policy | accepted | P05 D2; crit M1, BL-2 |
| [006](ADR-006-redirect-distribution-timing.md) | Redirect / epoch distribution timing | accepted | P05 D3; crit MI-1 |
| [007](ADR-007-ipc-critical-edge-registry.md) | IPC-critical edge registry | accepted / **proposed** | P05 D1; crit MA-2, V-MA-3 |
| [008](ADR-008-n1-degeneracy.md) | N=1 degeneracy criteria and PPA bar | accepted / **proposed** | P01 s5, P05 D5; crit BL-3, MA-4 |
| [009](ADR-009-frontend-graph-reconciliation.md) | Frontend graph reconciliation | **superseded by 019** | P03 s1-5 |
| [010](ADR-010-commit-stream-verification-port.md) | Commit-stream verification port | accepted | P06 s1 |
| [011](ADR-011-redirect-generation-point.md) | Redirect generation point (historical root) | **superseded by 019 for branch recovery** | crit B1, BL-1 |
| [012](ADR-012-canonical-tag-and-commit-broadcast.md) | Canonical in-flight tag and unified commit broadcast | accepted | crit B3, M4, MA-3, V-MA-5 |
| [013](ADR-013-redirect-merge-priority.md) | Redirect merge priority ladder | accepted | crit M3 |
| [014](ADR-014-publish-bus-width.md) | Publish bus width and peak-IPC contract | accepted / **proposed** | P01 O4; crit MA-1 |
| [015](ADR-015-spec-enforcement-and-nequiv.md) | Spec enforcement, interim checker, N-equivalence soundness | accepted | P06 s3-5; crit V-BL-1/2/3 |
| [016](ADR-016-udacore-identity-tilelink-boundary.md) | UDACore identity, XLEN-parametric datapath, TileLink boundary | accepted / **amended by 019** | owner directive 2026-07-06; amends ADR-003 D-3.14 |
| [017](ADR-017-extension-contribution.md) | Extension contribution convention (Feature pattern re-based on UDA) | accepted | owner directive 2026-07-06; imports main xxxFeature lessons |
| [018](ADR-018-spec-tdd.md) | Spec-TDD: every spec object bound to a test, red before green | accepted | owner directive 2026-07-06; extends ADR-015 enforcement |
| [019](ADR-019-conventional-ooo-root-architecture.md) | Conventional OoO root architecture | **accepted (current root)** | owner directive 2026-09-25; supersedes epoch/predecode/ROB-less base where stated |

## Proposed-status items (experiments that must settle them)

1. **ADR-003 / ADR-011 - speculative memory disambiguation and age-aware squash.**
   Base ships conservative (a load MUST NOT bypass an older store of unknown
   address; branch/memory-order redirect at commit head, global kill-everything).
   The proposed wide-OoO extension (per-tag / multi-context epoch enabling
   execute-time selective squash and speculative load bypass) is gated on the
   experiment in ADR-011 s"Experiment": measure branch-and-memory-order IPC at
   N=8/16/32 with commit-head redirect; if it falls below the workload target,
   adopt multi-context epochs and re-price tag width, epoch width, and the
   distribution fan-out (ADR-005/006).
2. **ADR-007 - `edgeWakeupSelect` budget = 0 at N=32.** Interim: 0 at all N.
   Final value is conditional on the STA experiment (ADR-008 s"Experiment" step 4):
   publish `maxWindowAtFreq(F)`. If N=32 cannot close the wakeup->select cone at
   the target Fmax, the ruling is "cap the window / bank the PRF," NOT "register
   the loop."
3. **ADR-008 - N=1 rename-map degeneracy.** Interim: the N=1 netlist MAY carry a
   degenerate direct-indexed map + single free bit (the +10% DFF envelope covers
   it); the "zero pointer map" clause of P05 D5 is relaxed to "zero CAM / zero
   multi-entry map / zero free-list encoder." The harder "literal in-place
   writeback at N=1" (a second commit path) is rejected unless the N=1 PPA bar
   fails, which the sweep decides.
4. **ADR-014 - publish/commit lane count.** Interim: single arbitrated result
   bus, `peakIssue = peakPublish = peakRetire = 1` at every N; N=32 is an
   MLP/latency-hiding machine, not IPC>1. Multi-lane is a proposed extension
   gated on a measured need and a re-derivation of PRF ports and CAM width.

## Consolidated open questions carried to the panel

- OQ-A (ADR-011): the branch/mem-order IPC number that would justify multi-context
  epochs. Needs the ported IPC harness.
- OQ-B (ADR-014): if multi-lane publish is ever adopted, the PRF write-port count
  and wakeup-CAM lane width must be re-derived; retire width becomes f(N)
  (P01 O2) and enters the area-vs-N sweep.
- OQ-C (ADR-008): +10% area envelope vs a harder "within parity" bar - an
  owner/project-identity call, not an engineering one.
- OQ-D (ADR-003): the exact single-hart memory-consistency statement (assumed
  RVWMO, sequentially-consistent-observable) - confirm no coherent second agent
  is ever in scope, because forwarding-only is insufficient if it is.
- OQ-E (ADR-004): waive `AGENT: DO NOT TOUCH` on `CSRCore` to delete the
  internal exception/mret writers (`csr/CSR.scala:405-408, 494-524`). Owner
  sign-off is the load-bearing approval; the rest is spec.
- OQ-F (ADR-015): Spike vs Sail as primary ISS golden (recommend Spike now,
  Sail periodic); the regression corpus seed (port main `verif/scn/**` verbatim).
