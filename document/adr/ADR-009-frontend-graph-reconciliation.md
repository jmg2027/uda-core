# ADR-009: Frontend Graph Reconciliation

Status: **accepted**.

Depends on: ADR-005 (eager filtering of issue-queue and slot-slicer carry),
ADR-006 (epoch guardrail G1 uses the combinational commit/redirect epoch),
ADR-011 (commit-head redirect; G4 return penalty), ADR-013 (single redirect edge).

## Context

The review flags the FetchUnit contract as contradicting the top graph (boot edge
destination, missing memory ports). Confirmed as a three-way inconsistency
(`03-frontend.md` s1.1): boot has one producer (BootSequencer) and two disagreeing
sinks (FetchUnit contract vs NextPcGen graph), FetchUnit declares neither memory
port yet the graph gives it both, and redirect is declared twice. SlotSlicer,
BranchPredecoder prune, IssueQueue, and fetchWidth are all under-specified.

## Decision

**D-9.1 (boot lands at NextPcGen).** Boot is a PC seed, not a fetch. It enters the
PC-generation vertex so the first NextPc token carries a valid epoch and flows
through the normal `npc -> fu` edge. FetchUnit never sees boot. Delete the stray
`intfBootIn`/`intfInstructionOut` from `FetchUnitSpecs.scala`.

**D-9.2 (FetchUnit owns exactly four edges).** in `NextPc`, out
`ExternalProgramMemoryReq`, in `ExternalProgramMemoryResp`, out `FetchResponse`.
FetchUnit is the memory-bridge vertex; its only architectural state is the
outstanding-request epoch latch (needed to tag/kill the single outstanding
request), not a pipeline register.

**D-9.3 (one redirect edge, declared at top).** The top-level `intfRedirectIn`
(`FrontendTopSpecs.scala:124`) is canonical; NextPcGen references it and the
top-level `intfBootAddrIn` rather than minting local duplicates. The corrected
FrontendTop mermaid MUST match child `.has(...)` port sets edge-for-edge
(graph-consistency, ADR-015 machine check 1). The normative graph is placed inside
`.draw("mermaid", ...)` of the FrontendTop CONTRACT (critique V-MA-1), not in prose.

**D-9.4 (SlotSlicer = one-half-word straddle machine).** SlotSlicer owns exactly
one architectural state element, the 16-bit carry (`carryValid`, `carryData`,
`carryPc`, `carryEpoch`). Invariants: INV-S1 byte conservation; INV-S2 carry is at
most one half-word; INV-S3 carry dropped on epoch change (eager filter, ADR-005);
INV-S4 2-byte slot PC alignment; INV-S5 the frontend TAGS `instrAddrMisaligned`
(and `programMemFault`) in slot metadata and never traps - the backend raises the
precise trap at commit (ADR-004). The frontend-tag / backend-raise handoff bundle
for I-fetch access fault is specified here (closes critique gap "misaligned/access
fault on fetch"): the slot carries `{exc: Bool, excCause}` and the backend, at
commit of that slot, raises with correct `mepc`/`mtval`.

**D-9.5 (first-taken prune).** Within a slot group in PC order, slots 0..t are
valid and t+1..K-1 pruned, where t is the first taken control transfer
(direct-jump always; conditional if predicted-taken; indirect always with unknown
target). The predecoder drives one epoch-tagged `PredictorOutput{valid, taken,
target, srcPc, srcEpoch, indirectStall}` per group to NextPcGen.

**D-9.6 (epoch guardrails, operational).** G1 redirect wins same-cycle and stamps
the post-increment (combinational, ADR-006 D-6.1) epoch on NextPc; G2 in-flight
fetch dropped on epoch mismatch at the FetchUnit response boundary; G3 prediction
consumed only if `srcEpoch === globalEpoch`; G4 indirect jumps stall fetch until
the backend redirects (base rule; under commit-head redirect this is a
~window-depth return bubble, so a BTB/RAS is the IPC lever, not optional at high N
- critique m2, recorded); G5 the wrap bound is subsumed by ADR-005 eager filtering
(P03's `prefetchDepth <= 2^epochWidth-1` require is withdrawn - the outstanding
fetch latch is an enumerated eager-filter vertex, ADR-005 D-5.2).

**D-9.7 (IssueQueue = rate-matching skid buffer, the one intentional cycle).**
INV-Q1 only-valid slots enqueued; INV-Q2 epoch-coherent dequeue (drop on
mismatch - AND, per ADR-005, ALSO eager-filter held entries each cycle, not only
at dequeue; this closes critique M1 - the queue is an enumerated eager-filter
vertex); INV-Q3 in-order issue; INV-Q4 atomic group enqueue at fetchWidth>1.
`iqDepth = max(prefetchDepth*slotsPerBeat, redirectRecoveryCycles*fetchWidth)`,
base 4. This is the irreducible frontend N=1 floor (included in the ADR-008
reference envelope, not counted as tax).

**D-9.8 (fetchWidth split).** Split the conflated `fetchWidth` into `memDataWidth`
(bus port width, contract tier, sets `slotsPerBeat = memDataWidth/16`) and
`fetchWidth` (instructions/cycle, derived, `fetchWidth <= slotsPerBeat`). Scaling
to 64/128-bit touches only SlotSlicer's scan (an O(log slotsPerBeat) parallel-prefix
length network - add it to the IPC-critical registry consideration via
`edgePredictToFetch` neighbours, ADR-007); NextPcGen never widens (one PC/cycle,
`fetchStride = memDataWidth/8`). `memDataWidth` SHOULD live in a shared `api/`
param (the memory subsystem needs the bus width too).

## Alternatives rejected

- **Boot enters FetchUnit.** Rejected: forces FetchUnit to own PC state,
  duplicating the NextPcGen priority mux and creating a second epoch-stamp owner
  (a double-fire seam).
- **Carry-free re-fetch of straddling instructions.** Rejected: doubles fetch
  bandwidth and re-issues a memory request on the redirect path.
- **Two-beat window buffer.** Rejected: a memDataWidth register is a pipeline
  register (retiming) forbidden in a node; the 16-bit carry is the minimal
  functional state.
- **Flush-on-redirect (clear the queue).** Rejected: violates control-as-data;
  epoch drain achieves the same with a comparator.
- **Validity check inside the queue.** Rejected: FrontendTop mandates validity
  upstream; duplicating it is a second source of truth.

## Consequences

- All five frontend contracts fold to ~zero incremental cost at N=1 (single-issue,
  32-bit bus, depth-2..4 skid queue, one epoch latch) and scale on one RTL by
  widening `memDataWidth`/`fetchWidth`/`iqDepth` without new kinds of structure.
- G4 + commit-head redirect makes function-return penalty ~ window depth; the
  BTB/RAS contract's ports should be defined now (as a degenerate at N=1) even if
  storage is deferred, so it is not a later graph change.

## Verification obligations

- Elaboration graph-consistency: FrontendTop instantiated edge set == union of
  child `.has(intf*)` sets (ADR-015 machine check 1, from the `.draw` mermaid).
- SlotSlicer: INV-S1 conservation over random byte streams; straddle-every-beat
  equivalence; INV-S3 redirect-mid-straddle drops the carry; assert INV-S2.
- Guardrails: boot pulse -> first NextPc = boot addr, epoch 0; G2/G3 race (epoch-e
  response + epoch-e prediction after redirect to e+1 reach nothing); G5 wrap test
  (epochWidth=2, 4 redirects, fetch killed at redirect 1).
- IssueQueue: INV-Q1 invalid slots never dequeued; INV-Q2 epoch-e entries dropped
  after redirect; INV-Q3 monotone dequeue PC; liveness rank function on occupancy.
- fetchWidth: instantiate {32/1, 64/4, 128/8}, assert `fetchWidth <= slotsPerBeat`
  and INV-S1 at each; equivalence test (same stream at 32-bit and 128-bit produces
  the identical issued slot sequence).
