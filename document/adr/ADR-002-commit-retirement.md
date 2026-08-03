# ADR-002: Commit and Retirement Without a ROB

Status: **accepted**.

Depends on: ADR-011 (redirect at commit head), ADR-012 (canonical tag and unified
commit broadcast), ADR-014 (retire width).

## Context

There is no ROB. Program order at the backend must come from somewhere, and
precise exceptions, in-order CSR/store visibility, and rename recovery (ADR-001)
all rest on it. The correctness critique (M4) flagged that four papers spawned
four commit-notification bundles keyed on different fields for one event; ADR-012
resolves the bundle/key. This ADR fixes what orders retirement and what CommitUnit
owns.

## Decision

**D-2.1 (normative).** The **allocation FIFO** produced by RenameUnit
(`intfDecodedUopAllocOut`, `RenameUnitSpecs.scala:43-48`; `bndDecodedUopAlloc`,
`BackendBundlesSpecs.scala:63-68`) and consumed by CommitUnit
(`intfDecodedUopAllocIn`, `CommitUnitSpecs.scala:29-34`) is the sole definition of
retirement order. Rename enqueues one alloc token per uop in program order;
CommitUnit retires strictly from the FIFO head. This matches the deleted
reference `robDepth==0` path (single head register, `robEnq.ready := !headValid`).

**D-2.2 (normative).** CommitUnit owns exactly: (1) the architectural
(retirement) map table (ADR-001 recovery source of truth); (2) retirement free
events (free the old prd of the retiring arch reg); (3) map-update events (point
the retiring arch reg at its new prd); (4) exception raising and the
redirect/serialization gate; (5) the single unified **commit broadcast** (ADR-012)
that fans the "uop U retired at the head" event to the store buffer (StoreCommit
view), CSR (commitGrant view), rename-free, and the retire stream (ADR-010).

**D-2.3 (normative).** A uop at the FIFO head retires when PublishMux has
published its result (`intfPublishResultOut`, `PublishMuxSpecs.scala:98-102` ->
`intfCommitResultIn`, `CommitUnitSpecs.scala:36-41`) AND its epoch matches.
CommitUnit holds a **completion scoreboard** indexed by the in-flight window
(size N, set by PublishResult, cleared at retire). This is the only per-uop state
commit needs - O(N) bits, not a data ROB.

**D-2.4 (normative, PROPERTY COMMIT-IN-ORDER).** Architectural state - map, free,
CSR side effects, store visibility - changes only at the FIFO head, in FIFO
order. Precise exceptions follow directly. This is the backend obligation that
makes the memory-ordering (ADR-003) and CSR-serialization (ADR-004) holes safe.

**D-2.5 (accepted, retire width).** Per ADR-014, base retire width = 1 (single
arbitrated commit, matching the single publish bus). Superscalar retire (P01 O2)
is a proposed extension carried in ADR-014; if adopted, retire width becomes
f(N) and enters the area-vs-N sweep (ADR-008).

Spec to write (`CommitUnitSpecs.scala`) - WP-A:

```scala
val propCommitInOrder = spec {
  PROPERTY("CommitInOrder")
    .desc("Architectural map updates, physical-register frees, CSR side effects, and store visibility occur only at the alloc-FIFO head, in FIFO order.")
    .note("Precise exceptions follow: the head is the precise architectural point.")
    .uses(intfDecodedUopAllocIn, intfCommitResultIn, intfExceptionOut).build()
}
val funcCompletionScoreboard = spec {
  FUNCTION("CompletionScoreboard")
    .desc("Per-in-flight-uop done bit set by PublishResult, cleared at retire; head retires when done and epoch-matched.")
    .note("Size = SpeculativeRegNum (N); degenerates to a single valid bit at N=1.")
    .uses(paramSpeculativeRegNum).build()
}
```

## Alternatives rejected

- **Data ROB (result payload stored, writeback at commit).** Rejected: it is
  exactly the writeback the unified-PRF choice exists to eliminate; duplicates PRF
  storage and adds a commit-time write port.
- **Retire directly on PublishResult (no ordering structure).** Rejected: breaks
  precise exceptions and in-order CSR/store visibility; PublishResult arrives out
  of program order.
- **Age counters in the RS as the order source.** Rejected: RS age orders
  *selection*, not *retirement*, and is not a program-order total once entries
  drain out of order. The alloc FIFO is authoritative.

## Consequences

- N=1: alloc FIFO collapses to a single head slot; completion scoreboard is one
  bit; ~zero over an in-order commit register.
- N=32: alloc FIFO depth ~N, each entry {arch rd, old prd, new prd, flags,
  seqTag, epoch} - small; completion scoreboard N bits; no data storage. This is
  the area win over a data ROB.
- Under ADR-011 the store-visibility and CSR-write gates are commit-head strobes,
  so stores/CSR are precise by construction (D-2.4).

## Verification obligations

- Assert: retire index monotone, equals FIFO dequeue order (simulation monitor).
- Assert: no architectural map/free/CSR/store-visible event occurs for a uop that
  is not the current FIFO head.
- Trap-precision directed test: inject a fault on uop i; assert uops < i retired
  and uops > i left no architectural trace (map, free, CSR, store).
