# Adversarial Critique: Verifiability and Spec Quality

Reviewer lens: verifiability and spec quality. Mandate: find every normative
claim with no assertion/test obligation, every contract that cannot be
machine-checked, and every place the six papers drift from the UDA philosophy or
from the stub spec-DSL they must be expressible in. Read against all six papers
(01..06), the DSL stub, and the tree. This critique is deliberately disjoint
from `critique-ppa-scaling.md`: where that reviewer owns "is the cost/scaling
real," I own "can any of this be checked, and does the spec-DSL actually carry
it." Overlap is cross-referenced, not re-litigated.

Grounding facts re-verified in the repo for this critique:

- The spec DSL is a no-op stub. `framework/specs/Spec.scala:5-22`: every builder
  method (`desc/is/has/uses/status/entry/table/markdownTable/draw/code/note`)
  returns `this` and discards its argument; `build()` returns `Unit`.
- `framework/macros/SpecEmit.scala:4`: `def spec[T](body: => T): T = body`. So
  `val intfX = spec { INTERFACE(...)...build() }` has static type **Unit**. Every
  spec val is Unit; `.has(intfX)` passes a Unit through an `Any*` parameter.
- The plugin is compiled out. `build.sbt:6` `useSpecPlugin` defaults false;
  `build.sbt:12` "SpecPlugin removed for open-source compilation"; the real
  `spec-core/spec-macros/spec-plugin` are `your.company` placeholder coordinates
  (`build.sbt:24-27,62-65`) absent from this environment.
- A real binding pattern DOES exist once, as the template everyone cites:
  `GlobalEpochUnit.scala:40-43`, `@LocalSpec(propEpochToggle) val epochToggle =
  when(redirectFire){ assert(epochIncrement =/= epoch, ...) }`. That is a
  simulation assert (chisel `assert`), not a formal proof. 20 such asserts exist
  in the whole design tree today.

Severity key: BLOCKER = a verification claim the project's identity rests on that
cannot be executed or is unsound as written; must be resolved before shell RTL.
MAJOR = a normative contract whose stated check does not check what it claims, or
a spec that cannot be machine-checked in the form the paper wrote it. MINOR = a
missing obligation or an ambiguity an owner must close.

---

## BLOCKERS

### V-BL-1. Every "fails elaboration / machine check / fails the build" claim in all six papers is non-executable today, and the one interim substitute is unowned prose

- Papers: 01 (Sec 1.5, 3.4, 5.1 "elaboration test greps the emitted Verilog"),
  02 (M3 "ELABORATION CHECK: .has ports must match the mermaid edges"; M5), 03
  (Sec 1.6, 5.6 elaboration/require tests), 04 (SVA + "elaboration assertion"),
  05 (Decision 1-5 `require`s), 06 (Sec 3 MACHINE CHECKS 1-3, Sec 5 gate 3) vs
  the tree (`Spec.scala:5-22`, `SpecEmit.scala:4`, `build.sbt:6,12`).
- Flaw: The specs carry nothing. The stub discards `desc/note/table/entry/draw/
  code`, returns Unit from `build()`, and `spec{}` is identity, so every spec val
  is a Unit with no recoverable kind, fields, edges, or graph. The plugin that
  would emit structured metadata is compiled out and out-of-environment.
  Therefore, at ratification time, not one of the "enforced at elaboration,"
  "machine-checked," or "fails the build" obligations that the other five papers
  lean on can run. Only paper 06 admits this (Sec 0) and proposes an interim
  source-reflection checker (Sec 3 alt-1, Sec 5 gate 3) as the teeth. But that
  checker is described only in prose: it has no owner, no file, no acceptance
  test, no schedule, and no worked example proving it can recover interface
  identity from Unit-typed vals. So the load-bearing sentence of the entire
  verification program -- "a spec that is not machine-checked is fiction" (06 Sec
  0) -- currently applies to every spec in papers 01-06 including 06's own.
- Fix / question the panel must answer: Make the interim checker a named,
  owned, scheduled deliverable with its own acceptance test BEFORE ratifying any
  contract whose enforcement is "at elaboration" or "machine check." Until it
  exists, downgrade every such obligation across all papers to "manual review"
  and say so, so no one believes a rotting branch is being guarded. Decide
  explicitly: source-reflection over `*Specs.scala` now (works against the stub,
  06 O3.1) vs waiting for the plugin (blocks indefinitely).

### V-BL-2. Assertion-as-string: the papers write asserts inside the spec text, which produces no design-side assertion, and by paper 06's own rule every one of those PROPERTY specs is unbound

- Papers: 01 (propN1Degenerate, propEpochNoWrap, propWakeupEpochQualified -- prose
  obligations), 02 (M1 "wire it as a Chisel assert bound to a PROPERTY"), 04
  (`.code("scala","assert(csr_reg_write_enable |-> ...)")` inside func specs; SVA
  strings), 05 (propWakeupSelectBudget/propEpochWrapBound/propEpochDistribution/
  propPrfReadAlwaysReady each `.code("scala","require(...)")`) vs 06 (MACHINE
  CHECK 2 propPropertyToAssertion: every PROPERTY must bind to a REAL design
  assert/require carrying the same spec id, per the `GlobalEpochUnit.scala:40-43`
  pattern).
- Flaw: `code()` discards its string (`Spec.scala:18-19`). An `assert(...)` or
  `require(...)` written as an argument to `.code` is documentation, not a
  monitor and not a structural check -- it emits nothing into any design file.
  The papers repeatedly conflate "I typed assert() in the snippet" with "the
  design asserts it." Worse, this collides head-on with 06 MACHINE CHECK 2: that
  check demands the assertion live in the design file, `@LocalSpec`-tagged to the
  property id (the epochToggle template). Measured against 06's own rule, EVERY
  property spec in 01/02/04/05 is unbound and would fail the coverage gate,
  because each ships its "assertion" as spec-internal text and none ships the
  paired design-side `@LocalSpec` assert val.
- Fix / question: Ban assertion text inside `.code`/`.note`. Each PROPERTY must
  be delivered as a pair: the PROPERTY spec val AND a concrete `@LocalSpec(prop)
  val ... = assert/require(...)` in the named design file (the only pattern the
  tree actually supports). The panel must ratify MACHINE CHECK 2 as a real gate
  or admit that "PROPERTY" is a documentation label with no enforcement -- it
  cannot be both "the teeth" and satisfied by prose.

### V-BL-3. The killer app (N=1 vs N=8 retire-stream equivalence) is unsound under asynchronous interrupts, and no paper bounds the corpus to make it sound

- Papers: 06 (Rung 4 propNEquivalence: "identical token-for-token in order/pc/rd/
  wdata/trap/cause; only cycle counts differ ... the primary acceptance test of
  the whole project identity") vs 04 (C3.1 interrupts sampled at the commit
  boundary; C3.2 mepc = PC of the next not-yet-retired instruction).
- Flaw: Interrupt delivery is timing-dependent by construction. An external
  interrupt asserted at a fixed wall-clock cycle is sampled at whatever commit
  boundary the machine happens to be at (04 C3.1). N=1 and N=8 retire at
  different rates, so the same interrupt lands between different instructions:
  different `mepc`, a trap token inserted at a different `order`, a different
  handler-entry sequence. The two retire streams then diverge legitimately, not
  because function changed but because "when did the async event land" is
  intrinsically window-dependent. So "identical token-for-token, only cycles
  differ" is FALSE for any program that takes an async interrupt, and the paper
  offers no corpus restriction or deterministic-injection discipline. This is the
  single check the project's identity is sold on (06 Sec 0), and it does not hold
  in the general case.
- Fix / question: The panel must pin one of: (a) restrict the Rung-4 corpus to
  interrupt-free (and otherwise deterministic) programs, stated normatively, and
  route interrupt precision to a separate directed suite (which 04 already has);
  or (b) define deterministic interrupt injection keyed to retire `order` (fire
  after the Kth committed instruction, not the Kth cycle) so both configs sample
  at the identical architectural point, and add that as a Rung-2/Rung-4 coverage
  obligation. Also settle 06 O1.2 (is a trap ENTRY a retire token) here, since it
  decides whether the trap even appears in the compared stream.

---

## MAJORS

### V-MA-1. The graph-consistency machine check (06 MACHINE CHECK 1) has no parseable substrate, and the papers that fix the graph bugs did not write the graph in the form the check needs

- Papers: 06 (propGraphConsistency: mermaid edges in each rawTop CONTRACT
  `.draw("mermaid",...)` must reconcile with child INTERFACE sets) vs 01/02/03/04
  which present their corrected graphs as ASCII prose blocks in the markdown
  (e.g. 02 M3 "rewritten subsystem graph," 03 Sec 1.2 corrected FrontendTop
  mermaid) rather than as `.draw` spec calls.
- Flaw: Three independent reasons the check cannot run against what was written.
  (1) `draw()` discards content (`Spec.scala:17`), so no mermaid text is
  recoverable from a stub build. (2) `.has(...)` receives Unit-typed vals, so a
  reflective pass cannot recover "which interfaces does this CONTRACT own" from
  the spec objects -- the identity is lost at `SpecEmit.spec` returning the Unit.
  (3) The papers' authoritative graphs live in prose, not in any `.draw` call, so
  even a perfect parser has nothing in the spec tree to parse. The very check
  meant to catch the FetchUnit / MemoryDispatcher / summary contradictions these
  papers exist to fix (the review's rot list) cannot see the papers' fixes.
- Fix / question: (a) Papers must place the normative graph inside
  `.draw("mermaid", <text>)` of the rawTop CONTRACT, not in markdown. (b) Panel
  must pin the mermaid dialect and edge-naming grammar (06 O3.2 is still open) so
  parsing is deterministic. (c) The interim checker must recover interface
  identity from the `@LocalSpec` annotation sites in the design (which DO name
  the spec val symbol) rather than from the Unit-valued spec objects, and MACHINE
  CHECK 1's spec must say so.

### V-MA-2. "Elaboration assertion" is used for properties that can only fail at runtime, and paper 06's ladder has no formal tier to discharge them

- Papers: 01 (Sec 1.5 "Elaboration assertion: after any epoch-change cycle
  union(map image)+freeMask == all physical registers"; Sec 2.4 monotone retire
  index), 04 (propPreciseCommit "Elaboration assertion: monotone commit uopId";
  C2.4 "provable mutual exclusion"; C4.3 "at most one CSRTrapWrite.fire per
  cycle") vs 06 (Rung 1-5 are all dynamic: chiseltest, latency-invariant gate,
  N-differential, ISS co-sim -- no formal/SVA tool named or budgeted).
- Flaw: Elaboration (a Scala `require`) sees only structure and parameters. Free-
  list conservation "each cycle," commit-id monotonicity, and one-hot-write-per-
  cycle are temporal/runtime facts; they cannot be evaluated at elaboration and
  can only be a simulation monitor or a formal property. Labeling them
  "elaboration assertion" promises build-time teeth for checks that can only
  trip in simulation. And the proof language -- "provable," "proven one-hot,"
  "SVA" (04 C2.4, C4.3, and its SVA blocks; 01 asserts) -- has no substrate: a
  chisel `assert` is a simulation monitor, and 06 specifies no formal flow
  (SymbiYosys/ChiselFormal) to actually prove anything.
- Fix / question: Every obligation must be typed as exactly one of {elaboration-
  require (structural only) | simulation-assert | formal-property}. Since 06's
  methodology is entirely dynamic, either add a formal tier with an owner and
  budget, or restate all "SVA/prove/provable/elaboration assertion" runtime
  claims in 01/04 as bounded simulation-coverage obligations. Mislabeled teeth
  are worse than none: they let a reviewer believe monotonicity is guaranteed by
  the compiler.

### V-MA-3. The IPC-critical `require(stages <= budget)` cannot observe the event it exists to forbid (post-PD retiming)

- Papers: 05 (Decision 1.2-1.3: inserting a registered stage beyond `B(N)` "is a
  contract violation and MUST fail elaboration," enforced by `ipcCriticalEdge`
  helper's `require`) vs the governing premise `dontcommit.md:5-6` (pipelining is
  a POST-PD retiming act) and 05's own framing (Sec 1.1).
- Flaw: Post-PD retiming is performed by the synthesis/retiming tool on the
  netlist, AFTER Chisel elaboration. The `require(stages <= budget)` only guards
  an explicit Scala `stages` knob at elaboration time; it has zero reach into a
  retiming pass that moves a flop across `edgeWakeupSelect` in the physical flow.
  A timing engineer closing frequency does it in the PD tool, not by bumping a
  Chisel parameter, so the exact scenario 05 names ("a future timing-closure pass
  can register the wakeup->select loop ... and no spec will flag it") sails
  straight past the require. As written, "MUST fail elaboration" is false for the
  post-PD case that motivates the whole registry.
- Fix / question: 05 must scope the elaboration `require` to pre-PD structural
  stages only, and name the actual post-PD guard: an STA/netlist artifact that
  reports registered depth on each named IPC-critical edge after synthesis, plus
  the issue-to-issue latency regression (05 Sec 1.6) -- both of which live in the
  un-ported harness (06), so they must be co-owned and scheduled with paper 06.
  Until then the registry is documentation, not enforcement.

### V-MA-4. Three divergent copies of the "N=1 folds away" property, no single owner, and the grep pattern the gate depends on is never actually specified

- Papers: 01 (propN1Degenerate: forbids "CAM, priority encoder, pointer map"), 05
  (Decision 5.2 structural clause + propN1PpaBar: forbids "wakeup CAM / free-list
  / map-CAM / multi-producer arbiter" + tag widths), 06 (propN1FoldsOoO: forbids
  "wakeup CAM, publish arbiter, free list, rename map").
- Flaw: The same acceptance gate is specified three times, in three files, with
  three different forbidden-structure lists and three prose mechanisms, and NONE
  of them names the concrete emitted-Verilog module names or cell classes the
  grep matches -- every paper says "grep the emitted Verilog for the named
  modules/CAM cells" without ever naming them. A grep gate is exactly its
  pattern; three specs guarantee three patterns and silent drift, and an
  unspecified pattern is unfalsifiable. (Cross-ref sibling MA-4: all three also
  omit the memory forwarding CAM and frontend issue-queue N-scaled structures, so
  the gate is scoped only to backend OoO.)
- Fix / question: One owner (recommend 05) defines a single canonical forbidden-
  structure list with concrete module/cell identifiers as they appear in the
  emitted netlist; 01 and 06 `.uses` it rather than restating it. The grep
  pattern itself becomes a committed, tested artifact: a unit test that a
  deliberately-broken N=1 build (one that keeps a CAM) trips the gate, proving the
  pattern actually matches. Add memory and frontend structures to the list.

### V-MA-5. The store-commit cross-domain key has no verification obligation proving producer and consumer key on the same field

- Papers: 02 (M1 rule 3: StoreBuffer entry marked committed when a StoreCommit
  matches "by `{seq}`"), 04 (C1.2: commitGrant keyed by `uopId`), 05 (Decision
  4.2: proposes merging `uopId == seq`), 01 (O7: declines to pin the identifier)
  vs the tree (`BackendBundles.scala:86,91`: uopId AND seq both 32b).
- Flaw: This is the single most important NEW edge in the review (02 cross-domain
  section), and its correctness rests entirely on the producer (CommitUnit) and
  consumer (StoreBuffer) agreeing on one identifier. 02's verification matrix
  proves "no speculative write," "in-order drain," "committed survives kill" --
  but has no row proving "a StoreCommit marks exactly the entry the backend
  intended, and that entry is the one at the commit head." With `seq` and `uopId`
  currently distinct 32b fields and 05 proposing to merge them, a
  wrong-field/wrong-width match silently commits the wrong store or none. This is
  a correctness seam, not a PPA nit, and it is precisely what spec-first is
  supposed to close, yet no assertion or contract test is named on either side.
- Fix / question: Backend (01) freezes the single canonical in-flight identifier
  and its width law BEFORE the StoreCommit edge is drawn. 02 adds a cross-domain
  contract test + `@LocalSpec` assert: every accepted StoreCommit matches exactly
  one buffered entry, and that entry's key equals the backend commit head's key.
  Same for 04's commitGrant.

### V-MA-6. "SVA" and "prove/provable" appear across 01/02/04 but no formal flow exists in the methodology

- Papers: 01 (Sec 1.5/3.4/4.4 assertions phrased as invariants), 02 (M1 "golden
  invariant," verification matrix "assert(...)"), 04 (Sec 1-5 "SVA:" blocks,
  "provable mutual exclusion," "proven one-hot select") vs 06 (Rung 1-5, all
  dynamic; Sec 4 O4.1 chooses an ISS for co-sim; no formal tool anywhere).
- Flaw: SVA is SystemVerilog formal-assertion syntax and "prove" implies a
  property checker. The project elaborates Chisel to Verilog and 06's ladder is
  simulation + ISS co-simulation only. A chisel `assert` is a runtime monitor
  that fails only on a stimulated path; it proves nothing about unexercised
  states. So 04's "provable mutual exclusion" of the trap-CSR writers (C2.4) and
  01's one-hot/monotone assertions are, under the actual methodology, at best
  simulation coverage -- their strength is overstated by an order of magnitude.
- Fix / question: Either paper 06 adds a formal tier (name the tool, the owner,
  which properties are discharged formally vs by simulation) or 01/02/04 drop
  "SVA/prove/provable" and restate as simulation-coverage obligations with a
  stated stimulus that reaches the state. The panel must not let "provable" ride
  into ratification unbacked.

---

## MINORS

### V-MI-1. No machine check for the two most load-bearing UDA structural rules

Paper 06's three machine checks are graph consistency, property binding, and
`@LocalSpec` coverage. Neither of the constitution's core structural invariants
is covered: (a) rawTop modules contain ONLY edge wiring, no behavioral logic
(CLAUDE.md, constitution); (b) no `Reg`/`Queue` in a design file unless spec-
sanctioned (CLAUDE.md pitfall 5, AGENTS.md). A node hiding stall logic, or a
rawTop with behavioral logic -- the exact failure modes UDA forbids -- passes all
of 06's gates. Fix: add a fourth/fifth machine check (rawTop body is `:<>=`
wiring only; every `Reg`/`Queue` site is `@LocalSpec`-tagged to a sanctioning
BUNDLE/FUNCTION), or state explicitly and normatively that these remain manual-
review-only and accept the rot risk.

### V-MI-2. Store-buffer full-vs-commit has no liveness/deadlock obligation

02 M1 rule 7 asserts a full store buffer stalling commit is "legal backpressure
(FCL)" but gives no liveness test. A speculative store that cannot enter a full
buffer, whose commit needs it buffered to be marked committed, is a candidate
deadlock; 02 hand-waves it. Contrast 03 Sec 4.7, which DOES give the issue queue
a rank-function liveness obligation. Fix: 02 adds the mirror obligation -- given
memory acks, the buffer eventually drains, and commit is never gated on an
enqueue the buffer refuses -- with a directed backpressure test (06 Rung 3
territory).

### V-MI-3. The `status("manual")` exemption is not machine-readable in the stub

06 MACHINE CHECK 2 lets a PROPERTY opt out via `.status("manual")`. But
`status()` discards its argument (`Spec.scala:10`), so a source-reflection
checker cannot distinguish "intentionally manual" from "forgot to bind." Fix: the
interim checker keys the manual exemption off something recoverable -- a naming
convention or an in-tree allowlist file -- not the discarded `.status` field; 06
Sec 3 must say which.

### V-MI-4. COVERAGE specs declare points but no closure bar

06 Rung 2 (covEpochWrap, covSameCycleEpoch) and 02 (M2 "COVERAGE spec: full-hit,
partial-hit, no-hit, late-address replay") name coverage points but state no
closure criterion (which bins, what percentage is "done"). A COVERAGE spec with
no enumerated bins and no pass bar is unfalsifiable. Fix: each COVERAGE spec
enumerates its bins and states the closure bar (e.g., 100% of listed bins hit in
the merge gate).

### V-MI-5. Retire `order` counter must be in the N=1 grep, not just the port gate

06 Sec 1 hardcodes retire `order` at 64b "regardless," folded by `usingRvvi`.
Verifiability angle (complements sibling MI-2): unless the COUNTER, not just the
output port, is inside the `if (usingRvvi)` elaboration branch, a 64b monotone
counter survives in the N=1 production netlist and silently violates 05 D4 and
its own N1 grep. Fix: add the retire counter to the canonical N=1 forbidden list
(V-MA-4) so the gate would actually catch a leaked counter, and require the fold
to gate the counter.

### V-MI-6. The retire token's `epoch` field will break the N-equivalence compare if included

06 Sec 1's retire token table carries `epoch` "for cross-check," while Rung 4's
compare list is `order/pc/rd/wdata/trap/cause` (no epoch). These must be
reconciled: `epoch` values legitimately differ between N=1 and N=8 (different
redirect counts reach the same instruction), so including `epoch` in the token-
for-token N-equivalence diff would produce false divergences. Fix: state
normatively that `epoch` is excluded from the N-equivalence compare (it is a
same-run cross-check field only), so an implementer does not wire it into the
differential.

---

## Cross-paper gaps no paper covered (verifiability lens)

- **No owner for the interim checker.** V-BL-1's checker is the linchpin of the
  whole verification program and is assigned to no one. Papers 01-05 assume
  "elaboration will catch it"; 06 describes the checker but does not commit to
  building it. This is the highest-leverage unassigned deliverable on the branch.
- **Spec-DSL cannot express cross-references at all.** Because every spec val is
  Unit (`SpecEmit.scala:4` + `build(): Unit`), `.has/.uses/.is` relationships are
  type-erased to `Any` and carry no recoverable target. Every paper's `.has(...)`/
  `.uses(...)` graph is, in the stub, a set of name lookups and nothing more. The
  panel should decide whether the plugin will re-type these (so relationships are
  first-class) or whether all relationship checking is deferred to `@LocalSpec`
  reflection. No paper states which, yet three of 06's checks depend on the
  answer.
- **No golden-model determinism contract.** 06 Rung 5 picks an ISS but never
  states the determinism contract that makes ISS-vs-DUT token comparison well-
  defined under the DUT's timing freedom (interrupt timing per V-BL-3, memory
  response reordering per 02 M4, OoO completion). Without it, Rung 5 has the same
  soundness hole as Rung 4.

## The panel questions this critique forces (verifiability)

1. (V-BL-1) Who owns the interim spec checker, when does it land, and what is its
   acceptance test? No "enforced at elaboration" contract is real until this is
   answered.
2. (V-BL-2) Is MACHINE CHECK 2 a real gate? If yes, every PROPERTY must ship a
   paired `@LocalSpec` design assert, not spec-internal text -- the papers must be
   reworked. If no, "PROPERTY" is a doc label and must stop being called teeth.
3. (V-BL-3) What makes N-equivalence sound: an interrupt-free/deterministic
   corpus, or retire-count-keyed interrupt injection? The project's headline
   check is unsound until this is pinned.
4. (V-MA-2 / V-MA-6) Does the methodology get a formal tier, or do all
   "SVA/prove/elaboration-assertion" runtime claims in 01/04 become simulation
   coverage? Decide before ratifying any "provable" contract.

Everything else (V-MA-1 graph substrate, V-MA-3 retiming reach, V-MA-4 grep
canonicalization, V-MA-5 cross-domain key, the minors) is downstream of these
four and of the sibling PPA critique's BL-1..BL-3.
