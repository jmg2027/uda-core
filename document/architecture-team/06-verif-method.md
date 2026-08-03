# 06 - Verification and Methodology: Position Paper

Author: Verification and methodology lead
Branch: claude/rebuild-architecture-review-jg0grl (rebuild lineage)
Status: normative proposal for panel ratification

This paper is normative, not a survey. It defines the verification contracts the
backend and core teams must design INTO the rebuild core now, so the harness is a
port and not a bolt-on. Every contract is given as spec-DSL ready to paste into a
`*Specs.scala` file, with alternatives, N=1 and N=32 cost, verification obligations,
and open questions.

The governing constraint is the project identity (owner-fixed): ONE RTL codebase, a
single window/speculation parameter scaling from a minimal in-order point (N=1, OoO
structures elaborate away) to wide OoO (N=32+). The killer verification app that this
identity enables - and that nothing else in the field gives us for free - is
**same-program, different-window differential equivalence**: the identical ISA trace
must retire identically at N=1 and N=8, so the window parameter is proven to change
timing and IPC but never architectural function (dontcommit.md items 5-6). The entire
ladder below is built to make that check cheap and central.

---

## 0. Preconditions: the executable-truth problem

Facts on the ground, from the tree:

- The spec DSL has no teeth. `src/main/scala/framework/specs/Spec.scala:5` is a
  `SpecBuilder` whose every method returns `this` and whose `build()` returns `Unit`
  (lines 5-22). The plugin is compiled out: `build.sbt:45` `useSpecPlugin` defaults
  false, and `build.sbt:51` comments "SpecPlugin removed for open-source compilation".
  So the only integrity check today is Scala name resolution. The rebuild review
  already documents resulting spec rot (rebuild_branch_architecture_review.md:141-151).
- No harness exists on this branch. The protected cluster tests
  (`src/test/scala/cluster/SingleCoreMulDivClusterTest.scala`,
  `TestCluster.scala`, `TestMem.scala`) target a legacy single-file core, not the UDA
  vertices; the UDA test directories are `.gitkeep` stubs
  (`src/test/scala/udacore/**/.gitkeep`).
- The core does not elaborate: `CoreTop.scala:13-40` is `???` on every port and body;
  `CommitUnit.scala:11-13` is an empty IO shell; `BackendParams.scala:38`
  `require(hartId > 0)` rejects the default hartId=0.
- The main branch already has exactly the harness we need: an AI-native scn/gate/trace
  engine driving the REAL core through one `CoreHarness`, capturing an RVVI committed-PC
  stream (`verif/README.md`, `verif/ARCHITECTURE.md`, `verif/AI-HARNESS.md`,
  `verif/bin/scn.sh`), plus a whole-core synth/STA flow keyed by named config
  (`verif/bin/sta.sh`, `verif/suites/EmitCore.scala`).

Methodology position, non-negotiable: **a spec that is not machine-checked and an RTL
vertex that has never retired an instruction are both fiction.** The rebuild's spec-first
discipline is only worth its cost if (a) specs bind to elaboration-time assertions and
graph checks, and (b) every vertex is exercised against a golden model the moment it has
a body. The port plans below exist to make both true from the first shell fill-in.

---

## 1. Commit-stream port CONTRACT (the DUT binding layer)

### The contract (normative)

The main harness grades a run by capturing stores plus a **retired-instruction stream**
(`CoreHarness` returns `RunResult(stores, pcStream, done, cycles, derailed)`;
`verif/ARCHITECTURE.md`). On main this is the RVVI port
(`origin/main:src/main/scala/udacore/include/rvvi/Bundle.scala`, a per-commit bundle:
`order`, `insn`, `trap`, `pc_rdata/pc_wdata`, `x_wdata`, `x_wb`, csr, mode). The rebuild
core MUST expose an equivalent **retire stream at the CommitUnit**, because commit is the
one in-order serialization point in the UDA graph and the only place the architectural map
is authoritative (`CommitUnitSpecs.scala:22-25`: "in program order ... map table update
only"). Bolting a trace port on later means re-deriving program order from a dataflow
graph - exactly the mistake to avoid.

The CommitUnit CONTRACT today (`CommitUnitSpecs.scala:10-27`) uses only
`intfExceptionOut` on the output side. Add a first-class retire interface. Paste into
`CommitUnitSpecs.scala`:

```scala
val intfRetireStreamOut = spec {
  INTERFACE("RetireStreamOut")
    .desc(
      "Committed-instruction observability stream, one token per in-order retirement. " +
      "This is the verification DUT-binding port (RVVI-lite): the harness grades a run " +
      "against a golden model by this stream, and the N-equivalence check compares two " +
      "streams token-for-token. It is a pure observation edge - a consumer that is never " +
      "ready must not stall commit (see propRetireNonBlocking)."
    )
    .is(rawReadyValidIntf)
    .uses(bndRetireToken)
    .markdownTable(
      List("Field", "Width", "Meaning"),
      List(
        List("order",   "64",        "monotone retire sequence number (RVVI order)"),
        List("pc",      "vAddrWidth", "architectural PC of the retired instruction"),
        List("insn",    "iLen",      "the retired (RVC-expanded) instruction word"),
        List("rd",      "5",         "architectural destination, 0 if none"),
        List("wdata",   "xLen",      "value written to rd (from PRF read at commit)"),
        List("wen",     "1",         "rd write-enable"),
        List("trap",    "1",         "this retirement is a trap entry"),
        List("cause",   "xLen",      "mcause when trap=1"),
        List("epoch",   "epochWidth","the token epoch at commit (for cross-check)")
      )
    )
    .note("Emitted only for tokens that actually retire (epoch-matched, in program order).")
    .note("Gated off at elaboration when usingRvvi=false; zero cost in production.")
    .build()
}

val bndRetireToken = spec {
  BUNDLE("RetireToken")
    .desc("One retired instruction, program-order-stamped, for golden comparison.")
    .uses(paramUsingRvvi, paramEpochWidth)
    .build()
}

val propRetireNonBlocking = spec {
  PROPERTY("RetireNonBlocking")
    .desc(
      "The retire stream is observation-only: its ready is tied high inside the harness " +
      "and commit progress is independent of it. Asserted at elaboration."
    )
    .code("scala",
      "assert(!io.retireStreamOut.valid || io.retireStreamOut.ready, " +
      "\"retire stream must not backpressure commit\")")
    .build()
}
```

And a config knob, mirroring main's `usingRvvi` (`origin/main:verif/config` sets it true;
production false, `origin/main:CLAUDE.md`). Paste into `BackendParamsSpecs.scala` /
`CoreParams`:

```scala
val paramUsingRvvi = spec {
  PARAMETER("UsingRvvi")
    .desc("Verification-only: elaborate the CommitUnit retire stream. Default false. " +
          "When false the port and its wdata read fold away (production baseline).")
    .build()
}
```

Add `contCommitUnit.uses(intfRetireStreamOut)` and tag the design port
`@LocalSpec(intfRetireStreamOut)` when the CommitUnit body lands.

### Alternatives considered and rejected

- **Full RVVI Vec-of-regfile bundle (main's `RvviBundle`).** Rejected as the standing
  contract: main's bundle carries `Vec(regNum, xLen)` architectural GPR/FPR/VPR snapshots
  and `Vec(4096, xLen)` CSR (`origin/main:.../rvvi/Bundle.scala`), ~16.6% of core area
  when on (`origin/main:CLAUDE.md`). That is a fine debug mode but a bad contract to make
  the backend team build around. Keep the lean per-token stream as the contract; offer the
  full snapshot as an optional `usingRvviFull` debug elaboration only.
- **Reconstruct order in the harness from the PublishMux/PRF write bus.** Rejected: the
  publish bus is out-of-order and speculative; reconstructing program order there
  re-implements the CommitUnit in the testbench and will silently disagree at N>1. Commit
  is the authoritative order point; emit from there.
- **Grade on stores only (like the scn `check` slots).** Rejected as sole mechanism:
  store-only grading cannot see register-only computation, cannot distinguish a wrong-path
  store squashed correctly from a missing store, and gives the N-equivalence check no
  per-instruction anchor. Stores stay as a coarse check; the retire stream is the fine one.

### Cost

- **N=1:** ~zero. `usingRvvi=false` folds the port and the commit-time PRF read; identical
  to main's production baseline where RVVI is off. The single retire path already exists
  (in-order commit is mandatory at N=1).
- **N=32:** one extra read port group on the PRF per commit lane and a `commitWidth`-wide
  fan of retire tokens. Bounded by `commitWidth`, not by window size. The `order` counter
  is 64b regardless. This is the cost of observability and is paid only in verif configs.

### Verification obligations

- Elaboration assertion `propRetireNonBlocking` (above) proves the port never stalls commit.
- A `RetireStreamMonotone` runtime assertion: `order` strictly increments by the number of
  lanes that fired; no gaps, no reorder.
- Golden binding test: for every scn in the ported suite, `retire.pc/rd/wdata` matches the
  ISS golden (section 4) token-for-token; this is the acceptance test for the port itself.

### Open questions for the panel

1. Does the retire token carry `wdata` (requires a commit-time PRF read) or just
   `rd`/`wen` (harness reads the PRF snapshot separately)? The former is self-contained and
   ISS-comparable; the latter is cheaper but couples the harness to PRF internals. I
   recommend carry `wdata`, gated by `usingRvvi`.
2. Trap/interrupt tokens: is a trap ENTRY a retire event (RVVI `trap=1`) or a suppression?
   Must be pinned before the TrapController contract is frozen, because the golden model's
   trap accounting depends on it.

---

## 2. Synthesis/STA flow as the N-sweep instrument (config emit CONTRACT)

### The contract (normative)

The N-sweep research question ("what does the window parameter cost across N=1..32?") is
answerable only with the main synth/STA flow: `verif/bin/sta.sh <cfg>` emits a named config
to SystemVerilog, maps to sky130 with a fanout buffer tree, and runs constrained OpenSTA
(`origin/main:verif/bin/sta.sh`; `origin/main:CLAUDE.md` "Measuring timing"). The flow is
driven entirely by a **named-config registry** - `EmitCore.configByName(name): Parameters`
(`origin/main:verif/suites/EmitCore.scala:44+`). Porting the instrument therefore reduces to
one obligation on the core team: **there must be a single elaboration entry point that takes
a config name and returns a fully-resolved `Parameters`, and the window parameter must be a
first-class axis of that registry.**

The rebuild entry point is `UDACoreElab.scala`; it must grow a config registry. Contract, as
a spec in a new `verif`-facing shared spec (or `CoreApi`):

```scala
val contConfigRegistry = spec {
  CONTRACT("ConfigRegistry")
    .desc(
      "Single source of named elaboration configs for the synth/STA/verif instrument. " +
      "sta.sh/synth.sh/EmitCore consume this by name. The window/speculation parameter " +
      "(SpeculativeRegNum) is a required, enumerable axis: the N-sweep configs n1,n2,n4," +
      "n8,n16,n32 differ ONLY in that parameter, so a PPA delta is attributable to window " +
      "size and nothing else."
    )
    .uses(paramSpeculativeRegNum)
    .markdownTable(
      List("Config name", "SpeculativeRegNum", "usingRvvi", "purpose"),
      List(
        List("n1",       "1",  "false", "in-order point; OoO structures must fold away"),
        List("n8",       "8",  "false", "mid OoO PPA point"),
        List("n32",      "32", "false", "wide OoO PPA point"),
        List("n1_rvvi",  "1",  "true",  "verif build at N=1 (harness on)"),
        List("n8_rvvi",  "8",  "true",  "verif build at N=8 (harness on)"),
        List("verif",    "8",  "true",  "default differential-equivalence pair partner")
      )
    )
    .note("Naming and one-axis-per-name discipline copied from main EmitCore.configByName.")
    .note("N=1 configs are the elaboration-away acceptance vehicle (see propN1FoldsOoO).")
    .build()
}

val paramSpeculativeRegNum = spec {
  PARAMETER("SpeculativeRegNum")
    .desc("The window/speculation parameter. N=1 => in-order (map table, wakeup CAM, " +
          "publish arbiter, alloc FIFO, uopId/seq tags must elaborate to trivial/away). " +
          "N=32+ => wide OoO. One parameter scales one RTL codebase.")
    .build()
}

val propN1FoldsOoO = spec {
  PROPERTY("N1FoldsOoO")
    .desc(
      "At SpeculativeRegNum=1 the OoO fabric must not survive into the netlist: the wakeup " +
      "CAM, publish arbiter, free list, and rename map degenerate to constants/wires. " +
      "Acceptance is STRUCTURAL, mirroring main's feedthrough-check: synthesize n1, then " +
      "assert the CAM/arbiter cell classes and the speculative-tag flop count are zero."
    )
    .build()
}
```

Required emit points (what the instrument needs, beyond a working `EmitCore`):

1. `verif.suites.EmitCore <cfg> <outDir>` - one SV file per named config (main's signature).
2. A structural fold check `n1` (yosys: post-map, count of speculative-tag flops and
   CAM/arbiter cells must be 0), analogous to main's `feedthrough-check.sh`
   (`origin/main:CLAUDE.md`, boundary-feedthrough section).
3. A whole-core `sta.sh`/`synth.sh` per config for the area/DFF (solid) and pre-layout ns
   (relative) numbers the N-sweep report consumes.

### Alternatives considered and rejected

- **Hand-edited per-N source variants (like main's parallel `udacore_lsu_refactor/`,
  `udacore_rvv_coprocessor/` project copies, `origin/main:CLAUDE.md`).** Rejected outright:
  the whole project identity is ONE codebase scaled by a parameter. Copy-per-N would make
  the N-equivalence claim vacuous. The window must be a `Parameters` axis, never a source fork.
- **Reuse `UDACoreElab` directly with -D flags.** Rejected as the contract: sbt/-D config is
  fine for a human but the instrument (sta.sh) shells out by config NAME; a named registry is
  the stable interface. Keep `UDACoreElab` as one caller of the registry.

### Cost

- **N=1:** the registry itself is elaboration-time Scala, zero hardware. The value of the
  contract is that N=1 is measurable and provably folds (propN1FoldsOoO is the in-order
  competitiveness gate the review demands, rebuild_..._review.md:171-173).
- **N=32:** none added by the instrument; it measures the cost, does not add it.

### Verification obligations

- The `n1` structural fold check is a CI gate: if an OoO structure survives at N=1, the
  in-order point is not competitive and the build fails (this is the teeth behind review
  recommendation 6).
- A monotonicity sanity check across the sweep: area(n1) <= area(n8) <= area(n32); a
  non-monotone point flags a parameterization bug.

### Open questions for the panel

1. Is `SpeculativeRegNum` owned by `CoreParams` (tuning tier) or `BackendParams`? It drives
   PRF depth, RS depth, and map width - all backend - but the epoch width and the core-level
   contract also scale with it. I recommend it live in `CoreParams.tuning` and be pushed down.
2. Do we commit to sky130+yosys+OpenSTA as-is (port main's `bin/` verbatim), or do we also
   want a fast area-only proxy (yosys stat) for every commit and reserve full STA for the
   sweep? I recommend area-only-per-commit, full-STA-on-demand.

---

## 3. Spec-framework reintegration and machine checks (CONTRACT)

### The contract (normative)

The real `spec-core`/`spec-macros`/`spec-plugin` (repo `jmg2027/spec-framework`) is not in
this environment; the in-tree `framework.specs.Spec` is a source-compatible no-op stub
(`Spec.scala:5-33`) and `framework.macros.SpecEmit.spec` / `LocalSpec` are the macro seams.
Reintegration MUST preserve source compatibility so the tree compiles with OR without the
plugin (`build.sbt:63-68` swaps in `spec-core`/`spec-macros` only under
`useSpecPlugin=true`, `build.sbt:101-107` adds `spec-plugin`; the emit dir is
`spec.meta.dir`, `build.sbt:75,79-83`).

Normative rules for the seam:

1. **Stub source-compatibility is frozen API.** Every method the stub exposes
   (`desc/note/table/markdownTable/draw/code/entry/is/has/uses/status/build`, `Spec.scala:6-21`
   and the category constructors `Spec.scala:24-32`) is the contract the real plugin must
   honor with identical signatures. No spec file may use a construct absent from the stub, or
   the no-plugin build breaks. This is a CI check (section 5).
2. **The plugin's job is emission + machine checks, not behavior change.** With the plugin on,
   `spec { ... }` emits structured metadata to `spec.meta.dir`; with it off, it is a no-op that
   still compiles. RTL behavior is byte-identical either way.
3. **`@LocalSpec` is the bind.** Every design port/module/function that a spec names must carry
   `@LocalSpec(specVal)` (AGENTS.md:175-211). Coverage of this binding is machine-checkable.

The three machine checks the panel wants the plugin to enforce, specified as acceptance
obligations:

```scala
val propGraphConsistency = spec {
  PROPERTY("GraphConsistency")
    .desc(
      "MACHINE CHECK 1 (top-mermaid vs module-CONTRACT port agreement). For each rawTop " +
      "module, the edges drawn in its CONTRACT .draw(\"mermaid\", ...) must match the union " +
      "of the child modules' CONTRACT interface sets: every mermaid edge has a producing " +
      "INTERFACE on one vertex and a consuming INTERFACE on another, and every non-terminal " +
      "INTERFACE appears on exactly one mermaid edge. The rebuild review found FetchUnit, " +
      "MemoryDispatcher, and docs/summary disagreeing with the top graph; this check makes " +
      "that a compile error."
    )
    .note("Fails the build on a mismatch; output names the missing/extra edge.")
    .build()
}

val propPropertyToAssertion = spec {
  PROPERTY("PropertyToAssertion")
    .desc(
      "MACHINE CHECK 2 (PROPERTY-to-assertion binding). Every PROPERTY spec that states an " +
      "invariant must be bound to an elaboration-time chisel assert/require carrying the same " +
      "spec id, OR be explicitly marked .status(\"manual\"). An unbound PROPERTY is spec " +
      "theater and fails the coverage gate. GlobalEpochUnit already models this: propEpochToggle " +
      "binds to an assert (GlobalEpochUnit.scala:40-43)."
    )
    .build()
}

val propLocalSpecCoverage = spec {
  PROPERTY("LocalSpecCoverage")
    .desc(
      "MACHINE CHECK 3 (@LocalSpec coverage). Every INTERFACE/FUNCTION/CONTRACT spec val that " +
      "is referenced by a module's CONTRACT (.has/.uses) must be bound by at least one " +
      "@LocalSpec annotation in the corresponding design file, and conversely every @LocalSpec " +
      "must name a live spec val. Orphans on either side fail. This turns the spec-first rule " +
      "from a convention (AGENTS.md) into a gate."
    )
    .build()
}
```

### Alternatives considered and rejected

- **Wait for the real plugin before enforcing anything.** Rejected: the plugin is out of
  environment and the branch is rotting now (review section "Spec system: right idea, no
  teeth"). Checks 1 and 3 are pure static analysis over the spec vals and `@LocalSpec` sites
  and can be run today as a small ScalaTest/reflection pass over the emitted `spec.meta.dir`
  OR over the source, without the plugin. Stand up a stub-level checker immediately; swap to
  the plugin when it lands.
- **Make the stub throw on unimplemented methods to force plugin use.** Rejected: it would
  break the open-source no-plugin build that `build.sbt:51` deliberately preserves. Source
  compatibility is the point.
- **Hand-audit graph consistency in review.** Rejected: the review already did this once and
  found three disagreements; humans do not re-run it every commit. Automate.

### Cost

- **N=1 / N=32:** zero hardware in all cases - these are build-time checks. Cost is CI wall
  time (seconds) and the discipline of writing a mermaid graph per rawTop and an assert per
  PROPERTY. Both are already required by AGENTS.md; the check just enforces them.

### Verification obligations

- Checks 1-3 run in the pre-commit gate (section 5), initially as a source/`spec.meta.dir`
  reflection pass, later as the real plugin. Each must emit the offending spec id and file:line.
- A "spec parity" test that the tree compiles with `useSpecPlugin=false` AND (when available)
  `=true`, guaranteeing the stub API stays a superset of nothing / subset of the plugin.

### Open questions for the panel

1. Do we author the interim checker against the SOURCE (scalameta/reflection over `*Specs.scala`)
   or against emitted `spec.meta.dir` metadata? Source-based works with the stub today;
   meta-based is what the plugin will feed. I recommend source-based now, meta-based later,
   same test names.
2. What is the canonical mermaid dialect and edge-naming rule so check 1 can parse it
   deterministically? This must be pinned before more rawTop CONTRACTs are written.

---

## 4. Verification ladder for a parametric core (five rungs)

The ladder is ordered so each rung gates the next; a vertex may not climb until the rung
below is green. This is the normative test taxonomy.

### Rung 1 - Per-vertex contract tests

Each UDA vertex is a pure function on Decoupled edges (dontcommit.md items 5-6), which makes
it independently testable the moment it has a body. A contract test drives the vertex's input
edges, checks output edges against a per-vertex reference, and asserts the CONTRACT's
PROPERTY invariants. Vehicle: ScalaTest + chiseltest in the currently-empty
`src/test/scala/udacore/<domain>/modules/` trees. Reference: the vertex's `verif.ref`-style
pure-Scala emulator (main already ships `Alu`/`Mul`/`Bit`, `verif/ARCHITECTURE.md`).

Obligation: no vertex is "done" without a contract test that (a) exercises ready/valid in
both directions including stall, and (b) checks the bound PROPERTY asserts fire on violation.

### Rung 2 - Epoch-transition tests

The rebuild's single correctness predicate is `token.epoch === globalEpoch`
(rebuild_..._review.md:44-47); the epoch is 2 bits by default (`CoreParams.scala:33`) and the
review flags a wrap hazard (a long-latency op surviving 4 redirects aliases back,
review:99-105) and a same-cycle combinational broadcast (`GlobalEpochUnit.scala:35-38`
muxes `epochIncrement` on `redirectFire`). These need dedicated tests:

```scala
val covEpochWrap = spec {
  COVERAGE("EpochWrap")
    .desc(
      "Drive >= 2^epochWidth redirects while a long-latency op (div) is in flight; the stale " +
      "result MUST be filtered, not aliased as current. Parameterized over epochWidth to prove " +
      "the chosen width (or a distance-compare) closes the wrap hazard the review raised."
    )
    .build()
}

val covSameCycleEpoch = spec {
  COVERAGE("SameCycleEpochExposure")
    .desc(
      "Redirect and epoch-consumer sampling in the same cycle: assert consumers see the " +
      "post-redirect epoch (GlobalEpochUnit.sameCycleEpochExposure) and no younger token " +
      "with the pre-redirect epoch retires."
    )
    .build()
}
```

Obligation: an epoch-transition suite runs at every N and specifically at N where multiple
redirect sources can coexist; the global-epoch kill-everything semantics
(review:99-101) must be shown either sufficient or explicitly bounded.

### Rung 3 - Credit / backpressure tests

All edges are Decoupled and edges own timing; queues are forbidden unless spec'd
(AGENTS.md:253). Backpressure correctness is therefore a first-class hazard: a consumer that
deasserts ready for K cycles must never drop, duplicate, or reorder a token, and must never
deadlock against a producer. The div-normal derail on main (`verif/scn/div_normal.scn`: the
iterative divide stall let prefetch overtake the held FetchQueue head, fixed by a fetch-credit
reserve) is the canonical example of what this rung catches. Vehicle: the latency-invariant
gate ported from main (`verif.gates.latencyInvariant`, `verif/ARCHITECTURE.md`) - run a
scenario across a set of imem/dmem latencies and flag any result that flips as harness- or
RTL-suspect. This is also the mechanism that turns "a suspicious result is a question, not a
finding" (main working rule) into an automated verdict (`scn.sh gate`).

Obligation: every load/store, div/mul, and redirect scenario passes `latencyInvariant` across
at least {ilat,dlat} in {1,2,3}; a flip is a blocking bug.

### Rung 4 - Differential N=1 vs N=8 same-program equivalence (the killer app)

This is the rung that justifies the architecture. Contract: for a fixed ISA program P, the
retire stream (section 1) from config `n1_rvvi` and config `n8_rvvi` must be **identical
token-for-token** in `order`/`pc`/`rd`/`wdata`/`trap`/`cause`. Timing (`cycles`) may and
should differ; architectural function may not. This is `verif.gates.differential`
(`verif/ARCHITECTURE.md`) applied across the window axis instead of an RTL variant.

```scala
val propNEquivalence = spec {
  PROPERTY("NEquivalence")
    .desc(
      "For every program in the regression corpus, the CommitUnit retire stream is identical " +
      "across SpeculativeRegNum in {1,2,4,8,...}: same order/pc/rd/wdata/trap sequence. Only " +
      "cycle counts differ. This is the direct proof that the window parameter changes timing " +
      "and IPC but never architectural function (dontcommit.md 5-6). It is the primary " +
      "acceptance test of the whole project identity."
    )
    .uses(paramSpeculativeRegNum)
    .note("Implemented as differential(retireStream(n1), retireStream(n8)); a first divergent " +
          "token names the failing instruction and both wdata values.")
    .build()
}
```

Obligation: N-equivalence runs over the full ported scn corpus at every merge to this branch.
A single divergent token blocks the merge. This is the check that makes rename recovery,
memory ordering, and CSR serialization (the three silent holes, review:118-138) VISIBLE:
each hole manifests as an N-dependent divergence (a leaked free-list entry, a reordered store,
a speculatively-committed CSR) that N=1 hides and N=8 exposes.

### Rung 5 - Golden model

The differential in rung 4 pins N=1 to N=8 but not either to architectural truth. We need an
external golden. Two-tier decision:

- **Primary golden: an ISS (Spike or Sail RV32IMC) driven from the retire stream.** The
  harness feeds the same image to the ISS and compares the ISS retire trace to the DUT retire
  stream, token-for-token. This is standard RVVI-to-ISS co-simulation and is why the retire
  port (section 1) is RVVI-shaped. It gives absolute correctness, not just self-consistency.
- **Secondary/bring-up golden: the deleted UDA backend as a reference model.** The working
  backend deleted at `5d172a5b` (review:13-16) is "the only executable statement of the
  architecture's intent" (review:164). Resurrect it via `git show 5d172a5b^:...` as a
  Scala-level reference for per-vertex (rung 1) checks during bring-up, NOT as production RTL.
- **Unit-level golden: the pure-Scala `verif.ref` emulators** (Alu/Mul/Bit) ported from main
  for rung 1.

Obligation: every scn in the corpus is graded against the ISS golden; the `verif.ref`
emulators grade rung-1 unit tests; the resurrected backend is a bring-up cross-check only.

### Alternatives considered and rejected (ladder-wide)

- **Skip rung 1, test only whole-core scn.** Rejected: whole-core-only testing cannot
  localize a UDA vertex bug and gives the N-equivalence check nothing to bisect against.
  Per-vertex tests are cheap precisely because vertices are pure functions.
- **Use only self-consistency (rung 4) and no external golden (rung 5).** Rejected: two wrong
  implementations that agree pass the differential. An ISS golden is mandatory for absolute
  correctness.
- **Author a bespoke golden model.** Rejected: Spike/Sail are the ratified references; writing
  our own is unbudgeted risk.

### Cost

- **N=1:** rungs 1-3 and 5 run; rung 4 degenerates (n1 vs n1 is trivially equal) but its
  machinery (retire-stream capture) is what makes rung 5 work, so it is not free-standing cost.
- **N=32:** rung 4 is the dominant new cost - two elaborations per program and a stream diff -
  but it is pure verif wall time, no hardware. This is the cost that buys the architecture's
  central claim.

### Open questions for the panel

1. Spike or Sail as the primary ISS golden? Spike is faster to stand up; Sail is the
   authoritative formal model. I recommend Spike for throughput now, Sail as a periodic
   cross-check.
2. What is the regression corpus? I propose: port main's `verif/scn/**` (add, csr_rmw,
   div_normal, jal_at_redirect, and the ~40 probes) verbatim as the seed, since they already
   encode the known findings (F-1..F-5), then grow it per rebuild vertex.
3. Do we require N-equivalence at N boundaries where OoO semantics genuinely differ
   architecturally (e.g. relaxed memory ordering observable to a second hart)? For a single
   hart RV32 the ISA is sequential-consistent-enough that token equality should hold; the
   panel must confirm no multi-hart config is in scope for the equivalence claim.

---

## 5. Interim gate: the pre-commit bar for this branch

Until the real spec-framework toolchain and the full scn/gate/trace harness are ported, the
only hard tool available in-session is the scalac compile gate. That is not enough to keep the
branch from rotting (it is already broken three ways, review:26-32). Normative pre-commit bar
for `claude/rebuild-architecture-review-jg0grl`, in order, each blocking:

1. **Compiles.** `sbt compile` green with the default (`useSpecPlugin=false`) build. This
   alone would have caught the `DesignSpecs` -> `DesignRuleSpecs` rename fallout and the
   deleted bundle-spec references (review:26-31). NOTE: shell `???` bodies (e.g.
   `CoreTop.scala:13`) do compile but throw at elaboration - see gate 4.
2. **Format + ASCII.** `sbt scalafmtCheckAll` and an ASCII-only check over all committed
   text (main ships `check-ascii.sh`, `origin/main:CLAUDE.md` code-style; CLAUDE.md here
   forbids non-ASCII and emoji). Blocking.
3. **Spec parity + machine checks (interim form).** The source-level checker from section 3:
   (a) no spec file uses a construct absent from the `Spec` stub API; (b) `@LocalSpec`
   coverage has no orphans (check 3); (c) each rawTop CONTRACT's mermaid edges reconcile with
   child interface sets (check 1). Run as a small ScalaTest reflection pass; blocking on
   violation with a named spec id.
4. **Elaboration progress ratchet.** A monotone counter: the set of modules that elaborate
   without `NotImplementedError` may only grow. A commit that regresses an
   already-elaborating module fails. This lets the shell fill in incrementally
   (`???` is legal in an unfilled shell, AGENTS.md permits documented empty `val`
   placeholders) while forbidding backsliding. `CoreTop`/`CommitUnit` are expected `???`
   today; once filled they are pinned green.
5. **Vertex test ratchet.** Once a vertex has a non-shell body, its rung-1 contract test
   (section 4) must exist and pass; a body without a test fails the gate. This prevents the
   "5% RTL, 0% run" state (review:20-25) from recurring silently.

Fix `BackendParams.scala:38` `require(hartId > 0)` to `>= 0` as an immediate unblock (it
rejects the default hartId=0 the same way `CoreParams.scala:26` correctly allows it); gate 1
will keep it fixed.

### Alternatives considered and rejected

- **Commit freely, fix at harness-port time.** Rejected: the branch has been dormant and
  broken since 2025-10 (review:181-185); another unbounded-rot interval is the failure mode we
  are hired to prevent. A compile+format+ratchet gate is cheap and stops it.
- **Full harness green as the bar.** Rejected as interim: the harness is not ported yet;
  blocking every commit on it stalls the port itself. The ratchets (gates 4-5) give
  monotone progress without requiring the end state.

### Cost

Zero hardware. CI wall time: `sbt compile` + scalafmt + a reflection pass, low minutes. The
ratchets are set-difference checks over an emitted module/test manifest.

### Open questions for the panel

1. Is the elaboration ratchet (gate 4) computed by attempting elaboration of every top in a
   test, or by a maintained allowlist of "must-elaborate" modules? I recommend attempt-based
   (self-maintaining) with an allowlist only for known-shell exclusions.
2. Do we adopt main's `.githooks/pre-commit` + SessionStart hook mechanism
   (`origin/main:.claude/hooks/session-start.sh`) to provision this gate, or keep it in the
   GitHub Actions `test.yml`? I recommend both: hook for local fast-fail, Actions for the
   authoritative gate.

---

## Consolidated open questions for the panel to settle

1. **Retire token payload** (1.Q1): carry `wdata` (self-contained, ISS-comparable) vs
   `rd`/`wen` only (cheaper, PRF-coupled). Recommend carry `wdata` under `usingRvvi`.
2. **Trap as a retire event** (1.Q2): RVVI `trap=1` entry vs suppression - pin before the
   TrapController contract freezes.
3. **`SpeculativeRegNum` ownership** (2.Q1): `CoreParams.tuning` vs `BackendParams`.
   Recommend `CoreParams.tuning`, pushed down.
4. **Per-commit PPA proxy** (2.Q2): area-only every commit + full STA on-demand.
5. **Interim checker substrate** (3.Q1): source-reflection now, `spec.meta.dir` metadata
   later, same test names.
6. **Mermaid dialect / edge-naming** (3.Q2): pin a deterministic grammar before more rawTop
   CONTRACTs are authored.
7. **Primary ISS golden** (4.Q1): Spike now, Sail as periodic cross-check.
8. **Regression corpus seed** (4.Q2): port main `verif/scn/**` verbatim, grow per vertex.
9. **Multi-hart scope for N-equivalence** (4.Q3): confirm single-hart only, so token equality
   is a sound equivalence.
10. **Ratchet mechanism** (5.Q1/Q2): attempt-based elaboration ratchet; local hook + CI
    Actions both.

The one thing I will not compromise on: the CommitUnit retire stream (section 1) and the
`SpeculativeRegNum` config axis (section 2) must be in the contracts BEFORE the backend shells
are filled. They are the two hooks the entire verification ladder hangs from, and retrofitting
either onto a finished dataflow graph is the exact bolt-on failure this paper exists to prevent.
