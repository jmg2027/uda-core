# UDACore verification harness

The scenario, gate, trace and sky130 synthesis/STA tools originated in the historical
KLASE32 harness. They now target this independent UDACore repository. The DUT is CoreTop. CoreHarness drives its
two TileLink masters and captures architectural retirement through the verification stream.

## Status

Works today:
- `verif/bin/build.sh` - compiles core + framework + suites with the pinned scalac/chisel
  toolchain (no sbt needed; `-Ymacro-annotations` for the spec macros).
- `verif/bin/scn.sh describe` - the JSON capability card, including this status.
- `.scn` parsing, assembly, checks scoring, JSON output, the gate logic, the daemon protocol.
- `scn.sh run|batch|gate|serve` against the real core. Verification configurations map the
  result slots and completion address as uncacheable platform windows so externally visible
  TileLink stores can be scored without observing private cache signals.
- `scn.sh trace` - per-cycle fetch lanes, retire tokens, RecoveryEvent, divider busy and
  ALU publication from a simulation-only wrapper using the same memory/reset driver.
- `verif/bin/run.sh verif.suites.EmitUnit <cfg> <dir>` - emit an implemented functional unit
  (Alu / Multiplier / Divider / BitAlu; `EmitUnit list` for the catalog).
- `verif/bin/sta.sh unit <cfg>` - full constrained gate-level STA of a unit (yosys map with
  fanout buffering + OpenSTA + sky130 HD; `setup-sta.sh` once per container).
- `verif/bin/ppa-unit.sh [cfgs...]` - area / DFF / wns / power table across unit configs.

Whole-core timing:
- `sta.sh core default` emits the product CoreTop with the verification retire stream off,
  converts SystemVerilog arrays with `sv2v`, preserves 32 physical cache SRAM instances,
  maps the remaining logic to sky130 HD cells, and runs OpenSTA with both libraries.
  Artifacts are stored in `verif/out/sta/core_default_sram/`; the older flop-only result
  remains in `verif/out/sta/core_default/`.
- `setup-sram.sh` provisions checksum-pinned macro Verilog, Liberty, LEF, GDS, SPICE,
  generation log, and license views. Macro and standard-cell areas are reported separately.
- The macro Liberty uses analytical Elmore characterization without PEX. The model uses
  statistical wire load and ideal clock/reset, with no placement, extracted parasitics or
  clock tree. The modeled clock is a measurement condition; OQ-C remains owner-defined.

## Quick start

```bash
bash verif/bin/setup.sh        # Linux toolchain
bash verif/bin/setup-macos.sh  # Apple Silicon toolchain (use instead of setup.sh)
bash verif/bin/build.sh        # compile everything -> verif/out/classes
python3 verif/bin/test-cli.py  # JSON, diagnostics and process exit-code regression
verif/bin/scn.sh describe      # capability card
verif/bin/scn.sh run verif/scn/add.scn --json
verif/bin/scn.sh trace verif/scn/add.scn --cycles=140 --json

bash verif/bin/setup-sta.sh    # once per container, only for PPA/STA (heavy source build)
verif/bin/sta.sh unit mul_csa16
verif/bin/ppa-unit.sh mul_csa16 mul_csa32 div_nrclz div_restoring
bash verif/bin/setup-sram.sh   # pinned SRAM views (once; verify-only is used by sta.sh)
verif/bin/sta.sh core default
```

## Integration details

- Config: plain `CoreParams` values (`verif.config.CoreConfigs`: default, minimal;
  `--config=<name>`), not a rocket-chip Parameters map; no shim or idecode overlay is needed.
  Verification configs opt the ADR-010 retire stream on (`usingRvvi = true`) and reserve
  cache-line-aligned uncacheable result and completion windows in their PMA map.
- Cache data uses the actual OpenRAM leaf instance with a cycle-accurate functional
  simulation view. Requests capture on rising edges; reads and writes occur on falling
  edges. An explicit holder preserves read data through backpressure. The functional
  view omits the vendor model's arbitrary delay values and is excluded from synthesis.
  CachedSimulator hashes inline/resource models and path-based model file contents.
- Assembler: the owner-held `src/main/scala/assembler` exports
  `RISCVAssembler.fromString`. Its historical RVC support does not enable RVC in the
  ADR-019 core. Coverage gaps show up as all-zero words and
  are flagged by Diagnose ("assembler gap"); `verif.toolchain.AsmDiff` cross-checks any line
  against riscv64-unknown-elf-as.
- `sta.sh` takes a target kind (`core` / `unit`); the SDC (`verif/sta/core.sdc`) budgets the
  TileLink boundary via `STA_MEM_GLOB` (default `io_*Bus_*`). The default clock period is
  10 ns, with 60% per-side IO delay on TileLink and 30% on other synchronous data ports.
- Historical KLASE32 findings (F-1..F-5) are not UDACore findings.

## Reading order

[AI-HARNESS.md](AI-HARNESS.md) explains the `.scn` grammar, gates and JSON. The local
implementation entry points are [CoreHarness](src/scala/verif/harness/CoreHarness.scala),
[CachedSimulator](src/scala/chisel3/simulator/CachedSimulator.scala) and
[Serve](src/scala/verif/ai/Serve.scala). Current validation and unresolved work are in
[HANDOFF.md](../document/HANDOFF.md).

CLI JSON results are written to stdout. Elaboration and simulator diagnostics
are preserved on stderr; runner exit codes come directly from the Java process.
