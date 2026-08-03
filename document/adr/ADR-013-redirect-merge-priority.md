# ADR-013: Redirect Merge Priority Ladder

Status: **accepted**.

Depends on: ADR-011 (commit-head redirect makes this trivially safe), ADR-004
(trap producers), ADR-003 (memory-order producer, proposed).

## Context

The correctness critique (M3) found that redirect producers now include branch
mispredict (P01), trap + interrupt + `mret`/`dret` + debug entry (P04, all via
TrapController), and memory-order (P02, proposed). The frontend accepts exactly
one redirect edge (ADR-009 D-9.3). RedirectUnit is the declared merge
(`RedirectUnitSpecs.scala:22-34`) but no priority is specified, and the BackendTop
mermaid does not even wire the branch-mispredict producer into `ru` (only
`trap -- Redirect --> ru`, `BackendTopSpecs.scala:117`). `GlobalEpochUnit`
increments on a single `redirectFire` Bool: if two producers assert the same
cycle, the epoch bumps once while the target mux is unresolved -> wrong target,
single generation consumed.

## Decision

**D-13.1 (normative, priority ladder).** RedirectUnit merges producers in strict
priority: **trap/interrupt (incl. mret/dret/debug entry) > branch mispredict >
memory-order violation**. Exactly one redirect target is selected per epoch
increment.

**D-13.2 (normative, safe by construction under ADR-011).** Because every redirect
is generated at the commit head (ADR-011) and commit retires one uop at a time
(ADR-002, base retire width 1, ADR-014), at most ONE redirect producer can be the
commit head in any cycle. The priority ladder is therefore a tie-break that, in
the base machine, never actually ties: trap and branch cannot both be the head the
same cycle. The ladder exists for defense-in-depth and for the proposed
superscalar-retire (ADR-014) and multi-context (ADR-011 D-11.4) extensions where
multiple producers could coincide.

**D-13.3 (normative, wire the graph).** The branch-mispredict producer MUST be
wired into `ru` in the BackendTop mermaid and `.has` list (today missing). The
memory-order producer (store-buffer -> RedirectUnit) is added only when
`MemDisambig=true` (ADR-003 D-3.11, proposed); in the base build it elaborates
away, so RedirectUnit has two producers (trap, branch) at base.

**D-13.4 (normative, one increment per redirect).** `GlobalEpochUnit` increments
by exactly one per accepted redirect; the target latched is the winner of the
priority ladder. PROPERTY: at most one redirect target selected per epoch
increment; no double-increment; no target-mux race.

Spec: rewrite `RedirectUnitSpecs.scala` (priority ladder FUNCTION + PROPERTY),
update `BackendTopSpecs.scala` mermaid and `.has` - WP-A. `GlobalEpochUnit`
single-increment PROPERTY - WP-D.

## Alternatives rejected

- **Leave priority unspecified (status quo).** Rejected: a same-cycle two-producer
  assert fetches the wrong target and consumes one generation - a silent
  correctness bug (M3).
- **Round-robin / fair merge.** Rejected: traps must strictly preempt branches for
  precise exceptions; fairness is meaningless for redirect.

## Consequences

- Under the base machine the ladder is provably non-ties (D-13.2), so it costs a
  small static priority mux and nothing dynamic.
- The graph edit (D-13.3) is a prerequisite for the ADR-015 graph-consistency
  machine check to pass on BackendTop.

## Verification obligations

- PROPERTY `propSingleRedirectPerEpoch` (design assert): per epoch increment, at
  most one redirect target is latched.
- Graph-consistency (ADR-015 check 1): BackendTop mermaid wires branch-mispredict
  and trap into `ru`, matching RedirectUnit `.has`.
- Directed test (M3 scenario): construct a case where trap and branch would both
  be candidates; assert the trap wins and the target/epoch are consistent. Under
  ADR-011 this requires the proposed superscalar-retire build to actually exercise
  a tie; in the base build assert the tie cannot arise.
