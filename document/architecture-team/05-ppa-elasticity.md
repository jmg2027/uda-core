# Position Paper 05 - Physical Design / PPA / Elasticity

Author: Physical design / PPA / elasticity lead
Scope: Normative contracts that keep the "one RTL, N=1 in-order to N=32 OoO"
claim honest at the gate level. Branch `claude/rebuild-architecture-review-jg0grl`.

Normative language: MUST / MUST NOT / SHOULD / MAY are used in the RFC-2119
sense. Each decision states the contract, alternatives rejected, cost at N=1
(which MUST be ~zero), cost at N=32, the verification obligation, and open
questions for the panel.

This paper owns the elasticity discipline: the project's central bet is
`dontcommit.md` items 5-6 - "no stall logic or pipeline registers in nodes;
the whole backend may legally execute in one cycle; pipelining is post-PD
retiming." That bet is sound as a *function/timing* separation. It is
dangerous as a *PPA/IPC* statement, because retiming is free in function and
never free in IPC. The accepted review says this in one line
(`document/rebuild_branch_architecture_review.md`, section 3, "Where the
philosophy overreaches", bullet 1): the loops that define a CPU lose IPC with
every register inserted, and the specs never name which edges are
IPC-critical. My job is to name them and price them.

---

## 0. Binding constraints (inherited, not up for debate here)

- **C1 - Nodes pure, edges own timing.** No stall logic, no pipeline registers
  inside a vertex. Stall is ready backpressure; a pipeline register is an edge
  property (`dontcommit.md` 5-6). The whole backend MAY execute in one cycle.
- **C2 - Control is data.** Redirects/traps bump `GlobalEpochUnit.epoch`;
  `token.epoch === globalEpoch` is the correctness predicate; buffers check it
  before they act (`docs/foundations/dataflow-execution-model.md` sec 1).
- **C3 - Unified PRF, `33 + N` entries**, commit updates the map only
  (`src/main/scala/udacore/backend/spec/modules/PhysicalRegisterFileSpecs.scala:28`).
- **C4 - One parameter (`SpeculativeRegNum` = N) scales the design**
  (`src/main/scala/udacore/backend/spec/shared/BackendParamsSpecs.scala:84-89`).
  N=1 MUST be competitive with a plain in-order core; OoO structures MUST
  elaborate away (review sec 4, recommendation 6).

### 0.1 Calibration: the in-order PPA reality we must not regress

From the shipped `main` core (yosys 0.33 + sky130 HD, out-of-context; the same
flow this branch will port). Numbers are the *floor*, not tape-out.

| metric | value | source (origin/main) |
|--------|-------|----------------------|
| whole-core area (prod, RVVI off) | 532,234 um^2 | `document/whole_core_ppa_findings.md` |
| DFF (state bits) | 8,119 | same |
| standard cells | 83,132 | same |
| OOC worst-case frequency estimate | ~46-47 MHz (period ~21.5 ns) | same |
| `CSRModule` (64-entry TNP bank) | 219,129 um^2 | same |
| `RegisterFile` (31x32 GPR + read ports) | 50,843 um^2 | same |
| `SliceMultiplier` | 48,918 um^2 | same |
| `LSUwithStoreBuffer` | 42,325 um^2 | same |
| `NonRestoringDividerCore` | 11,335 um^2 | same |
| IPC (BpBench, gshare16) | ~0.57 | `document/ipc_freq_winwin_findings.md` |
| whole-core worst path | load-data bypass -> rs1 -> AGU/TGU add -> JALR target -> TNP matcher -> hold reg (reg-to-reg) | `document/ipc_freq_winwin_findings.md`, `document/whole_core_ppa_findings.md` |

Two facts that anchor every decision below:

1. **The register file is already ~10% of the core** at 32 architectural
   entries with a couple of read ports. A unified PRF of `33 + N` entries with
   the read/write/CAM port count OoO needs is the single largest area risk in
   this project. The N=1 point must not pay for ports it does not use.
2. **The worst path on the shipped core is already a wide combinational
   reg-to-reg cone terminating in a fan-out-heavy compare** (TNP matcher, and
   separately the redirect/trigger fan-out documented in
   `document/frontend_redirect_critical_path.md`). A *global combinational
   epoch broadcast* (decision 3) drops a second such cone on top of it. This
   is the timing wall the review predicts (sec 3, bullet 3).

---

## 1. The IPC-critical edge registry (DECIDE 1)

### 1.1 The hole

`dontcommit.md` says pipelining is a post-PD retiming act that "should not
change function." True and irrelevant to IPC. There is today no artifact that
records, per edge, whether inserting a register costs IPC and by how much. So
a future timing-closure pass can register the wakeup->select loop to hit
frequency and silently halve OoO IPC, and no spec will flag it. The review
names this as recommendation 5.

### 1.2 Contract (normative)

Every Decoupled edge in the backend/frontend graph is classified as one of:

- **ELASTIC** - latency-insensitive; any number of registers MAY be inserted
  post-PD; only frequency is affected. This is the default (C1). No budget.
- **IPC-CRITICAL** - added latency costs measurable IPC. Each such edge
  carries an explicit per-configuration **cycle budget** `B(N)` = the maximum
  number of registered stages the edge may contain. Inserting a stage beyond
  `B(N)` is a **contract violation** and MUST fail elaboration.

The IPC-critical registry (the complete list; anything not here is ELASTIC):

| id | edge (producer -> consumer) | why IPC-critical | B(1) | B(8) | B(32) |
|----|-----------------------------|------------------|------|------|-------|
| `edgeWakeupSelect` | PublishMux wakeup (prd) -> RS match -> select/grant | back-to-back dependent issue; a register here forces a bubble between producer and dependent every time | 0 | 0 | 0 |
| `edgePublishToWakeup` | FU result -> PublishMux -> wakeup broadcast | closes the wakeup loop; a register adds one bubble to *every* dependent chain | 0 | 0 | 1 |
| `edgePrfReadToExec` | RS issue -> PRF read -> FU operand | on the issue-to-execute path; a register delays every issue by a cycle but does not open the wakeup loop | 0 | 1 | 1 |
| `edgeRedirectToFetch` | branch/trap resolve -> GlobalEpoch -> AddressArbiter -> fetch req | mispredict penalty; each stage adds one cycle to every mispredict | 1 | 2 | 2 |
| `edgeLoadUse` | AGU -> mem -> load data -> bypass -> dependent operand | load-use penalty; each stage adds one cycle to every dependent-on-load | 1 | 1 | 2 |

Reading of the budgets:

- `edgeWakeupSelect` = **0 at every N.** This is the single hard invariant of
  OoO: wakeup and select MUST resolve in one cycle or dependent instructions
  cannot issue back-to-back and the window stops hiding latency. At N=1 this
  is trivially met (no wakeup CAM exists; see decision 4/paper 01). If this
  edge cannot close timing at N=32, the answer is a smaller window or a
  physically-banked PRF, **not** a register on this edge.
- `edgePublishToWakeup` may take **1 stage at N=32** because at a wide window
  the result-bus fan-out is large; the cost is one bubble on dependent chains,
  which a deep window can partially absorb by issuing independent work. At N=1
  and N=8 it stays 0 (the window is too shallow to absorb the bubble).
- `edgePrfReadToExec` may take 1 stage from N=8 up (classic register-read
  pipeline stage). At N=1 it is 0: the "whole backend in one cycle" model
  (C1) means issue and execute share a cycle.
- `edgeRedirectToFetch` B(1)=1 matches the shipped in-order core, which
  already spends a cycle from resolve to redirected fetch. Widening to 2 at
  N>=8 is the budget that pays for registering the epoch broadcast (decision
  3).
- `edgeLoadUse` B(1)=1 matches the shipped core's combinational-bus TCM load
  (`document/ipc_freq_winwin_findings.md`: the load-data bypass path); paper
  02 (memory) owns the mechanism, this registry owns the number.

### 1.3 Spec-DSL form (paste-ready)

The budget is expressed as a `PROPERTY` per edge plus a derived-parameter
function, and enforced by an elaboration-time `require` on the edge's stage
count. Because the SpecBuilder is a stub (`src/main/scala/framework/specs/Spec.scala`),
the *teeth* are the Scala `require`, not the DSL; the DSL records intent.

```scala
// in backend/spec/shared/EdgeBudgetSpecs.scala
object EdgeBudgetSpecs {
  // B(N) tables as pure functions so specs and RTL share one source of truth.
  def wakeupSelectBudget(n: Int): Int   = 0
  def publishToWakeupBudget(n: Int): Int = if (n >= 32) 1 else 0
  def prfReadToExecBudget(n: Int): Int   = if (n >= 8) 1 else 0
  def redirectToFetchBudget(n: Int): Int = if (n >= 8) 2 else 1
  def loadUseBudget(n: Int): Int         = if (n >= 32) 2 else 1

  val propWakeupSelectBudget = spec {
    PROPERTY("WakeupSelectBudget")
      .desc("Wakeup broadcast to select/grant MUST resolve in one cycle at all N.")
      .entry("budget.N1",  "0 registered stages")
      .entry("budget.N8",  "0 registered stages")
      .entry("budget.N32", "0 registered stages")
      .note("Violating this makes back-to-back dependent issue impossible; " +
            "reduce window or bank the PRF instead of registering this edge.")
      .code("scala",
        "require(edge.stages <= wakeupSelectBudget(N), " +
        "s\"edgeWakeupSelect over budget at N=$N\")")
      .build()
  }
  // ... one PROPERTY per registry row, identical shape ...
}
```

The enforcement hook (normative): every IPC-critical edge is constructed
through a helper that takes its budget function and asserts at elaboration:

```scala
// backend/design/shared/RetimedEdge.scala
def ipcCriticalEdge[T <: Data](gen: T, stages: Int, budget: Int, id: String): T = {
  require(stages <= budget, s"$id: $stages registered stages exceeds budget $budget")
  // stages == 0 -> pass-through (Wire); stages > 0 -> that many Queue(1)/Pipe cuts.
  ...
}
```

`stages` defaults to 0 everywhere; a timing engineer who wants to cut an edge
must raise it and will hit the `require` if the edge is over budget. That is
the mechanism that makes "pipeline it later" a contract violation unless
budgeted.

### 1.4 Alternatives rejected

- **Comment/convention only.** Rejected: the review already documents spec rot
  when claims are not machine-checked (review sec 3, "Spec system: right idea,
  no teeth"). A budget that is not a `require` is decoration.
- **A single global "max pipeline depth" knob.** Rejected: it cannot express
  that `edgeWakeupSelect` is 0-forever while `edgePrfReadToExec` may grow.
  Per-edge budgets are the minimum expressiveness.

### 1.5 Cost

- N=1: zero. All budgets that matter at N=1 are 0 or match the shipped
  in-order core (redirect=1, load-use=1). The `require`s are elaboration-time,
  no gates.
- N=32: zero area; the budgets *permit* retiming that a timing pass would want
  anyway, and *forbid* the retiming that would wreck IPC.

### 1.6 Verification obligation

- Elaboration: the `require` per edge (above) - a synthesis of the netlist with
  an over-budget `stages` MUST fail to elaborate. Add a unit test that sets
  `edgeWakeupSelect.stages = 1` and asserts elaboration throws.
- IPC regression: a directed test (dependent-chain microbench, ported from the
  main-branch `verif` suites) that measures issue-to-issue latency of a
  dependent pair and asserts it is 1 cycle at every N. If a future edit
  registers the wakeup loop, this test's cycle count changes.

### 1.7 Open questions for the panel

- Is `edgePublishToWakeup` B(32)=1 acceptable, or must the wide-window point
  also hold 0 (forcing a banked result bus)? This is an IPC-vs-frequency call
  the workload target (decision 5) should settle.
- Does the frontend have any IPC-critical edge beyond `edgeRedirectToFetch`
  (e.g. fetch-to-decode under a taken predict)? Paper 03 (frontend) should
  confirm the registry is complete on its side.

---

## 2. Epoch wrap policy (DECIDE 2)

### 2.1 The hole

`GlobalEpochUnit` (`src/main/scala/udacore/core/design/modules/GlobalEpochUnit.scala:19-33`)
increments a `RegInit(0.U(epochWidth.W))` on every `redirectFire`, default
`epochWidth = 2` (`CoreParams.scala:33`). Four redirects wrap `E -> E`. A
token that latched epoch `E` and is checked for `epoch === globalEpoch` only
at consumption will, after four redirects, alias back to the current epoch and
be **wrongly accepted as valid** (review sec 3, bullet 2). The 2-bit width is
unsafe *unless* something guarantees a token cannot survive that many
generations unchecked.

### 2.2 Contract (normative)

Adopt **eager filtering plus a proven wrap bound**, not a wide counter.

1. **Eager-filter invariant (the real fix).** Every element that *stores* an
   epoch-tagged token - reservation-station entry, FU request latch, any edge
   register, any buffer - MUST evaluate `token.epoch === globalEpoch`
   combinationally *every cycle it holds the token* and self-invalidate on
   mismatch. It MUST NOT defer the compare to consumption. This is already the
   correct pattern the review praises (mul/div latch request epoch and kill on
   mismatch: review sec 3, "genuinely good", bullet 2;
   `MultiplierUnit.scala`/`DividerUnit.scala`). Under this invariant a token
   dies at the *first* redirect after it becomes wrong-path, so it can never
   survive even one wrap.

2. **Wrap-bound assertion (belt and suspenders).** Define a derived parameter
   `maxSurvivableGenerations` = the maximum number of `redirectFire` events a
   single token may observe while still holding an unchecked epoch. Under the
   eager-filter invariant this is `1` (a token is checked every cycle, so it
   survives at most the cycle of the redirect itself). The elaboration
   assertion MUST hold:

   ```
   require((1 << epochWidth) > maxSurvivableGenerations + 1)
   ```

   With `maxSurvivableGenerations = 1` this gives `epochWidth >= 2`, which the
   default already satisfies with one generation of margin. If anyone later
   adds a vertex that latches an epoch and defers its compare (breaking the
   eager rule), `maxSurvivableGenerations` grows to that vertex's latency and
   the `require` forces `epochWidth` up or the design fails to build.

3. **Exact-match compare, not distance.** Because the eager rule kills at the
   first mismatch, tokens never legitimately carry a "recent but not current"
   epoch, so distance comparison buys nothing and costs a subtractor on the
   fan-out-heavy compare. Keep `epoch === globalEpoch`.

### 2.3 Spec-DSL form

```scala
val propEpochWrapBound = spec {
  PROPERTY("EpochWrapBound")
    .desc("Epoch width must exceed the generations any token can survive unchecked.")
    .entry("invariant", "every epoch-holding element compares epoch === globalEpoch each cycle")
    .entry("maxSurvivableGenerations", "1 (eager filtering) unless a deferring vertex is added")
    .code("scala",
      "require((1 << epochWidth) > maxSurvivableGenerations + 1, " +
      "\"epoch too narrow for the deferred-compare vertices present\")")
    .note("A deferring vertex raises maxSurvivableGenerations to its latency and forces a wider epoch.")
    .build()
}
```

### 2.4 Alternatives rejected

- **Widen epoch to cover the worst FU latency (divider ~34 cycles -> 6 bits)
  with distance compare.** Rejected as the *primary* mechanism: it puts a
  6-bit tag on every token and a subtractor on every compare, taxing N=1 for a
  hazard that eager filtering removes for free. It survives only as the
  fallback the `require` selects if the eager invariant is ever broken.
- **Bound in-flight generations by stalling `redirect.ready`** until the
  aliasing generation drains. Rejected: it puts a stall on the mispredict
  path - the most IPC-sensitive event - and contradicts kill-everything
  semantics (a redirect must be able to fire immediately).

### 2.5 Cost

- N=1: zero over today (`epochWidth = 2` unchanged; the eager compare is a
  2-bit XOR/equality already implied by C2 "buffers check before they act").
- N=32: the eager compare fans `globalEpoch` to every RS entry and buffer -
  this is exactly the broadcast load decision 3 prices. `epochWidth` stays 2,
  so the tag is 2 bits regardless of window.

### 2.6 Verification obligation

- Elaboration: the `require` above.
- Directed test: a long-latency op (divide) in flight across >= `2^epochWidth`
  back-to-back redirects; assert its result is dropped, not committed. This is
  the exact wrap-alias scenario; it must pass at `epochWidth = 2`.
- Assertion in RTL: at every consumption of an epoch-tagged token, `assert(!fire
  || token.epoch === globalEpoch)` - a wrong-path token reaching a consumer is
  a bug by construction under eager filtering.

### 2.7 Open questions

- Is the eager-filter invariant acceptable to the frontend's fetch queue and
  the memory subsystem's store buffer, or do those structures need a
  documented exception (and thus a wider epoch)? Papers 02 and 03 must confirm
  they can self-invalidate each cycle.

---

## 3. Redirect / epoch distribution timing (DECIDE 3)

### 3.1 The problem

`GlobalEpochUnit` exposes epoch same-cycle:
`io.epochOut := Mux(redirectFire, epochIncrement, epoch)`
(`GlobalEpochUnit.scala:35-38`, spec `funcSameCycleEpochExposure`
`GlobalEpochUnitSpecs.scala:70-79`). Combined with "epoch injected
everywhere" and the eager-filter invariant (decision 2), `globalEpoch` becomes
a combinational net fanning out to every RS entry, every buffer, every FU
latch, and the fetch redirect - on top of the redirect *target* fan-out that
is already a top timing problem on the shipped core
(`document/frontend_redirect_critical_path.md`). The review predicts the first
synthesis run hits a wall here (sec 3, bullet 3).

### 3.2 Contract (normative)

Split the epoch fan-out into two classes with different timing rules:

1. **Commit/redirect-generation path: 0 stages, forever.** The epoch value
   consumed by (a) the commit gate that permits architectural state change and
   (b) the redirect generator MUST be the combinational, same-cycle value. No
   register. Rationale: correctness. If commit saw a stale epoch it could
   retire wrong-path work.

2. **Speculative-consumer path: registerable, budgeted.** The epoch copy fed
   to purely-speculative consumers that CANNOT commit architectural state (RS
   entries, PRF read, FU operand latches, fetch-queue filtering) MAY be
   registered - i.e. distributed one cycle late - **iff** those consumers only
   ever *drop* work on mismatch and never *commit* on match. Registering this
   copy costs one extra cycle of "wrong-path work kept alive," which is
   harmless (it self-drops one cycle later) but adds one cycle to
   `edgeRedirectToFetch`. Budgeted under decision 1:
   - N=1: 0 stages (combinational broadcast). The graph is small; the fan-out
     is bounded; `edgeRedirectToFetch` = 1 total, matching the shipped core.
   - N>=8: 1 registered stage permitted on the speculative-consumer copy;
     `edgeRedirectToFetch` = 2. This buys the frequency the large fan-out
     needs, at a +1-cycle mispredict penalty.

### 3.3 Mispredict-penalty cost (quantified)

IPC penalty of registering the broadcast = mispredict_rate x 1 cycle. From
`document/ipc_freq_winwin_findings.md`, BpBench at gshare16 sees ~62
mispredicts; the shipped core there runs IPC ~0.57 over the bench's
instruction count. Adding one cycle per mispredict is roughly
`62 / totalCycles` IPC loss - low single-digit percent on that bench,
front-loaded onto exactly the workloads with poor prediction. For the N=1
point we refuse to pay it (0 stages); for N>=8 the window absorbs part of the
bubble and the frequency headroom is worth more than the IPC. This is the
per-config split.

### 3.4 Spec-DSL form

```scala
val propEpochDistribution = spec {
  PROPERTY("EpochDistributionTiming")
    .desc("Commit/redirect see epoch combinationally; speculative consumers may see it registered.")
    .entry("commitPath.stages", "0 (normative, all N)")
    .entry("specPath.stages.N1", "0")
    .entry("specPath.stages.N8plus", "0..1 (counts against edgeRedirectToFetch budget)")
    .entry("invariant", "registered epoch is legal only where the consumer cannot commit arch state")
    .code("scala",
      "require(commitEpochStages == 0, \"commit must see epoch same-cycle\")\n" +
      "require(specEpochStages <= redirectToFetchBudget(N) - 1)")
    .note("Registering the speculative copy adds one cycle to mispredict penalty; " +
          "priced at mispredictRate x 1 cycle, refused at N=1.")
    .build()
}
```

### 3.5 Alternatives rejected

- **Always combinational (today).** Rejected at N>=8: it is the documented
  timing wall; the shipped core already fights redirect fan-out at
  single-issue, and eager filtering multiplies the endpoint count.
- **Always registered.** Rejected: taxes N=1 with a mispredict cycle it does
  not need, and risks registering the *commit* epoch, which is a correctness
  bug.

### 3.6 Verification obligation

- Elaboration: the two `require`s (commit stages == 0; spec stages within
  budget).
- Assertion: `assert(commitFire -> committedEpoch === globalEpoch_comb)` - the
  committing element used the same-cycle epoch.
- STA experiment (decision 5 flow): report the epoch net's fan-out and worst
  slack at N=1/8/32; confirm N=1 combinational closes at the in-order target
  frequency and N=32 needs the registered stage.

### 3.7 Open questions

- Does trap/interrupt redirect share the same distribution path as branch
  redirect, or does it need its own (traps are rarer, may tolerate an extra
  cycle)? CSR/priv paper 04 should weigh in.

---

## 4. Tag budget and handshake overhead (DECIDE 4)

### 4.1 The hole

`uopId` and `seq` are hard-coded `UInt(32.W)` in the bundles
(`src/main/scala/udacore/backend/design/shared/BackendBundles.scala:86,91,97,
102,145,148,155,159`). 32 bits of tag ride on every token, every edge, every
RS entry, every FU latch, regardless of window size. At N=1 this is pure waste
that lands in the netlist the review flags (sec 3, bullet 4: "32-plus-bit
uopId/seq tags remain regardless of window size").

### 4.2 Contract (normative)

All tags are derived parameters, functions of N and the in-order archregnum:

- **Physical register id.** `physRegIdWidth = log2Ceil(archRegNum + N)`.
  N=1 -> `log2Ceil(33+1)=6` bits; N=32 -> `log2Ceil(33+32)=7` bits. Never a
  fixed constant.
- **In-flight sequence tag.** `seqWidth = log2Ceil(maxInFlight)`, where
  `maxInFlight` is the maximum number of simultaneously live uops = a function
  of N, RS depth, and FU count. At N=1 in-order, `maxInFlight` is a small
  constant (a handful of stages + the longest FU) -> ~3-4 bits. At N=32 ->
  ~6-7 bits. Never 32.
- **uopId == seq (proposed merge).** In a single-issue machine `uopId` (uop
  identity) and `seq` (program order) are the same rolling counter; carrying
  both is redundant. Contract: unify into one `seqTag` of `seqWidth`. (Open
  question 4.6 if multi-issue lanes later need a distinct within-bundle id.)

```scala
// BackendParams derivations (normative)
def physRegIdWidth: Int = log2Ceil(regNum /*arch*/ + speculativeRegNum + 1)
def maxInFlight: Int    = /* f(N, rsDepth, fuCount): in-order small, OoO ~ window */
def seqWidth: Int       = log2Ceil(maxInFlight)
```

### 4.3 Handshake-overhead audit: legal always-ready edges

C1 makes every edge Decoupled, but a `ready` that is provably always-true is
dead logic the N=1 point should not pay for. An edge MAY be declared
**always-ready** (ready hard-wired true, the ready wire and its back-pressure
tree elaborated away) **iff** a named `PROPERTY` invariant proves the consumer
can never back-pressure. The audited list:

| edge | may be always-ready? | named invariant |
|------|----------------------|-----------------|
| PRF read port (RS issue -> PRF) | YES | PRF read is stateless combinational; a read has no structural hazard and never stalls |
| PublishMux -> PRF write (per producer) | YES, iff one write port per result producer | no structural write hazard when write ports == result producers (RAA: resource reserved at issue) |
| wakeup broadcast | N/A | not a Decoupled edge; a broadcast is always consumed (fan-out, no back-pressure) |
| epoch / interrupt / debugReq | already exempt | constitution / C2 |
| RS enqueue (rename -> RS) | NO | RS can be full; back-pressure is real and load-bearing |
| dispatch -> FU (issue) | NO | FU can be busy (mul/div multi-cycle); back-pressure is real |

The contract: always-ready is an *opt-in with a proof obligation*, never a
default, and the invariant string is mandatory so a reviewer can audit it.

```scala
val propPrfReadAlwaysReady = spec {
  PROPERTY("PrfReadAlwaysReady")
    .desc("PRF read edge carries no back-pressure; ready is constant true.")
    .entry("invariant", "combinational stateless read, no structural hazard")
    .code("scala", "prfReadEdge.ready := true.B // elaborated away")
    .note("Any change that makes PRF read stateful (e.g. a read FIFO) voids this invariant.")
    .build()
}
```

### 4.4 Alternatives rejected

- **Keep 32-bit tags for simplicity.** Rejected: it is the exact fixed tax the
  review names; it lands in every RS entry and edge register and directly
  regresses the N=1 area bar (decision 5).
- **Make every edge unconditionally ready-handshaked.** Rejected: the always-
  ready audit removes provably-dead ready trees at N=1 for free; refusing it
  taxes the in-order point for OoO generality it is not using.

### 4.5 Cost

- N=1: strictly negative (removes waste). Tags shrink from 64 bits (uopId+seq)
  to ~4-6 bits total; provably-dead ready trees vanish.
- N=32: tags are ~7+7 bits, sized to the window; no over-provision.

### 4.6 Verification obligation

- Elaboration: assert `physRegIdWidth`, `seqWidth` derive from N (a config
  sweep N in {1,8,32} produces monotonically non-decreasing widths, never 32).
- Netlist check: grep the emitted Verilog at N=1 for a 32-bit `uopId`/`seq`
  field; its presence is a failure.
- For each always-ready edge, an RTL assertion `assert(edge.ready)` proving the
  hard-wire is never contradicted by a consumer that (wrongly) tries to stall.

### 4.7 Open questions

- `maxInFlight` needs a closed-form from paper 01 (backend): it depends on the
  RS depth and FU latencies that paper owns. Until it is pinned, `seqWidth` is
  a placeholder.
- If a future multi-issue lane count > 1 is in scope, does `uopId` need a
  within-bundle lane sub-field distinct from `seq`? (Would un-merge 4.2.)

---

## 5. The N=1 PPA acceptance criterion (DECIDE 5)

### 5.1 The claim that needs a number

C4 says one RTL scales N=1..32 and the N=1 point is competitive with a plain
in-order core. That is a falsifiable PPA claim and currently has no bar. The
review's recommendation 6 demands the CAM/arbiter/map structures "elaborate
away or the in-order point is not competitive."

### 5.2 Contract (normative)

Define the reference and the bar.

**Reference baseline.** A scoreboard in-order RV32IMC core of equivalent ISA
scope. The most honest available reference is the shipped `main` core with its
non-general special features stripped for apples-to-apples: subtract the
64-entry TNP CSR bank (219,129 um^2 of the 532,234 total is that bank alone;
`document/whole_core_ppa_findings.md`) and RVVI (already off in prod). That
leaves an in-order integer RV32IMC core of order ~310-330k um^2, ~8,000 DFF,
~46 MHz OOC as the reference envelope. (The exact reference is re-measured by
the synth flow below, not asserted from this subtraction.)

**Acceptance bar for the N=1 elaboration of the rebuild RTL:**

1. **Area:** within **+10%** of the reference in-order core (sky130 HD, yosys
   OOC, same flow).
2. **State:** DFF count within **+10%** of the reference.
3. **Frequency:** OOC worst-slack frequency estimate within **-5%** of the
   reference.
4. **Structural elaboration-away (hard gate, not a percentage):** the emitted
   N=1 netlist MUST contain **zero** instances of - the wakeup CAM / RS match
   matrix, the free-list storage, the map-table CAM, the publish arbiter tree
   beyond a single producer, and MUST reduce `uopId`/`seq` to their N=1 widths
   (decision 4). This is checked by grepping the emitted Verilog for the named
   modules; any hit fails the bar regardless of the area percentage.

The 10%/5% envelope is the "honest" margin: it allows the unified-PRF machine
a small structural overhead for its generality (the map table degenerates to a
direct index, not literally free) while forbidding it from carrying an OoO
netlist into the in-order config.

### 5.3 Spec-DSL form

```scala
val propN1PpaBar = spec {
  PROPERTY("N1PpaAcceptance")
    .desc("At SpeculativeRegNum=1 the core must be competitive with a scoreboard in-order baseline.")
    .entry("baseline", "shipped main core, TNP-bank and RVVI stripped; re-measured by synth flow")
    .entry("area", "<= reference + 10% (sky130 HD, yosys OOC)")
    .entry("dff", "<= reference + 10%")
    .entry("freq", ">= reference - 5% (OOC worst-slack estimate)")
    .entry("structural", "netlist contains ZERO wakeup-CAM / free-list / map-CAM / multi-producer arbiter; tags at N=1 width")
    .note("Structural clause is a hard gate; percentages allow small map-degeneracy overhead only.")
    .build()
}
```

### 5.4 Synthesis experiments to run once the main synth flow lands

Port the main-branch flow verbatim (it is the calibration source): the
`verif/suites/EmitCore` emitter, the yosys 0.33 + sky130 HD OOC synthesis, and
`verif/bin/setup-sta.sh` / `verif/bin/sta.sh` for constrained STA
(`document/whole_core_ppa_findings.md`, methodology). Then:

1. **N sweep.** Emit the rebuild core at N in {1, 2, 4, 8, 16, 32}. Report
   chip area (um^2), DFF, cells, OOC worst slack for each. Produce area-vs-N
   and freq-vs-N curves. Expected shape: area roughly linear in N above a
   fixed floor; the floor at N=1 is what the bar constrains.
2. **N=1 vs reference.** Emit the reference in-order core (main, TNP/RVVI
   stripped) in the same flow; compute the three percentages and run the
   structural grep. This is the pass/fail gate.
3. **Epoch-broadcast STA (feeds decision 3).** At N=1/8/32 report the
   `globalEpoch` net fan-out and the worst path through it, combinational vs
   registered, to confirm the per-config split (0 stages at N=1, 1 at N>=8).
4. **Wakeup-select STA (feeds decision 1).** At N=8/32 report the worst path
   through `edgeWakeupSelect`; confirm it closes at the target period with 0
   registered stages, or the panel must accept a smaller max window.

### 5.5 Alternatives rejected

- **No bar / "trust the parameter."** Rejected: it is precisely the untested
  claim the review distrusts; without a number the N=1 point can silently be
  an OoO core wearing an in-order label.
- **Area-only bar.** Rejected: an OoO netlist can hit area parity while missing
  frequency (the wakeup/broadcast cones) or while retaining the CAM as dark
  logic; the structural hard-gate and the freq clause close both loopholes.
- **Compare against a published third-party in-order core** (e.g. a Rocket
  small config). Rejected as the primary baseline: different flow, ISA scope,
  and cell library make the percentages meaningless; the shipped `main` core
  in the *same* flow is the only apples-to-apples reference.

### 5.6 Cost

- N=1: the bar *is* the N=1 cost budget; it does not add cost, it bounds it.
- N=32: the sweep quantifies the OoO area/freq the window buys; no bar imposed
  there beyond "monotonic and explainable."

### 5.7 Verification obligation

- CI gate: the N=1-vs-reference experiment (5.4 step 2) runs in the synth CI
  once the flow lands; a regression past +10% area or the structural grep is a
  build failure.
- The N-sweep curves are a committed artifact (like the main-branch
  `document/*_ppa_report.md`), regenerated per release.

### 5.8 Open questions

- Is +10% area the right envelope, or does the owner want the harder "within
  parity" bar (which likely forces the map table to a literal direct-index mux
  at N=1, extra elaboration branching)? This trades N=1 competitiveness
  against RTL uniformity - a project-identity call.
- Should the reference include M-extension (mul/div) or be a pure integer
  scoreboard core? The mul/div area (~60k um^2 combined) is common to both, so
  including it tightens the percentage; recommend including it.

---

## 6. Summary of contracts

1. **IPC-critical edge registry** (5 edges, per-N cycle budgets, enforced by
   elaboration `require`; wakeup-select = 0 forever).
2. **Epoch wrap** = eager per-cycle filtering + `require((1<<epochWidth) >
   maxSurvivableGenerations+1)`; keep `epochWidth = 2`, exact-match compare.
3. **Epoch distribution** = combinational to commit/redirect (0 stages,
   forever); registerable to speculative consumers (0 at N=1, <=1 at N>=8,
   budgeted).
4. **Tags** = `physRegIdWidth = log2Ceil(33+N)`, `seqWidth =
   log2Ceil(maxInFlight)`, unify uopId/seq; provably-dead `ready` trees
   elaborated away with named invariants.
5. **N=1 bar** = within +10% area / +10% DFF / -5% freq of a stripped
   main-branch in-order reference, plus a hard structural grep that the
   CAM/free-list/map-CAM/wide-tag elaborate away; enforced in synth CI once the
   main flow is ported.

All five hold N=1 cost at zero or negative; all five have an elaboration-time
or synth-CI enforcement so they cannot silently rot.
