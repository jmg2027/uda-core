# 02 - Memory Subsystem / LSU Architecture (Normative Position Paper)

Author: Memory subsystem / LSU architect
Branch: claude/rebuild-architecture-review-jg0grl (rebuild lineage)
Status: NORMATIVE PROPOSAL for panel ratification. Contracts here are written to be
pasted into `*Specs.scala` before any shell RTL is filled in.

This paper closes silent spec hole #2 from the accepted review
(`document/rebuild_branch_architecture_review.md:129-132`): "No store buffer vertex,
no stores-drain-at-commit rule, no load-store ordering or forwarding contract. A
speculative store reaching memory before commit is architecturally fatal; this must
be a spec-level contract on the AGU -> memory path."

Everything below is scaled by the single window parameter `N` (the count of
speculative registers, a.k.a. in-flight speculative uops the machine may hold). The
memory subsystem exposes exactly two derived knobs, `StoreBufferDepth` and
`LoadOutstanding`, both functions of `N`. At `N=1` all speculative machinery must
elaborate to a bypass wire with zero registers.

---

## 0. What the repo says today (the starting point)

- Subsystem graph (top mermaid): `MemorySubsystemSpecs.scala:22-74`. The dispatcher
  is drawn as a SPLITTER: `dis -- LoadReq --> lu` and `dis -- StoreReq --> su`
  (`MemorySubsystemSpecs.scala:56-57`). Vertices are MemoryDispatcher, StoreUnit,
  LoadUnit, MemoryController, ResponseArbiter. There is NO store buffer vertex.
- MemoryDispatcher CONTRACT contradicts the top mermaid: it says the dispatcher
  "routes load and store requests toward the shared controller" and lists
  `intfLoadIn`, `intfStoreIn` as inputs with `intfControllerOut` as the single output
  (`MemoryDispatcherSpecs.scala:11-42`). That is a MERGE, i.e. the reverse dataflow
  direction from the top graph. This is the direction contradiction called out at
  `document/rebuild_branch_architecture_review.md:149`.
- The deleted reference implementation (`git show 5d172a5b^:.../modules/MemoryDispatcher.scala`)
  is a SPLITTER (one `memOpIssue` in, `loadDispatch` + `storeDispatch` out). The top
  mermaid is correct; the current MemoryDispatcher CONTRACT is wrong.
- Backend path: AGU emits `MemoryOpReq` straight out of BackendTop
  (`BackendTopSpecs.scala:121`), and `MemoryOpResp` returns into PublishMux
  (`BackendTopSpecs.scala:103`). There is NO edge from CommitUnit to the memory
  subsystem. So as specified today, a store leaves the backend the moment the AGU
  fires - i.e. speculatively, before commit. This is the architecturally fatal case.
- Bus contract: `ExternalDataMemoryReq/Resp` carry `{addr, command, txnId, mask,
  size, data}` / `{data, command, txnId, exception}` (`CoreBundlesSpecs.scala:104-147`).
  `DataMemoryTxnIdWidth` default is `0`, noted "current system won't use transaction
  ID" (`CoreParamsSpecs.scala:50-58`). `DataMemoryCommandWidth` default `5`
  (`CoreParamsSpecs.scala:42-48`).
- Params: `MemorySubsystemTuningParams` defaults are `loadQueueSize=16`,
  `storeQueueSize=16`, `loadUnitCount=2`, `storeUnitCount=1`
  (`design/shared/MemorySubsystemParams.scala:32-54`); the `standard` preset is a
  64-bit / 2-load-unit server template (`.scala:111-123`). These are wrong defaults
  for an RV32 TCM core.
- The deleted `StoreBuffer.scala` (`git show 5d172a5b^:.../modules/StoreBuffer.scala`,
  262 lines) already implemented an age-ordered circular buffer with speculative
  enqueue, commit-marking by `{seq, epoch}`, byte-granular store-to-load forwarding,
  and a head-drain-when-committed rule. It is the executable statement of intent and
  the base for the contracts below - with two bugs I fix (see M1 note).

---

## 1. Design position in one paragraph

The memory subsystem is the ONE place in this machine where UDA's "any token may be
squashed by epoch" rule meets an irreversible side effect (a write to memory).
Therefore the store buffer is not an optimization; it is the correctness boundary
that converts speculative, epoch-tagged store tokens into a strictly in-order,
commit-gated, irrevocable write stream. Loads are pure functions of memory plus
older in-flight stores and may execute freely and speculatively, because a wrong-path
load has no architectural side effect - it only needs its result filtered by epoch,
which the backend already does at PublishMux. This asymmetry (stores gated at commit,
loads free) is the entire LSU contract. Everything else is depth and width scaling.

---

## M1. Store lifecycle contract (store buffer vertex)

### Contract (normative)

1. There SHALL be a `StoreBuffer` vertex in the memory subsystem graph, on the store
   path between `StoreUnit` and `MemoryController`. It is an age-ordered
   (seq-ordered) circular buffer of depth `StoreBufferDepth`.
2. A store token entering the buffer is SPECULATIVE (`committed = false`). A
   speculative store SHALL NEVER produce a `MemoryController` write request. This is
   the single load-bearing invariant of the subsystem.
3. A store becomes COMMITTED only on arrival of a `StoreCommit` token from the
   backend CommitUnit that matches the buffered entry by `{seq}` (uopId/seq is unique
   within the live window; epoch is checked for staleness, see rule 5). Commit is
   in-order in the backend, so commit tokens arrive in seq order.
4. COMMIT-DRAIN RULE: only the buffer HEAD, and only when `committed = true`, may
   issue a write to `MemoryController`. Writes therefore reach memory strictly
   in program order, one at a time, after retirement. On write acknowledgement
   (store-ack) the head entry is freed and the head pointer advances.
5. EPOCH-KILL RULE: on `token.epoch =/= globalEpoch`, every SPECULATIVE
   (uncommitted) entry SHALL be invalidated and squeezed out of the age order.
   COMMITTED entries are IRREVOCABLE and SHALL survive any epoch change; they drain
   regardless of the current global epoch. (This fixes two bugs in the deleted
   reference: it flushed committed entries on epoch change, and it gated head-drain on
   `headEntry.epoch === globalEpochIn`, which would deadlock a committed store from an
   older epoch. Committed => epoch-independent.)
6. STORE-ACK SEMANTICS on the publish bus: a committed store, once its write is
   acknowledged by memory, SHALL emit a `StoreComplete` token to the ResponseArbiter
   carrying `{seq, fault}` and NO data. This is how the backend learns a store retired
   from the subsystem's point of view (needed for fences, and for exact fault
   reporting on stores). A store's architectural retirement in the backend is NOT
   blocked on this ack (the backend already committed it); the ack is bookkeeping and
   fault delivery only.
7. BACKPRESSURE: `StoreBuffer.ready` (to StoreUnit / dispatcher) deasserts when the
   buffer is full. Because commit cannot advance past an un-drained store and the
   buffer can fill, the backend commit path MUST tolerate a full store buffer without
   deadlock: a store at the head of the ROB that cannot enter a full buffer stalls
   commit, which is legal backpressure (FCL paradigm).

### Depth

`StoreBufferDepth = f(N)`. Normative: `StoreBufferDepth = max(1, N)` capped by a
tuning parameter `StoreBufferDepthMax` (default 8). At `N=1` the buffer is a single
entry and degenerates to a commit-gated holding register (one store in flight, drained
before the next can issue). This is exactly the in-order store semantics with zero
speculation depth.

### Spec-DSL to paste (new file `spec/modules/StoreBufferSpecs.scala`)

```scala
object StoreBufferSpecs {
  val contStoreBuffer = spec {
    CONTRACT("StoreBuffer")
      .desc("""Age-ordered, commit-gated holding buffer that converts speculative,
              |epoch-tagged store tokens into an in-order irrevocable write stream.
              |The correctness boundary between UDA speculation and memory side effects.""".stripMargin)
      .has(intfStoreDispatchIn, intfStoreCommitIn, intfLoadFwdQuery, intfLoadFwdData,
           intfControllerWriteOut, intfStoreCompleteOut, intfGlobalEpochIn)
      .uses(paramStoreBufferDepth)
      .note("Speculative (committed=false) entries NEVER issue a controller write.")
      .note("Only the HEAD, when committed=true, drains to the controller, in seq order.")
      .note("Epoch mismatch invalidates uncommitted entries only; committed entries are irrevocable.")
      .build()
  }
  val intfStoreCommitIn = spec {
    INTERFACE("StoreCommitIn")
      .desc("Commit token from backend CommitUnit that marks a buffered store irrevocable.")
      .uses(bndStoreCommit).is(rawReadyValidIntf).build()
  }
  val intfLoadFwdQuery = spec {
    INTERFACE("LoadFwdQuery")
      .desc("Load address/size/age probe from LoadUnit for store-to-load forwarding.")
      .uses(bndLoadFwdQuery).is(rawReadyValidIntf).build()
  }
  val intfLoadFwdData = spec {
    INTERFACE("LoadFwdData")
      .desc("Per-byte forwarded store bytes + coverage mask + full-hit flag.")
      .uses(bndLoadFwdData).is(rawReadyValidIntf).build()
  }
  val intfStoreCompleteOut = spec {
    INTERFACE("StoreCompleteOut")
      .desc("Post-ack store retirement/fault token to the ResponseArbiter (no data).")
      .uses(bndStoreComplete).is(rawReadyValidIntf).build()
  }
  // intfStoreDispatchIn, intfControllerWriteOut, intfGlobalEpochIn reuse existing bundles.
}
```

```scala
val bndStoreCommit = spec {
  BUNDLE("StoreCommit")
    .desc("Marks a buffered speculative store as committed (irrevocable).")
    .note("Fields: seq, epoch")   // seq identifies the entry; epoch checks staleness
    .build()
}
val bndStoreComplete = spec {
  BUNDLE("StoreComplete")
    .desc("Store retirement acknowledgement from the memory subsystem.")
    .note("Fields: seq, fault")   // no data field
    .build()
}
```

### Alternatives considered and rejected

- No store buffer; gate stores in the backend ROB and release at commit. REJECTED:
  the AGU->memory edge already leaves the backend before commit
  (`BackendTopSpecs.scala:121`); putting the gate in the backend duplicates ROB state
  into the memory path and breaks the domain boundary. The buffer is the natural home
  for age order + forwarding.
- Write-through with undo (log old value, roll back on squash). REJECTED: memory
  writes to a TCM are not cheaply reversible and the epoch model has no rollback
  primitive; speculative-write-then-undo is strictly more logic than never-write.
- Store buffer that also holds committed stores indefinitely as a write-combining
  cache. REJECTED at spec level for RV32 TCM: adds coalescing/eviction policy with no
  workload justification; deferred to a future tuning knob, not the base contract.

### Cost at N=1: ~zero

Single entry, `committed` bit, `seq`/`epoch` registers already needed for the token.
No CAM, no age-compaction network (depth 1 => head==tail). Elaborates to a
commit-gated register between StoreUnit and MemoryController. This is the minimal
in-order LSU.

### Cost at N=32

`StoreBufferDepth = min(32, StoreBufferDepthMax)` entries; a priority
age-compaction / squeeze network on epoch kill; a fully-associative forwarding CAM
(see M2). This is the standard OoO store-queue cost and is paid only when the window
is opened.

### Verification obligations

- ASSERTION (elaboration+runtime): no `MemoryController` write request may fire whose
  source entry has `committed = false`. This is the golden invariant; wire it as a
  Chisel `assert` bound to a PROPERTY spec.
- ASSERTION: writes to the controller leave in monotonically non-decreasing `seq`
  order (in-order drain).
- ASSERTION: on epoch kill, no committed entry changes state.
- TEST: extend `SingleCoreMulDivClusterTest` "Store test" cases with a
  store-under-misprediction case: a store on a wrong path must produce zero external
  writes (observable on `TestMem`).

### Open questions for the panel

- Q-M1a: Does `StoreComplete` need to feed the backend at all, or can store faults be
  reported entirely at AGU time (misaligned) plus a fenced re-check? I propose keeping
  `StoreComplete` for precise bus faults, but it is the one edge we could cut for a
  pure-TCM no-fault build.
- Q-M1b: `StoreBufferDepthMax` default: 8 (my proposal) vs strictly `= N`.

---

## M2. Load ordering, forwarding, speculation, replay

### Contract (normative)

1. Loads MAY execute speculatively and out of order with respect to their own epoch;
   a wrong-path load has no architectural effect and its result is filtered at
   PublishMux by the existing epoch rule. The LSU adds NO extra load-speculation
   gate.
2. LOAD vs OLDER STORE - forwarding, not stall (MDG paradigm,
   `docs/foundations/unified-microarchitectural-paradigms.md:43-58`): every load
   SHALL probe the StoreBuffer via `LoadFwdQuery{addr, size, olderThan=seq}`. The
   buffer returns `LoadFwdData{data, mask, hit}` computed per byte from all buffered
   stores with `entry.seq < load.seq` (older only), youngest-per-byte wins, same word.
   - FULL HIT (mask covers all requested bytes): the load result is the forwarded
     data; NO controller read is issued.
   - PARTIAL / NO HIT: the load issues a controller read and merges forwarded bytes
     over the returned line (forwarded bytes take priority per the coverage mask).
   This is a conservative, address-EXACT (same-word + byte-mask) forwarding check. It
   never stalls on a store whose address is known; it only merges.
3. UNKNOWN-ADDRESS ORDERING: at N=1 all older stores have resolved addresses before a
   load can issue (single in-flight), so there is no unknown-address hazard. For N>1,
   a load MAY issue while an older store's address is not yet in the buffer. Base
   policy: CONSERVATIVE - a load may only forward/bypass past stores that are ALREADY
   in the buffer; the LoadUnit SHALL NOT issue a controller read past an older store
   whose address is not yet resolved IF the machine is built with
   `MemDisambig = false`. With `MemDisambig = true` (the OoO build), the load issues
   speculatively and is subject to selective replay (rule 4).
4. REPLAY IS SELECTIVE (MDG): when a store commits/drains that a younger already-
   completed load should have forwarded from but did not (address arrived late), ONLY
   that load and its dependents replay, not the pipeline. Mechanism in the UDA idiom:
   the StoreBuffer, on enqueue of a store whose `addr` matches a
   younger-already-issued load, emits a memory-ordering mispredict that advances the
   epoch for that load's tag. Because epoch advance re-derives the younger slice, the
   dependent chain re-executes; older work is untouched. At N=1, `MemDisambig=false`,
   this logic elaborates away entirely (no younger load can pass an unresolved store).
5. Loads are NOT buffered in a load queue for ordering at the base point. A load
   queue (`LoadOutstanding > 1`) exists only to track multiple outstanding controller
   reads by `txnId` (see M4), not to enforce ordering.

### Spec-DSL to paste (bundles)

```scala
val bndLoadFwdQuery = spec {
  BUNDLE("LoadFwdQuery")
    .desc("Store-to-load forwarding probe.")
    .note("Fields: addr, size, olderThan(seq)")
    .build()
}
val bndLoadFwdData = spec {
  BUNDLE("LoadFwdData")
    .desc("Per-byte forwarding result from the store buffer.")
    .note("Fields: data, mask (per-byte coverage), hit (full-cover flag)")
    .build()
}
```

Add to LoadUnit CONTRACT (`LoadUnitSpecs.scala`): `.has(intfLoadFwdQuery,
intfLoadFwdData)` on the query side, and a PROPERTY:

```scala
val propLoadForwardExact = spec {
  PROPERTY("LoadForwardExact")
    .desc("A load forwards a byte only from an OLDER store (entry.seq < load.seq) at the same word; youngest older store wins per byte.")
    .note("Full byte-mask coverage suppresses the controller read entirely.")
    .build()
}
```

### Alternatives considered and rejected

- Non-forwarding, stall-on-any-older-store. REJECTED: kills load-use latency, which is
  the dominant exploitable latency on an embedded RV32; contradicts MDG's explicit
  "forwarding, not ad-hoc stall" purpose.
- Speculative memory dependence predictor (store-set / NoSQ) at the base point.
  REJECTED for base; it is the correct N=32 luxury but pays a predictor table at N=1.
  Fold it behind `MemDisambig` so it elaborates away in-order.
- Load queue that enforces total load order. REJECTED: RVWMO on a single hart with a
  single in-order commit and a per-word forwarding buffer already gives the required
  program-order-for-same-address semantics; a load ordering queue is dead area for
  TCM.

### Cost at N=1: ~zero

`MemDisambig=false`, `LoadOutstanding=1`. Forwarding CAM degenerates to a single
comparator against the one store buffer entry. No replay logic, no load queue. Load
path = address compare + optional single read.

### Cost at N=32

Fully-associative byte-mask forwarding CAM over `StoreBufferDepth` entries;
memory-dependence mispredict/replay edge; `LoadOutstanding` read tracking. Standard
OoO LSU cost, gated on `MemDisambig` / window.

### Verification obligations

- ASSERTION: forwarded bytes only ever come from entries with `seq < load.seq`.
- ASSERTION (with MemDisambig): every replay advances only the offending load's tag
  epoch, never a global flush of older work (compare live seq range before/after).
- TEST: store-to-load forward same word, partial overlap (byte/half over word),
  RAW hazard back-to-back; wrong-path load produces no committed writeback.
- COVERAGE spec: full-hit, partial-hit, no-hit, and (N>1) late-address replay.

### Open questions

- Q-M2a: Is `MemDisambig` a compile-time bool derived from `N>1`, or an independent
  tuning knob (allowing wide-window-but-conservative builds)? I propose independent,
  defaulting to `N>1`.
- Q-M2b: Panel must confirm the target memory-consistency statement. I assume
  single-hart RVWMO with no fence-free reordering visible to software; if a second
  hart / coherent agent is ever in scope, forwarding-only is insufficient.

---

## M3. MemoryDispatcher direction fix + rewritten subsystem graph

### The contradiction, resolved

The MemoryDispatcher CONTRACT (`MemoryDispatcherSpecs.scala:11-42`: inputs
`intfLoadIn`+`intfStoreIn`, output `intfControllerOut`, "toward the shared
controller") describes a MERGE. The top mermaid (`MemorySubsystemSpecs.scala:56-57`)
and the deleted reference implementation both describe a SPLITTER. NORMATIVE RESOLUTION:
the MemoryDispatcher is a SPLITTER. It takes one `MemoryOpReq` in and routes to
`LoadUnit` OR `StoreBuffer` (via StoreUnit) by the load/store bit. The current
CONTRACT is rewritten below. The MERGE role belongs to the MemoryController's request
arbiter, not the dispatcher.

### Rewritten MemoryDispatcher CONTRACT (paste over `MemoryDispatcherSpecs.scala`)

```scala
val contMemoryDispatcher = spec {
  CONTRACT("MemoryDispatcher")
    .desc("Splits one incoming MemoryOpReq into a load-path token or a store-path token by the op's load/store attribute.")
    .has(intfMemOpIn, intfLoadOut, intfStoreOut)
    .note("Splitter, not merger. Exactly one of load/store fires per input; asserts not(isLoad && isStore).")
    .note("Backpressure: memOpIn.ready = (isLoad ? loadOut.ready : storeOut.ready).")
    .build()
}
val intfMemOpIn = spec {
  INTERFACE("DispatcherMemOpIn").desc("Unified memory op from the AGU/backend.")
    .uses(bndMemoryOpReq).is(rawReadyValidIntf).build()
}
val intfLoadOut = spec {
  INTERFACE("DispatcherLoadOut").desc("Routed load-path token to the LoadUnit.")
    .uses(bndDispatcherLoad).is(rawReadyValidIntf).build()
}
val intfStoreOut = spec {
  INTERFACE("DispatcherStoreOut").desc("Routed store-path token to the StoreUnit.")
    .uses(bndDispatcherStore).is(rawReadyValidIntf).build()
}
```

### The rewritten subsystem graph (normative)

Vertices: `MemoryDispatcher` (splitter), `LoadUnit`, `StoreUnit`, `StoreBuffer`
(NEW), `MemoryController` (merge arbiter + external bus master), `ResponseArbiter`.

Edges (all Decoupled ready/valid unless noted; epoch and StoreCommit noted):

```
MemoryOpReq(in) ----------> MemoryDispatcher
MemoryDispatcher --Load---> LoadUnit
MemoryDispatcher --Store--> StoreUnit
StoreUnit -----------------> StoreBuffer            (enqueue speculative)
StoreCommit(in, backend) --> StoreBuffer            (mark committed; NEW cross-domain edge)
GlobalEpoch (-.-> broadcast) -> StoreBuffer, LoadUnit  (epoch kill / staleness)
LoadUnit --FwdQuery-------> StoreBuffer              (forwarding probe)
StoreBuffer --FwdData-----> LoadUnit
LoadUnit --ReadReq--------> MemoryController
StoreBuffer --WriteReq----> MemoryController         (HEAD, committed only)
MemoryController --ReadResp--> LoadUnit
MemoryController --WriteAck--> StoreBuffer           (free head, emit StoreComplete)
LoadUnit --LoadResp-------> ResponseArbiter
StoreBuffer --StoreComplete--> ResponseArbiter
ResponseArbiter --MemoryOpResp(out)-->  (to backend PublishMux)
MemoryController <---> ExternalDataMemoryReq/Resp    (bus boundary)
```

ORDERING IS ENFORCED IN EXACTLY ONE PLACE: the StoreBuffer (age order + commit gate +
forwarding). The MemoryController is a stateless-ish arbiter/bus-master and enforces
NO program order; it only multiplexes ready reads and the single committed write, and
tags them with `txnId` (M4). This keeps the ordering contract auditable to one vertex,
per MDG's "no hidden bypass around the dependency table."

The mermaid in `MemorySubsystemSpecs.scala` MUST be updated to add the StoreBuffer
vertex, the StoreCommit input, the FwdQuery/FwdData edges, and the WriteAck edge, and
the top CONTRACT `.has(...)` list gains `intfStoreCommitIn`.

### Alternatives considered and rejected

- Keep dispatcher as merge (honor the current CONTRACT text), move the split
  upstream into the AGU. REJECTED: the AGU emits one `MemoryOpReq` stream by design
  (`BackendTopSpecs.scala:121`); splitting belongs at the subsystem boundary, and the
  deleted reference confirms intent.
- Put forwarding in the LoadUnit with its own copy of store state. REJECTED:
  duplicates buffer state, creates a second ordering authority; violates single-owner.

### Cost at N=1 / N=32

Graph shape is identical across N; only StoreBuffer depth and the forwarding CAM width
scale. The dispatcher, controller, arbiter are N-invariant.

### Verification obligations

- ELABORATION CHECK: module CONTRACT `.has` ports must match the top mermaid edges
  (this is exactly the graph-consistency check the review asks for at
  `document/rebuild_branch_architecture_review.md:145-151`). The StoreBuffer must
  appear in both.
- ASSERTION: dispatcher never asserts both `loadOut.valid` and `storeOut.valid`.

### Open questions

- Q-M3a: Does StoreUnit remain a distinct vertex, or fold into StoreBuffer? I keep it
  as the token-shaping / mask-generation front of the buffer (matches deleted
  `captureIssue`), but a panel could merge them to shrink the graph.

---

## M4. Bus contract (txnId, outstanding, ordering, fence/AMO)

### Contract (normative)

1. TXNID WIDTH POLICY: `DataMemoryTxnIdWidth = ceil(log2(LoadOutstanding))`, i.e. it
   is DERIVED, not a hand-set contract number. At `LoadOutstanding = 1` this is `0`
   bits (matches today's `CoreParamsSpecs.scala:50-58` default and its note "current
   system won't use transaction ID") - a single outstanding request, responses
   trivially in order, no txnId storage. At `LoadOutstanding = k`, txnId is a
   `log2(k)`-bit tag the LoadUnit assigns and the response uses to reunite data with
   the waiting load. Stores use a reserved single txnId (or 0) since only one committed
   write is outstanding at a time.
2. OUTSTANDING REQUEST RULES: at most `LoadOutstanding` reads and at most ONE write
   may be outstanding on the external bus concurrently. A write (committed store
   drain) and reads MAY overlap on the bus ONLY when the addresses do not alias; the
   StoreBuffer forwarding already resolves read-after-write for buffered stores, and a
   committed-but-not-yet-acked store is still in the buffer, so a concurrent younger
   load still forwards from it. Therefore read/write overlap is safe by construction.
3. RESPONSE ORDERING THE BACKEND MAY ASSUME: NONE by position. The backend must treat
   `MemoryOpResp` as UNORDERED and reassociate by `txnId` (loads) / `seq`
   (StoreComplete). At `LoadOutstanding=1` responses are in order as a degenerate case,
   but the CONTRACT the backend codes against is "match by tag," so widening the window
   never changes backend code. The existing `MemoryOpResp` bundle
   (`BackendBundlesSpecs.scala:21`, fields data/meta/fault) MUST carry the tag in
   `meta` (add `txnId`/`seq`).
4. FENCE PLACEHOLDER SEMANTICS: `FENCE`/`FENCE.I` is executed at commit as a
   barrier: the backend holds commit of instructions after the fence until the
   StoreBuffer reports empty (all committed stores drained and acked). No new bus
   command is needed; the fence is a commit-gate condition on `StoreBuffer.empty`.
   `FENCE.I` additionally requires the frontend I-fetch path to observe drained stores
   (self-modifying code); for a split TCM this is a redirect after drain. The
   normative placeholder: `command` field reserves a `FENCE` encoding but the base
   TCM build implements fence purely as the commit-gate, emitting no external
   transaction.
5. AMO PLACEHOLDER SEMANTICS: RV32A is OUT OF SCOPE for the base RV32IMC contract.
   The bus `command` field (`DataMemoryCommandWidth=5`,
   `CoreParamsSpecs.scala:42-48`) reserves encodings for a future read-modify-write
   `command` and a `lr`/`sc` reservation bit, but the base build SHALL NOT generate
   them. Documented reservation, not a stub: no `command := AMO` assignment exists
   anywhere until RV32A is specced. This honors the "no stubs" rule.

### Spec-DSL to paste (params)

```scala
val paramLoadOutstanding = spec {
  PARAMETER("LoadOutstanding")
    .desc("Max concurrent outstanding external read requests.")
    .note("Tuning tier. Derived default = 1 at N=1 (single, in-order, txnId width 0).")
    .note("DataMemoryTxnIdWidth = ceil(log2(LoadOutstanding)).")
    .build()
}
val paramStoreBufferDepth = spec {
  PARAMETER("StoreBufferDepth")
    .desc("Age-ordered store buffer depth; = max(1, min(N, StoreBufferDepthMax)).")
    .note("Contract-relevant: sets the speculative store capacity and forwarding CAM width.")
    .build()
}
```

And retie `paramDataMemoryTxnIdWidth` (`CoreParamsSpecs.scala:50-58`) note to:
"derived = ceil(log2(LoadOutstanding)); 0 when single-outstanding."

### Alternatives considered and rejected

- Fixed nonzero txnId width for forward-compat. REJECTED: dead bits at N=1; derive it.
- Bus-level ordered responses (backend assumes response order == request order).
  REJECTED: forces the bus/controller to reorder, and breaks the moment
  `LoadOutstanding>1` or the memory has variable latency. Tag-based reassociation is
  the only contract that scales unchanged.
- Real AMO/LR-SC now. REJECTED: not in the RV32IMC contract; reserving encodings
  without emitting them is the correct spec-first placeholder.

### Cost at N=1: ~zero

txnId width 0, one outstanding read, one outstanding write, in-order-by-construction.
Fence = `StoreBuffer.empty` gate (already have empty). No AMO logic elaborated.

### Cost at N=32

`log2(LoadOutstanding)`-bit tags, a small read-tracking table in the LoadUnit,
concurrent read/write on the bus. AMO still absent unless RV32A is specced.

### Verification obligations

- ASSERTION: number of in-flight reads never exceeds `LoadOutstanding`; writes never
  exceed 1.
- ASSERTION: every response `txnId` matches an outstanding request tag (no orphan
  responses).
- ASSERTION: after a `FENCE` retires, `StoreBuffer.empty` held at the retire cycle.
- TEST: `LoadOutstanding>1` with out-of-order response arrival reassociates correctly
  (drive `TestMem` to return responses reordered by tag).

### Open questions

- Q-M4a: Is `FENCE.I` in scope for the TCM (self-modifying code)? If the I-side is a
  separate read-only TCM in practice, `FENCE.I` may be a no-op beyond the store drain.
- Q-M4b: Reserve the AMO/LR-SC `command` encodings now (my proposal) or leave
  `DataMemoryCommandWidth` sized only for Read/Write (2 values) and widen later?
  Reserving costs nothing today and avoids a later width change.

---

## M5. Parameter cleanup (RV32 TCM contracts)

### Contract (normative)

Replace the 64-bit / 2-load-unit server template with RV32-TCM-fit contracts.

1. `MemorySubsystemContractParams`: `addressWidth = 32`, `memOpWidth = 32` (fixed for
   RV32). The `Set(8,16,32,64)` check on `memOpWidth`
   (`design/shared/MemorySubsystemParams.scala:22-25`) is retained but the DEFAULT and
   every preset used by this core is 32. `memoryPorts` default `1` (single TCM port).
2. Remove `loadUnitCount` / `storeUnitCount` as tuning knobs from the base contract.
   The graph has exactly ONE LoadUnit and ONE StoreUnit + ONE StoreBuffer. Multi-port
   LSU is not on the RV32 roadmap; a `2`-load-unit default
   (`design/shared/MemorySubsystemParams.scala:35`) is dead complexity. If ever
   needed, it returns as a private knob, not a default.
3. Replace `loadQueueSize`/`storeQueueSize` (default 16,
   `design/shared/MemorySubsystemParams.scala:33-34`) with the two derived knobs:
   `StoreBufferDepth = max(1, min(N, StoreBufferDepthMax))` and `LoadOutstanding`
   (default 1). Remove the `>= 4` lower-bound `require`s
   (`design/shared/MemorySubsystemParams.scala:38-45`): they forbid the N=1
   single-entry point, which is exactly the in-order target this project exists to
   support.
4. Delete/relabel the `standard` (64-bit, `.scala:111-123`) and `highPerformance`
   (`.scala:127-139`) presets, or re-base them to 32-bit. The `minimal` preset
   (`.scala:95-107`, already 32-bit, 1 port, depth 4) becomes the ONLY sane default;
   rename to `rv32Tcm` and set store depth from `N`.
5. `ArbitrationPolicy` (`MemorySubsystemParamsSpecs.scala:41-46`, string
   "RoundRobin"/...) is over-general. For one committed write vs `LoadOutstanding`
   reads on one bus port, the policy is fixed: writes at the head drain when committed;
   reads fill the remaining bus slots. Reduce to a `WritePriority` bool (default: reads
   may not starve the single committed write). Keep it PRIVATE tier.

### Spec-DSL to paste (replace `MemorySubsystemParamsSpecs.scala` tuning entries)

```scala
val paramStoreBufferDepth = spec {
  PARAMETER("StoreBufferDepth")
    .desc("Speculative store capacity = max(1, min(N, StoreBufferDepthMax)).")
    .note("Contract tier - sets speculative store window and forwarding CAM width.")
    .note("N=1 => depth 1 => commit-gated holding register (in-order store).")
    .build()
}
val paramLoadOutstanding = spec {
  PARAMETER("LoadOutstanding")
    .desc("Max concurrent outstanding external reads (default 1).")
    .note("Tuning tier - derives DataMemoryTxnIdWidth = ceil(log2(LoadOutstanding)).")
    .build()
}
```

### Alternatives considered and rejected

- Keep the three-preset (minimal/standard/highPerformance) ladder. REJECTED: two of
  three presets are 64-bit non-RV32 configurations that can never elaborate against an
  RV32IMC core; they are copy-paste from a generic template and mislead integrators.
- Keep `loadQueueSize>=4`. REJECTED: directly blocks the N=1 in-order identity of the
  project (`document/rebuild_branch_architecture_review.md:156-159`, "make
  SpeculativeRegNum=1 a true degenerate case").

### Cost at N=1 / N=32

This decision has no gate cost; it is a defaults/contract cleanup. The point is that
the N=1 build now elaborates (previously blocked by `>=4` requires) with a depth-1
buffer and a 32-bit datapath.

### Verification obligations

- ELABORATION TEST: instantiate the subsystem at `N=1` (depth 1, LoadOutstanding 1,
  txnId width 0) and confirm no CAM / no age network / no load queue survives (netlist
  inspection or Chisel `require` guards on generate blocks).
- ELABORATION TEST: `N=32` build elaborates with depth capped at
  `StoreBufferDepthMax`.

### Open questions

- Q-M5a: `StoreBufferDepthMax` value (8?) and whether depth should track `N` linearly
  or saturate earlier (store bursts on embedded code are short).
- Q-M5b: Do we keep a 64-bit datapath option at all for a future RV64, or hard-fix 32
  now and reintroduce width parametrically later? I propose hard-fix 32 for the base
  contract and keep `memOpWidth` parametric only in the type, not the presets.

---

## Cross-domain contract addition (requires backend + core panel sign-off)

The single most important NEW edge this paper introduces is `StoreCommit` from the
backend CommitUnit to the memory subsystem StoreBuffer. It does not exist today
(`BackendTopSpecs.scala` has no commit->memory edge). Without it there is no way to
gate stores at commit, and the fatal speculative-store-to-memory case cannot be
prevented at the spec level. Concretely:

- BackendTop gains an output interface `intfStoreCommitOut` (bundle `bndStoreCommit`,
  fields `{seq, epoch}`), driven by the CommitUnit when a store uop retires.
- MemorySubsystemTop gains an input interface `intfStoreCommitIn` (same bundle),
  routed to the StoreBuffer.
- CoreTop wires BackendTop.storeCommitOut -> MemorySubsystem.storeCommitIn as a
  Decoupled edge (`:<>=`).

This is the memory-side counterpart to silent hole #1 (rename recovery): both need a
commit-time signal the current graph omits. I flag it explicitly so the backend
architect's paper and this one converge on one `StoreCommit` bundle.

---

## Consolidated verification matrix (what proves each contract)

| Contract | Golden assertion | Cluster test |
|---|---|---|
| M1 no speculative write | `assert(!(ctrlWrite.fire && !entry.committed))` | store-under-mispredict => 0 external writes |
| M1 in-order drain | `assert(writeSeq monotonic)` | back-to-back store ordering |
| M1 committed survives kill | `assert(epochKill does not clear committed)` | redirect between commit and drain |
| M2 forward-from-older-only | `assert(fwd.src.seq < load.seq)` | RAW same/partial word |
| M2 selective replay (N>1) | live-seq-range unchanged except offending load | late-address load replay |
| M3 graph consistency | CONTRACT `.has` == mermaid edges | elaboration graph check |
| M3 split exclusivity | `assert(!(loadOut.valid && storeOut.valid))` | mixed load/store stream |
| M4 outstanding bound | `assert(inflightReads <= LoadOutstanding)` | reordered-response test |
| M4 fence drain | `assert(sb.empty at fence retire)` | fence between stores/loads |
| M5 N=1 folds away | generate-time `require` on CAM blocks | N=1 elaboration + netlist |

---

## Top open questions to settle at the panel (ranked)

1. Ratify the `StoreCommit` cross-domain edge (M1/cross-domain). Nothing else works
   without it. Backend architect must own the CommitUnit-side driver.
2. `MemDisambig` as derived-from-N vs independent knob, and the exact
   memory-consistency target (single-hart RVWMO assumed) (M2).
3. `StoreBufferDepth` law: `max(1,min(N,Max))` and `StoreBufferDepthMax` value (M1/M5).
4. Fence.I scope for split TCM; AMO/LR-SC encoding reservation now vs later (M4).
5. Whether StoreUnit stays a separate vertex or folds into StoreBuffer (M3).
6. Deleting the 64-bit presets outright vs re-basing them to 32 (M5).
