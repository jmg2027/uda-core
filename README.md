<!-- markdownlint-disable MD013 MD022 MD032 MD033 MD012 MD058 MD031 MD007 MD040 -->
# UDACore - Conventional Out-of-Order RISC-V Core (Unified Dataflow Architecture)

UDACore is a personal, conventional out-of-order RISC-V core written in Chisel with the
**Unified Dataflow Architecture (UDA)** engineering discipline: every component is a vertex,
every token-moving connection is a ready/valid edge, and every contract is specified in the
Scala spec DSL before RTL is written. The root architecture is
[ADR-019](document/adr/ADR-019-conventional-ooo-root-architecture.md).

The v0 architectural point is RV32IM_Zicsr_Zifencei + Svade with M/S/U privilege and Sv32 virtual memory, fixed
32-bit instructions (no C extension), and two TileLink master links at the core boundary.

## Status

Specification phase. The ADR-019 architecture is written as spec DSL contracts with
red/PENDING verification bindings; the new vertices are documented design shells. The
implemented and tested pieces today are the external functional units (ALU, multiplier,
divider, bit-ALU), the CSR decorator library, the TileLink bundles, the BootSequencer, and
the verification/PPA instruments.

## v0 Reference Machine

| Area | v0 reference |
| --- | --- |
| Frontend | PC-indexed BTB + TAGE + RAS, FTQ, 16-byte / 4-instruction fetch block, fetch buffer |
| Decode / rename | decode width 2, rename width 1 |
| Backend | explicit data-less ROB (16), sRAT + rRAT + free list, 48-entry PRF, 8-entry RS, branch checkpoints |
| Execution | out-of-order issue and completion, in-order commit, one publish lane |
| Recovery | execute-time selective branch recovery through one RecoveryEvent broadcast; older work survives |
| Memory | LQ8 + SQ8, physical-address ordering, conservative unknown-store disambiguation, committed StoreBuffer |
| Caches | VIPT 16 KiB 4-way 64-byte-line I-cache and D-cache; D-cache 2 MSHRs, hit-under-miss |
| MMU | 16-entry ITLB and DTLB, shared Sv32 PTW, SFENCE.VMA full flush |
| Boundary | TileLink instBus (TL-UH) and dataBus (TL-UH; coherence is a separate option) |

## Core Principles

1. **Unified Dataflow Architecture**: all components communicate through ready/valid edges; a stall is backpressure.
2. **Selective recovery**: the RecoveryEvent names the recovery point by ROB tag; each speculative holder discards only younger work. There is no global-epoch squash and no flush wire.
3. **Spec-first development**: specs define contracts before implementation; every FUNCTION/PROPERTY binds to a test (ADR-018).
4. **Parametric widths and depths**: the v0 numbers are tuning parameters, not maxima.

## Repository Structure

- `src/main/scala/udacore/` - domains `core` (CoreTop, MMU, caches, bus adapters), `frontend`, `backend`, `external` (functional-unit IP), `common`
  - `spec/` - specifications (single source of truth)
  - `design/` - Chisel implementations and design shells
- `document/adr/` - binding architecture decisions (ADR-019 is the root)
- `verif/` - verification engine, `.scn` scenarios, L1 SpecTests, synthesis/STA flow
- `docs/` - methodology background (ADR-019 wins where they disagree)

## Build and Verify

```bash
bash verif/bin/setup.sh                        # toolchain (once per container)
bash verif/bin/build.sh                        # compile gate: 0 errors
python3 tools/spec-check.py                    # spec gate (ADR-015/018): 0 errors
verif/bin/run.sh verif.spectest.RunSpecTests   # L1 SpecTests
verif/bin/scn.sh run verif/scn/<name>.scn      # L2 scenario (harness-not-ready until CoreTop has RTL)
```

## Documentation

1. [ADR-019](document/adr/ADR-019-conventional-ooo-root-architecture.md) and the [ADR index](document/adr/ADR-000-index.md)
2. [Work Order 07](document/architecture-team/07-ooo-v0-spec-work-order.md) - the spec-authoring plan
3. [AGENTS.md](AGENTS.md) - spec DSL and mandatory rules
4. [document/HANDOFF.md](document/HANDOFF.md) - live state and next steps
5. [verif/README.md](verif/README.md) - the instruments
6. [docs/](docs/) - UDA methodology background
