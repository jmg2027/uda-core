# rebuild branch: architecture and philosophy review

Review of `origin/rebuild` (HEAD `24b72334`, last commit 2025-10-24) from the
perspective of the shipped core on `main`. The branch is the original
development line of the project (1133 commits, 2024-04 to 2025-10, no common
ancestor with `main`); it re-founds the core around the Unified Dataflow
Architecture (UDA) and a spec-first workflow. This document records what the
branch is, what its design philosophy actually claims, and a team-level
assessment of both.

## 1. What the branch is

- 2025-09-25, commit `5d172a5b` ("clean up making usable thing leave only")
  deliberately deleted a previously working UDA backend implementation
  (386-line BackendTop, 257-line Renamer, 282-line ReservationStation,
  406-line VirtualGPR, 334-line CommitUnit, plus a large spec corpus).
- 2025-10-13, commit `e311f21b` rewrote the backend specs from the VirtualGPR
  scheme to a Unified Physical Register File (map table + free list, commit
  updates mapping only, no data writeback).
- HEAD is a spec-first skeleton: of the frontend, backend, memory subsystem
  and core top, roughly 5 percent is implemented RTL. Real logic survives only
  in leaf IP (RvcExpander, Decoder core, CSR/Debug/Trigger, external
  ALU/BitAlu/Multiplier/Divider, BootSequencer, GlobalEpochUnit). CoreTop is
  `???` on every port; BackendTop/FrontendTop/MemorySubsystemTop and all
  pipeline vertices are empty IO shells.
- HEAD does not build, on three independent levels: (1) 39 files import
  `udacore.common.spec.DesignSpecs._` which was renamed to `DesignRuleSpecs`
  in the last two commits, and the frontend bundle-spec rewrite deleted
  definitions still referenced by design and spec files; (2)
  `require(hartId > 0)` in CoreParams rejects the default `hartId = 0` config;
  (3) CoreTop elaborates to `NotImplementedError`. No verification harness
  exists on the branch, so no RTL at HEAD has ever run.

## 2. The design philosophy

Reconstructed from `docs/foundations/`, `AGENTS.md`, the spec DSL, the
committed review (`response/microarchitecture-review.md`, 2025-10-12) and the
owner's replies (`dontcommit.md`).

1. Everything is a dataflow graph. Modules are vertices; every vertex-to-
   vertex edge is a Decoupled ready/valid channel. Raw-top modules are wiring
   only (`:<>=`), no behavioral logic.
2. Control is data. There is no flush signal anywhere. Redirects and traps
   advance a global epoch; every token carries an epoch tag and
   `token.epoch === globalEpoch` is the single correctness predicate. Stale
   work self-filters locally.
3. Nodes are pure function; edges own timing. No stall logic or pipeline
   registers inside a vertex: stall is ready backpropagation, a pipeline
   register is an edge property. The base configuration may legally execute
   fetch in cycle 0 and decode/rename/issue/execute/commit in cycle 1;
   pipelining is a post-synthesis retiming act that must not change function
   (dontcommit.md items 5-6). This is latency-insensitive design applied to a
   CPU core.
4. Unified PRF backend. Decode -> Rename (map table + free list) ->
   ReservationStation (8 entries, wakeup by prd broadcast) -> Dispatch -> 7
   FUs -> PublishMux (a common data bus: PRF write, RS wakeup, commit feed) ->
   CommitUnit (in-order retirement, mapping update only, frees old physical
   register). One parameter (`SpeculativeRegNum`) is claimed to scale the same
   RTL from N=1 in-order to N=32+ wide OoO.
5. Spec first. Every vertex has a CONTRACT spec written before RTL, bound by
   `@LocalSpec`; interfaces and bundles have INTERFACE/BUNDLE specs; specs are
   "textbooks" (desc/note/table/mermaid/code). Four recurring paradigms (ECA
   epoch algebra, RAA resource algebra, MDG memory dependence, FCL flow
   control) organize review.

## 3. Assessment

### What is genuinely good

- The function/timing separation is a real methodological idea with a real
  literature behind it (latency-insensitive design, elastic circuits), and it
  is applied consistently: all-Decoupled edges make per-edge retiming and
  critical-path surgery cheap, which directly serves the configurable-PPA
  goal the project has always had.
- Epoch-only speculation control is elegant and locally checkable, and the
  one place it is implemented (Multiplier/Divider units: latch request epoch,
  kill on mismatch, gate response valid instead of buffering) is exactly the
  right lightweight pattern.
- Unified PRF with commit-time map update (no data copy) is the standard
  modern choice and is the correct resolution of the VirtualGPR ambiguity the
  2025-10-12 review flagged.
- The domain layering (design/spec mirroring, contract/tuning/private
  parameter tiers, api/ exports instead of implicit Parameters/CDE) fixes
  real maintainability pain, and the spec cross-references are Scala vals, so
  renames break compilation: cheap referential integrity.
- The repo is explicitly organized for AI-agent collaboration (AGENTS.md,
  textbook specs, `AGENT: DO NOT TOUCH` markers, committed review/reply
  documents). The spec DSL doubles as a context layer for machine
  contributors; this is ahead of its time and worth keeping.

### Where the philosophy overreaches

- "Pipelining does not change function" is true but incomplete: it changes
  IPC, and IPC is part of the microarchitecture contract. The loops that
  define a CPU (RS wakeup-select, load-use, redirect recovery) lose
  performance with every register inserted; the specs never name which edges
  are IPC-critical. Latency insensitivity should be the default, not a claim
  that timing is free.
- A single global epoch is kill-everything semantics: any redirect discards
  all younger in-flight work; there is no selective (branch-tag) squash. Fine
  for a single-issue core with one redirect source at a time, but it caps the
  OoO end of the claimed N=1..32 range. The 2-bit epoch also has an
  unaddressed wrap hazard: a long-latency op that survives 4 redirects
  aliases back to the current epoch. Either bound in-flight generations or
  widen and compare with distance.
- Same-cycle epoch exposure (`epochOut` muxed with redirect fire) plus
  "epoch injected everywhere" makes redirect a global combinational
  broadcast: the first synthesis run will hit a wall, and the philosophy's
  own remedy (register the edge) adds a cycle to the most latency-critical
  path in the machine.
- The N=1 degenerate case does not fold away the fixed tax: map table, free
  list, wakeup CAM, publish arbiter, alloc FIFO and 32-plus-bit uopId/seq
  tags remain in the netlist regardless of window size. For an embedded RV32
  whose exploitable ILP is mul/div/load latency hiding, a scoreboard on an
  in-order core captures most of the benefit at a fraction of the area. The
  OoO fabric needs a stated workload/IPC target to justify itself.

### The three silent spec holes (blocking; fill before any shell RTL)

1. Rename recovery. Epoch filtering kills in-flight results, but nothing
   reclaims physical registers or repairs the map table for renamed-then-
   squashed uops; commit only frees the old register of committed uops. As
   specified, the first mispredict leaks free-list entries and corrupts the
   speculative map. The cheap fix fits the existing philosophy: keep an
   architectural (retirement) map in CommitUnit and copy it over the rename
   map on redirect, and reclaim allocations by walking the alloc FIFO that
   already exists (`DecodedUopAlloc`).
2. Memory ordering. No store buffer vertex, no stores-drain-at-commit rule,
   no load-store ordering or forwarding contract. A speculative store
   reaching memory before commit is architecturally fatal; this must be a
   spec-level contract on the AGU -> memory path, not an implementation
   detail.
3. CSR and exception serialization. CSRCore commits side effects the cycle a
   request arrives; dispatched speculatively from an RS this corrupts
   architectural state with no undo. The spec needs an execute-at-commit (or
   drain-then-execute) rule for CSR ops, mret, and interrupt sampling, and a
   single owner for mepc/mcause writes (the current trapWrite-vs-internal
   priority mux is a double-fire seam).

### Spec system: right idea, no teeth

The SpecBuilder is a no-op (every method returns `this`, `build()` returns
Unit) and the checker plugin is compiled out, so the only integrity check is
name resolution, and HEAD already shows spec rot: FetchUnit's module contract
contradicts the top-level graph (boot edge destination, missing memory
ports), MemoryDispatcher's contract has the reverse dataflow direction from
the top mermaid, and docs/summary.md describes a third, older frontend. A
spec system whose claims are not machine-checked converges to spec theater.
To keep it honest: emit the specs to rendered docs, machine-check graph
consistency (module CONTRACT ports vs top mermaid edges), and tie PROPERTY
entries to elaboration-time assertions.

## 4. Recommendations

1. Decide the identity first: an embedded elastic in-order core (keep epochs,
   LI edges, drop rename/PRF) or a research vehicle for parametric
   in-order-to-OoO scaling. They are different projects with different
   verification budgets; the current tree pays OoO complexity at in-order
   IPC.
2. Fill the three spec holes above before writing any shell RTL.
3. Repair the mechanical breakage: DesignSpecs import rename fallout, deleted
   bundle spec definitions, `require(hartId > 0)` vs default 0, BootSequencer
   ignoring the `bootCycles` config knob, epoch wrap policy.
4. Resurrect the deleted implementation (`git show 5d172a5b^:...`) as a
   reference model; it is the only executable statement of the architecture's
   intent. Port the main-branch verif harness (scn/gate/trace) - it is
   AI-native and exactly what this branch lacks.
5. Name the IPC-critical edges in the specs (wakeup-select, load-use,
   redirect) with explicit cycle budgets, so "pipeline it later" cannot
   silently regress performance.
6. Make SpeculativeRegNum=1 a true degenerate case: the CAM, arbiter and map
   structures must elaborate away, or the in-order point of the range is not
   competitive with the shipped core.

## 5. Verdict

Philosophy: coherent, literate, and largely correct as a discipline; its two
pillars (control-as-data epochs, edges-own-timing elasticity) are proven ideas
applied with unusual consistency. Architecture: a credible unified-PRF sketch
whose three hardest problems (rename recovery, memory ordering, CSR
serialization) are exactly the ones the specs do not yet address. Execution: a
mid-refactor snapshot, broken at HEAD, dormant since 2025-10, with the working
predecessor deleted and only recoverable from git history. The ideas deserve
to live on; the branch as it stands is a design document encoded in Scala, and
should be treated (and repaired) as such.
