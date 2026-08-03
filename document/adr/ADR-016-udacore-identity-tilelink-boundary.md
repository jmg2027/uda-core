# ADR-016: UDACore Identity, XLEN-Parametric Datapath, and TileLink Boundary

Status: **accepted** (owner decision, 2026-07-06).

Depends on: ADR-003 (store lifecycle - D-3.14 amended here), ADR-004 (CSR/trap
ownership - gains the mode ladder), ADR-010 (retire stream - unchanged),
ADR-015 (enforcement - the new capabilities inherit its checker discipline).

## Context

The rebuild line is a personal research vehicle, now fully decoupled from the
company core. Three inherited assumptions no longer serve it: the name
(klase32), the fixed 32-bit datapath baked into names and requires, and the
bespoke external memory request/response pairs. The owner directed (this
session): rename the core, drop the 32-bit fixation, move the boundary to the
standard full TileLink protocol, and shape the parameter/spec structure so
caches, TLBs, and the U/S/H privilege modes can be added without rework.

## Decision

**D-16.1 (identity).** The project is **UDACore** (package root `udacore`,
display UDACore, elaboration entry `UDACoreElab`). References to the company
line keep its original name (KLASE32); the GitHub repository name is a hosting
detail and does not track the project name.

**D-16.2 (XLEN-parametric).** `CoreParams.dataWidth` is XLEN in {32, 64}. No
width may hard-code 32; the memory subsystem's `memOpWidth == 32` require
(ADR-003 D-3.14) is amended to `memOpWidth == xLen`. Honest debt: the decode
tables and the verif assembler cover RV32 today - RV64 decode is tracked work,
and `rawParametricISA` says so rather than claiming RV64 silently.

**D-16.3 (TileLink boundary).** CoreTop's external memory boundary is two
standard TileLink links (SiFive spec 1.8.x), master view: `instBus` and
`dataBus`. The full five-channel protocol is specified
(`common/tilelink/TileLinkSpecs.scala`) with bundle shapes in
`common/tilelink/TileLink.scala`; a link elaborates B/C/E only when coherent
(`hasBCE`). Internal vertices keep the core's own edge bundles; two adapter
vertices owned by CoreTop (`InstBusAdapter`, `DataBusAdapter`) translate at the
boundary and own source-id allocation, size/mask formation, and channel-D
denied/corrupt to access-fault conversion. Link geometry derives in one place:
`CoreParams.instBusParams` / `dataBusParams`.

**D-16.4 (accommodation seams).** Caches, TLBs, and privilege modes are
elaboration-time options that splice into existing edges, never boundary
changes:

- `PrivilegeParams(usingUser, usingSupervisor, usingHypervisor)` - M-only
  default; S requires U, H requires S. Mode-dependent behavior is confined to
  TrapController (ADR-004) and the TLB vertices.
- `icache/dcache: Option[CacheParams]` - None is the TCM point. A dcache flips
  the data link to TL-C and raises maxTransferBytes to blockBytes; an icache
  raises the instruction link to burst fills, never to coherence. The
  StoreBuffer drain contract (ADR-003) is unchanged; the cache sits below it.
- `itlb/dtlb: Option[TlbParams]` - require S-mode; translation faults are
  raised in-core and never appear on the TileLink boundary; the PTW walks
  through the DataBusAdapter.

**D-16.5 (adapter dataflow rules).** The bus adapters are ordinary vertices:
pure ready/valid dataflow, wired with `:<>=` in the rawTop. They are
epoch-blind - a request already committed to the bus must complete and be
reunited (by txnId/seqTag) or drained; epoch filtering happens in the vertices
above them. TileLink channel priority (E > D > C > B > A) is a PROPERTY
(`propTLChannelPriority`) whose paired assert lands with the first adapter RTL
(ADR-015 D-15.2 pairing rule).

## Consequences

- Every spec/design/doc self-reference renamed; ADR-001..015 continue to apply
  to UDACore unchanged except D-3.14's width fixation.
- The verif harness DUT-binding contract now targets the TileLink links: the
  memory model serves A-channel requests and answers on D (B/C/E once a
  coherent config exists). Harness status reporting is unchanged.
- The N-sweep/PPA instruments gain two boundary-owned config axes (cache
  on/off, coherence on/off) that must NOT be conflated with the N axis in
  named configs (ADR-015 D-15.5 one-axis-per-name).
- New machine-check obligations when RTL lands: adapter channel-priority
  assert, TL-C permission-transition legality, and the D-16.2 no-hardcoded-32
  grep join the ADR-015 checker list.
