# Spec Framework Guidelines

## Philosophy: Specs as Textbooks

**Think of spec files as textbooks for your hardware.**

When you open a textbook, you see:
- Chapter titles and section headings
- Main paragraphs explaining concepts
- Tables organizing data
- Diagrams illustrating structure
- Code examples showing implementation
- Footnotes providing additional context

The Spec DSL provides these same building blocks. Write specs that read naturally, as if you're explaining the design to a colleague.

## 1. Quick Start

### Minimal Spec

```scala
import framework.macros.SpecEmit.spec
import framework.spec.Spec._

val mySpec = spec {
  CONTRACT("ModuleName")
    .desc("What this module does")
    .build()
}
```

Every spec needs:
1. A category (CONTRACT, FUNCTION, etc.)
2. A description via `.desc()`
3. A terminating `.build()`

Everything else is optional.

### Complete Example

```scala
val contDecoder = spec {
  CONTRACT("InstructionDecoder")
    .desc("Decodes RISC-V instructions into micro-operations")
    .has(intfInstructionInput, intfUopOutput)
    .uses(bndInstruction, paramDataWidth)
    .draw("mermaid", """
      graph LR
        inst[Instruction] --> decode[Decode Logic]
        decode --> uop[Micro-op]
    """)
    .markdownTable(
      List("Field", "Bits", "Purpose"),
      List(
        List("opcode", "[6:0]", "Operation type"),
        List("rd", "[11:7]", "Destination register"),
        List("rs1", "[19:15]", "Source register 1")
      )
    )
    .note("Supports RV32IMC instruction set")
    .note("Single-cycle decode for all instructions")
    .build()
}
```

## 2. Categories

Categories are **organizational labels**, not programming constraints. Choose what makes your documentation naturally readable.

| Category | Purpose | Example Use |
|---------|---------|-------------|
| `CONTRACT` | Module/component identity | "What is this module?" |
| `INTERFACE` | Connection points | "How do modules connect?" |
| `FUNCTION` | Behavioral specification | "What does it do?" |
| `PROPERTY` | System guarantee | "What is always true?" |
| `PARAMETER` | Configuration value | "How is it configured?" |
| `CAPABILITY` | Feature support | "What features does it have?" |
| `BUNDLE` | Data structure | "How is data organized?" |
| `COVERAGE` | Verification goal | "How do we test it?" |
| `RAW` | Custom documentation | "Anything else" |

### Usage Examples

```scala
// CONTRACT: Define a module
val contFetchUnit = spec {
  CONTRACT("FetchUnit")
    .desc("Fetches instructions from program memory")
    .build()
}

// INTERFACE: Define a connection point
val intfMemoryReq = spec {
  INTERFACE("MemoryRequest")
    .desc("Memory request interface")
    .is(rawReadyValidIntf)
    .uses(bndMemoryReq)
    .build()
}

// FUNCTION: Describe behavior
val funcPcGeneration = spec {
  FUNCTION("PcGeneration")
    .desc("Generates next PC based on redirect and prediction")
    .code("scala", """
      val nextPc = Mux(redirect.valid, redirect.target, pc + 4.U)
    """)
    .build()
}

// PROPERTY: State a guarantee
val propEpochFiltering = spec {
  PROPERTY("EpochFiltering")
    .desc("Stale tokens are automatically filtered by epoch mismatch")
    .note("No explicit flush signals required")
    .build()
}

// CAPABILITY: Document a feature
val capRV32M = spec {
  CAPABILITY("RV32M")
    .desc("Integer multiplication and division extension support")
    .entry("MUL, MULH", "Multiplication variants")
    .entry("DIV, REM", "Division and remainder")
    .build()
}
```

## 3. Documentation Methods

All methods are optional except `.desc()` and `.build()`.

### Text Content

```scala
.desc("Main description text")     // Required: primary content
.note("Additional context")         // Optional: footnotes
```

### Structured Data

```scala
// Tables
.table("markdown", "| Col | Val |\n|-----|-----|\n| A | 1 |")
.table("csv", "Col,Val\nA,1")
.table("| Col | Val |")  // Defaults to markdown

// Structured tables (recommended)
.markdownTable(
  List("Signal", "Width", "Direction"),
  List(
    List("data", "32", "input"),
    List("valid", "1", "input"),
    List("ready", "1", "output")
  )
)

// Key-value pairs
.entry("Author", "Hardware Team")
.entry("Status", "Implemented")
.entry("Version", "2.0")

// Hierarchical lists
.entry("Stages")
.entry("  Fetch")
.entry("  Decode")
.entry("  Execute")
```

### Visual Content

```scala
// Diagrams
.draw("mermaid", "graph TD; A-->B")
.draw("plantuml", "@startuml\nA -> B\n@enduml")
.draw("ascii", "  +---+\n  | A |\n  +---+")

// Code examples
.code("scala", "val x = Wire(UInt(32.W))")
.code("verilog", "wire [31:0] data;")
.code("text", "Plain text example")
.code("Some code")  // Defaults to "text"
```

### Metadata

```scala
.status("implemented")      // Development status
.status("draft")
.status("deprecated")
```

## 4. Relationships

Connect specs using three relationship types:

```scala
.is(...)      // Classification: "X IS a Y"
.has(...)     // Composition: "X HAS a Y"
.uses(...)    // Dependency: "X USES a Y"
```

### Natural Language Test

**Read your spec aloud.** If it sounds natural, you're using relationships correctly.

```scala
// Good examples
CONTRACT("CoreTop")
  .is(rawTop)                    // "CoreTop IS a raw top module"
  .has(intfBootAddr, intfHartEn) // "CoreTop HAS boot address and hart enable"
  .uses(paramEpochWidth)         // "CoreTop USES epoch width parameter"

INTERFACE("MemoryPort")
  .is(rawReadyValidIntf)         // "MemoryPort IS a ready/valid interface"
  .uses(bndMemoryReq)            // "MemoryPort USES MemoryReq bundle"

FUNCTION("Decode")
  .uses(funcValidation)          // "Decode USES validation function"
```

### Multiple References

```scala
.has(intfInput, intfOutput, intfControl)
.uses(paramWidth, paramDepth, funcHelper)
.is(rawTop, rawParametric)
```

## 5. Workflow

### Step 1: Write Spec First

Before touching any design code, write the spec:

```scala
// src/main/scala/udacore/frontend/spec/modules/FetchUnitSpecs.scala
object FetchUnitSpecs {
  val contFetchUnit = spec {
    CONTRACT("FetchUnit")
      .desc("Fetches instructions from external program memory")
      .has(intfProgramMemoryReq, intfProgramMemoryResp, intfInstructionOut)
      .uses(paramProgramMemoryAddrWidth)
      .build()
  }

  val intfProgramMemoryReq = spec {
    INTERFACE("ProgramMemoryReq")
      .desc("Request interface to program memory")
      .is(rawReadyValidIntf)
      .uses(bndProgramMemoryReq)
      .build()
  }
}
```

### Step 2: Tag Design with @LocalSpec

Link implementation to specification:

```scala
// src/main/scala/udacore/frontend/design/modules/FetchUnit.scala
import framework.macros.LocalSpec
import udacore.frontend.spec.modules.FetchUnitSpecs._

@LocalSpec(contFetchUnit)
class FetchUnit(params: FrontendParams) extends Module {
  val io = IO(new Bundle {
    @LocalSpec(intfProgramMemoryReq)
    val programMemoryReq = Decoupled(new ProgramMemoryReq(params))

    @LocalSpec(intfProgramMemoryResp)
    val programMemoryResp = Flipped(Decoupled(new ProgramMemoryResp(params)))
  })

  // Implementation...
}
```

### Step 3: Link Related Specs

Use relationships to create traceable dependency chains:

```scala
val funcFetchLogic = spec {
  FUNCTION("FetchLogic")
    .desc("Main fetch control logic")
    .uses(paramProgramMemoryAddrWidth, funcEpochCheck)
    .build()
}

val funcEpochCheck = spec {
  FUNCTION("EpochCheck")
    .desc("Validates epoch of returning memory responses")
    .uses(paramEpochWidth)
    .build()
}
```

## 6. File Organization

### Directory Structure

Mirror the design directory structure:

```
subsystem/
  design/
    top/
      SubsystemTop.scala
    modules/
      ModuleA.scala
      ModuleB.scala
    shared/
      Bundles.scala
      Params.scala
  spec/
    top/
      SubsystemTopSpecs.scala
    modules/
      ModuleASpecs.scala
      ModuleBSpecs.scala
    shared/
      BundlesSpecs.scala
      ParamsSpecs.scala
```

### Naming Conventions

**Spec Files:**
- Format: `<DesignFileName>Specs.scala`
- Example: `FetchUnit.scala` → `FetchUnitSpecs.scala`

**Spec Objects:**
- Format: `<category><ModuleName><Feature>`
- Examples:
  - `contFetchUnit` (CONTRACT for FetchUnit)
  - `intfMemoryReq` (INTERFACE MemoryReq)
  - `funcDecodeLogic` (FUNCTION DecodeLogic)
  - `propNoFlush` (PROPERTY NoFlush)
  - `capRV32M` (CAPABILITY RV32M)

### Recommended Ordering

For natural reading flow (not enforced):

```scala
object ModuleNameSpecs {
  // 1. CONTRACT - "What is it?"
  val contModule = spec { CONTRACT(...) }

  // 2. INTERFACES - "How do I connect to it?"
  val intfInput = spec { INTERFACE(...) }
  val intfOutput = spec { INTERFACE(...) }

  // 3. FUNCTIONS - "What does it do?"
  val funcMainBehavior = spec { FUNCTION(...) }
  val funcHelper = spec { FUNCTION(...) }

  // 4. Everything else - "Other details"
  val propGuarantee = spec { PROPERTY(...) }
  val capFeature = spec { CAPABILITY(...) }
}
```

## 7. Style Guide

### Write for Humans

```scala
// Good: Clear and concise
.desc("Fetches instructions from program memory and forwards to decode stage")

// Avoid: Over-engineered
.desc("Leveraging advanced memory interface protocols, this sophisticated fetch mechanism orchestrates...")
```

### Use Real Names

Refer to actual signal and parameter names:

```scala
// Good: Uses real names
.desc("Compares token.epoch with globalEpoch to filter stale tokens")

// Avoid: Generic descriptions
.desc("Compares values to filter invalid data")
```

### Footnotes for Context

Use `.note()` for additional information that supports but doesn't replace the main description:

```scala
.desc("Decodes RV32IMC instructions into micro-operations")
.note("Single-cycle decode for all instructions")
.note("Future: consider two-cycle decode for complex instructions")
```

### Diagrams for Clarity

Add diagrams when structure is complex:

```scala
.draw("mermaid", """
  graph LR
    Frontend --> Backend
    Backend --> MemorySubsystem
    Backend --> Frontend
    GlobalEpoch -.-> Frontend
    GlobalEpoch -.-> Backend
""")
```

## 8. Common Patterns

### Documenting a rawTop Module

```scala
val contCoreTop = spec {
  CONTRACT("CoreTop")
    .desc("Top-level core integration with only wiring logic")
    .is(rawTop)  // Indicates no behavioral logic
    .has(intfBootAddr, intfHartEn, ...)
    .draw("mermaid", "graph LR...")
    .note("Uses :<>= operator for all Decoupled connections")
    .note("No manual valid/ready assignments")
    .build()
}
```

### Documenting Interfaces

```scala
val intfMemoryPort = spec {
  INTERFACE("MemoryPort")
    .desc("Bidirectional memory port with ready/valid handshake")
    .is(rawReadyValidIntf)
    .uses(bndMemoryReq, bndMemoryResp)
    .markdownTable(
      List("Signal", "Direction", "Width", "Description"),
      List(
        List("req", "output", "varies", "Memory request"),
        List("resp", "input", "varies", "Memory response")
      )
    )
    .build()
}
```

### Documenting Parameters

```scala
val paramEpochWidth = spec {
  PARAMETER("EpochWidth")
    .desc("Width of epoch counter in bits")
    .entry("Default", "2")
    .entry("Range", "1-8")
    .entry("Impact", "Determines max speculation depth")
    .note("Wider epochs allow deeper speculation but increase token overhead")
    .build()
}
```

### Documenting Capabilities

```scala
val capEpochControl = spec {
  CAPABILITY("EpochBasedControl")
    .desc("Epoch-based speculation eliminates flush signals")
    .entry("Global epoch counter", "Tracks program flow branches")
    .entry("Automatic filtering", "Stale tokens filtered by epoch mismatch")
    .entry("Scalable depth", "Configurable via epochWidth parameter")
    .build()
}
```

## 9. Integration with Design

### Linking Specs to Design

```scala
// Spec
val funcBootSequence = spec {
  FUNCTION("BootSequence")
    .desc("Coordinates hart enable and boot address delivery")
    .build()
}

// Design
@LocalSpec(funcBootSequence)
val bootSequence = {
  when(io.hartEn) {
    fetchUnit.io.bootAddr := io.bootAddr
  }
}
```

### Empty Placeholders

For pending implementation, use empty val with @LocalSpec:

```scala
// Spec exists
val funcDebugInterface = spec {
  FUNCTION("DebugInterface")
    .desc("Debug module integration")
    .status("planned")
    .build()
}

// Design placeholder
@LocalSpec(funcDebugInterface)
val debugInterface = {
  // TODO: Implement debug interface
}
```

**Never use stub assignments:**
```scala
// ❌ Wrong
io.debug.valid := false.B

// ✅ Correct
@LocalSpec(funcDebugInterface)
val debugInterface = {
  // Pending implementation
}
```

## 10. Summary

**Key principles:**
1. Specs are textbooks - write for human readers
2. Categories are organizational labels, not rules
3. All methods except `.desc()` and `.build()` are optional
4. Use natural language for relationships
5. Write specs before design code
6. Tag design with @LocalSpec annotations
7. Link related specs with `.uses()`

**When in doubt, read it aloud.** If your spec reads naturally, like explaining to a colleague, you're doing it right.
