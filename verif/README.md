# verif - the AI verification harness, ported from main

This is the main line's verification methodology (scn/gate/trace + the sky130 synthesis/STA
flow) ported onto the rebuild line as its research instruments (HANDOFF next-step 2). The
engine is the same; the DUT is CoreTop, which is still a spec shell - so the harness is split
into what works today and what activates with the RTL fill-in.

## Status

Works today:
- `verif/bin/build.sh` - compiles core + framework + suites with the pinned scalac/chisel
  toolchain (no sbt needed; `-Ymacro-annotations` for the spec macros).
- `verif/bin/scn.sh describe` - the JSON capability card, including this status.
- `.scn` parsing, assembly, checks scoring, JSON output, the gate logic, the daemon protocol.
- `verif/bin/run.sh verif.suites.EmitUnit <cfg> <dir>` - emit an implemented functional unit
  (Alu / Multiplier / Divider / BitAlu; `EmitUnit list` for the catalog).
- `verif/bin/sta.sh unit <cfg>` - full constrained gate-level STA of a unit (yosys map with
  fanout buffering + OpenSTA + sky130 HD; `setup-sta.sh` once per container).
- `verif/bin/ppa-unit.sh [cfgs...]` - area / DFF / wns / power table across unit configs.

Activates with the RTL fill-in (raises `harness-not-ready`, exit 3, until then):
- `scn.sh run|batch|gate|trace|serve` against the real core. The DUT binding lives in
  `verif/src/scala/verif/harness/CoreHarness.scala`; its doc comment carries the binding
  contract. Per HANDOFF, the committed-pc capture needs the ADR-010 retire stream
  (`bndRetireToken` / `intfRetireStreamOut`, `paramUsingRvvi`), and that design port must be
  built together with the CommitUnit RTL - not bolted on here.
- `Probe` taps (Trace's signal set): bind to rebuild vertex signals at the same time.
- `EmitCore` / `sta.sh core` - once CoreTop elaborates.
- `verif/suites/SmokeSuite.scala` - the first suite to run when the binding lands.

## Quick start

```bash
bash verif/bin/setup.sh        # once per container: scalac/chisel jars, firtool, verilator,
                               # espresso, ccache (idempotent, reuse-first)
bash verif/bin/build.sh        # compile everything -> verif/out/classes
verif/bin/scn.sh describe      # capability card

bash verif/bin/setup-sta.sh    # once per container, only for PPA/STA (heavy source build)
verif/bin/sta.sh unit mul_csa16
verif/bin/ppa-unit.sh mul_csa16 mul_csa32 div_nrclz div_restoring
```

## Differences from the main-line harness

- Config: plain `CoreParams` values (`verif.config.CoreConfigs`: default, minimal;
  `--config=<name>`), not a rocket-chip Parameters map; no shim or idecode overlay is needed.
  Verification configs opt the ADR-010 retire stream on (`usingRvvi = true`).
- Assembler: the rebuild tree keeps its own `src/main/scala/assembler`, which has RVC support
  the main line's lacks; the main line's has wider B/F/Zicsr coverage. The engine only needs
  `RISCVAssembler.fromString`, present in both. Coverage gaps show up as all-zero words and
  are flagged by Diagnose ("assembler gap"); `verif.toolchain.AsmDiff` cross-checks any line
  against riscv64-unknown-elf-as.
- `sta.sh` takes a target kind (`core` / `unit`); the SDC (`verif/sta/core.sdc`) budgets the
  external-memory boundary ports via `STA_MEM_GLOB` (default `io_external*`, CoreTop's
  externalProgramMemory* / externalDataMemory*) the way the main line budgeted its TileLink
  ports.
- Known main-line findings (F-1..F-5) do not apply; the card's findings list starts empty.

## Reading order

`AI-HARNESS.md` here explains the ai-facing layer (the `.scn` grammar, gates, JSON). The
engine internals are documented in the main line's `verif/ARCHITECTURE.md`; the ported code
keeps the same structure (CachedSimulator workspace cache, Suite/runBatch one-compile
batching, the file-spool daemon).
