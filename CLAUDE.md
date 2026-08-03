# CLAUDE.md

Guidance for Claude Code working in this repository. This file is the entry point; the
binding law lives in `document/adr/` and the spec tree, and the mechanical gates enforce
what prose cannot.

## Language and Style Policy

- **All conversation responses and commit messages: Korean.**
- **All files (specs, design comments, docs): English, ASCII only.** The pre-commit hook
  rejects non-ASCII in staged files (known pre-existing debt is grandfathered in
  `.githooks/ascii-allow.txt`; touching a listed file means cleaning it).
- No emoji anywhere. No decorative characters.
- No TODO comments: use documented placeholders (a `??? `/empty val whose doc comment
  states the contract) - the spec shell convention.
- Existing English documentation stays English.

## Project Identity

UDACore is a **personal research vehicle for parametric in-order-to-OoO scaling**: one RTL
codebase whose window parameter `SpeculativeRegNum` (N) scales from an in-order point (N=1,
where the OoO fabric must elaborate away - ADR-008) to wide OoO (N=32+), built on
**Unified Dataflow Architecture (UDA)**: every component is a vertex in a dataflow graph,
every connection a ready/valid edge, and epoch-based speculation replaces flush signals.

It is XLEN-parametric (RV32/RV64, IMC), speaks standard full TileLink at its memory
boundary, and carries elaboration-time accommodation seams for caches, TLBs, and the U/S/H
privilege ladder (ADR-016). It is independent of the company line (KLASE32); references to
"main" mean that lineage, from which the verif/PPA instruments were ported.

## Order of Authority (the law)

1. **ADRs** - `document/adr/ADR-000-index.md` first, then the referenced rulings. ADRs are
   binding contracts; "proposed" ADRs name the experiment that settles them.
2. **Specs** - `src/main/scala/udacore/**/spec/`. Single source of truth for every
   contract, interface, behavior, and property.
3. **Design** - `src/main/scala/udacore/**/design/`. Implements specs, never precedes them.

Spec-first workflow, the UDA edge rules (sanctioned rawNoDecoupled classes, no flush,
rawTop wiring-only, epoch stances), the extension-contribution convention (ADR-017), and
the Spec-TDD loop (ADR-018: every FUNCTION/PROPERTY binds to a test, red before green)
are operationalized in the **spec-first skill** (`.claude/skills/spec-first/SKILL.md`) -
invoke it before touching `src/main/scala`. The spec DSL guide is `AGENTS.md`.

Owner-gated items (never decide as an agent): project identity calls, PPA bars (OQ-C),
protected-file waivers (OQ-E), RV64 decode scheduling. Record them in
`document/HANDOFF.md` under "Open questions needing the OWNER".

## Current State (calibrate before promising results)

- **CoreTop and most vertices are spec shells** (`???` placeholders, deliberately).
  DUT-facing verif commands answer `harness-not-ready` (exit 3) - that is the designed
  status, not a failure. The RTL fill-in order is in `document/HANDOFF.md`.
- **Implemented and elaborating today**: the external functional units (Alu, Multiplier,
  Divider, BitAlu), GlobalEpochUnit, BootSequencer, the CSR decorator library
  (`common/system/csr`), TileLink bundles (`common/tilelink`).
- **Working instruments today**: the whole verif engine (scn parse/gate/trace/daemon
  machinery), EmitUnit + sta.sh unit + ppa-unit.sh (real synthesis/STA on the units).

## Build, Verify, Measure

No sbt in the remote environment; the scalac-direct gate is primary.

```bash
bash verif/bin/setup.sh          # once per container: jars, firtool, verilator (hook does this)
bash verif/bin/build.sh          # THE compile gate: core + framework + suites, 0 errors required
python3 tools/spec-check.py      # THE spec gate (ADR-015/018 checker), 0 errors required
verif/bin/run.sh verif.spectest.RunSpecTests  # L1 SpecTests (ADR-018); PENDING = spec shell

verif/bin/scn.sh describe        # verif capability card + readiness state
verif/bin/scn.sh run my.scn --json           # .scn on the real core (verif skill)
bash verif/bin/setup-sta.sh      # once, heavy: yosys + sky130 + OpenSTA
verif/bin/sta.sh unit mul_csa16  # constrained gate-level STA (ppa skill)
verif/bin/ppa-unit.sh            # area/DFF/timing/power table across unit configs
```

Local machines with sbt can additionally run `sbt compile` / `sbt scalafmtCheckAll test`
(chisel selected by `-DchiselVer`, default 3; the verif gate pins chisel 6.2.0 - code must
compile under both, and chisel-3-only constructs that pass compile can still fail chisel-6
elaboration).

Both repo git hooks run automatically (`core.hooksPath .githooks`, set by the session
hook): staged-ASCII check + spec-check on any src/main/scala commit.

## Architecture in One Screen

- Domains: `core` (CoreTop integration, epoch, boot, bus adapters, cache/TLB vertices when
  configured), `frontend`, `backend`, `memorysubsystem`, `external` (IP-style functional
  units), `common` (isa, system/csr, tilelink, util, cross-domain specs).
- **Epochs, not flushes**: redirect fires only at the in-order commit head (ADR-011); the
  global epoch increments and stale tokens self-filter (eager-filter vertices enumerated in
  the spec). Committed state (StoreBuffer committed entries, caches) is epoch-exempt.
- **Edges**: everything ready/valid except the five sanctioned rawNoDecoupled classes
  (epoch broadcast / async inputs / boot statics / commit-broadcast strobes / wakeup
  broadcast) - see `DesignRuleSpecs.rawNoDecoupled`.
- **Boundary**: two TileLink master links, `instBus` (TL-UL/UH) and `dataBus` (TL-C when a
  DCache is configured), bridged by CoreTop-owned adapter vertices (ADR-016). Cache/TLB
  vertices splice into internal edges; the boundary never changes.
- **Extensions** (ADR-017): optional vertices + data contributions (decode rows absent when
  disabled, so instructions trap; CSR map entries into the one readFromCsr map). Never
  main-style Feature weaving.
- **Parameters**: three tiers (contract/tuning/private) per domain; XLEN in {32, 64};
  nothing hard-codes 32.

## Naming

- Spec files `<DesignFileName>Specs.scala`; spec vals `<cat><Module><Feature>`
  (cont/intf/func/prop/param/bnd/cap prefixes); one CONTRACT per file.
- Classes PascalCase with acronyms as words (`Alu`, `Csr`, `TLBundle` is the sanctioned
  exception for the standard protocol); instances camelCase.
- Interfaces: control = verbs, data = nouns.

## Protected (do not modify without owner permission)

- `src/main/scala/udacore/backend/design/modules/csr/CSR.scala` (AGENT: DO NOT TOUCH;
  OQ-E pending)
- `src/main/scala/assembler/*` (it has RVC support main's lacks)
- `src/test/scala/cluster/*`, `src/test/scala/assembler/*` (stale vs the rebuild APIs but
  owner-held)
- The verif Gate discipline (latency/skew/differential verdicts) may be extended, never
  weakened.

## Session Discipline

- The SessionStart hook provisions the toolchain and prints readiness; long setup logs are
  in `/tmp/verif-session-start.log`.
- End of any substantive session: run the **handoff skill** (`--write` updates
  `document/HANDOFF.md`). The container is ephemeral; durable state lives in git.
- Commit policy: commit and push only when the user asks (or a skill's contract says so).
  Korean commit messages; logical units, not end-of-day dumps.

## Reading Order

1. `README.md` - philosophy and quick start
2. `document/adr/ADR-000-index.md` - the binding rulings (then ADR-011, -016, -017, -018)
3. `AGENTS.md` - spec DSL and workflow detail
4. `document/HANDOFF.md` - live state, next steps, owner questions
5. `verif/README.md` + `verif/AI-HARNESS.md` - the instruments
6. `docs/` - foundations and practices (background)
