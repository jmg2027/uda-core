# Position Paper 01 - Backend / OoO Architecture

Author: Backend/OoO architect
Scope: Normative contracts for the UDACore rebuild backend (rename, commit,
reservation station, PRF read, N=1 degeneracy). Branch
`claude/rebuild-architecture-review-jg0grl`.

Normative language: MUST / MUST NOT / SHOULD / MAY are used in the RFC-2119
sense. Each numbered decision states a normative contract, the alternatives
rejected, the cost at N=1 and N=32, the verification obligation, and open
questions for the panel.

---

## 0. Binding constraints (inherited, not up for debate here)

These frame every decision below and are quoted so the contracts stay honest.

- **C1 - Nodes are pure, edges own timing.** No stall logic and no pipeline
  registers inside a vertex; stall is ready backpropagation, a pipeline
  register is an edge property (`dontcommit.md` items 5-6). The whole backend
  MAY legally execute in one cycle: "Cycle 1: Decode, Rename, RS
  wakeup/issue, FU execute, Commit" (`dontcommit.md` item 5). Pipelining is a
  post-PD retiming act that MUST NOT change function.
- **C2 - Control is data (epochs, no flush).** Redirects/traps bump the
  global epoch; `token.epoch === globalEpoch` is the single correctness
  predicate; stale work self-filters locally
  (`docs/foundations/dataflow-execution-model.md` sec 1;
  `core-design-principles.md` sec 2).
- **C3 - Unified PRF, map-table commit.** Total PRF entries `33 + N`, commit
  updates the map only (no data writeback), commit frees the old physical
  register (`PhysicalRegisterFileSpecs.scala:27-33`;
  `BackendTopSpecs.scala:105-113`).
- **C4 - One parameter scales the design.** `SpeculativeRegNum` (= N) must
  scale one RTL from N=1 in-order to N=32+ wide OoO
  (`BackendParamsSpecs.scala:84-89`; `BackendTopSpecs.scala:146`). The N=1
  point MUST be competitive with a plain in-order core: OoO structures MUST
  elaborate away (accepted review, `rebuild_branch_architecture_review.md`
  sec 3 recommendation 6).
- **C5 - Deliberate cycle breaking.** Relay stations/queues appear only to
  break a strongly connected component, and each is recorded with an exit
  criterion (`core-design-principles.md` sec 4).

The accepted architecture review names three silent spec holes as blocking;
this paper owns the first (rename recovery) fully, and states the backend-side
contract obligations that the memory-ordering and CSR-serialization holes
impose on commit ordering.

---

## 1. Rename recovery protocol on redirect

### 1.1 The hole

The map-table + free-list scheme is specified (`RenameUnitSpecs.scala:10-27`),
and commit frees the *old* physical register of *committed* uops
(`CommitUnitSpecs.scala:22-26`). Nothing in the current specs reclaims the
physical registers of renamed-then-squashed uops, nor repairs the speculative
map table after a redirect. As written, the first mispredict leaks free-list
entries and leaves the rename map pointing at physical registers that will
never be written. The deleted reference simply cleared the whole map on
`epochSwitch` (`git show 5d172a5b^:.../Renamer.scala`, `funcEpochRestore`,
`mapValid(i) := false.B`) - correct only because that map was a VirtualGPR
valid-bitmap, not a unified-PRF pointer map, so there was nothing to restore
to. Under a unified PRF the map MUST be *restored to the architectural
mapping*, not cleared.

### 1.2 Contract (NORMATIVE): retirement-map copy + alloc-FIFO free reclaim

**RENAME RECOVERY = architectural-map overwrite of the speculative map,
combined with free-list reconstruction from the retirement map.** On the
cycle a redirect fires (global epoch increments):

1. The **RenameUnit speculative map table** is overwritten by the
   **architectural (retirement) map table** owned by CommitUnit.
2. The **free list** is reconstructed as the complement of the physical
   registers named by the retirement map plus the retirement free set. This is
   the RAA "free a resource in all error paths" rule
   (`unified-microarchitectural-paradigms.md` sec 2).
3. All in-flight speculative uops self-filter by epoch (C2); no per-uop
   unwind walk is required for correctness, only for free-list accounting,
   which the reconstruction in step 2 subsumes.

CommitUnit is the single owner of the architectural map and therefore the
single source of recovery truth. This is the cheap fix the accepted review
already endorsed (`rebuild_branch_architecture_review.md` sec 3 hole 1) and it
composes with the no-ROB commit design in Decision 2.

Paste-ready spec additions (RenameUnitSpecs.scala and CommitUnitSpecs.scala):

```scala
// --- RenameUnitSpecs.scala : add interface + function ---
val intfArchMapRestoreIn = spec {
  INTERFACE("ArchMapRestoreIn")
    .desc("Architectural (retirement) map snapshot from CommitUnit, applied to the speculative map on redirect.")
    .uses(bndArchMapSnapshot)
    .is(rawNoDecoupled)   // sampled combinationally with epoch; not a token
    .note("Consumed only in the cycle the global epoch changes; ignored otherwise.")
    .build()
}

val funcRenameRecovery = spec {
  FUNCTION("RenameRecovery")
    .desc("On global-epoch change, overwrite the speculative map table with the architectural map and reconstruct the free list as its complement plus the retirement free set.")
    .note("No per-uop rollback walk: squashed uops self-filter by epoch (C2); free reclamation is bulk, derived from the restored map.")
    .note("Recovery MUST complete in the same cycle the epoch changes so the next-fetch rename sees a consistent map.")
    .uses(intfArchMapRestoreIn, paramSpeculativeRegNum)
    .build()
}
```

```scala
// --- CommitUnitSpecs.scala : CommitUnit owns and exports the arch map ---
val intfArchMapRestoreOut = spec {
  INTERFACE("ArchMapRestoreOut")
    .desc("Architectural map snapshot broadcast to RenameUnit for redirect recovery.")
    .uses(bndArchMapSnapshot)
    .is(rawNoDecoupled)
    .build()
}
val funcArchMapMaintain = spec {
  FUNCTION("ArchMapMaintain")
    .desc("Maintain the architectural register->physical map, updated in program order at retirement; this is the recovery source of truth.")
    .note("Retirement map + retirement free set fully determine post-redirect rename state.")
    .build()
}
```

```scala
// --- BackendBundlesSpecs.scala : the snapshot bundle ---
val bndArchMapSnapshot = spec {
  BUNDLE("ArchMapSnapshot")
    .desc("Full architectural register->physical map plus the free-set mask.")
    .note("Fields: mapPhys[RegNum] (physical id per arch reg), freeMask[33+N]")
    .build()
}
```

### 1.3 Alternatives considered and rejected

- **Per-branch checkpointing (RAT snapshots keyed by branch tag).** Rejected
  for the base machine: a single global epoch is kill-everything semantics
  (C2), there is exactly one live redirect context at a time, so a bank of
  checkpoints buys nothing at N=1..small-N and costs N x RegNum x
  log2(33+N) flops. Reconsider only if the panel adopts selective (branch-tag)
  squash for the wide-OoO end (see open question O1). The contract above is
  forward-compatible: a checkpoint scheme is "keep K arch-map snapshots" and
  reuses `bndArchMapSnapshot`.
- **Alloc-FIFO backward walk (unwind youngest-first, returning each prd).**
  Rejected as the *primary* mechanism: it takes O(in-flight) cycles, which
  violates the same-cycle recovery budget and re-introduces sequential
  unwinding the epoch model exists to avoid. The `DecodedUopAlloc` FIFO
  (`bndDecodedUopAlloc`, `BackendBundlesSpecs.scala:63-68`) is retained for
  *commit ordering* (Decision 2), not for recovery. Bulk free-list
  reconstruction from the restored map is strictly cheaper and exact.
- **Do nothing / clear the whole map (deleted reference behavior).** Rejected:
  under unified PRF this discards architectural mappings and is functionally
  wrong (loses committed state pointers).

### 1.4 Cost

- **N=1:** ~zero. With N=1 the speculative map equals the architectural map at
  all times (only one outstanding rename can exist, and it cannot be
  speculative past commit in the same-cycle base machine, C1). The recovery
  path degenerates to a no-op restore of an identity/committed map; the
  free-list is a single bit. Elaboration SHOULD prune the snapshot mux
  (Decision 5).
- **N=32:** one RegNum-wide (<=32 entries) map overwrite mux plus a
  (33+N)-bit free-mask recompute, both combinational, both gated by the
  epoch-change pulse. Cost is one snapshot broadcast bus RegNum x
  log2(33+N) wide. No sequential walk, no per-cycle penalty in steady state.

### 1.5 Verification obligation

- Elaboration assertion: after any epoch-change cycle, `union(map image) +
  freeMask == all physical registers` (no leaked, no double-owned register).
  This is a PROPERTY tied to an assertion per the review's "tie PROPERTY to
  elaboration-time assertions" directive.
- Directed test: rename K uops writing the same arch reg, fire a redirect
  mid-flight, assert free-list count returns to its pre-burst value and the
  map equals the retirement map. Run at N=1, N=2, N=8, N=32.
- Random test: interleave allocations and redirects; invariant checker on the
  free-list conservation property above must never fire.

### 1.6 Open questions

- **O1:** Does the wide end need selective (branch-tag) squash, or is
  global-epoch kill-everything the permanent contract? This decides whether
  `bndArchMapSnapshot` stays single or becomes a checkpoint bank.

---

## 2. Commit without a ROB: what orders retirement

### 2.1 Contract (NORMATIVE): alloc-FIFO is the retirement order; a completion
### scoreboard gates the head; CommitUnit owns the architectural map and free
### events.

There is no ROB. Program order at the backend is defined solely by the
**allocation FIFO** produced by RenameUnit (`intfDecodedUopAllocOut`,
`RenameUnitSpecs.scala:43-48`; `bndDecodedUopAlloc`,
`BackendBundlesSpecs.scala:63-68`) and consumed by CommitUnit
(`intfDecodedUopAllocIn`, `CommitUnitSpecs.scala:29-34`). Rename enqueues one
alloc token per uop in program order; CommitUnit retires strictly from the FIFO
head. This matches the deleted reference, whose `robDepth==0` path was a single
head register with `robEnq.ready := !headValid` and in-order head exposure
(`git show 5d172a5b^:.../CommitUnit.scala`, `funcRobOps`).

**CommitUnit owns exactly:**
1. the architectural (retirement) map table (Decision 1 source of truth);
2. the retirement free events (`intfPhysicalRegFreeOut`,
   `CommitUnitSpecs.scala:50-55`) - free the *old* prd of the retiring arch
   reg;
3. the map-update events (`intfMapTableUpdateOut`,
   `CommitUnitSpecs.scala:43-48`) - point the retiring arch reg at its new prd;
4. exception raising (`intfExceptionOut`, `CommitUnitSpecs.scala:57-62`) and
   the redirect/serialization gate.

**Completion tracking:** a uop at the FIFO head retires when PublishMux has
published its result (`intfPublishResultOut`, `PublishMuxSpecs.scala:98-102`
-> `intfCommitResultIn`, `CommitUnitSpecs.scala:36-41`) AND its epoch matches.
CommitUnit holds a **completion scoreboard indexed by the in-flight window**
(size N, not a separate parameter), set by PublishResult, cleared at retire.
This is the only per-uop state commit needs; it is O(N) bits, not a data ROB.

**Precise exceptions / serialization (backend-side obligations of the other
two holes):**
- **PROPERTY COMMIT-IN-ORDER:** architectural state (map, free, CSR side
  effects, stores) changes only at the FIFO head, in FIFO order. This keeps
  traps/interrupts precise (ECA, `unified-microarchitectural-paradigms.md`
  sec 1: "Commit happens in program order").
- **Stores drain at commit (MDG hole, backend contract):** a store MUST NOT
  become visible to memory before it is the FIFO head and its epoch matches.
  The AGU->memory path is speculative for address, but the store *commit*
  event is a CommitUnit-gated token. Stated here as a normative constraint on
  the commit contract; the store-buffer vertex itself is the memory team's
  paper.
- **CSR / mret / interrupt sample at commit (CSR hole, backend contract):**
  CSR side-effecting ops execute at the FIFO head only (execute-at-commit),
  never speculatively from the RS. `mepc`/`mcause` have a single writer
  (CommitUnit/TrapController handoff), removing the double-fire seam the
  review flagged.

Paste-ready:

```scala
val propCommitInOrder = spec {
  PROPERTY("CommitInOrder")
    .desc("Architectural map updates, physical-register frees, CSR side effects, and store visibility occur only at the alloc-FIFO head, in FIFO order.")
    .note("Precise exceptions follow directly: the head is the precise architectural point.")
    .note("Backend obligation for the memory-ordering and CSR-serialization spec holes.")
    .uses(intfDecodedUopAllocIn, intfCommitResultIn, intfExceptionOut)
    .build()
}
val funcCompletionScoreboard = spec {
  FUNCTION("CompletionScoreboard")
    .desc("Per-in-flight-uop done bit set by PublishResult, cleared at retire; head retires when done and epoch-matched.")
    .note("Size = SpeculativeRegNum (N); degenerates to a single valid bit at N=1.")
    .uses(paramSpeculativeRegNum)
    .build()
}
```

### 2.2 Alternatives considered and rejected

- **Data ROB (result payload stored, writeback at commit).** Rejected: it is
  exactly the writeback the unified-PRF choice exists to eliminate (C3). A ROB
  would duplicate PRF storage and add a commit-time write port.
- **Retire directly on PublishResult (no ordering structure).** Rejected:
  breaks precise exceptions and in-order CSR/store visibility; PublishResult
  arrives out of program order.
- **Age counters in the RS as the order source.** Rejected: the RS age field
  (deleted reference `ageCounter`) orders *selection*, not *retirement*, and
  is not a program-order total once entries drain out of order. The alloc FIFO
  is the authoritative order.

### 2.3 Cost

- **N=1:** alloc FIFO collapses to a single head slot (deleted reference
  `robDepth==0` path), completion scoreboard is one bit. ~zero over an
  in-order commit register.
- **N=32:** alloc FIFO depth ~N, each entry is {arch rd, old prd, new prd,
  flags, epoch} ~ small; completion scoreboard N bits. No data storage. This
  is the area win over a data ROB.

### 2.4 Verification obligation

- Assertion: retire index is monotone and equals FIFO dequeue order.
- Assertion: no architectural map/free/CSR/store-visible event occurs for a
  uop that is not the current FIFO head.
- Trap-precision test: inject a fault on uop i; assert uops < i retired and
  uops > i left no architectural trace (map, free, CSR, store).

### 2.5 Open questions

- **O2:** Can commit retire more than one head per cycle at large N
  (superscalar retire), and if so does the same-cycle base machine (C1) force
  retire width = fetch/issue width? Panel to set the retire-width contract.
- **O3:** Who physically holds the store-visibility gate token - CommitUnit or
  a memory-subsystem store buffer keyed by the commit event? (Cross-paper with
  memory team.)

---

## 3. RS / wakeup / select contract

### 3.1 Contract (NORMATIVE)

- **Entries vs N.** RS entry count is the tuning parameter
  `ReservationStations` (`BackendParamsSpecs.scala:47-52`), decoupled from N.
  The normative tie is: **usable OoO window = min(RS entries, N,
  alloc-FIFO depth)**. At N=1 the effective window is 1 regardless of RS
  entries, so RS entries MUST also elaborate to 1 (Decision 5). At large N, RS
  entries SHOULD equal N for a non-throttling window; a smaller RS is a legal,
  documented throughput throttle (RAA).
- **Wakeup broadcast semantics.** PublishMux broadcasts `(prd, valid,
  epochTag)` (`bndWakeupBroadcast`, `BackendBundlesSpecs.scala:239-244`;
  `PublishMuxSpecs.scala:91-95`). Each RS entry compares its `prs1`/`prs2`
  against the broadcast prd; on match with epoch-match it marks that source
  ready. Wakeup is **tag-match on prd**, one comparator pair per entry per
  broadcast lane. This is a CAM of depth (RS entries) x (broadcast lanes).
- **Broadcast MUST be epoch-qualified.** A wakeup whose epoch does not match
  the global epoch MUST NOT wake an entry; stale results cannot mark a live
  operand ready (C2). Stated as PROPERTY.
- **Select-to-execute is same-cycle at the base point.** Per C1, the base
  machine MAY do RS-wakeup, select, PRF read, and FU execute in one cycle.
  Therefore the RS select result MUST be combinationally available to Dispatch
  in the same cycle wakeup arrives. The wakeup->select->execute path is the
  **IPC-critical loop**; it MUST be named in the spec with a cycle budget so
  post-PD pipelining cannot silently regress IPC (accepted review rec 5).
- **Select policy.** Oldest-ready-first, ordered by the RS age field (deleted
  reference `funcIssueSelect`, oldest = lowest age among `readyVec`). Age is an
  RS-local ordering, not the retirement order (Decision 2).

Paste-ready:

```scala
val propWakeupEpochQualified = spec {
  PROPERTY("WakeupEpochQualified")
    .desc("A wakeup broadcast marks an RS source ready only if prd matches AND broadcast epoch matches the global epoch.")
    .uses(intfWakeupBroadcastIn)
    .note("Stale-path results never satisfy a live dependency.")
    .build()
}
val funcSelectOldestReady = spec {
  FUNCTION("SelectOldestReady")
    .desc("Among epoch-matched entries with all sources ready, select the oldest by RS age; drive Dispatch combinationally at the base point.")
    .note("IPC-CRITICAL LOOP: wakeup -> select -> PRF read -> execute -> publish. Budget = 1 cycle at the base point; any register inserted here is a post-PD retiming that trades IPC and MUST be recorded.")
    .uses(intfWakeupBroadcastIn, intfDispatchedUopOut)
    .build()
}
```

### 3.2 Alternatives considered and rejected

- **Data-capturing RS (operands latched into the RS on wakeup).** Rejected:
  it stores operand data in the RS, duplicating the PRF and adding write
  ports; conflicts with the top graph, where RS reads the PRF at issue
  (`BackendTopSpecs.scala:79-83`). We use a **non-data-capturing** RS: wakeup
  sets a ready bit, operands are read from PRF at select (Decision 4).
- **Position/matrix scheduler (age matrix).** Rejected at base scale as
  overkill; the age-field priority select is adequate and elaborates cleanly
  to a 1-entry no-op at N=1. Revisit only if O1 (selective squash / very wide
  issue) is adopted.
- **Broadcasting the full result (data) on wakeup for same-cycle bypass.**
  Deferred: the top graph routes data through PRF write + read, not a wakeup
  data bus; the owner note says PublishMux "does not have a role for data
  forward" (`dontcommit.md` item 3). Bypass is a post-PD edge optimization,
  not a base contract. See O4.

### 3.3 Cost

- **N=1:** RS is a single entry; the "select oldest ready" reduces to "is the
  one entry ready"; the wakeup CAM is one comparator pair. ~zero over an
  in-order operand-ready check / scoreboard bit. MUST elaborate away the
  age field, the priority encoder, and the multi-entry CAM (Decision 5).
- **N=32:** CAM of 32 entries x broadcast lanes (7 FUs + mem = up to 8
  producers, but only one publish/cycle if PublishMux arbitrates to a single
  result bus - see O4), plus a 32-wide age-priority select. This is the
  dominant OoO area/timing term and the reason the loop budget must be named.

### 3.4 Verification obligation

- Assertion: no entry is selected unless epoch-matched and both sources ready.
- Assertion: selected entry is the oldest among ready (age-monotone check).
- Wakeup test: publish prd X, assert exactly the entries depending on X wake,
  none others, and none wake on epoch mismatch.
- Loop-budget test (post-PD): a lint/report that flags any inserted register
  on the named wakeup->select->execute edge, forcing an explicit IPC sign-off.

### 3.5 Open questions

- **O4:** Is PublishMux a single-result-per-cycle arbiter (one wakeup lane,
  one PRF write port) or multi-lane? This sets CAM width, PRF write ports, and
  whether the design can sustain >1 IPC. The owner's PublishMux note
  (`dontcommit.md` item 3) implies arbitration to a single write; confirm.
- **O5:** Is same-cycle bypass (result->dependent execute without a PRF
  round trip) in the base contract, or strictly a post-PD edge optimization?

---

## 4. PRF read strategy

### 4.1 Contract (NORMATIVE): read-at-select (issue), ports scale with issue
### width, not N.

The top graph routes operand reads from the RS to the PRF and back
(`BackendTopSpecs.scala:79-83`: `rs -- RegisterFileReadReq --> prf`, `prf --
RegisterFileReadResp --> rs`). The normative choice is **read-at-select**: the
RS reads the PRF only for the entry it selects to dispatch, in the same cycle
as select (base machine, C1). Read ports therefore scale with **issue width
x 2 sources**, NOT with N and NOT with RS entries.

- Base machine: 1 selected uop/cycle -> 2 read ports.
- Register file storage is `33 + N` entries (C3,
  `PhysicalRegisterFileSpecs.scala:27-29`), but read *ports* are an
  issue-width property, decoupled from N.

Paste-ready:

```scala
val funcReadAtSelect = spec {
  FUNCTION("ReadAtSelect")
    .desc("PRF is read only for the RS-selected uop, in the select cycle; read ports = issueWidth * 2, independent of SpeculativeRegNum.")
    .note("Alternative read-at-dispatch (read every ready candidate) is rejected: ports would scale with RS entries.")
    .uses(intfRegisterFileReadReqOut, intfRegisterFileReadRespIn)
    .build()
}
val propPrfPortsVsN = spec {
  PROPERTY("PrfPortsIndependentOfN")
    .desc("PRF depth is 33 + SpeculativeRegNum; PRF read-port count is a function of issue width only.")
    .uses(paramSpeculativeRegNum, paramPhysicalRegNum)
    .build()
}
```

### 4.2 Alternatives considered and rejected

- **Read-at-dispatch / read-all-candidates.** Rejected: reading every
  ready entry before select needs ports = RS entries x 2, which explodes at
  large N and is wasted work for entries not selected.
- **Read-at-rename (operands captured at rename into the RS).** Rejected:
  that is the data-capturing RS of Decision 3, duplicates PRF, and violates
  the top graph's read-at-RS edge. Also, operands are frequently not ready at
  rename, so it cannot be the general path.

### 4.3 Cost

- **N=1:** 2 read ports on a `34`-entry (33+1) file, equivalent to an in-order
  GPR read. ~zero.
- **N=32:** 2 read ports (base, 1-issue) on a `65`-entry file. Wider issue
  multiplies ports by issue width; N only grows depth (address width
  `log2(33+N)`), which is cheap.

### 4.4 Verification obligation

- Assertion: at most (issueWidth x 2) PRF read requests are asserted per cycle.
- Assertion: a read request's prs is a currently-allocated physical register
  (not on the free list).
- Read/write same-cycle test: PRF write of prd X and read of prd X in the same
  cycle must return the specified (old or bypassed) value per the chosen
  read-during-write policy - panel to fix policy (O6).

### 4.5 Open questions

- **O6:** Read-during-write policy when PublishMux writes prd X the same cycle
  the RS reads prd X: bypass the new value, or read-old-then-rely-on-wakeup?
  This couples to O5 (same-cycle bypass).

---

## 5. The N=1 degeneracy plan (per structure)

C4 requires that at `SpeculativeRegNum = 1` the OoO fabric elaborates away so
the in-order point is competitive. This is stated per structure; each is a
Chisel elaboration-time (`if (N == 1)`) prune, not a runtime mux. The accepted
review makes this a blocking requirement
(`rebuild_branch_architecture_review.md` rec 6).

| Structure | N>=2 form | N=1 degenerate form | How it folds |
|---|---|---|---|
| Map table (RenameUnit) | RegNum x log2(33+N) pointer map | identity/committed map; speculative == architectural always | at N=1 no rename can outlive commit (C1 base), so the speculative map is never distinct; elaborate the map as a direct arch->fixed-phys identity, drop the pointer storage |
| Free list | (33+N)-bit mask + alloc encoder | single free bit (the one spec reg) | mask width 1; PriorityEncoder collapses to a constant |
| Wakeup CAM (RS) | entries x lanes comparators | one ready-bit scoreboard, no tag compare | at 1 entry the prd-match reduces to "the single producer wrote my source"; elaborate to a scoreboard bit |
| Select arbiter (RS) | age-priority over entries | pass-through of the one entry | PriorityEncoder over width 1 is the identity; drop age field |
| Alloc FIFO / retire order | depth ~N FIFO | single head register | deleted reference `robDepth==0` path; `robEnq.ready := !headValid` |
| Completion scoreboard | N done-bits | 1 done-bit | width 1 |
| Arch-map snapshot bus (recovery) | RegNum-wide restore mux | no-op restore | at N=1 restore target == current map; elaborate away the mux |
| uopId / seq / epoch tags | log2(window)+ bits | minimal: epoch only (seq degenerates) | seq/uopId width -> 0 or 1 when window==1; keep epoch (C2 still needed for redirect) |

Paste-ready guard property:

```scala
val propN1Degenerate = spec {
  PROPERTY("N1Degenerate")
    .desc("At SpeculativeRegNum == 1, the map table, free list, wakeup CAM, select arbiter, alloc FIFO, completion scoreboard, and recovery snapshot mux all elaborate to their trivial (width-1 / identity / no-op) forms, leaving an in-order datapath.")
    .note("Elaboration-time (if N==1) pruning, not runtime muxing. Netlist MUST contain no CAM, no priority encoder, no pointer map at N=1.")
    .uses(paramSpeculativeRegNum)
    .build()
}
```

### 5.1 Cost / verification

- **Cost at N=1:** target is parity with a classic in-order scoreboard core:
  a GPR file (34 entries), a per-register busy bit, one issue slot, one commit
  head. The OoO tax MUST be zero in the netlist.
- **Cost at N=32:** the full table above materializes; this is the intended
  OoO area.
- **Verification obligation:** an elaboration test that builds at N=1 and
  greps the emitted Verilog / FIRRTL for the forbidden structures (CAM
  comparator arrays, multi-entry priority encoders, pointer-map registers).
  This is the concrete teeth for C4; without it "scales from 1 to 32" is an
  unverified claim (the review's "spec theater" warning).

### 5.2 Open questions

- **O7:** Is `SpeculativeRegNum` truly one knob, or do RS-entries and
  alloc-FIFO depth need independent parameters that must satisfy `>= N is
  wasteful, < N is a throttle`? Recommendation: keep them independent tuning
  params but assert the window relation of Decision 3.1 so misconfiguration is
  caught at elaboration.

---

## 6. Cross-cutting: epoch algebra and the wrap hazard

Decisions 1-3 all lean on `token.epoch === globalEpoch`. Two backend-relevant
hazards the accepted review raised (`rebuild_branch_architecture_review.md`
sec 3):

- **Epoch wrap.** A narrow epoch (the review cites 2-bit) aliases: a
  long-latency op (div, load) that survives `2^epochWidth` redirects sees its
  stale epoch match the current one and is wrongly accepted. **Contract:**
  either (a) bound the number of in-flight epoch generations to
  `< 2^epochWidth` by stalling redirect issue when the oldest in-flight epoch
  is `2^epochWidth - 1` generations behind, or (b) widen `epochWidth` and
  compare with distance. Recommendation: (a) as a PROPERTY on the commit/redirect
  path, because it costs nothing in steady state and needs no wider tag. This
  binds the divider/multiplier/load producers, which already latch request
  epoch and kill on mismatch (the review's cited good pattern).
- **Redirect as global combinational broadcast.** Same-cycle epoch exposure
  makes redirect a core-wide comb path. This is a C5 "deliberate cycle
  breaking" candidate: the epoch edge MAY be registered post-PD, but doing so
  adds a cycle to redirect recovery (the most latency-critical loop). It MUST
  be a named edge with a recorded exit criterion, not silently pipelined.

Paste-ready:

```scala
val propEpochNoWrap = spec {
  PROPERTY("EpochNoWrap")
    .desc("At most 2^epochWidth - 1 epoch generations may be in flight; redirect issue stalls before the oldest in-flight epoch would alias the current one.")
    .note("Prevents a long-latency stale token from re-matching the global epoch after wrap.")
    .uses(paramEpochWidth)
    .build()
}
```

Open question **O8:** does the panel prefer bounded-generations (cheap, adds a
rare redirect stall) or wide-epoch-with-distance-compare (no stall, wider tag
everywhere)? Backend prefers bounded-generations.

---

## 7. Summary of normative contracts

1. **Rename recovery** = single-cycle overwrite of the speculative map by
   CommitUnit's architectural map + bulk free-list reconstruction. No
   per-branch checkpoints at base scale; no sequential unwind.
2. **Commit without ROB** = alloc-FIFO defines retirement order; an N-bit
   completion scoreboard gates the head; CommitUnit owns the arch map, free
   events, exceptions, and the commit-in-order gate that makes stores/CSR
   precise.
3. **RS/wakeup/select** = non-data-capturing RS, prd-tag CAM wakeup that is
   epoch-qualified, oldest-ready select, same-cycle wakeup->select->execute at
   the base point named as the IPC-critical loop with a 1-cycle budget.
4. **PRF read** = read-at-select; ports scale with issue width (2 at base),
   not with N; depth is 33+N.
5. **N=1 degeneracy** = every OoO structure elaborates to a width-1/identity/
   no-op form; a netlist grep test enforces zero OoO tax at N=1.

## 8. Consolidated open questions for the panel

- O1: selective (branch-tag) squash at the wide end, or permanent global-epoch
  kill-everything?
- O2: retire width vs issue width at large N.
- O3: owner of the store-visibility commit gate (commit vs store buffer).
- O4: PublishMux single vs multi result-lane (sets CAM width, PRF write ports,
  peak IPC).
- O5: same-cycle result bypass in the base contract or post-PD only.
- O6: PRF read-during-write policy.
- O7: one knob (N) vs independent RS-entry / FIFO-depth tuning params.
- O8: epoch wrap policy - bounded generations (preferred) vs wide-with-distance.
```
