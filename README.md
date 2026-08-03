<!-- markdownlint-disable MD013 MD022 MD032 MD033 MD012 MD058 MD031 MD007 MD040 -->
# UDACore - Parametric RISC-V Core (Unified Dataflow Architecture)

UDACore is an XLEN-parametric RISC-V core (RV32/RV64, IMC extensions) written in [Chisel 6.7.0](https://www.chisel-lang.org/) that demonstrates **Unified Dataflow Architecture (UDA)**. Its external memory boundary is standard full TileLink, with elaboration-time accommodation for caches, TLBs, and the U/S/H privilege ladder (ADR-016).

## Quick Start

```bash
git clone https://github.com/jmg2027/klase32.git
cd klase32
sbt compile
```

**Prerequisites**: JDK 17+, sbt 1.9+, Verilator 5.x for simulation

## Core Principles

1. **Unified Dataflow Architecture**: All components communicate through ready/valid interfaces
2. **Epoch-based control**: No flush signals - epoch comparison filters stale tokens
3. **Spec-first development**: Specifications define contracts before implementation
4. **Parametric scaling**: Single codebase from embedded to high-performance via config

## Repository Structure

- `src/` - Domain roots (frontend, backend, memorysubsystem, core)
  - `design/` - Chisel implementations
  - `spec/` - Specifications (single source of truth)
  - `shared/` - Domain-internal assets
  - `api/` - Exports to parent domains
- `docs/` - Design methodology and guidelines
- `test/` - Test infrastructure and cluster tests

## Key Concepts

- **Vertex**: Graph node with CONTRACT spec, where ready/valid edges terminate
- **Edge**: Connection between vertices using DecoupledIO or documented scalar
- **Raw top**: Vertex that wires children without behavioral logic
- **Epoch**: Global ID for program flow branch - replaces flush signals

## Build & Test

### Elaborate RTL
```bash
sbt runMain UDACoreElab
```
Output: `./UDACoreElab` directory

### Unit Tests
```bash
sbt scalafmtCheckAll test
```

### Cluster Tests
```bash
sbt testOnly udacore.cluster.SingleCoreMulDivClusterTest
sbt 'testOnly *SingleCoreMulDivClusterTest* -- -z "Store test 0"'
```
Waveforms: `test_run_dir/*` (import `signals.gtkw` in GTKWave)

## Documentation

Read in order:
1. **[ONBOARDING.md](ONBOARDING.md)** - Quick start for Chisel developers
2. **[docs/foundations/](docs/foundations/)** - Core design principles
   - `uda-methodology.md` - UDA workflow
   - `design-constitution.md` - Vertex and edge rules
   - `dataflow-execution-model.md` - Epoch and register renaming
3. **[docs/process/](docs/process/)** - Development workflow
   - `spec-usage-guideline.md` - Spec DSL and workflow
4. **[docs/practices/](docs/practices/)** - Implementation patterns
   - `decoupled-io-guide.md` - Ready/valid protocol
   - `implementation-techniques.md` - Day-to-day RTL work
5. **[AGENTS.md](AGENTS.md)** - Mandatory rules and critical guidelines

## Architecture Highlights

### 2-stage pipeline
Frontend (PC gen, fetch) -> Backend (decode, execute, commit)

### Epoch-based speculation
- Global epoch counter tracks program flow
- Redirect increments epoch, stale tokens filtered by comparison
- No flush signals - simpler control logic

### Unified PRF (Physical Register File)
- Map table: architectural -> physical register mapping
- On commit: update map table only, no data writeback
- Scales from N=1 (in-order) to N=32+ (wide OoO)

### Spec-first workflow
- Write specs before design
- Use `@LocalSpec` annotations
- Specs are single source of truth

## Development Workflow

1. Update spec in `spec/` before touching `design/`
2. Tag implementations with `@LocalSpec(specObject)`
3. Raw top modules use `:<>=` operator for Decoupled connections
4. Run `sbt scalafmtCheckAll test` before committing

See [docs/process/spec-usage-guideline.md](docs/process/spec-usage-guideline.md) for detailed workflow.

## Credits

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.
