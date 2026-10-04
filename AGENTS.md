# UDACore Agent Guide

This is the entry point for coding agents working in this repository. Binding
architecture decisions live in `document/adr/`; specifications precede design.

## Project Identity and Authority

UDACore is a personal, conventional out-of-order RISC-V core following the Unified
Dataflow Architecture discipline. The v0 point is RV32IM_Zicsr_Zifencei + Svade,
M/S/U privilege, Sv32, fixed 32-bit instructions, and two TileLink master links.
It is independent of the company KLASE32 lineage; historical references to that
lineage's "main" do not describe this repository's current main branch.

Read `document/adr/ADR-000-index.md` and ADR-019 before older architecture papers.
ADRs outrank specs; specs outrank design. Invoke
`.claude/skills/spec-first/SKILL.md` before changing `src/main/scala`. For OoO spec
work, also use `.claude/skills/ooo-spec-author/SKILL.md`. Skill resources remain in
`.claude/skills/`; this AGENTS.md is the shared instruction entry point.

Project identity decisions, PPA bars (OQ-C), protected-file waivers, and RV64 decode
scheduling require the owner. Record unresolved decisions in
`document/HANDOFF.md` under "Open questions needing the OWNER".

## Language and Session Discipline

- Conversation responses and commit messages are Korean.
- Files, including specs, comments, and documentation, are English ASCII.
- No emoji or decorative characters. No TODO comments. Pending design shells use
  documented empty vals or `???`, never fake signal assignments.
- Consult `README.md` and `document/HANDOFF.md` for current implementation status.
  Backend, BranchPredictor, and FetchTargetQueue have passing L1 tests; whole-core
  integration remains unfinished. DUT-facing scenario commands report
  `harness-not-ready` (exit 3) until CoreTop and its harness are implemented.
- The optional SessionStart hook provisions tools and logs to
  `/tmp/verif-session-start.log`. Set `git config core.hooksPath .githooks` to enable
  the staged ASCII and spec-check hook in a new checkout.
- End substantive sessions with `.claude/skills/handoff/SKILL.md`; preserve the
  standing owner questions and toolchain gotchas in `document/HANDOFF.md`.
- Commit and push only when requested by the user or explicitly authorized by an
  invoked skill. Keep commits in logical units.

## Documentation Reading Order

1. **[README.md](README.md)** - Core design philosophy and quick start
2. **[ADR-019](document/adr/ADR-019-conventional-ooo-root-architecture.md)** - Binding root architecture for OoO v0 work
3. **[docs/](docs/)** - Detailed design documentation
   - `foundations/` - Core design principles
   - `practices/` - Implementation guidelines
   - `process/` - Development workflow
   - `tooling/` - Build system
4. **This document (AGENTS.md)** - Mandatory rules and workflows

## Binding Rules (mechanically enforced on this branch)

- Order of authority: ADRs (`document/adr/ADR-000-index.md`) > specs > design. Specs are
  written BEFORE design; design carries `@LocalSpec` back-references.
- Gates (also run by the pre-commit hook): `bash verif/bin/build.sh` must report 0 errors;
  `python3 tools/spec-check.py` must report 0 errors. A new PROPERTY ships with its paired
  design assert or an explicit `tools/spec-check-allow.txt` entry (ADR-015).
- Edge doctrine: every token-moving interface is ready/valid except the sanctioned
  rawNoDecoupled FACT/static classes enumerated in
  `common/spec/DesignRuleSpecs.rawNoDecoupled`: local transaction-generation tags, async
  inputs, boot statics, committed-state views and commit strobes, wakeup broadcast, and the
  ADR-019 speculative RecoveryEvent broadcast. No ad-hoc flush/kill side-channels are
  allowed: selective squash is derived locally from RecoveryEvent identity. Stalls are
  ready backpressure only.
- Extensions follow ADR-017: optional vertices plus data contributions (decode rows,
  CSR map entries). Feature-style host-signal weaving is forbidden.
- Spec-TDD (ADR-018): every FUNCTION/PROPERTY binds to a test by name (SpecTest
  `verifies=Seq(...)` or `.scn` `@verifies`), red observed before the implementation;
  unbound names go to `tools/spec-test-allow.txt` (shrink-to-zero).
- Style: files are English ASCII (staged-file check in `.githooks/pre-commit`); commit
  messages are Korean; no emoji; no TODO comments (documented placeholders instead).

## Spec DSL System

**Spec files are textbooks.** Write specs as naturally readable documentation.

### Basic Structure

```scala
package udacore.subsystem.spec.modules

import framework.macros.SpecEmit.spec
import framework.spec.Spec._
import udacore.common.spec.DesignRuleSpecs._

object ModuleNameSpecs {
  val contModuleName = spec {
    CONTRACT("ModuleName")
      .desc("Module purpose and function")           // Main text
      .has(intfInterface1, intfInterface2)          // Composition
      .uses(paramConfiguration)                     // Dependencies
      .is(rawTop)                                   // Classification
      .draw("mermaid", "graph LR...")               // Diagram
      .note("Additional context")                   // Footnote
      .build()
  }

  val intfInterfaceName = spec {
    INTERFACE("InterfaceName")
      .desc("Interface description")
      .is(rawReadyValidIntf)
      .uses(bndBundleName)
      .markdownTable(                               // Table
        List("Signal", "Direction", "Width"),
        List(List("data", "input", "32"))
      )
      .build()
  }

  val funcFeatureName = spec {
    FUNCTION("FeatureName")
      .desc("Behavioral specification")
      .code("scala", "val result = input + 1")      // Code example
      .build()
  }
}
```

### Categories as Documentation Labels

```scala
CONTRACT      // "What is this component?"
INTERFACE     // "How do modules connect?"
FUNCTION      // "What does it do?"
PROPERTY      // "What is always true?"
PARAMETER     // "Configuration knobs"
CAPABILITY    // "What features does it support?"
BUNDLE        // "Data structure definition"
COVERAGE      // "Verification goals"
RAW           // "Custom documentation"
```

Choose categories that make your spec naturally readable. These are organizational labels, not strict rules.

### Core Spec Properties

```scala
import udacore.common.spec.DesignRuleSpecs._

rawTop              // Graph structure only, no logic blocks
rawReadyValidIntf   // Interface implements ready/valid protocol
rawNoDecoupled      // Broadcast FACT/static exception classes only; see DesignRuleSpecs
```

### Documentation Methods

Think of these as textbook elements:

```scala
.desc("Main paragraph")                    // Required: main text
.note("Footnote")                         // Optional: additional context
.table("markdown", "| col | val |\n...")  // Tables
.markdownTable(headers, rows)             // Structured tables
.draw("mermaid", "graph TD...")           // Diagrams
.code("scala", "val x = 1")               // Code examples
.entry("key", "value")                    // Metadata
```

### Relationship Methods

Connect specs using natural language:

```scala
.is(...)      // "Module IS rawTop" - classification
.has(...)     // "Module HAS interfaces" - composition
.uses(...)    // "Module USES bundles" - dependency
```

**Read it aloud.** If it sounds natural, it's correct:

```scala
// Good: "CoreTop HAS boot address input"
CONTRACT("CoreTop").has(intfBootAddrIn)

// Good: "Interface IS ready/valid protocol"
INTERFACE("Port").is(rawReadyValidIntf)

// Good: "Function USES ROB depth parameter"
FUNCTION("Control").uses(paramRobDepth)
```

### Recommended File Organization

For readability (not enforced):

```scala
object ModuleNameSpecs {
  // 1. CONTRACT - module identity
  val contModuleName = spec { CONTRACT(...) }

  // 2. INTERFACES - connection points
  val intfInput = spec { INTERFACE(...) }
  val intfOutput = spec { INTERFACE(...) }

  // 3. FUNCTIONS - behaviors
  val funcMainLogic = spec { FUNCTION(...) }

  // 4. Everything else
  val propGuarantee = spec { PROPERTY(...) }
  val capFeature = spec { CAPABILITY(...) }
}
```

This follows natural reading flow: identity -> interfaces -> behavior -> details

## Critical Rules

### Protected Test Files

**Do not modify without explicit permission:**

- `src/test/scala/cluster/TestMem.scala`
- `src/test/scala/cluster/TestCluster.scala`
- `src/test/scala/cluster/SingleCoreMulDivClusterTest.scala`
- `src/test/scala/cluster/util/*`
- `assembler/*`
- `src/main/scala/assembler/*`
- `src/test/scala/assembler/*`
- All other `src/test/scala/cluster/*` files

These are owner-held infrastructure, including historical tests against older
APIs. The verification Gate discipline (latency, skew, differential verdicts) may
be extended but never weakened. The former `csr/CSR.scala` protection was waived
by the owner for the ADR-019 migration (OQ-E).

### Interface Implementation Rules

**No stubs or placeholders:**

- Forbidden: `io.someInterface.valid := false.B`
- Forbidden: `// TODO: implement later`
- Forbidden: `DontCare` assignments
- Required: Full implementation matching specs

**For rawTop modules:**

- Use `:<>=` operator for all Decoupled connections
- No manual `.valid` or `.ready` assignments
- No behavioral logic - wiring only

### Spec-First Workflow

**Mandatory steps:**

1. Write/update spec in `spec/` before any `design/` changes
2. Tag implementations with `@LocalSpec(specObject)`
3. Create empty `val` placeholders for pending work
4. Never use stub assignments

**Design file pattern:**

```scala
package udacore.subsystem.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.subsystem.spec.modules.ModuleNameSpecs._

@LocalSpec(contModuleName)
class ModuleName(params: Params) extends Module {
  val io = IO(new Bundle {
    @LocalSpec(intfInput)
    val input = Flipped(Decoupled(new BndInput(params)))

    @LocalSpec(intfOutput)
    val output = Decoupled(new BndOutput(params))

    // Exception interfaces (rawNoDecoupled in specs):
    @LocalSpec(intfRecoveryEventIn)
    val recoveryEvent = Input(new RecoveryEvent(params))
  })

  @LocalSpec(funcMain)
  val mainBehavior = {
    // Implementation pending
  }
}
```

### Naming Conventions

**Spec files:**
- Format: `<DesignFileName>Specs.scala`
- Example: `BackendTop.scala` -> `BackendTopSpecs.scala`

**Spec objects:**
- Format: `<category><ModuleName><Feature>`
- Example: `contBackendTop`, `funcVirtualGprRefcount`

**Module names:**
- Classes: PascalCase treating acronyms as words
- `ALU` -> `Alu`, `CSR` -> `Csr`, `GPR` -> `Gpr`
- Instances: camelCase

**Interface names:**
- Control signals: verbs (`enable`, `redirect`)
- Data signals: nouns (`data`, `addr`, `result`)

### Language Requirements

All specifications and documentation use English:

- Spec descriptions: English only
- Code comments: English only
- Documentation: English only
- Exception: Commit messages may use other languages

## Development Principles

- **Unified Dataflow Architecture**: All components are vertices with ready/valid edges
- **Spec-First Development**: Specifications before implementation
- **Selective OoO Recovery**: ADR-019 RecoveryEvent + ROB age/checkpoints recover younger speculative work; epochs are not the branch-ordering mechanism
- **Module Encapsulation**: Clear contracts and interfaces
- **Parameter Resolution**: Elaboration-time, unused hardware optimized away
- **Interface-by-Interface**: Every spec interface needs design implementation

## Ready/Valid Integration

Do not introduce queues, skid buffers, or response registers unless specs require them.
Do not invent per-module kill wires. For branch recovery, consume the common ADR-019
RecoveryEvent and derive local younger-than invalidation from the shared ordering rule.

## Directory Structure

For each subsystem marked `rawTop`:

```
domain/
  design/
    top/        # Wiring only, no logic
    modules/    # Implementations
    shared/     # Domain-internal assets
    api/        # Exports to parents
  spec/         # Mirrors design/
  test/
```

**Rules:**
- Package names match physical directories
- Single spec file = single spec object = single CONTRACT
- Reusable assets in `shared/`, promote to `api/` when parents need them


## ADR-019 OoO v0 Architecture Overlay

For new OoO work, ADR-019 is the root architecture decision. Older ADR/spec text is
historical where ADR-019 explicitly supersedes it.

Non-negotiable v0 anchors:

- RV32IM with fixed 32-bit instructions. No RVC, RvcExpander, half-word slot/carry,
  or BranchPredecoder prediction path.
- Conventional frontend prediction from fetch PC: BTB + TAGE + RAS + FTQ + fetch buffer.
- ITLB/DTLB + shared Sv32 PTW; U/S privilege.
- VIPT L1 I-cache and D-cache reference point.
- Explicit data-less ROB; sRAT/rRAT/free list; RS; LSQ; in-order retirement.
- Execute-time selective branch recovery. Older correct-path work survives.
- RecoveryEvent is the sole normal branch-squash broadcast fact. Global epoch equality
  must not be used to kill all in-flight work on a branch misprediction.
- Speculative stores remain above the committed memory/cache boundary; wrong-path loads
  may leave cache/TLB performance state but no architectural result.
- Cache presence and coherence enable are separate configuration decisions.

When translating this architecture into the DSL, follow
`document/architecture-team/07-ooo-v0-spec-work-order.md`. Agents doing this work
should also read `.claude/skills/ooo-spec-author/SKILL.md`.

ADR-019 migration is clean-break. Obsolete non-protected specs/design shells/bundles/
parameters/docs may be deleted or replaced wholesale. Do not add compatibility shims
whose only purpose is to preserve the superseded RVC/predecode/ROB-less/epoch-only
architecture.

## Build, Test, and Measurement

The primary gate compiles the sibling `../spec-framework` sources, UDA specs,
RTL, and verification suites. Override its location with `SPEC_FRAMEWORK_HOME`.
Both build paths use Scala 2.13.12 and Chisel 6.2.0. See
`docs/tooling/spec-framework.md` for setup and report interpretation.

```bash
bash verif/bin/setup.sh                     # Linux toolchain provisioning
bash verif/bin/build.sh                     # compile and spec artifact gate
python3 tools/spec-check.py                 # ADR-015/018 gate
bash verif/bin/test-spec-framework.sh       # macro integration regressions
verif/bin/run.sh verif.spectest.RunSpecTests # active OoO L1 tests
sbt specCheck                              # clean sbt compile and index export
```

Spec IDs must be globally unique. Metadata exports contain real spec relations
and source tags; design-shell annotations alone do not prove implementation or
formal coverage. Existing coverage gaps remain visible in the reports.

Run `sbt scalafmtCheckAll test` before committing. `src/test` contains protected
historical cluster/assembler tests;
report incompatibilities or unavailable dependencies without weakening the gates.

Additional instruments:

```bash
verif/bin/scn.sh describe
verif/bin/scn.sh run my.scn --json
bash verif/bin/setup-sta.sh
verif/bin/sta.sh unit mul_csa16
verif/bin/ppa-unit.sh
```

## Documentation Guidelines

- Maintain natural, human tone
- Remove machine-generated or overly ornate phrases
- Use plain-text characters only (no emoji)

## Commit Policy

Agent should not commit automatically. Only when user requests.
