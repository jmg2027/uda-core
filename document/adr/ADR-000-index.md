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
- **superseded/amended** - retained as historical rationale, but a later ADR is
  authoritative for new work.

## The current root ruling

For new implementation work, **ADR-019: Conventional OoO Root Architecture** is
the root ruling. It supersedes the earlier commit-head/global-epoch substrate
where explicitly stated: the new machine has an explicit data-less ROB,
PC-indexed BTB+TAGE+RAS frontend, execute-time selective branch recovery,
VIPT L1 caches, and Sv32 ITLB/DTLB/PTW.

ADR-011 remains historical rationale for why the old single-global-epoch machine
required commit-head redirect. Do not apply that constraint to ADR-019 work.

ADR-019 is amended by **ADR-019A (v0 Erratum 01)**: RS allocation only for uops that need
execution, an explicit `sysOp` in the decoded uop, RobStatus timing, the domain of
`funcRobOlder`, and same-cycle commit/ArchRedirect ordering. The `242feaf` freeze is the
baseline; ADR-019A is applied on top of it. **ADR-019B (v0 Erratum 02)** follows it:
RecoveryCause.Debug, WFI as a serializing NOP, the usingRvvi commit PRF read path, the
executing-privilege meaning of RetireToken.priv, trap-entry observation tokens, and atomic
retiring redirects.

### ADR-019 spec migration record (Work Order 07)

- The position papers and critiques these ADRs were synthesized from
  (`document/architecture-team/01..06-*.md`, `critique-*.md`, `work-orders.md`) described
  the superseded machine and were deleted in the clean-break migration; recover them from
  git history when reading an older ADR's rationale.
- ADR-015 D-15.4/D-15.5 are applied without the N axis, which ADR-019 removed with ADR-008:
  the retire-stream soundness rule now governs ISA-model equivalence
  (`DesignRuleSpecs.propIsaRetireEquivalence`), and the ConfigRegistry names one-axis
  variants of the v0 reference point instead of n1/n8/n32.
- ADR-015 D-15.2 machine check 5 is re-based on ADR-019 D-19.10: a Reg/Queue site must
  state its recovery stance (RecoveryEvent, transaction generation, or recovery-exempt);
  `tools/spec-check.py` check `reg-queue-recovery` replaces `reg-queue-epoch`.
- ADR-004 serialization is re-based on the ROB: a serializing uop is renamed only into an
  empty ROB and blocks younger rename until it retires; TrapController remains the single
  trap-CSR writer and is now the only commit-head ArchRedirect producer.
- Review round 1 (spec-level refinements, no new ADR ruling needed):
  - Trap hand-off protocol: an exception/interrupt hand-off locks the ROB head and
    CommitUnit holds until the ArchRedirect naming that robTag (propTrapHoldUntilRedirect).
  - RAS recovery is a full-contents snapshot per FTQ entry so propHistoryRestoreExact holds
    under wrong-path wrap; a write log is a later area optimization.
  - The ISA identity is RV32IM_Zicsr_Zifencei + Svade (A/D faults, no hardware A/D update),
    M/S/U, Sv32. ADR-019's "RV32IM" is read as this precise string.
  - Uncacheable loads and stores are executed at the ROB head under a HeadMemGrant, before
    and separate from commit: the LSQ performs the single bus access (stores through a
    dedicated uncached D-cache port, never the StoreBuffer; loads through the matching
    physical UncachedLoadReq port) and completes the uop, which then
    retires through the ordinary atomic commit or traps precisely. Cacheable PMA regions are
    fill/writeback-fault-free by platform contract (resolves the former OQ-G).
  - Debug requests are sampled by the CommitUnit at a precise retire boundary, like
    interrupts, and handed to the TrapController as Exception{Debug}.
  - Rename checkpoints are freed at branch resolution (CheckpointRelease) or right after
    the recovering restore, not at commit; FTQ history checkpoints still live to commit.
  - PTW refills are global when any PTE on the walk has G = 1.

## Index

| ADR | Title | Status | Settles (paper / critique) |
|-----|-------|--------|----------------------------|
| [001](ADR-001-rename-recovery.md) | Rename recovery on redirect | accepted / **amended by 019** | P01 s1; crit-correctness B1.2 |
| [002](ADR-002-commit-retirement.md) | Commit and retirement without a ROB | **superseded by 019** | P01 s2; P06 |
| [003](ADR-003-store-lifecycle-load-ordering.md) | Store lifecycle, load ordering, disambiguation | accepted / **amended by 019** | P02 M1/M2; crit B2, BL-1 |
| [004](ADR-004-csr-serialization.md) | CSR serialization and single-owner trap | accepted | P04 s1-5; crit M2 |
| [005](ADR-005-epoch-wrap-policy.md) | Epoch wrap policy | **superseded for program-order speculation by 019** | P05 D2; crit M1, BL-2 |
| [006](ADR-006-redirect-distribution-timing.md) | Redirect / epoch distribution timing | **amended by 019** | P05 D3; crit MI-1 |
| [007](ADR-007-ipc-critical-edge-registry.md) | IPC-critical edge registry | accepted concept / **budgets superseded by 019** | P05 D1; crit MA-2, V-MA-3 |
| [008](ADR-008-n1-degeneracy.md) | N=1 degeneracy criteria and PPA bar | **superseded by 019** | P01 s5, P05 D5; crit BL-3, MA-4 |
| [009](ADR-009-frontend-graph-reconciliation.md) | Frontend graph reconciliation | **superseded by 019** | P03 s1-5 |
| [010](ADR-010-commit-stream-verification-port.md) | Commit-stream verification port | accepted | P06 s1 |
| [011](ADR-011-redirect-generation-point.md) | Redirect generation point (historical root) | **superseded by 019 for branch recovery** | crit B1, BL-1 |
| [012](ADR-012-canonical-tag-and-commit-broadcast.md) | Canonical in-flight tag and unified commit broadcast | accepted concept / **horizon amended by 019** | crit B3, M4, MA-3, V-MA-5 |
| [013](ADR-013-redirect-merge-priority.md) | Redirect merge priority ladder | **amended by 019** | crit M3 |
| [014](ADR-014-publish-bus-width.md) | Publish bus width and peak-IPC contract | accepted / **proposed** | P01 O4; crit MA-1 |
| [015](ADR-015-spec-enforcement-and-nequiv.md) | Spec enforcement, interim checker, N-equivalence soundness | accepted | P06 s3-5; crit V-BL-1/2/3 |
| [016](ADR-016-udacore-identity-tilelink-boundary.md) | UDACore identity, XLEN-parametric datapath, TileLink boundary | accepted / **amended by 019** | owner directive 2026-07-06; amends ADR-003 D-3.14 |
| [017](ADR-017-extension-contribution.md) | Extension contribution convention (Feature pattern re-based on UDA) | accepted | owner directive 2026-07-06; imports main xxxFeature lessons |
| [018](ADR-018-spec-tdd.md) | Spec-TDD: every spec object bound to a test, red before green | accepted | owner directive 2026-07-06; extends ADR-015 enforcement |
| [019](ADR-019-conventional-ooo-root-architecture.md) | Conventional OoO root architecture | **accepted (current root)** | owner directive 2026-09-25; supersedes epoch/predecode/ROB-less base where stated |
| [019A](ADR-019A-v0-erratum-01.md) | ADR-019 v0 erratum 01 (RS allocation set, sysOp, recovery ordering) | **accepted** | owner ruling 2026-09-25; amends 019 on top of the 242feaf freeze |
| [019B](ADR-019B-v0-erratum-02.md) | ADR-019 v0 erratum 02 (commit, debug, retire semantics) | **accepted** | owner ruling 2026-09-25; amends 019 and ADR-010 on top of 242feaf + 019A |

## Status of pre-ADR-019 proposed experiments

The old N-sweep / multi-context-epoch experiments are no longer gates for the
current architecture. ADR-019 accepted explicit ROB ordering and selective
execute-time recovery directly.

Still relevant as later performance work:

1. **Publish/commit width (ADR-014).** V0 may remain single-lane. A later
   superscalar-width ADR must re-derive PRF ports, wakeup bandwidth, retire
   width, and predictor/fetch bandwidth from measured need.
2. **Critical recurrence timing (ADR-007 concept).** Keep a named recurrence
   registry, but re-measure budgets for BTB/TAGE next-PC, wakeup/select,
   load-use, and execute-time recovery in the ADR-019 pipeline. Old N-specific
   budgets are not binding.
3. **Memory dependence prediction.** ADR-019 ships conservative unknown-store
   disambiguation. A later predictor/speculative-bypass ADR requires measured
   need and an explicit memory-order recovery contract.

## Consolidated open questions carried to the panel

- OQ-A (ADR-011): the branch/mem-order IPC number that would justify multi-context
  epochs. Needs the ported IPC harness.
- OQ-B (ADR-014): if multi-lane publish is ever adopted, the PRF write-port count
  and wakeup-CAM lane width must be re-derived; retire width becomes f(N)
  (P01 O2) and enters the area-vs-N sweep.
- OQ-C (ADR-008): +10% area envelope vs a harder "within parity" bar - an
  owner/project-identity call, not an engineering one.
- OQ-D (ADR-003) - DECIDED by the owner 2026-09-25: v0 is single-hart, non-coherent, with no
  DMA or coherent agent; no load-load ordering check and no memory-order replay.
  Original text: the exact single-hart memory-consistency statement (assumed
  RVWMO, sequentially-consistent-observable) - confirm no coherent second agent
  is ever in scope, because forwarding-only is insufficient if it is.
- OQ-E (ADR-004) - WAIVED by the owner 2026-09-25 under the ADR-019 clean-break
  authority; CSR.scala is rewritten with the RTL. Original text: waive `AGENT: DO NOT TOUCH` on `CSRCore` to delete the
  internal exception/mret writers (`csr/CSR.scala:405-408, 494-524`). Owner
  sign-off is the load-bearing approval; the rest is spec.
- OQ-F (ADR-015): Spike vs Sail as primary ISS golden (recommend Spike now,
  Sail periodic); the regression corpus seed (port main `verif/scn/**` verbatim).
