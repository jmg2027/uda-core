# Critique: Functional Correctness and Architectural Safety

Adversarial review of position papers 01-06 in `document/architecture-team/`.
Lens: state corruption, deadlock, RISC-V semantic violations, and contradictory
contracts (same edge, different rules across papers).

Method: each finding has a severity (blocker / major / minor), the paper(s) and
section, the flaw with a concrete failure scenario, and either a fix or the
question the panel must answer. Grounded in the papers and the tree
(`GlobalEpochUnit.scala`, `CoreParams.scala:33`, `BackendTopSpecs.scala`,
`RedirectUnitSpecs.scala`).

The through-line: five of the six papers silently assume mutually different
answers to one unstated question -- **where and when is a branch redirect
generated?** The single-global-epoch model makes that question load-bearing for
correctness, and no paper pins it. B1 below is the root; B2, M1, M3 are its
branches.

---

## BLOCKERS

### B1. Redirect timing is unspecified, and every recovery/precision contract is correct ONLY if branch redirect fires at the in-order commit head

Papers: 01 sec1-2, 04 sec2-4, 05 DECIDE 1/3, 02 M1. Root cause.

The machine has a **single global epoch** incremented by one `Bool`
(`GlobalEpochUnit.scala:22-33`) and a **pure equality** consumption predicate
`token.epoch === globalEpoch` (`dataflow-execution-model.md:8-12`; Paper 05
DECIDE 2 mandates exact-match, not distance). Equality cannot distinguish an
older in-flight token from a younger one: both were stamped in epoch E, and after
any redirect `globalEpoch = E+1`, so **both mismatch**. The scheme therefore
silently requires that at the instant a redirect fires, there is **no
correct-path (older) work still in flight** -- i.e. the redirect is generated at
the in-order commit head, where everything older has already retired.

Three independent contracts each depend on this and each breaks under
execute-time branch resolution:

1. **Completion scoreboard deadlock (Paper 01 sec2.1).** An older long-latency op
   (divide, ~34 cycles; `DividerUnit` latches request epoch and kills on
   mismatch -- the "good pattern") is in flight when a younger branch mispredicts.
   If the branch redirects at execute, `epoch E -> E+1`; the divide eager-filters
   itself (Paper 05 DECIDE 2) and drops its response. But the divide is the
   alloc-FIFO head's older sibling and is correct-path: its `PublishResult` never
   arrives, the completion-scoreboard done-bit is never set, and the commit head
   **stalls forever**. Deadlock + lost instruction.

2. **Rename recovery discards correct renames (Paper 01 sec1.2).** Recovery is
   "overwrite the speculative map with CommitUnit's architectural (committed)
   map" and Paper 01 explicitly **rejects** per-branch checkpoints and the
   alloc-FIFO backward walk (sec1.3). The architectural map reflects only
   *committed* state. If uncommitted-but-older uops exist at redirect time, bulk
   restore to the committed map **erases their mappings** -> they can never write
   back -> corruption. Correct only if nothing older than the redirect point is
   uncommitted, i.e. redirect at commit.

3. **Older speculative store lost (Paper 02 M1 rule 5).** Epoch-kill invalidates
   every uncommitted store-buffer entry. An older, not-yet-committed store killed
   by a younger branch's epoch bump vanishes; when its `StoreCommit` later arrives
   there is no entry to mark -> the store never reaches memory -> **architectural
   state corruption** (a committed write is silently dropped).

Meanwhile Paper 05 prices `edgeRedirectToFetch` at B(1)=1, B(8..32)=2 cycles
(DECIDE 1 table) -- a *resolve-to-fetch* number that only makes sense for
execute-time resolution, and Paper 01 sells the wakeup->select loop as the OoO
IPC win, which is pointless if every branch drains at commit. So the papers'
performance model assumes fast (execute-time) redirect while their correctness
model requires slow (commit-time) redirect. They cannot both hold.

Failure scenario (concrete): `div x1, ...; <20 indep instrs>; beq taken-mispredict`.
Under any execute-time branch redirect the divide is dropped mid-flight and the
core hangs at commit.

Fix / question the panel MUST answer: **Declare the branch-redirect generation
point normatively.** If it is the commit head (the only choice consistent with a
single global epoch and Paper 01's rejection of checkpoints/walks), then:
(a) state it as a PROPERTY owned by CommitUnit/RedirectUnit; (b) Paper 05 must
re-price the mispredict penalty as resolve-to-commit latency (a full window
drain), not 1-2 cycles, and re-justify the OoO window against that penalty;
(c) Paper 01 O1 ("selective squash?") is thereby answered "no, and here is the
IPC cost." If instead execute-time redirect is wanted, the design needs
age-aware squash (branch tags / per-tag epochs), which contradicts the entire
single-epoch premise and Papers 01/05.

### B2. Memory-ordering replay assumes a selective per-tag epoch the architecture does not have

Papers: 02 M2 rule 4 vs 01 O1 / `GlobalEpochUnit.scala`.

Paper 02 M2 rule 4: on a late-arriving store address, "the StoreBuffer ... emits a
memory-ordering mispredict that **advances the epoch for that load's tag**.
Because epoch advance re-derives the younger slice ... **older work is
untouched**." There is no per-tag epoch. Epoch is one global counter
(`GlobalEpochUnit`), and Paper 01 O1 confirms there is no selective squash: a
redirect is kill-everything. So "advance the epoch for that load's tag while
leaving older work untouched" is **not implementable** as written.

Two consequences: (1) memory-dependence replay, if forced through the global
epoch, is a **full squash from the load's PC onward** (correct but far more
expensive than Paper 02 claims, and it re-executes independent younger work it
calls "untouched"); (2) the BackendTop/MemorySubsystem graphs have **no
redirect edge from the store buffer** (`BackendTopSpecs.scala` wires only
`trap -- Redirect --> ru`; RedirectUnit has only trap + branch-mispredict inputs,
`RedirectUnitSpecs.scala:22-34`), so the replay redirect has no home. At N>1 with
`MemDisambig=true` this leaves same-address load/store ordering (RVWMO
`SingleCoreMulDivClusterTest` cannot exercise it) **unspecified and unsafe**: a
younger load that missed forwarding from an older store commits a stale value ->
wrong result.

Fix / question: either (a) drop speculative memory disambiguation from the
contract entirely (N>1 loads may not bypass an unresolved older store -- conservative,
folds cleanly to N=1), or (b) route the memory-ordering mispredict through the
real global-epoch redirect path (add the store-buffer -> RedirectUnit edge, target
= offending load PC) and delete the "older work untouched / selective" language,
re-pricing it as a full squash. Panel must pick; (a) is the safe default.

### B3. Tag-uniqueness is the hidden correctness invariant of three papers, and Paper 05 makes tags wrapping counters with an admitted-undefined bound

Papers: 05 DECIDE 4 vs 02 M1 rule 3 + M2 rule 2 + 04 C1.2 + 01 sec2.

Paper 05 DECIDE 4 sizes `seqWidth = log2Ceil(maxInFlight)` and merges
`uopId == seq` into one small **wrapping** tag -- and admits `maxInFlight` "is a
placeholder" pending Paper 01 (Paper 05 sec4.7). Three separate correctness
mechanisms silently require these tags to be **unique among all simultaneously
live tokens**:

- Paper 02 M1 rule 3: a store becomes irrevocable when a `StoreCommit` matches a
  buffered entry **by `{seq}`**. Paper 02 M2 rule 2: a load forwards from stores
  with **`entry.seq < load.seq`** (a monotone-age comparison).
- Paper 04 C1.2: a CSR write applies when `commitGrant.uopId === req.uopId`.
- Paper 01 sec2: the completion scoreboard is indexed by in-flight window position.

Two failures: (1) **Age compare on a wrapping tag is ill-defined.**
`entry.seq < load.seq` (Paper 02) is meaningless once `seq` wraps modulo
`maxInFlight`; the load will forward from the wrong stores or fail to forward,
returning a wrong byte. (2) **Match aliasing across the store-buffer horizon.**
`maxInFlight` as Paper 05 frames it covers the *backend* window, but a committed
store can sit in the store buffer draining (Paper 02 M1 rule 5: committed entries
survive indefinitely) **after** the backend has retired it and recycled its seq
to a new in-flight uop. A `StoreCommit` (or a load's age compare, or a
`commitGrant`) can then match/relate to the wrong entry -> **wrong store to
memory / wrong CSR write / lost forward**.

Fix / question: define one tag-uniqueness PROPERTY normatively: the tag width
must cover **every live token simultaneously**, including committed-but-undrained
store-buffer entries and staged CSR writes, not just the rename window. Specify
age comparison as **modular (wrap-aware) relative to a bounded live range**, or
forbid seq reuse until the store buffer has drained the entry. Until
`maxInFlight` has a closed form that includes the store-buffer and CSR-stage
horizons, B3 blocks M1/M2/M4/CSR.

---

## MAJOR

### M1. Epoch-wrap: three papers, three incompatible mechanisms; Paper 05's proof is contradicted by Paper 03's queue

Papers: 01 sec6/O8, 05 DECIDE 2, 03 G5 + INV-Q2.

- Paper 01 sec6 + O8 **prefers** bounding in-flight generations by **stalling
  redirect issue** when the oldest epoch is `2^epochWidth-1` behind.
- Paper 05 DECIDE 2 **explicitly rejects** stalling `redirect.ready` and mandates
  **eager per-cycle filtering**, keeping `epochWidth=2`.
- Paper 03 G5 adds a **third** mechanism: `prefetchDepth <= 2^epochWidth-1`.

These are three different normative contracts for the same hazard, and Paper 05
directly rejects Paper 01's stated preference. Worse, Paper 05's wrap-safety proof
rests on the claim that **every** epoch-holding element re-evaluates
`epoch === globalEpoch` every cycle (`maxSurvivableGenerations = 1`). Paper 03
IssueQueue **violates that invariant**: INV-Q2 states entries are dropped **at
dequeue**, "the queue is NOT cleared on redirect; entries age out by epoch
mismatch." A deep queue entry stamped epoch E is not re-checked until it reaches
the head; if it sits across `2^epochWidth` redirects it **aliases back to a live
epoch** and is wrongly issued -- exactly the hazard Paper 05 claims to have
removed. Paper 05 sec2.7 even flags this as an open question against Papers 02/03
without resolving it.

Fix / question: pick ONE mechanism. If eager-filter (Paper 05), then Paper 03's
IssueQueue and Paper 02's store buffer MUST eager-filter **all** held entries, not
just the head, and the contract must say so; Paper 01's redirect stall and Paper
03's prefetch bound are then redundant and should be deleted. If the queue cannot
eager-filter all entries cheaply, epoch must widen and the eager-filter proof is
void.

### M2. Two papers describe the CSR datapath incompatibly

Papers: 01 sec2 vs 04 sec1.

Paper 01 sec2.1: "CSR side-effecting ops execute at the FIFO head only
(execute-at-commit), **never speculatively from the RS**." Paper 04 C1.1: the CSR
uop "flows through rename, reservation station, and dispatch as an ordinary uop.
Its functional-unit visit is a pure read plus a staged intent: it returns the old
CSR value onto the publish bus." These are different machines: Paper 04 has a CSR
**FU visited from the RS** that produces the `rd` writeback speculatively (write
staged to commit); Paper 01 says the CSR never visits an FU and executes at
commit. This changes whether a CSR FU exists, whether CSRRW's old-value `rd`
writeback is on the publish bus or produced at commit, and RS occupancy.

Fix / question: ratify one. Paper 04's read-in-FU / write-at-commit is the more
standard and preserves the publish-bus `rd` path; if adopted, Paper 01 sec2 must
strike "never speculatively from the RS" and acknowledge the CSR FU. Confirm the
speculative read is safe under C1.3 single-in-flight serialization (it is, given
no older uncommitted CSR writer -- but state it).

### M3. Redirect merge point has no priority rule; the epoch may double-increment or fetch the wrong target

Papers: 01 sec2, 02 B2, 04 sec2-3, 03 D3; `RedirectUnitSpecs.scala`,
`GlobalEpochUnit.scala`.

Redirect producers now include: branch mispredict (Paper 01 / `intfMispredictIn`),
trap + interrupt + mret/dret + debug entry (Paper 04, all via TrapController ->
Redirect), and memory-ordering (Paper 02, see B2). The frontend accepts **exactly
one** redirect edge (Paper 03 D3). RedirectUnit is the declared merge
(`RedirectUnitSpecs.scala:22-34`: trap `RedirectIn` + branch `MispredictIn` ->
`RedirectOut`) but **no priority is specified**, and the BackendTop mermaid does
not even wire the branch-mispredict producer into `ru` (only
`trap -- Redirect --> ru`, `BackendTopSpecs.scala:117`). `GlobalEpochUnit`
increments on a single `redirectFireIn` Bool: if two producers assert the same
cycle, the epoch bumps **once** while the target mux is unresolved -> the core
fetches from the wrong redirect target while consuming only one epoch generation.
Under redirect-at-commit (B1) trap and branch cannot both be the commit head the
same cycle, which resolves it -- another reason B1 must land first.

Fix / question: specify RedirectUnit's priority ladder (trap/interrupt > branch >
memory-order) and a PROPERTY that at most one redirect target is selected per
epoch increment. Wire the branch-mispredict producer into the top graph.

### M4. Four different commit-notification bundles for one in-order commit event

Papers: 01 sec2, 02 M1/cross-domain, 04 sec1, 06 sec1.

The single in-order commit event now spawns: `StoreCommit{seq,epoch}` (Paper 02),
`CommitGrant{uopId,epoch,valid}` (Paper 04), `MapTableUpdate` + `PhysicalRegFree`
(Paper 01), and `RetireStream` (Paper 06). They key on **different fields**
(`seq` vs `uopId`) -- feeding B3 -- and duplicate the same "this uop retired at the
head" signal. Divergent keys across the commit fan-out are a latent
mismatch: if `StoreCommit` uses `seq` and `commitGrant` uses `uopId` and Paper 05
has NOT yet proven `uopId == seq` holds across the store-buffer horizon, the store
and CSR paths can disagree about which uop retired.

Fix / question: define ONE commit-broadcast bundle with ONE key (post-B3),
fanned to store buffer, CSR, rename-free, and retire-stream consumers. Owner:
CommitUnit.

---

## MINOR

### m1. PRF read-port property omits the commit/RVVI read port
Paper 01 propPrfPortsVsN ("read ports are a function of issue width only") is
contradicted by Paper 06 sec1, which adds a commit-time PRF read for `wdata` in
the retire token. Reconcile: restate the property as "issue-width read ports plus
`commitWidth` read ports when `usingRvvi`," gated away in production.

### m2. Indirect-jump fetch stall interacts badly with redirect-at-commit
Paper 03 G4 stalls fetch on every JALR/`ret` until the backend redirects. Under
B1 (redirect at commit) that is a full-window fetch bubble on **every function
return** -- not incorrect, but it makes the BTB/RAS (Paper 03 Q3.2) non-optional
for acceptable behavior, contrary to "defer the predictor." Panel should note G4
+ commit-time redirect = return penalty ~ window depth.

### m3. Commit-side liveness: store-buffer / fence / WFI circular wait unproven
Paper 02 M1 rule 7 (full store buffer stalls commit) + Paper 04 C4.2 (fence gates
commit on `StoreBuffer.empty`) + Paper 04 C3.4 (WFI parks commit) create a chain:
fetch waits on commit, commit waits on store-buffer drain, drain waits on the
external bus. No paper gives the deadlock-freedom / liveness argument (a rank
function) for this loop. Add a PROPERTY: the external bus always eventually
accepts a committed write, so the store buffer always eventually drains, so commit
always eventually advances. Required before N>1 sign-off.

### m4. `StoreComplete` fault delivery vs already-retired store (precise-exception gap)
Paper 02 M1 rule 6: a committed store's bus fault returns via `StoreComplete`
**after** the backend already retired the store (M1 rule 6: retirement is "NOT
blocked on this ack"). A late bus fault on an already-committed store cannot be
delivered precisely (the instruction is gone). RISC-V requires precise store
access faults. Panel: either block store retirement on the ack (precise, slower)
or document that bus store faults are imprecise for this TCM (acceptable only if
the TCM is guaranteed fault-free). Paper 02 Q-M1a gestures at this but does not
resolve the precision obligation.

---

## Cross-paper contract matrix (same edge / field, differing rules)

| Edge / field | Paper A rule | Paper B rule | Finding |
|---|---|---|---|
| branch redirect timing | 05: 1-2 cyc resolve->fetch | 01/04: implied commit-head | B1 |
| epoch semantics | 02 M2r4: per-tag selective | 01 O1 / HW: single global | B2 |
| `seq`/`uopId` | 05: small wrapping counter | 02/04: unique, age-ordered | B3 |
| epoch-wrap fix | 05: eager-filter, no stall | 01: stall redirect issue | M1 |
| IssueQueue filtering | 05: every cycle, all entries | 03 INV-Q2: at dequeue only | M1 |
| CSR execution | 01: at commit, not from RS | 04: FU visit from RS | M2 |
| redirect merge/priority | (unspecified) | 3+ producers, 1 sink | M3 |
| commit notification | seq (02) / uopId (04) / map (01) | one event | M4 |

## What no paper covered (gaps)

- **Redirect generation point** (B1) -- the single most load-bearing unstated
  contract.
- **N>1 same-address load/store ordering** under a global epoch (B2) -- RVWMO
  correctness for the OoO end is undefined.
- **Global tag-uniqueness horizon** including the store buffer and CSR stage
  (B3).
- **Commit-loop liveness** (m3) and **precise store faults** (m4).
- **Misaligned / access-fault on the fetch path**: Paper 03 tags
  `instrAddrMisaligned` and `programMemFault` in slot metadata and defers raising
  to the backend, but no paper defines how a fetch **access fault** (bus error on
  I-fetch) becomes a precise trap with the correct `mepc`/`mtval` at commit. The
  frontend-tag / backend-raise handoff bundle is unspecified.

## Recommended panel resolution order

1. **B1 first** -- pin redirect at the commit head (or justify age-aware squash).
   It collapses M3 and de-risks B2's replay, and forces Paper 05 to re-price the
   mispredict penalty honestly.
2. **B3** -- one tag-uniqueness contract; unblocks M2/M4 and the store/CSR/forward
   matching.
3. **B2** -- choose conservative-no-disambiguation (safe, folds to N=1) or a real
   global-epoch memory-order redirect edge.
4. **M1** -- one epoch-wrap mechanism; if eager-filter, mandate all-entry filtering
   in the IssueQueue and store buffer.
5. **M2, M4, m1-m4** -- unify CSR datapath, commit bundle, and close the liveness /
   precise-fault gaps.
