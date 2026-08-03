# ADR-006: Redirect / Epoch Distribution Timing

Status: **accepted**.

Depends on: ADR-005 (eager filtering), ADR-011 (commit-head redirect), ADR-007
(edge budget registry).

## Context

`GlobalEpochUnit` exposes epoch same-cycle
(`epochOut := Mux(redirectFire, epochIncrement, epoch)`,
`GlobalEpochUnit.scala:35-38`; spec `funcSameCycleEpochExposure`,
`GlobalEpochUnitSpecs.scala:70-79`). Combined with eager filtering (ADR-005),
`globalEpoch` becomes a combinational net fanning out to every RS entry, buffer,
and FU latch, on top of the redirect-target fan-out that is already a top timing
problem on the shipped core (`document/frontend_redirect_critical_path.md`,
`document/whole_core_ppa_findings.md`). The review predicts the first synthesis
run hits a wall here.

## Decision

**D-6.1 (normative, commit/redirect path = 0 stages, forever).** The epoch value
consumed by (a) the commit gate that permits architectural state change and (b)
the redirect generator MUST be the combinational same-cycle value. No register.
If commit saw a stale epoch it could retire wrong-path work. This includes the
ADR-001 arch-map restore broadcast and the ADR-002 commitGrant/commit broadcast.

**D-6.2 (normative, speculative-consumer path = registerable, budgeted).** The
epoch copy fed to purely-speculative consumers that CANNOT commit architectural
state (RS entries, PRF read, FU operand latches, fetch-queue filtering) MAY be
registered - distributed one cycle late - IFF those consumers only ever DROP work
on mismatch and never COMMIT on match. Registering costs one cycle of wrong-path
work kept alive (harmless, self-drops one cycle later) and adds one stage to
`edgeRedirectToFetch`:

- N=1: 0 stages (combinational). The graph is small; `edgeRedirectToFetch` = 1
  total, matching the shipped core.
- N>=8: 1 registered stage permitted on the speculative copy; `edgeRedirectToFetch`
  = 2. This buys the frequency the large fan-out needs, at +1-cycle mispredict
  penalty, priced as `mispredictRate x 1 cycle` (low single-digit percent on
  BpBench per `document/ipc_freq_winwin_findings.md`).

**D-6.3 (normative, guardrail interaction).** Frontend guardrails (ADR-009 G1:
redirect wins same-cycle and stamps the post-increment epoch on NextPc) depend on
the commit/redirect path (D-6.1), which is combinational, so they are safe. A
consumer of the REGISTERED speculative copy (D-6.2) MUST NOT emit a token whose
epoch is then treated as authoritative for commit; NextPc epoch stamping uses the
combinational post-increment value. Add the assertion of critique MI-1.

## Alternatives rejected

- **Always combinational (today).** Rejected at N>=8: the documented timing wall;
  eager filtering multiplies the endpoint count.
- **Always registered.** Rejected: taxes N=1 with a mispredict cycle it does not
  need, and risks registering the COMMIT epoch, which is a correctness bug.

## Consequences

- Ties directly to ADR-007: the speculative-copy stage counts against
  `edgeRedirectToFetch`'s budget and is enforced by the same elaboration
  `require`.
- The mispredict-penalty split (0 at N=1, up to 1 extra at N>=8) is the honest
  per-config trade the N-sweep (ADR-008) must confirm with STA fan-out numbers.

## Verification obligations

- Elaboration `require(commitEpochStages == 0)` and
  `require(specEpochStages <= redirectToFetchBudget(N) - 1)` (structural).
- Runtime assert `commitFire -> committedEpoch === globalEpoch_comb`.
- Runtime assert (critique MI-1): on a redirect cycle, emitted `NextPc.epoch`
  equals the combinational post-increment epoch, even if the speculative copy is
  registered.
- STA experiment (ADR-008 flow): report the epoch net fan-out and worst slack at
  N=1/8/32; confirm N=1 combinational closes at the in-order target and N=32 needs
  the registered stage.

## Open question carried

Whether trap/interrupt redirect shares the branch redirect distribution path or
gets its own (traps are rarer, may tolerate an extra cycle). Deferred to the STA
experiment; default is shared (one distribution network).
