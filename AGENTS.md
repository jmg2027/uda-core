# UDACore Development Workflow & Guidelines

## Documentation Reading Order

1. **[README.md](README.md)** - Core design philosophy and quick start
2. **[docs/](docs/)** - Detailed design documentation
   - `foundations/` - Core design principles
   - `practices/` - Implementation guidelines
   - `process/` - Development workflow
   - `tooling/` - Build system
3. **This document (AGENTS.md)** - Mandatory rules and workflows

## Binding Rules (mechanically enforced on this branch)

- Order of authority: ADRs (`document/adr/ADR-000-index.md`) > specs > design. Specs are
  written BEFORE design; design carries `@LocalSpec` back-references.
- Gates (also run by the pre-commit hook): `bash verif/bin/build.sh` must report 0 errors;
  `python3 tools/spec-check.py` must report 0 errors. A new PROPERTY ships with its paired
  design assert or an explicit `tools/spec-check-allow.txt` entry (ADR-015).
- Edge doctrine: every interface is ready/valid except the five sanctioned rawNoDecoupled
  classes enumerated in `common/spec/DesignRuleSpecs.rawNoDecoupled` (epoch broadcast,
  async inputs, boot statics, commit-broadcast strobes, wakeup broadcast). No flush/kill
  side-channels; stalls are ready backpressure only.
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
import framework.specs.Spec._
import udacore.common.spec.DesignSpecs._

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
import udacore.common.spec.DesignSpecs._

rawTop              // Graph structure only, no logic blocks
rawReadyValidIntf   // Interface implements ready/valid protocol
rawNoDecoupled      // Exception: epoch, interrupt, debugReq only
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

// Good: "Function USES epoch parameter"
FUNCTION("Control").uses(paramEpochWidth)
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

These provide validated test infrastructure for all regression testing.

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
    @LocalSpec(intfEpoch)
    val epoch = Input(UInt(EpochWidth.W))
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
- **Epoch-Based Control**: Epoch values replace flush signals
- **Module Encapsulation**: Clear contracts and interfaces
- **Parameter Resolution**: Elaboration-time, unused hardware optimized away
- **Interface-by-Interface**: Every spec interface needs design implementation

## Ready/Valid Integration

Do not introduce queues, skid buffers, or response registers unless specs require them. Gate `valid` or use kill mechanisms instead.

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

## Testing

Run cluster tests:
```bash
sbt testOnly udacore.cluster.SingleCoreMulDivClusterTest
sbt 'testOnly *SingleCoreMulDivClusterTest* -- -z "Store test 0"'
```

Format and test before committing:
```bash
sbt scalafmtCheckAll test
```

## Documentation Guidelines

- Maintain natural, human tone
- Remove machine-generated or overly ornate phrases
- Use plain-text characters only (no emoji)

## Commit Policy

Agent should not commit automatically. Only when user requests.
