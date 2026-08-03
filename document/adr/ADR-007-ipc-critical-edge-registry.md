# ADR-007: IPC-Critical Edge Registry

Status: **accepted** (the registry and enforcement mechanism) with a **proposed**
final value for `edgeWakeupSelect` at N=32 (pending STA).

Depends on: ADR-011 (re-prices `edgeRedirectToFetch`), ADR-014 (publish lanes),
ADR-006 (epoch distribution stage), ADR-015 (post-PD reach).

## Context

`dontcommit.md` says pipelining is a post-PD retiming act that "should not change
function" - true and irrelevant to IPC. No artifact records, per edge, whether a
register costs IPC. A future timing pass could register the wakeup->select loop
and silently halve OoO IPC with no spec flag (review rec 5). The verifiability
critique (V-MA-3) further established that a Chisel-elaboration `require` cannot
observe a post-PD retiming pass, so the registry needs a second, netlist-level
guard.

## Decision

**D-7.1 (normative, classification).** Every Decoupled edge is ELASTIC (default,
any number of registers post-PD, frequency-only) or IPC-CRITICAL (added latency
costs measurable IPC, carries a per-configuration cycle budget `B(N)`; exceeding
it is a contract violation).

**D-7.2 (normative, the registry).** The complete IPC-critical list; anything not
here is ELASTIC. Budgets re-priced per ADR-011 (commit-head redirect) and ADR-014
(single publish lane base):

| id | edge | B(1) | B(8) | B(32) |
|----|------|------|------|-------|
| `edgeWakeupSelect` | PublishMux wakeup(prd) -> RS match -> select/grant | 0 | 0 | **0 (proposed, see D-7.4)** |
| `edgePublishToWakeup` | FU result -> PublishMux -> wakeup broadcast | 0 | 0 | 1 |
| `edgePrfReadToExec` | RS issue -> PRF read -> FU operand | 0 | 1 | 1 |
| `edgeRedirectToFetch` | resolve@commit -> GlobalEpoch -> fetch req | 1 | 2 | 2 |
| `edgeLoadUse` | AGU -> mem -> load data -> bypass -> dependent | 1 | 1 | 2 |
| `edgePredictToFetch` | predecoder prediction -> NextPcGen -> fetch | 0 | 0 | 0 |

`edgePredictToFetch` is added per critique MI-3 / P03 Q3.1: the predict->fetch
loop is IPC-critical; base config collapses it to a same-cycle wire (fetch in
cycle 0, `dontcommit.md`), pipelined only post-PD under budget. `edgeRedirectToFetch`
is now resolve-to-commit-to-fetch (ADR-011 D-11.3); the numbers hold because the
resolve-to-commit portion is not a registered-edge property but a window-drain
latency counted separately in the N-sweep.

**D-7.3 (normative, enforcement, two layers).** (a) Pre-PD structural stages:
every IPC-critical edge is constructed through a helper `ipcCriticalEdge(gen,
stages, budget, id)` whose `require(stages <= budget)` fails elaboration if an
engineer raises the explicit `stages` knob over budget. `stages` defaults 0. (b)
Post-PD retiming (critique V-MA-3): a netlist/STA artifact reports registered
depth on each named IPC-critical edge AFTER synthesis, plus an issue-to-issue
latency regression; this lives in the ported harness (ADR-015) and is co-owned
with the verification package. The elaboration `require` is explicitly scoped to
pre-PD structural stages ONLY; the post-PD guard is the STA artifact. "MUST fail
elaboration" is corrected to "MUST fail elaboration (pre-PD) AND MUST be flagged
by the post-PD STA registered-depth report."

**D-7.4 (proposed, `edgeWakeupSelect` at N=32).** Interim value 0 at all N. The
final N=32 value is CONDITIONAL on the STA experiment (ADR-008 step 4): publish
`maxWindowAtFreq(F)`. If the 32-entry x lane wakeup CAM + 32-wide select + 65-entry
PRF read + ALU cone cannot close at the target Fmax with 0 stages, the ruling is
**reduce the max window or physically bank the PRF**, NOT register this edge. The
edge budget stays 0; the window parameter absorbs the constraint. Reconcile the
loop partition with ADR-014: with a single publish lane (`edgePublishToWakeup`
B(32)=1), back-to-back dependent issue at N=32 is 1 per 2 cycles unless the
window hides it; this is the MLP-only framing of ADR-014, not a bug.

## Alternatives rejected

- **Comment/convention only.** Rejected: a budget that is not enforced is
  decoration (review "no teeth").
- **A single global max-pipeline-depth knob.** Rejected: cannot express
  `edgeWakeupSelect`=0-forever while `edgePrfReadToExec` may grow.
- **Register `edgeWakeupSelect` to close N=32 timing.** Rejected: it is the single
  hard OoO invariant; a register here forces a bubble between every producer and
  dependent. Cap the window instead (D-7.4).

## Consequences

- N=1: zero; all budgets that matter are 0 or match the shipped in-order core.
- N=32: zero area; budgets permit the retiming a timing pass wants and forbid the
  retiming that wrecks IPC. The `edgeWakeupSelect`=0 constraint may bound the
  buildable max window (D-7.4), which the sweep quantifies.

## Verification obligations

- Elaboration: `require` per edge; a unit test sets `edgeWakeupSelect.stages=1`
  and asserts elaboration throws.
- Post-PD STA: registered-depth report per named edge; an over-budget depth is a
  build-flagging failure (co-owned with ADR-015).
- IPC regression: a dependent-chain microbench measures issue-to-issue latency of
  a dependent pair; assert 1 cycle at every N where the budget says 0.

## Experiment (settles D-7.4)

The wakeup-select STA at N=8/32 (ADR-008 step 4): report the worst path through
`edgeWakeupSelect`; confirm it closes at the target period with 0 stages, or the
panel accepts a smaller max window / banked PRF. Until it runs, "0 forever at
N=32" is `proposed`, not verified.
