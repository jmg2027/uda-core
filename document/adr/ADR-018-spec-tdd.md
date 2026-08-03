# ADR-018: Spec-TDD - Every Spec Object Is Bound to a Test, Red Before Green

Status: **accepted** (owner directive, 2026-07-06).

Depends on: ADR-015 (interim checker, PROPERTY-to-assert pairing), ADR-010 (retire
stream = the L2/L3 observation channel), ADR-017 (extension seams the tests pin).

## Context

The branch had one mechanical verification obligation: a PROPERTY must pair with an
elaboration-time `@LocalSpec` assert or be allowlisted (ADR-015 D-15.2). Nothing bound
spec objects to EXECUTABLE tests: the COVERAGE category was unused, `src/test` is stale
protected infra, and the verif engine's scenarios carried no record of WHICH contract
they exercise. So a FUNCTION could be "implemented" with no artifact that would have
failed beforehand, and a green suite could not answer "which spec claims are actually
tested?". The owner directed a TDD discipline where writing a spec creates a test
obligation, tests map to spec objects by name, and the loop is hierarchical:
spec -> test -> test review -> implement.

## Decision

**D-18.1 (binding).** Every FUNCTION and PROPERTY spec val MUST be bound to at least one
executable test artifact that names it, or be listed in `tools/spec-test-allow.txt` (a
KNOWN interim gap, one name per line, shrink-to-zero; same locking discipline as the
assert allowlist). Binding forms:
- Scala vertex test: a `verif.spectest.SpecTest(name, verifies = Seq("funcX", "propY"))`.
- Core scenario: a `.scn` file carrying `@verifies funcX propY`.
CONTRACT/INTERFACE/BUNDLE/PARAMETER objects are covered transitively by the tests of
their functions/properties plus the ADR-015 structural checks; they carry no direct
test obligation.

**D-18.2 (the test ladder).** Tests live at the lowest level that can observe the
contract, and a spec val is bound at the level where its truth is visible:
- **L0 - elaboration**: `require`/paired `@LocalSpec` asserts (ADR-015, unchanged).
  Width laws, legality requires, structural invariants.
- **L1 - vertex**: `verif.spectest` simulates ONE vertex (or the CSR/TileLink library)
  against the pure reference models (`verif.ref`). This is the default home of FUNCTION
  vals. Runner: `verif/bin/run.sh verif.spectest.RunSpecTests`.
- **L2 - core**: `.scn` scenarios on the real core via the harness (`@verifies` names
  the contract; the Gate discipline of the verif skill still owns bug promotion).
  Default home of cross-vertex FUNCTIONs and ISA-visible behavior.
- **L3 - config**: equivalence experiments across elaboration points (N=1 vs N=8
  retire-stream equivalence D-15.4, cache/TLB function-transparency, disabled-extension
  trap probes). Default home of *FunctionTransparent / *FoldsOoO class PROPERTYs.

**D-18.3 (red before green).** The TDD loop per contract, hierarchically: write/extend
the spec -> write the test that names it -> OBSERVE the test not-passing -> review ->
implement -> observe green -> delete the allowlist entries it retires. "Not-passing" has
two sanctioned colors: FAIL (implementation exists and is wrong) and PENDING (the DUT is
a spec shell - the runner converts `NotImplementedError`/harness-not-ready into PENDING).
A test that PASSES on first run against a shell is vacuous by definition and MUST be
rejected in review. The implementing commit's message references the red observation.

**D-18.4 (test review is a named step).** Before implementation starts, the test is
reviewed adversarially against three questions: (1) would a plausible WRONG
implementation pass it (too weak)? (2) does every check trace to a sentence in the spec
val it claims to verify (no invented behavior)? (3) does it test the contract, not the
implementation's internals (no overfitting to one legal implementation - UDA explicitly
permits re-timing)? Findings amend the test or the spec, never the implementation plan.

**D-18.5 (enforcement).** `tools/spec-check.py` gains check 6, `spec-test-coverage`
(ERROR): every FUNCTION/PROPERTY val name must appear in a `verifies` binding under
`verif/` (Scala string or `.scn` `@verifies` line) or in `tools/spec-test-allow.txt`.
`--update-test-allow` seeds the allowlist. The pre-commit hook already runs spec-check,
so an unbound new spec val cannot be committed.

## Consequences

- The verif engine grows a thin L1 layer (`verif/src/scala/verif/spectest/`) and the
  `.scn` grammar gains `@verifies`; both surface the bindings in their JSON so coverage
  is machine-readable end to end.
- First conforming examples ship with this ADR: Alu/Multiplier/Divider L1 tests (green
  today - they pin already-implemented units against `verif.ref`) and a CSR-library
  testbed (write protocol, legalize totality, counter, shadow). The RTL fill-in
  (HANDOFF next steps) now starts every vertex with its red L1 test.
- The existing allowlists become the work queue: `spec-check-allow.txt` tracks missing
  asserts (L0), `spec-test-allow.txt` missing tests (L1-L3). Both shrink toward zero;
  neither may grow silently.
