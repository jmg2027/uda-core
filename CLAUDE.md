# CLAUDE.md

Guidance for Claude Code working in this repository. This file is the entry point; the
binding law lives in `document/adr/` and the spec tree, and the mechanical gates enforce
what prose cannot.


> OoO v0 note: for frontend/backend/MMU/cache redesign, read
> `document/adr/ADR-019-conventional-ooo-root-architecture.md` and
> `document/architecture-team/07-ooo-v0-spec-work-order.md` before older
> architecture papers. Use `.claude/skills/ooo-spec-author/SKILL.md`.

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

UDACore is a **personal, conventional out-of-order RISC-V core** (ADR-019) built with the
**Unified Dataflow Architecture (UDA)** discipline: every component is a vertex in a
dataflow graph, every token-moving connection a ready/valid edge, and speculation is
recovered selectively through one RecoveryEvent broadcast - never a flush wire and never a
global-epoch squash.

The v0 point is RV32IM with M/S/U privilege and Sv32, fixed 32-bit instructions (no RVC), a
PC-indexed BTB+TAGE+RAS frontend with an FTQ, an explicit data-less ROB with sRAT/rRAT
renaming, an RS, an LSQ, VIPT L1 caches, ITLB/DTLB and a shared PTW, and two TileLink master
links at the boundary. It is independent of the company line (KLASE32); references to
"main" mean that lineage, from which the verif/PPA instruments were ported.

## Order of Authority (the law)

1. **ADRs** - `document/adr/ADR-000-index.md` first, then the referenced rulings. ADRs are
   binding contracts; "proposed" ADRs name the experiment that settles them.
2. **Specs** - `src/main/scala/udacore/**/spec/`. Single source of truth for every
   contract, interface, behavior, and property.
3. **Design** - `src/main/scala/udacore/**/design/`. Implements specs, never precedes them.

Spec-first workflow, the UDA edge rules (ready/valid transfers, sanctioned
rawNoDecoupled facts/statics, rawTop wiring-only), the ADR-019 selective-recovery
overlay for new OoO work, the extension-contribution convention (ADR-017), and
the Spec-TDD loop (ADR-018: every FUNCTION/PROPERTY binds to a test, red before green)
are operationalized in the **spec-first skill** (`.claude/skills/spec-first/SKILL.md`) -
invoke it before touching `src/main/scala`. The spec DSL guide is `AGENTS.md`.

Owner-gated items (never decide as an agent): project identity calls, PPA bars (OQ-C),
protected-file waivers (OQ-E), RV64 decode scheduling. Record them in
`document/HANDOFF.md` under "Open questions needing the OWNER".

## Current State (calibrate before promising results)

- **ADR-019 spec phase**: the new architecture exists as spec DSL contracts; CoreTop and
  every ADR-019 vertex are design shells (`???` placeholders, deliberately). DUT-facing
  verif commands answer `harness-not-ready` (exit 3) - the designed PENDING color of the
  ADR-019 `.scn` red tests, not a failure. The RTL fill-in order is in `document/HANDOFF.md`.
- **Implemented and elaborating today**: the external functional units (Alu, Multiplier,
  Divider, BitAlu), BootSequencer, the CSR decorator library (`common/system/csr`),
  TileLink bundles (`common/tilelink`), and the parameter legality requires.
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

- Domains: `core` (CoreTop, BootSequencer, ITLB/DTLB/PTW, VIPT I/D caches, bus adapters),
  `frontend` (FetchPcGen, BranchPredictor, FTQ, FetchUnit, FetchBuffer), `backend` (decode,
  rename, ROB, RS, execution units, LSQ, StoreBuffer, commit, trap/CSR, RecoveryController),
  `external` (IP-style functional units), `common` (isa, system/csr, tilelink, util,
  cross-domain doctrine).
- **Selective recovery, not flushes**: a mispredicted branch recovers at execute; the
  RecoveryController broadcasts one RecoveryEvent and every speculative holder discards
  only entries younger than its robTag (`funcRecoveryKills` over `funcRobOlder`). Older
  work survives. Commit-head traps/xRET/refetch are ArchRedirect events that discard the
  whole window. Committed state (rRAT, StoreBuffer, caches, TLBs) is recovery-exempt.
- **Edges**: everything token-moving is ready/valid except the sanctioned rawNoDecoupled
  classes (transaction-generation tags / async inputs / boot statics / committed-state views
  / wakeup / RecoveryEvent) - see `DesignRuleSpecs.rawNoDecoupled`.
- **Boundary**: two TileLink master links, `instBus` and `dataBus`, bridged by
  InstBusAdapter/DataBusAdapter. v0 is TL-UH on both; coherence (TL-C) is a separate
  parameter, never implied by the D-cache (ADR-019 D-19.13).
- **Extensions** (ADR-017): optional vertices + data contributions (decode rows absent when
  disabled, so instructions trap; CSR map entries into the one readFromCsr map). Never
  main-style Feature weaving.
- **Parameters**: three tiers (contract/tuning/private) per domain; the v0 reference values
  are the case-class defaults; XLEN stays a parameter (v0 = 32).

## Naming

- Spec files `<DesignFileName>Specs.scala`; spec vals `<cat><Module><Feature>`
  (cont/intf/func/prop/param/bnd/cap prefixes); one CONTRACT per file.
- Classes PascalCase with acronyms as words (`Alu`, `Csr`, `TLBundle` is the sanctioned
  exception for the standard protocol); instances camelCase.
- Interfaces: control = verbs, data = nouns.

## Protected (do not modify without owner permission)

- `src/main/scala/udacore/backend/design/modules/csr/CSR.scala` (AGENT: DO NOT TOUCH;
  OQ-E pending)
- `src/main/scala/assembler/*` (it can emit RVC; the v0 core treats those encodings as illegal)
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
