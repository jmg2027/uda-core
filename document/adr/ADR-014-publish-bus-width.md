# ADR-014: Publish Bus Width and Peak-IPC Contract

Status: **accepted** (single-lane base, peak IPC = 1 at all N) with a **proposed**
multi-lane extension.

Depends on: ADR-002 (retire width), ADR-007 (loop budgets), ADR-012 (tag).
Settles P01 O4/O2 and critique MA-1.

## Context

The PPA critique (MA-1) established that the tree already commits to ONE arbitrated
result bus (`PublishMuxSpecs.scala:11-13`, 7 FUs + memory), not one write port per
producer. Two consequences the papers left unpriced: (1) P05 D4's "PublishMux ->
PRF write is always-ready" precondition ("one write port per producer") is FALSE -
producers structurally contend, the edge CAN back-pressure; (2) a single publish
bus caps PEAK throughput at 1 result/cycle at EVERY N, so N=32 buys latency hiding
but not IPC>1, while area grows with N. No paper stated `peakIPC(N)`.

## Decision

**D-14.1 (accepted, single-lane base).** The base machine has ONE arbitrated
publish/result bus, ONE PRF write port fed by it, and `peakIssue = peakPublish =
peakRetire = 1` at EVERY N. This is a normative statement, not an omission. The
value of N=32 is **MLP / latency hiding** (independent work issues to fill FU and
memory latency behind the 1/cycle drain), NOT instruction-level parallelism above
1 IPC. The N-sweep report (ADR-008) MUST state this so no reader believes wide OoO
delivers ILP a 1-wide publish bus cannot drain.

**D-14.2 (accepted, correct the always-ready audit).** P05 D4's "PublishMux -> PRF
write: always-ready" row is STRUCK: the publish->PRF-write edge is a real
arbitrated port with real back-pressure; its ready tree is NOT elaborated away.
The always-ready audit retains only the edges whose no-back-pressure invariant
genuinely holds (PRF READ port - stateless combinational read; epoch/interrupt/
debugReq - constitution-exempt). RS enqueue and dispatch->FU stay handshaked.

**D-14.3 (accepted, PublishMux arbitrates).** PublishMux arbitrates FU results to
the single write bus, writes the PRF, and broadcasts one wakeup lane. `edgePublishToWakeup`
carries 1 stage at N=32 (ADR-007), which means back-to-back dependent issue at
N=32 is bounded by the window's ability to hide that bubble - consistent with the
MLP-only framing (D-14.1), not a contradiction with `edgeWakeupSelect`=0.

**D-14.4 (proposed, multi-lane).** A multi-lane publish/commit/retire machine
(peak IPC > 1) is a proposed extension gated on a measured need. If adopted:
PRF write ports = publish lanes; wakeup CAM width = entries x lanes; retire width
becomes f(N) (P01 O2) and its extra PRF read ports enter the area-vs-N sweep
(ADR-008); the always-ready claim for publish->write stays struck (more ports, not
zero back-pressure). Until then, single-lane is the contract and the N=32
structures (32-entry CAM, 65-entry PRF, 32-wide select) are justified purely as a
deep window feeding a 1-wide drain - the sweep (ADR-008) checks whether a 32-deep
window is even reachable behind a 1/cycle publish, which is itself a useful result.

## Alternatives rejected

- **Silent multi-lane assumption (readers infer IPC>1 from "wide OoO").**
  Rejected: unpriced and false for the single-bus tree; MA-1's core finding.
- **Adopt multi-lane now.** Rejected for the base: it multiplies PRF ports and CAM
  width - the largest area risks (P05 s0.1: the RF is already ~10% of the core) -
  for an ILP target not yet justified by a workload. Deferred behind D-14.4.

## Consequences

- N=1: single bus, single write port, single retire - identical to in-order.
- N=32: single-lane drain; the window is an MLP device. This bounds the useful
  window depth (a 32-deep window behind a 1/cycle publish may not fill on real
  code), which the sweep quantifies and which feeds the ADR-007 D-7.4 max-window
  decision.

## Verification obligations

- PROPERTY `propSingleDrain` (design assert): at most one PRF write / one wakeup
  broadcast / one retire per cycle in the base build.
- Assert the publish->PRF-write edge's ready is a real signal (not tied high); a
  contending producer that is not granted stalls, not drops.
- N-sweep (ADR-008): report achieved IPC vs N; confirm it saturates near 1 (MLP
  ceiling), which is the evidence for or against opening D-14.4.
