# ADR-015: Spec Enforcement, Interim Checker, and N-Equivalence Soundness

Status: **accepted**.

Depends on: ADR-010 (retire stream), ADR-012 (config axis). Underpins the
enforcement clause of every other ADR.

## Context

The verifiability critique is devastating and correct: the spec DSL is a no-op
stub (`framework/specs/Spec.scala:5-22` returns `this`, `build():Unit`;
`SpecEmit.spec` is identity, so every spec val is Unit), the plugin is compiled
out (`build.sbt`), and therefore NOT ONE "fails elaboration / machine check /
fails the build" obligation in any paper can run today (V-BL-1). Assertions
written inside `.code("scala", "assert(...)")` emit nothing (V-BL-2). "SVA /
prove / provable" appears across papers with no formal flow (V-MA-2/6). And the
headline N-equivalence check is unsound under asynchronous interrupts (V-BL-3).
This ADR makes the enforcement real or honestly downgrades it.

## Decision

**D-15.1 (accepted, named owner + interim checker).** The interim spec checker is
a NAMED, OWNED, SCHEDULED deliverable with its own acceptance test, delivered
BEFORE any contract whose enforcement is "at elaboration/machine check" is treated
as enforced. It is a source-reflection ScalaTest pass over `*Specs.scala` and the
`@LocalSpec` annotation sites (works against the stub today; swaps to the plugin's
`spec.meta.dir` metadata later, SAME test names). Owner: the verification work
package (WP-D shared verif specs + the checker test). Until it lands, every
"enforced at elaboration" obligation across ADR-001..014 is DOWNGRADED to
"manual review" in status, so no one believes a rotting branch is guarded.

**D-15.2 (accepted, three machine checks).** (1) Graph consistency: each rawTop
CONTRACT's `.draw("mermaid", ...)` edges reconcile with the union of child
INTERFACE sets; recovered from `@LocalSpec` annotation sites (which name the spec
val symbol), NOT from the Unit-valued spec objects (critique V-MA-1). The
normative graphs MUST be placed inside `.draw("mermaid", ...)`, not markdown prose.
(2) PROPERTY-to-assertion binding: every PROPERTY ships as a PAIR - the PROPERTY
spec val AND a concrete `@LocalSpec(prop) val ... = assert/require(...)` in the
named design file (the `GlobalEpochUnit.scala:40-43` template) - OR is marked
manual via a recoverable convention (an in-tree allowlist file, NOT the discarded
`.status` field, critique V-MI-3). Assertion text inside `.code`/`.note` is BANNED
(it emits nothing). (3) `@LocalSpec` coverage: every INTERFACE/FUNCTION/CONTRACT
referenced by a CONTRACT `.has`/`.uses` is bound by at least one `@LocalSpec`, and
every `@LocalSpec` names a live spec val. Add two more checks (critique V-MI-1):
(4) rawTop bodies are `:<>=` wiring only; (5) every `Reg`/`Queue` design site is
`@LocalSpec`-tagged to a sanctioning BUNDLE/FUNCTION (and, per ADR-005, tagged
eager-filter or epoch-exempt if it holds an epoch).

**D-15.3 (accepted, assertion typing).** Every obligation is typed as exactly one
of {elaboration-require (structural/parameter only) | simulation-assert (runtime
monitor) | formal-property}. Free-list conservation "each cycle", commit-id
monotonicity, one-hot trap write are TEMPORAL -> simulation-assert, NOT
"elaboration assertion" (critique V-MA-2). The methodology is dynamic (chiseltest
+ ISS co-sim); there is NO formal tier unless one is later added with a named tool
and owner. Therefore "SVA / prove / provable" language across ADR-001/002/004 is
restated as simulation-coverage obligations with a stated stimulus that reaches
the state (critique V-MA-6). "Provable mutual exclusion" (ADR-004) is a
STRUCTURAL argument (single writer) checked by machine check 2's single-writer
grep, plus a simulation monitor - not a formal proof.

**D-15.4 (accepted, N-equivalence soundness).** The killer app - N=1 vs N=8
retire-stream equivalence - is made sound by BOTH: (a) the Rung-4 corpus is
restricted to DETERMINISTIC, interrupt-free programs, stated normatively; AND (b)
interrupt precision is tested by a SEPARATE directed suite (ADR-004) using
DETERMINISTIC interrupt injection keyed to retire `order` (fire after the Kth
COMMITTED instruction, not the Kth cycle), so both configs sample at the identical
architectural point. The compare set is `order/pc/rd/wdata/trap/cause`; `epoch` is
excluded (ADR-010 D-10.4, critique V-MI-6). This resolves V-BL-3: "identical
token-for-token, only cycles differ" holds for the deterministic corpus, and
async-interrupt precision is proven by the order-keyed directed suite instead.

**D-15.5 (accepted, ladder + config axis).** The five-rung ladder is normative:
(1) per-vertex contract tests, (2) epoch-transition tests, (3) credit/backpressure
`latencyInvariant` across {ilat,dlat} in {1,2,3}, (4) N=1-vs-N=8 differential
(D-15.4), (5) ISS golden (Spike primary, Sail periodic cross-check). The
`SpeculativeRegNum` config axis is a first-class enumerable dimension of a named
`ConfigRegistry` (`n1,n8,n32,n1_rvvi,n8_rvvi,verif`), one-axis-per-name, driving
`EmitCore`/`sta.sh`. `SpeculativeRegNum` and `usingRvvi` live in `CoreParams`
(tuning tier), pushed down to backend. A golden-model DETERMINISM contract
(interrupt timing per D-15.4, memory-response reordering per ADR-003 M4, OoO
completion) is stated so ISS-vs-DUT token comparison is well-defined (critique gap).

**D-15.6 (accepted, interim pre-commit gate).** Blocking, in order: (1) `sbt
compile` green at `useSpecPlugin=false`; (2) scalafmt + ASCII-only; (3) spec
parity + machine checks (interim source-reflection form); (4) elaboration-progress
ratchet (the set of modules that elaborate without `NotImplementedError` may only
grow); (5) vertex-test ratchet (a non-shell body requires its rung-1 test). Fix
`BackendParams.scala:38` `require(hartId > 0)` to `>= 0` as an immediate unblock.

## Alternatives rejected

- **Wait for the real plugin before enforcing anything.** Rejected: the branch is
  rotting now; checks 1-5 are static analysis runnable today over the stub.
- **Keep asserting inside `.code` strings.** Rejected: emits nothing; conflates
  "I typed assert()" with "the design asserts it." Banned by D-15.2.
- **Claim formal ("provable/SVA") with no formal flow.** Rejected: overstates
  strength by an order of magnitude; restated as simulation coverage (D-15.3).
- **N-equivalence over an unrestricted corpus.** Rejected: unsound under async
  interrupts; restricted + order-keyed injection (D-15.4).

## Consequences

- Every other ADR's "verification obligations" section is now typed per D-15.3;
  where a paper said "elaboration assertion" for a temporal fact, this ADR
  reclassifies it as a simulation monitor.
- The interim checker (D-15.1) is the single highest-leverage unassigned
  deliverable on the branch; it is assigned to WP-D here.

## Verification obligations (of this ADR itself)

- The interim checker ships with an acceptance test: a deliberately-broken spec
  (an orphan `@LocalSpec`, a mermaid edge with no child interface, a PROPERTY with
  no paired assert) MUST trip each of the five machine checks.
- The N=1 grep gate (ADR-008) ships with a deliberately-broken N=1 build that keeps
  a CAM and MUST trip.
- N-equivalence ships with a known-divergent pair (a seeded rename-recovery bug)
  that MUST be caught at the first divergent token.
