# ADR-003: Store Lifecycle, Load Ordering, and Memory Disambiguation

Status: **accepted** (store lifecycle, forwarding, subsystem graph, bus,
parameter cleanup) with a **proposed** clause on speculative load disambiguation.

Depends on: ADR-011 (redirect at commit head), ADR-002 (commit broadcast),
ADR-012 (canonical tag). Cross-references ADR-004 (fence drain).

## Context

This closes silent hole #2 of the accepted review: no store buffer vertex, no
stores-drain-at-commit rule, no load-store ordering or forwarding contract
(`document/rebuild_branch_architecture_review.md:129-132`). A speculative store
reaching memory before commit is architecturally fatal. The subsystem graph today
has no store buffer, and the AGU path emits `MemoryOpReq` straight out of
BackendTop (`BackendTopSpecs.scala:121`) with no commit->memory edge.

The correctness/PPA critiques (B2, BL-1) established that P02 M2 rule 4's
"selective per-tag replay" has no substrate under a single global epoch. This ADR
adopts the conservative base and defers speculative disambiguation to a proposed
extension gated on ADR-011 D-11.4.

## Decision - store lifecycle (accepted)

**D-3.1 (normative).** There SHALL be a `StoreBuffer` vertex on the store path
between `StoreUnit` and `MemoryController`: an age-ordered (seqTag-ordered)
circular buffer of depth `StoreBufferDepth`. New spec file
`memorysubsystem/spec/modules/StoreBufferSpecs.scala` - WP-B.

**D-3.2 (normative, the single load-bearing invariant).** A store entering the
buffer is SPECULATIVE (`committed=false`); a speculative store SHALL NEVER produce
a `MemoryController` write.

**D-3.3 (normative).** A store becomes COMMITTED only on the **commit broadcast**
(ADR-012, the unified bundle; StoreCommit is its store-path view) that matches the
buffered entry by the canonical `seqTag`. Commit is in-order (ADR-002), so commit
tokens arrive in seqTag order.

**D-3.4 (normative, COMMIT-DRAIN).** Only the buffer HEAD, and only when
`committed=true`, may issue a write. Writes reach memory strictly in program
order, one at a time, after retirement. On write-ack the head frees and advances.

**D-3.5 (normative, EPOCH-KILL).** On `epoch =/= globalEpoch`, every SPECULATIVE
entry is invalidated and squeezed out of the age order. COMMITTED entries are
IRREVOCABLE and survive any epoch change; they drain regardless of the current
global epoch. (This fixes the two deleted-reference bugs: it flushed committed
entries on epoch change and gated head-drain on epoch match, which would deadlock
a committed store from an older epoch.) Committed entries are an enumerated
epoch-exempt vertex per ADR-005.

**D-3.6 (normative, STORE-ACK).** A committed store, once acked, emits a
`StoreComplete{seqTag, fault}` (no data) to the ResponseArbiter for fences and
fault reporting. Backend retirement is NOT blocked on this ack (ADR-002 already
committed it); the ack is bookkeeping and fault delivery only. Precise store-fault
policy: see D-3.13.

**D-3.7 (normative, depth).** `StoreBufferDepth = max(1, min(N, StoreBufferDepthMax))`,
`StoreBufferDepthMax` default 8. At N=1 the buffer is a single commit-gated
holding register.

## Decision - load ordering and forwarding (accepted base)

**D-3.8 (normative).** Loads MAY execute speculatively and out of order; a
wrong-path load has no architectural effect and its result is filtered at
PublishMux by epoch. The LSU adds no extra load-speculation gate.

**D-3.9 (normative, forwarding not stall).** Every load probes the StoreBuffer via
`LoadFwdQuery{addr, size, olderThan=seqTag}`; the buffer returns
`LoadFwdData{data, mask, hit}` computed per byte from all buffered stores with
`entry.seqTag < load.seqTag` (older only, youngest-per-byte wins, same word).
Full byte-mask coverage suppresses the controller read; partial/no hit issues a
read and merges forwarded bytes. Age comparison is **wrap-aware** relative to the
bounded live range (ADR-012), because seqTag is a small wrapping counter.

**D-3.10 (normative, base disambiguation = conservative).** With `MemDisambig=false`
(the base and the N=1 build), a load MUST NOT issue a controller read past an
older store whose address is not yet resolved; it may only forward/bypass past
stores already in the buffer with known addresses. At N=1 all older stores have
resolved before a load can issue, so this is free.

## Decision - speculative disambiguation (PROPOSED)

**D-3.11 (proposed).** With `MemDisambig=true` (a wide-OoO build), a load MAY
issue speculatively past an unresolved older store; a late-arriving store address
matching a younger already-completed load triggers a **memory-order redirect at
the commit head** (ADR-011, global kill of younger work, target = offending load
PC), NOT a selective per-tag replay. The BackendTop/RedirectUnit graph gains a
store-buffer -> RedirectUnit memory-order-mispredict producer (ADR-013 priority
ladder). This clause is adopted ONLY if ADR-011 D-11.4 (multi-context epochs) is
later adopted OR the measured cost of a full-kill memory-order replay is
acceptable; until then `MemDisambig` defaults false and the whole replay path
elaborates away. P02 M2 rule 4's "older work untouched / selective" language is
struck.

## Decision - subsystem graph, bus, parameters (accepted)

**D-3.12 (normative, dispatcher is a SPLITTER).** Resolve the
MemoryDispatcher contradiction (`MemoryDispatcherSpecs.scala:11-42` describes a
merge; the top mermaid and the deleted reference describe a splitter): the
MemoryDispatcher is a SPLITTER (one `MemoryOpReq` in, load-path OR store-path out
by the load/store bit). The merge role belongs to the MemoryController's request
arbiter. Rewrite `MemoryDispatcherSpecs.scala` - WP-B. Add StoreBuffer,
StoreCommit input, FwdQuery/FwdData edges, WriteAck edge to the top mermaid in
`MemorySubsystemSpecs.scala`.

**D-3.13 (normative, bus).** `DataMemoryTxnIdWidth = ceil(log2(LoadOutstanding))`
(derived; 0 at LoadOutstanding=1). At most `LoadOutstanding` reads and ONE write
outstanding. The backend treats `MemoryOpResp` as UNORDERED and reassociates by
`txnId` (loads) / `seqTag` (StoreComplete). `MemoryOpResp.meta` carries the tag.
FENCE is a commit-gate on `StoreBuffer.empty` (ADR-004 C4.2), emitting no external
transaction. AMO/LR-SC: reserved `command` encodings only, never emitted in the
RV32IMC base (no stub assignment).

Precise store fault: the base TCM is assumed fault-free; bus store faults on an
already-retired committed store are documented as imprecise for the TCM. If a
faulting memory is ever in scope, store retirement MUST block on the ack (OQ).

**D-3.14 (normative, parameter cleanup).** RV32-TCM contracts: `addressWidth=32`,
`memOpWidth=32`, `memoryPorts=1`. Remove `loadUnitCount`/`storeUnitCount` as base
tuning knobs (one LoadUnit, one StoreUnit, one StoreBuffer). Replace
`loadQueueSize`/`storeQueueSize` with derived `StoreBufferDepth` and
`LoadOutstanding` (default 1). Remove the `>=4` lower-bound `require`s that forbid
the N=1 single-entry point. Re-base or delete the 64-bit `standard`/`highPerformance`
presets; `minimal` becomes the RV32-TCM default. Edit
`MemorySubsystemParamsSpecs.scala`, `design/shared/MemorySubsystemParams.scala` - WP-B.

## Alternatives rejected

- **No store buffer; gate in the backend ROB.** Rejected: no ROB (ADR-002); the
  AGU->memory edge leaves before commit; the buffer is the natural home for age
  order + forwarding.
- **Write-through with undo.** Rejected: TCM writes are not cheaply reversible;
  the epoch model has no rollback primitive; never-write is strictly less logic.
- **Selective per-tag memory-order replay in the base (P02 M2 rule 4).**
  Rejected: no substrate under one global epoch (critique B2/BL-1). Replaced by
  D-3.10 conservative base + D-3.11 proposed full-kill/multi-context.
- **Bus-ordered responses.** Rejected: forces the controller to reorder and
  breaks the moment `LoadOutstanding>1`; tag reassociation is the only scaling
  contract.

## Consequences

- N=1: single-entry buffer, `committed` bit, forwarding degenerates to one
  comparator, no replay, no load queue, txnId width 0. Minimal in-order LSU.
- N=32: `StoreBufferDepth` entries, byte-mask forwarding CAM, read-tracking table.
  This CAM is an N-scaled structure that MUST be in the N=1 structural grep scope
  (ADR-008, extending P05 D5 per critique MA-4).
- Introduces the cross-domain commit->memory edge (ADR-012 broadcast); without it
  stores cannot be gated at commit.

## Verification obligations

- Assert `!(ctrlWrite.fire && !entry.committed)` (golden invariant); write order
  monotone in seqTag; epoch kill does not clear committed entries.
- Assert forwarded bytes only from `entry.seqTag < load.seqTag` (wrap-aware).
- Cross-domain key test (critique V-MA-5): every accepted commit broadcast marks
  exactly one buffered entry, and that entry's seqTag equals the commit head's.
- Liveness (critique m3/V-MI-2): a PROPERTY that the external bus eventually
  accepts a committed write, so the buffer always drains, so commit always
  advances; directed backpressure test (full buffer + slow bus, no deadlock).
- Cluster test: store-under-misprediction produces zero external writes (observable
  on `TestMem`).
- `LoadOutstanding>1` reordered-response reassociation test.
- (proposed D-3.11) late-address memory-order redirect test, only when
  `MemDisambig=true`.
