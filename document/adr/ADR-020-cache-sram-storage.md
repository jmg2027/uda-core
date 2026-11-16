# ADR-020: Clocked SRAM storage for L1 cache data

Status: accepted implementation decision (owner request, 2026-11-16).
Amends the storage implementation of ADR-019 D-19.4/D-19.5 without changing
the ISA, cache capacity, coherence, or architectural memory-order contract.

## Context

The first whole-core synthesis expanded both L1 data memories into flops and
muxes. An I-cache RAM read-address bit drove 68353 loads. The owner requested
SRAM replacement. A clocked SRAM requires explicit read timing and port
arbitration; an asynchronous Mem declaration alone does not provide a macro.

## Decision

### D-20.1 - data organization and physical view

Both L1 data arrays SHALL use CacheLineSram, a ready/valid storage vertex.
Each logical row is one cache line, addressed by way * sets + set. Each
XLEN-sized word is a bank. The RV32 reference geometry has 256 rows and
16 banks per cache, preserving 16 KiB per cache.

The physical reference bank SHALL instantiate the pinned
sky130_sram_1kbyte_1rw1r_32x256_8 macro. Geometries with more rows SHALL use
additional 256-row pages; smaller geometries SHALL use a subset of rows.
Tags, valid/dirty bits, replacement state, MSHRs, and bounded transaction
buffers MAY remain registers. No bulk line array may be substituted with
flops in the SRAM synthesis target.

### D-20.2 - read/write timing and arbitration

Read requests and full-line byte-masked writes SHALL be ready/valid tokens.
A read captures its row at the rising edge and the macro supplies data after
the falling edge; the response is consumed at a following rising edge.
One response may be outstanding. A blocked response SHALL be preserved in
an explicit line holder because the macro model invalidates output data on
subsequent rising edges. The next read may transfer when the prior response
transfers. Reset clears protocol state and metadata, not SRAM contents.

The independent write port updates selected bytes after its accepting edge.
A simultaneous read and write to different rows is legal. A write to the
same row SHALL win arbitration and backpressure the read so no undefined
read-during-write value is observed. Cache controllers SHALL preserve the
owner/identity of SRAM reads until their responses transfer, including load,
PTE, victim capture, and clean-all readers.

StoreDrainResp remains the ADR-019F committed-store visibility point.
Hit-under-miss, dirty eviction, maintenance, generation identity, and
backpressured response behavior SHALL remain architecturally equivalent.

### D-20.3 - physical pins and simulation view

The SRAM is a leaf storage subcore inside CacheLineSram. Its technology clock,
chip-select, address and data pins are not inter-vertex UDA edges. All
cache-to-storage transfers remain ready/valid; no rawNoDecoupled exception
or recovery side channel is added.

A functional simulation view SHALL reproduce edge ordering, write masks,
and invalid-output behavior. Arbitrary vendor model delays are not a timing
signoff model. Synthesis SHALL preserve the macro as a library cell and STA
SHALL read its Liberty view, rather than removing its timing paths or mapping
its behavioral memory into gates. Source commit and view hashes are pinned.

## Consequences

Cache hits, PTE hits, victim snapshots and clean writebacks gain explicit SRAM
read states. Existing ready/valid interfaces absorb the timing change.
The v0 product contains 32 physical SRAM instances for 32 KiB total data.
Macro area and standard-cell area must be reported separately. The available
OpenRAM Liberty view uses analytical/Elmore characterization without PEX;
results remain pre-layout estimates and do not settle owner question OQ-C.
