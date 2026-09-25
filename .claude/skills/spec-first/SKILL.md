---
name: spec-first
description: Use BEFORE any change under src/main/scala on UDACore - adding/modifying a vertex, interface, parameter, or behavior; making an architectural decision; or reviewing spec conformance. Encodes the binding discipline: ADRs are law, specs precede design, spec-check is the gate, ready/valid is the default transfer protocol, rawTop is wiring-only, and only sanctioned rawNoDecoupled facts/statics bypass handshakes. For ADR-019 OoO work, selective recovery is RecoveryEvent/ROB-age based, not epoch-only. Trigger on "add a module/vertex", "change an interface", "write a spec", "new parameter", "is this UDA-compliant", "architectural decision".
---

# UDACore spec-first discipline

This branch's order of authority: **ADRs (document/adr/, read ADR-000-index.md first; for new OoO work read ADR-019 immediately after it) >
specs (src/main/scala/udacore/**/spec/) > design (design/)**. Design code implements specs;
specs implement ADRs. Never edit design/ ahead of its spec.

## The Spec-TDD loop (ADR-018) - the shape of any design change

spec -> test -> RED -> test review -> implement -> GREEN, hierarchically:

1. Spec (with ADR if architectural).
2. Test bound BY NAME to the new FUNCTION/PROPERTY vals, at the lowest level that can
   observe them: L0 elaboration require/assert (ADR-015) | L1 vertex SpecTest
   (`verif.spectest`, `verifies = Seq("funcX")`) | L2 `.scn` with `@verifies funcX` |
   L3 config-equivalence experiment. Runner: `verif/bin/run.sh verif.spectest.RunSpecTests`.
3. RED: observe the test not-passing BEFORE implementing - FAIL (impl exists, is wrong)
   or PENDING (spec-shell DUT). A first-run PASS against a shell is vacuous: reject it.
4. Test review (adversarial, before implementation): would a plausible WRONG impl pass?
   does every check trace to a spec sentence? does it test the contract, not one legal
   retiming? Fix the test or the spec, never pre-shape the implementation.
5. Implement; observe GREEN; remove the retired entries from tools/spec-test-allow.txt
   (and spec-check-allow.txt when the paired assert lands).
6. A deterministic, reset-applied, isolation-reproduced failure of an owner-protected
   unit is a FINDING, not a fix: tag the checks `known = Some("UDA-Fn")` (XFAIL,
   non-gating; an XPASS after a fix forces tag cleanup) and file it in HANDOFF's owner
   questions. Lesson encoded from UDA-F1: always reset in the testbench (`sim()` does
   it) and never claim an RTL bug from a nondeterministic failure set.

## Workflow for any design change
1. Is there an architectural decision involved (new invariant, boundary, ownership,
   protocol)? Then it needs an ADR entry first: add `document/adr/ADR-0NN-<slug>.md`
   (Context / Decision D-NN.x / Consequences, RFC-2119 language) + a row in
   `ADR-000-index.md`. Owner-level calls (project identity, PPA bars, protected-file
   waivers) go to the "Open questions needing the OWNER" list in document/HANDOFF.md
   instead of being decided by an agent.
2. Write/update the spec: `<domain>/spec/...Specs.scala` mirroring the design path.
   Ordering: CONTRACT -> INTERFACEs -> FUNCTIONs -> others. One file = one CONTRACT
   (sub-cores inside a vertex are RAW("...","subcore"), per the Divider/BranchPredictor subcore ruling).
3. Implement in design/ with `@LocalSpec(<specVal>)` on the class, each IO field, and each
   spec'd behavior val. No stubs, no `DontCare`, no TODO comments - documented placeholders
   only (`val x = ???` with the contract in the doc comment is the shell convention).
4. Gate: `bash verif/bin/build.sh` (0 errors) and `python3 tools/spec-check.py` (0 errors;
   the pre-commit hook also runs it). A new PROPERTY must either ship with its paired
   `@LocalSpec(prop) val ... = assert/require(...)` in the design, or be added to
   `tools/spec-check-allow.txt` (interim gap, removed when the assert lands - ADR-015).
   A new FUNCTION/PROPERTY must also carry a test binding (SpecTest `verifies` / `.scn`
   `@verifies`) or a `tools/spec-test-allow.txt` entry (ADR-018 check 6).

## UDA edge rules (what reviews check)
- Every vertex-to-vertex interface is a ready/valid edge: `.is(rawReadyValidIntf)`.
- `.is(rawNoDecoupled)` ONLY for the sanctioned classes
  (DesignRuleSpecs.rawNoDecoupled): (1) local transaction-generation tags of uncancelable
  request/response pairs, (2) async inputs (interrupt, debugReq), (3) boot statics
  (bootAddr, hartEn), (4) commit-time strobes and committed-state views, (5) wakeup
  broadcast, and (6) ADR-019 speculative RecoveryEvent broadcast. Broadcast FACT vs queued TRANSFER remains the
  test: token-moving projections stay ready/valid.
- No ad-hoc flush/kill/squash side-channels. Under ADR-019, branch recovery is selective:
  the common RecoveryEvent identifies the recovery point and each speculative holder
  locally invalidates only younger entries using the shared wrap-aware order rule.
  Global epoch equality is not the branch-ordering mechanism. External IP kill ports
  remain forbidden unless a later ADR explicitly owns them.
- Stall = ready backpressure on an edge. Never a dedicated stall wire.
- rawTop specs/modules: vertex instantiation + `:<>=` wiring only; the mermaid in the
  CONTRACT must reconcile edge-for-edge with the child INTERFACE union (spec-check
  graph-consistency enforces the drawn-boundary version).
- State-holding vertices declare their recovery stance (rawSpeculativeHolder). Uncancelable
  transaction state may use a local generation tag (propGenerationTagScope); program-order
  speculative state must
  declare selective-recovery behavior (ordering key, younger-than rule, resource reclaim)
  or committed/architectural exemption.
- Extensions (ADR-017): optional vertices + data contributions only - decode rows
  (Seq[InstPattern], absent-when-disabled so instructions trap) and CSR map entries
  (Map[Int, Csr] into CsrAccess.readFromCsr). Never Feature-style host-signal weaving.
- Widths derive from parameters (XLEN 32|64); nothing hard-codes 32 (ADR-016 D-16.2).

## Spec DSL quick reference
Categories: CONTRACT/INTERFACE/FUNCTION/PROPERTY/PARAMETER/CAPABILITY/BUNDLE/COVERAGE/RAW.
Relationships: `.has` = composition (a contract HAS its interfaces/functions/properties),
`.uses` = external dependency (params, bundles, shared props), `.is` = classification.
Full guide: AGENTS.md; naming: `<cat-prefix><Module><Feature>` (cont/intf/func/prop/param/
bnd/cap), module classes PascalCase with acronyms as words (Alu, Csr).

## Protected files (do not modify without owner permission)
- `src/main/scala/udacore/backend/design/modules/csr/CSR.scala` (marked AGENT: DO NOT
  TOUCH; pending OQ-E sign-off)
- `src/test/scala/cluster/*`, `src/test/scala/assembler/*`, `src/main/scala/assembler/*`
- `verif/` engine core may be extended, but the Gate discipline must never be weakened.


## ADR-019 OoO spec overlay

When the requested work touches the new OoO frontend/backend/MMU/cache architecture,
also load `.claude/skills/ooo-spec-author/SKILL.md` and follow
`document/architecture-team/07-ooo-v0-spec-work-order.md`.

Do not copy the old RVC/BranchPredecoder, ROB-less retirement, commit-head branch
redirect, or universal epoch-kill contracts into new specs. They are superseded where
ADR-019 says so.
