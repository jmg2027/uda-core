# ADR-019: Conventional OoO Root Architecture

Status: **accepted** (owner directive, 2026-09-25).
Amended by: ADR-019A (v0 Erratum 01), ADR-019B (v0 Erratum 02), and ADR-019C (v0 Erratum 03),
applied on top of the 242feaf spec freeze.

Supersedes or amends: ADR-001, ADR-002, ADR-003, ADR-005, ADR-006, ADR-007,
ADR-008, ADR-009, ADR-011, ADR-012, ADR-013, ADR-016. ADR-015/017/018 remain
binding; ADR-014 remains the v0 single-lane throughput point.

## Context

UDACore is being repurposed from the earlier N=1-to-N=32 epoch-centered
research architecture into a personal, conventional out-of-order RISC-V core.
The owner explicitly selected the following direction:

- explicit OoO scheduling and an explicit reorder buffer;
- execute-time selective recovery that preserves older correct-path work;
- a conventional PC-indexed frontend with BTB + TAGE + RAS, not instruction
  predecode-driven prediction;
- fixed-width 32-bit instructions; the C extension and RVC expansion are out of
  scope;
- real L1 instruction/data caches, MMU, ITLB/DTLB, and a shared page-table
  walker from the first architectural specification;
- U/S privilege and Sv32 for the RV32 v0 point.

The existing spec-first framework, ready/valid edge discipline, rawTop
wiring-only rule, TileLink system boundary, leaf execution units, and
verification contracts remain valuable and are retained.

## Decision

### D-19.1 - v0 architectural point

The first complete configuration SHALL be RV32IM with U-mode and S-mode, Sv32,
and fixed 32-bit instruction alignment. RVC is not decoded, fetched, expanded,
or represented in frontend slot metadata.

The initial performance point is:

- fetch block: 16 bytes / 4 instructions;
- decode width: 2;
- rename width: 1;
- issue: out of order, at least one result-producing uop per cycle;
- commit width: 1;
- ROB: 16 entries;
- integer PRF: 48 entries;
- integer reservation station: 8 entries;
- load queue: 8 entries;
- store queue: 8 entries.

Widths and depths are tuning parameters. These values define the v0 reference
configuration, not permanent maxima.

### D-19.2 - conventional speculative frontend

The frontend SHALL predict from the fetch PC before instruction bytes are
available. The predictor consists of:

- a BTB for control-flow location/type and target;
- TAGE for conditional-branch direction;
- a RAS for return targets;
- an FTQ holding prediction metadata and recovery checkpoints;
- a fetch buffer decoupling I-side latency from decode.

The old BranchPredecoder/first-taken feedback loop is not part of the new
architecture. Instruction bytes are not required to generate the normal
prediction for the same fetch block.

The v0 predictor MAY predict only one taken control-flow instruction per fetch
block. Multi-branch-per-block prediction and an indirect-target predictor such
as ITTAGE are later performance extensions and MUST NOT be silently assumed by
v0 specs.

### D-19.3 - no RVC frontend

All architectural instructions are 32 bits and instruction PCs are 4-byte
aligned. SlotSlicer half-word carry state, RvcExpander, 16-bit slot accounting,
and compressed-instruction target handling are removed from the architectural
frontend contract.

A fetch packet is a vector of aligned 32-bit instructions plus per-instruction
PC and exception metadata.

### D-19.4 - I-side translation and cache

The v0 instruction path SHALL contain an ITLB and a VIPT L1 I-cache looked up
in parallel from the virtual fetch address.

Reference geometry:

- 16 KiB;
- 4 ways;
- 64-byte cache line;
- 64 sets;
- 16-entry ITLB;
- one I-cache miss context in v0.

With a 4 KiB Sv32 page, sets * lineBytes MUST NOT exceed the page size for the
VIPT reference configuration.

Instruction translation/page faults are converted into frontend exception
tokens and enter program order; they do not directly create an architectural
trap at the fetch response boundary.

### D-19.5 - D-side translation, cache, and LSQ

Loads/stores SHALL use an AGU, DTLB, load/store queue, and VIPT L1 D-cache.

Reference geometry:

- 16 KiB;
- 4 ways;
- 64-byte cache line;
- 64 sets;
- 16-entry DTLB;
- two D-cache MSHRs;
- hit-under-miss support;
- at least four simultaneously tracked load transactions in the core.

A DTLB miss blocks only the affected memory uop when resources permit. The LSQ
records translationPending and wakes the uop when the PTW returns.

Memory-dependence correctness is based on resolved physical addresses. A
virtual-address comparison MAY be added only as an early optimization and MUST
be confirmed before it can create architectural forwarding/order effects.

The base disambiguation policy remains conservative: a load MUST NOT pass an
older store whose effective address is unresolved. More aggressive speculative
disambiguation requires a later ADR.

### D-19.6 - shared Sv32 MMU

ITLB and DTLB misses feed a shared page-table walker. The PTW performs physical
memory accesses and MUST NOT recursively pass through the DTLB. PTW reads may
use the D-cache.

The v0 MMU contract includes at least:

- satp and Sv32;
- U/S permission checks;
- R/W/X/U/G/A/D PTE semantics;
- SUM, MXR, and MPRV interactions needed by RV32 privileged execution;
- ASID fields in the TLB contract;
- Sv32 superpages;
- instruction/load/store page-fault distinction;
- SFENCE.VMA.

The first SFENCE.VMA implementation MAY invalidate all ITLB/DTLB entries for
every encoding. Selective address/ASID invalidation is a later optimization.

### D-19.7 - explicit data-less ROB

ADR-002's "without a ROB" decision is superseded.

The backend SHALL have an explicit ROB. Result data remains in the unified PRF;
the ROB stores ordering and precise-state metadata rather than duplicating
register values. A ROB entry includes, as applicable:

- validity/completion;
- PC and instruction/uop identity;
- architectural destination;
- new and old physical destination;
- exception/cause/tval;
- branch outcome/recovery metadata;
- memory ordering/store metadata;
- FTQ reference.

Retirement is in program order from the ROB head.

### D-19.8 - rename and precise state

The integer rename architecture SHALL use:

- speculative RAT (sRAT);
- retirement RAT (rRAT);
- physical-register free list;
- branch recovery checkpoints;
- unified PRF.

Normal commit updates rRAT and releases the old physical register. A precise
trap or architectural full recovery can restore sRAT from rRAT.

Branch recovery restores speculative rename/free-list state from the matching
branch checkpoint rather than overwriting all speculative state from the
retirement map.

### D-19.9 - execute-time selective branch recovery

ADR-011 commit-head redirect is superseded for branch misprediction.

A mispredicted branch MAY redirect as soon as the branch resolves. Recovery
MUST preserve every older in-flight uop and invalidate only younger speculative
work. The ordering test is a wrap-aware ROB-age/branch-tag relation, never
global epoch equality.

A non-backpressurable RecoveryEvent broadcast is a sanctioned rawNoDecoupled
FACT. It carries enough identity to let every speculative structure make the
same younger-than decision, including at minimum:

- recovering ROB position or canonical sequence tag;
- recovery/checkpoint id;
- redirect target;
- FTQ reference;
- recovery cause.

ROB, RS, LSQ, frontend fetch buffer/FTQ, and other speculative holders SHALL
declare how they discard younger entries on RecoveryEvent.

If an execute-time branch recovery and an older architectural redirect from the
commit head are presented in the same cycle, the commit-head architectural
redirect wins. The branch event belongs to younger speculative work and is
discarded with it. V0 has at most one branch-recovery producer selected per
cycle.

### D-19.10 - role of epoch after this ADR

The single global epoch is no longer the correctness mechanism for branch
squash and MUST NOT kill older work.

An epoch/generation field MAY remain as a stale-response generation tag for
transactions that cannot be canceled after issue (for example an I-cache fill,
D-cache fill, or PTW transaction). Such use is local transaction bookkeeping,
not program-order recovery.

Specs written for ADR-005/006 that require every speculative pipeline holder to
self-invalidate solely from global-epoch mismatch are superseded where they
conflict with this decision.

### D-19.11 - predictor recovery and training

Speculative global history and RAS state SHALL be checkpointable through FTQ
metadata. On branch misprediction, the frontend restores the state associated
with the recovering branch and applies the resolved branch outcome before
resuming prediction.

Predictor training MUST NOT alter architectural correctness. The v0 spec SHALL
choose and document one deterministic training point (resolution of a still-live
branch or retirement) and use it consistently. Predictor state is
microarchitectural and need not be rolled back merely because a wrong-path
cache fill or predictor lookup occurred.

### D-19.12 - cache speculation stance

Speculative stores MUST NOT become externally visible or update committed cache
state before the store reaches the architectural commit point. The SQ is the
speculative owner; an optional committed store-drain buffer may sit below it.

A wrong-path load is allowed to allocate/fill cache state. Cache lines,
replacement state, and TLB refill state are microarchitectural performance
state and are not rolled back on branch recovery. The wrong-path load's ROB/LSQ
result is discarded.

### D-19.13 - TileLink and coherence scope

The two TileLink master boundaries from ADR-016 are retained.

Cache presence no longer implies coherence. The v0 D-cache MAY use a
non-coherent TL-UH data link. Coherence is a separate elaboration-time
capability; enabling TL-C later MUST NOT require changing the backend/LSQ/MMU
architectural interfaces.

This amends ADR-016 D-16.4, where dcache presence directly implied TL-C.

### D-19.14 - UDA/spec-framework rules retained

ADR-015, ADR-017, and ADR-018 remain binding:

- specs precede design;
- one CONTRACT per spec file;
- rawTop modules are wiring only;
- vertex transfers are ready/valid by default;
- FUNCTION/PROPERTY objects have named test obligations;
- PROPERTY objects pair with real design assertions or a temporary allowlist.

The rawNoDecoupled doctrine is amended to add one class: speculative
RecoveryEvent broadcast. It is a broadcast FACT, not a queued transfer and not
a backpressure path.

No other ad-hoc flush/kill/stall side-channel is permitted. Selective squash
must be derived locally from the common RecoveryEvent identity.

## Supersession matrix

| Existing decision | ADR-019 ruling |
|---|---|
| ADR-001 bulk branch recovery from architectural map | superseded for branch recovery by branch checkpoints; rRAT remains the architectural full-recovery source |
| ADR-002 no ROB | superseded by explicit data-less ROB |
| ADR-003 store lifecycle | amended: speculative ordering lives in LSQ/SQ; a committed store-drain buffer may remain below commit |
| ADR-005 global epoch as speculative kill | superseded for program-order speculation; epoch may remain transaction-generation metadata |
| ADR-006 epoch/redirect distribution | amended; execute-time RecoveryEvent is the branch-recovery path |
| ADR-007 N-based critical-edge budgets | recurrence-registry idea retained, but budgets must be re-derived for the ADR-019 pipeline |
| ADR-008 N=1 degeneracy/PPA bar | superseded; v0 is an OoO reference core, not an in-order-to-OoO single-mechanism sweep |
| ADR-009 RVC/predecode frontend | superseded by BTB+TAGE+RAS+FTQ fixed-width frontend |
| ADR-011 commit-head branch redirect | superseded by execute-time selective branch recovery |
| ADR-012 canonical tag | concept retained; width/lifetime must be re-derived from ROB + LSQ + committed-store horizons |
| ADR-013 old redirect merge | amended: older commit-head architectural redirect wins over same-cycle execute branch recovery |
| ADR-014 single result lane | retained for v0 unless a later width ADR changes it |
| ADR-016 PIPT base and dcache=>TL-C | amended to VIPT L1 reference point and independent coherence enable |
| ADR-015/017/018 spec enforcement | retained unchanged |

## Consequences

The current frontend/backend shell specs are no longer an implementation plan;
they are migration inputs. New specs MUST be authored against this ADR before
new RTL is written.

The project no longer claims that one global epoch scales the same mechanism
from in-order to wide OoO. The new research question is a conventional,
understandable OoO core with explicit recovery/order structures while retaining
the repository's spec-first and dataflow engineering discipline.

## Verification obligations

- Deterministic retire-stream comparison against an ISA model for RV32IM U/S
  programs, including Sv32 faults.
- Directed recovery case: older long-latency DIV, younger mispredicted branch;
  DIV MUST survive and retire.
- Directed rename checkpoint case: multiple writes to one architectural
  register around a mispredicted branch; sRAT/free-list state MUST recover with
  no leak/double-free.
- Precise page-fault cases for instruction, load, and store accesses.
- TLB-miss case where independent OoO work continues while one DTLB miss waits
  for the PTW.
- D-cache hit-under-miss with two independent misses and reordered completion.
- Wrong-path load may fill cache but MUST leave no architectural result.
- Store on a wrong path MUST cause no committed memory update.
- BTB/TAGE/RAS recovery tests MUST show prediction history restored from the
  recovering FTQ/checkpoint state.
