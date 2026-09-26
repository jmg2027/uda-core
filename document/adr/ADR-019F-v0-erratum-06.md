# ADR-019F: ADR-019 v0 Erratum 06 - Committed-store visibility and backend memory seam

Status: **accepted** (owner ruling, 2026-09-26).

Amends: ADR-019 (the LSQ / StoreBuffer / DataCache memory seam), ADR-019E E-4 (Debug Mode
decode of ECALL/MRET/SRET).
Baseline: the `242feaf` freeze plus ADR-019A..E, all immutable history. This erratum is
applied on top of them; every DSL change it causes cites `ADR-019F`.

## Context

The backend RTL (through ac48d53) relies on two timing facts that were recorded only in
HANDOFF: the StoreBuffer answers a forwarding query in the query cycle, and a D-cache load
answer reflects every store drain completed before it. Together they close the window in which
a committed store moves SQ -> StoreBuffer -> D-cache while a younger load is in flight. They
are protocol choices of this implementation, not ISA requirements, and are made contractual
here. Separately, ADR-019E let Debug Mode decode as M for ECALL, which the RISC-V debug
specification does not require.

## Decision

### E-1 - StoreBuffer forwarding is a same-cycle lookup (v0 timing choice)

When the LSQ presents StoreForwardQuery{paddr, mask}, the StoreBuffer presents the matching
StoreForwardData in the same cycle. The LSQ decides and accepts a DCacheLoadResp only in a
cycle in which the query is accepted and the matching StoreForwardData is valid; a query is
consumed exactly once, in that cycle. This closes the SQ -> StoreBuffer -> D-cache migration
hole without a store generation/version protocol. It is a timing/protocol choice, not an ISA
requirement. If the path later fails PPA, a register must not simply be inserted: a pipelined
forwarding design needs an explicit store-visibility/version scheme (or an equivalent
mechanism) and a later ADR. New LSQ PROPERTY `propForwardQueryConsumedOnce`.

### E-2 - StoreDrainResp is the committed-store visibility linearization point

A DataCache StoreDrainResp for committed store S fires only after S's bytes are visible to
every later D-cache load answer: if StoreDrainResp(S) fired before load answer L is produced,
S overlaps L, and no later committed store overwrote those bytes, L's cache data includes S.
This holds whether S hit, needed write allocation, waited behind a miss, or met an existing
MSHR. The StoreBuffer may drop S right after StoreDrainResp: the D-cache owns visibility from
then on. New DataCache PROPERTY `propDrainResponseMakesStoreVisible`, verified by a directed
race (load miss outstanding; an older committed store drains to the same line; its
StoreDrainResp fires; the fill/answer returns later and observes the store), including
partial-byte masks.

### E-3 - same-cycle drain response and load answer

No stronger ordering is required when StoreDrainResp(S) and an overlapping load answer occur
in the same cycle: either the answer already contains S, or S is still in the StoreBuffer and
takes part in that cycle's forwarding lookup. After the cycle boundary at which
StoreDrainResp fires, the D-cache alone owns S's visibility.

### E-4 - WFI legality (ruling on the current rule)

M-mode WFI is legal; S-mode WFI is legal with TW = 0 and illegal immediately with TW = 1 (the
v0 bounded wait time is 0); U-mode WFI is illegal immediately in this v0 implementation. The
existing DecodeUnit rule already implements this.

### E-5 - Debug Mode ECALL / MRET / SRET

RISC-V does not require Debug Mode ECALL to behave as an M-mode ECALL. For v0: ordinary
instruction privilege checks in Debug Mode may use M-level access permissions where already
implemented (CSR access); DRET keeps its ADR-019E behavior; ECALL, MRET, and SRET executed in
Debug Mode are unsupported in v0 and decode to illegal instruction (cause 2, tval = insn).
Debug software must not rely on them. This is not a Program Buffer implementation.

## Consequences

- LSQ gains `propForwardQueryConsumedOnce`; StoreBuffer and LSQ texts state the same-cycle
  contract; DataCache gains `propDrainResponseMakesStoreVisible` and the E-3 note.
- funcSystemPrivLegality: ECALL/MRET/SRET are illegal in Debug Mode (supersedes the ADR-019E
  "Debug Mode decodes as M" for these three).
