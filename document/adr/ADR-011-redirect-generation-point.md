# ADR-011: Redirect Generation Point (ROOT DECISION)

Status: **accepted** (commit-head redirect for the base machine) with a
**proposed** wide-OoO escape hatch (age-aware / multi-context squash).

Depends on: nothing (this is the root). Depended on by: ADR-001, ADR-002,
ADR-003, ADR-004, ADR-005, ADR-006, ADR-013.

## Context

The correctness critique (`critique-correctness.md` B1) and the PPA critique
(`critique-ppa-scaling.md` BL-1) independently identify the same unstated
contract as the root of five conflicts: *where and when is a redirect
generated?*

The machine has a single global epoch incremented by one `Bool`
(`GlobalEpochUnit.scala:22-33`) and a pure equality consumption predicate
`token.epoch === globalEpoch` (`docs/foundations/dataflow-execution-model.md`
sec 1). Equality cannot distinguish an older in-flight token from a younger one:
after any redirect both mismatch. Therefore a redirect is sound only if, at the
instant it fires, no older correct-path work is still in flight and speculative.
Three contracts break otherwise:

1. **Completion-scoreboard deadlock.** An older divide (~34 cycles) in flight
   when a younger branch resolves at execute would eager-filter itself on the
   epoch bump; its `PublishResult` never arrives; the commit head stalls forever
   (`critique-correctness.md` B1.1).
2. **Rename recovery erases correct renames.** Bulk restore to the architectural
   map (ADR-001) discards mappings of uncommitted-but-older uops if any exist at
   redirect time (`critique-correctness.md` B1.2).
3. **Older speculative store lost.** Epoch-kill invalidates every uncommitted
   store-buffer entry; an older not-yet-committed store killed by a younger
   branch vanishes (`critique-correctness.md` B1.3).

All three vanish iff nothing older than the redirect point is unretired, i.e.
the redirect is generated at the in-order commit head.

## Decision

**D-11.1 (accepted, normative).** Every architectural redirect - branch
mispredict, trap, interrupt, `mret`/`dret`, debug entry, `fence.i`, and (base)
memory-order violation - is **generated at the in-order commit head**. A branch
that resolves early in a functional unit does NOT redirect from the FU; it
carries its resolved direction/target as commit-pending payload and the redirect
token is emitted only when that uop becomes the commit head. This makes
kill-everything-on-epoch-bump correct by construction: at the redirect cycle all
older work has retired, all surviving in-flight work is younger wrong-path and
correctly dies on `epoch =/= globalEpoch`.

**D-11.2 (accepted, normative).** Because redirect is at the commit head, there
is exactly **one** redirect generation context at a time and **no selective
(branch-tag) squash** in the base machine. This answers P01 O1 and P01/P05 O1
"no." The recovery of ADR-001 (restore speculative map to the architectural map)
is therefore exact, not approximate.

**D-11.3 (accepted, normative).** The mispredict/redirect penalty is
**resolve-to-commit latency** (the time from FU resolution to that uop reaching
the commit head), NOT the resolve-to-fetch 1-2 cycles P05 D1 originally quoted.
At N=1 (whole backend legally one cycle, `dontcommit.md` 5-6) this is ~1 cycle
and matches the shipped in-order core. At N>1 it is bounded by the in-flight
window depth. ADR-007 re-prices `edgeRedirectToFetch` accordingly.

**D-11.4 (proposed).** IF the branch-and-memory-order IPC at N=8/16/32 with
commit-head redirect falls below the workload target, the wide-OoO configuration
MAY adopt **multi-context epochs** (per in-flight generation, e.g. a small ring
of live epoch tags with an age-ordered "kill younger-than-K" compare) to permit
execute-time selective squash and speculative load bypass (ADR-003 D-3.4). This
is a different correctness substrate and is deliberately deferred behind a
measurement, not adopted on assertion.

## Alternatives rejected

- **Execute-time redirect under a single global epoch (status quo assumption of
  P05's penalty table).** Rejected: not implementable safely - it kills older
  correct-path work (B1.1-B1.3). It is only sound with age-aware squash, which
  the base machine does not have.
- **Adopt multi-context epochs now (P02 M2 rule 4's implicit substrate).**
  Rejected for the base: it destroys the single-epoch simplicity that P05/P06
  rest on and taxes N=1 with tag width and distance-compare fan-out it does not
  need. Kept as the proposed wide-OoO extension (D-11.4).

## Consequences

- ADR-013's redirect-merge priority becomes trivially safe: trap and branch
  cannot both be the commit head in the same cycle, so at most one redirect
  target is selected per epoch increment.
- ADR-003's memory-order replay, in the base, is a commit-time redirect (full
  kill of younger work), not a selective per-tag replay. The "older work
  untouched" language of P02 M2 rule 4 is struck for the base.
- P05's OoO IPC story is re-framed: the window hides FU latency and memory
  latency (MLP), and buys nothing for branch throughput until D-11.4 lands. This
  is the honest cost of a single global epoch and MUST be stated in the N-sweep
  report (ADR-008).
- The frontend indirect-jump stall (ADR-009 G4) plus commit-head redirect means
  a function return costs ~window-depth fetch bubble; a BTB/RAS becomes the
  IPC lever, not optional at high N (`critique-correctness.md` m2). Recorded, not
  blocking.

## Verification obligations

- PROPERTY `propRedirectAtCommit` bound to a design assert
  (`@LocalSpec`): every `redirectFire` originates from the commit head; no FU
  emits a redirect token directly.
- Directed test (the B1.1 scenario): `div x1,..; 20 indep ops; beq mispredict`.
  Assert the divide retires (no deadlock) and the mispredict recovers.
- N-equivalence (ADR-015): the same program retires identically at N=1 and N=8;
  a redirect-timing bug manifests as an N-dependent divergence.

## Experiment (settles D-11.4)

Once the IPC harness is ported (ADR-015), run the branch-heavy and
memory-aliasing microbenchmarks at N in {1,8,16,32} with commit-head redirect.
Report branch-mispredict penalty in cycles and resulting IPC. Decision rule: if
IPC at N=32 is within the workload target of the projected multi-context-epoch
ceiling, keep commit-head redirect permanently and close D-11.4 as rejected.
Otherwise open the multi-context-epoch design (new ADR) with the measured gap as
justification.
