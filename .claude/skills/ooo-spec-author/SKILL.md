---
name: ooo-spec-author
description: Author or migrate UDACore specifications for the ADR-019 conventional OoO architecture. Use for frontend TAGE/BTB/RAS/FTQ specs, ROB/rename/recovery specs, LSQ, VIPT caches, MMU/TLB/PTW, or any request to translate the new OoO architecture into the Scala spec framework. This skill is specification-first: do not implement RTL until the DSL contracts and red/PENDING tests exist.
---

# UDACore OoO v0 spec author

## Authority

Read in this order:

1. `document/adr/ADR-019-conventional-ooo-root-architecture.md`
2. `document/architecture-team/07-ooo-v0-spec-work-order.md`
3. `AGENTS.md`
4. `.claude/skills/spec-first/SKILL.md`
5. ADR-015 and ADR-018.

ADR-019 overrides older architecture specs where it explicitly conflicts.

## Mission

Write the architecture as Scala DSL objects under
`src/main/scala/udacore/**/spec/`. The design tree is not the source of truth.
Do not preserve stale structures just because a shell file already exists.

For this phase, stop before new OoO RTL implementation unless the owner
explicitly asks for implementation.

## Architecture anchors

- RV32IM, fixed 32-bit instructions, no RVC.
- Conventional frontend: BTB + TAGE + RAS + FTQ + fetch buffer.
- Prediction is PC-indexed and precedes same-block instruction fetch.
- ITLB/DTLB + shared Sv32 PTW.
- VIPT I$/D$; v0 16 KiB, 4-way, 64-byte line.
- Explicit data-less ROB.
- sRAT/rRAT/free list + branch checkpoints.
- RS + LSQ; OoO execution, in-order retirement.
- Execute-time selective branch recovery; older work survives.
- RecoveryEvent is the common selective-squash identity.
- Global epoch is not the branch-recovery ordering mechanism.
- D-cache v0 may be non-coherent; coherence is a separate option.
- TileLink core boundary remains.

## DSL discipline

One file owns one CONTRACT.

Order objects:

1. CONTRACT
2. INTERFACEs
3. FUNCTIONs
4. PROPERTYs
5. local PARAMETER/COVERAGE/RAW objects if needed

Use shared BUNDLE/PARAMETER objects from `spec/shared` instead of re-declaring
fields in multiple module specs.

Every INTERFACE must say:
- producer/consumer;
- payload bundle;
- whether it backpressures;
- ready/valid vs rawNoDecoupled;
- ordering/recovery semantics.

Every state-holding speculative CONTRACT must say:
- how entries are ordered;
- what makes an entry live;
- how RecoveryEvent decides "younger";
- what state survives recovery;
- how resources are reclaimed.

Every MMU/cache CONTRACT must distinguish:
- virtual vs physical address fields;
- page fault vs access fault;
- architectural state vs microarchitectural performance state;
- canceled core uop vs uncancelable external transaction.

## Recovery doctrine

Do not introduce independent flush/kill wires.

The only normal selective branch-recovery fact is `RecoveryEvent`. Consumers
locally invalidate entries younger than its recovery point using the shared
wrap-aware order definition.

A ready/valid edge moves a token. A rawNoDecoupled recovery broadcast publishes
an already-made fact and is not backpressured.

## Predictor doctrine

TAGE predicts direction only.

BTB predicts control-flow presence/type/target. RAS predicts returns. If v0 has
no indirect-target predictor, state that JALR falls back to BTB target behavior
and that ITTAGE is future work.

FTQ owns predictor metadata/checkpoints needed to recover speculative GHR/RAS
and later train the predictor. Do not make instruction predecode the normal
same-block prediction path.

## Memory doctrine

LSQ owns speculative load/store ordering. StoreBuffer, if retained, owns only
committed stores waiting to drain.

Physical addresses are the correctness key for forwarding/order. Virtual
comparisons are optional early hints only.

A DTLB miss is a per-uop wait state, not a whole-core stall when independent
work/resources exist.

A wrong-path load may fill cache/TLB microarchitectural state; its architectural
result must be discarded. A wrong-path store must never become committed memory
state.

## Spec-TDD

For each new FUNCTION/PROPERTY:
- bind a red/PENDING test by exact spec val name when possible;
- otherwise add only that name to the appropriate interim allowlist;
- never regenerate an entire allowlist to make the gate green.

PROPERTY assertion text belongs in real design `@LocalSpec` assertions later,
not inside DSL `.code` strings.

## Before stopping

Run or require:
- Scala compile for the spec tree;
- `python3 tools/spec-check.py`;
- graph consistency for every changed rawTop;
- grep/review confirming no active v0 spec still relies on RVC,
  BranchPredecoder, commit-head branch redirect, universal epoch kill, or
  ROB-less retirement.

Report:
- specs created/rewritten;
- older contracts superseded/deleted;
- remaining allowlist debt;
- unanswered architecture questions.