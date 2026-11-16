# Documentation index

Start with the [repository README](../README.md) and
[live handoff](../document/HANDOFF.md). This directory contains UDA methodology
and tooling guidance. Detailed architectural contracts live in the spec DSL under
`src/main/scala/udacore/**/spec`.

## Authority and current architecture

ADRs outrank specs; specs outrank design. Read the
[ADR index](../document/adr/ADR-000-index.md),
[ADR-019](../document/adr/ADR-019-conventional-ooo-root-architecture.md), and
[ADR-020](../document/adr/ADR-020-cache-sram-storage.md) before applying older
examples. [Work Order 07](../document/architecture-team/07-ooo-v0-spec-work-order.md)
records the v0 spec-authoring plan. [AGENTS.md](../AGENTS.md) gives mandatory
contributor rules and skill entry points.

Several methodology documents were written for the earlier epoch-centered
machine. Global-epoch squash, commit-head-only branch redirect, ROB-less
retirement, compressed instruction paths, and N=1-to-N=32 scaling examples are
historical where ADR-019 supersedes them. Ready/valid edges, rawTop wiring,
spec-first development, and naming conventions remain applicable. Explicit
non-token broadcast exceptions are defined in DesignRuleSpecs.

## Tooling and verification

| Document | Use |
| --- | --- |
| [Verification guide](../verif/README.md) | Active L1 tests, whole-core scenarios, Trace, and synthesis/STA commands |
| [Spec framework integration](tooling/spec-framework.md) | Sibling framework checkout, toolchain setup, spec export, and report interpretation |
| [sbt command reference](tooling/sbt-commands-reference.md) | Compile/spec-export commands and the required formatting/test attempt |
| [Bundled assembler notes](tooling/riscv-builtin-assembler-with-rvc.md) | Owner-protected historical assembler; it can emit RVC, while the v0 core accepts fixed 32-bit instructions |

## Foundations

| Document | Use |
| --- | --- |
| [Core design principles](foundations/core-design-principles.md) | UDA worldview and design invariants |
| [Unified design principles](foundations/unified-design-principles.md) | Conventions and their enforcement |
| [Design constitution](foundations/design-constitution.md) | Vertex/edge graph terminology and axioms |
| [Microarchitectural paradigms](foundations/unified-microarchitectural-paradigms.md) | ECA/RAA/MDG/FCL terminology used in older methodology discussions |
| [UDA methodology](foundations/uda-methodology.md) | Consolidated methodology background and historical roadmap |

A vertex is a contract-level component defined by a spec DSL CONTRACT. An edge
is its ready/valid channel or a specifically sanctioned broadcast. A rawTop
vertex instantiates children and wires their edges without behavioral logic.
The Chisel implementation still uses Module; internal helpers do not become
contract vertices merely because they are modules.

## Implementation practices

| Document | Use |
| --- | --- |
| [Design principles primer](practices/design-principles-primer.md) | Ready/valid and dependency-closure checklist |
| [Decoupled IO guide](practices/decoupled-io-guide.md) | Handshake semantics and buffering guidance |
| [Ready/valid design patterns](practices/ready-valid-design-patterns.md) | Producer, consumer, and adapter patterns |
| [Role-based interface parameterization](practices/role-based-interface-parameterization.md) | Bundle sizing by endpoint responsibility |
| [Parameter architecture guide](practices/parameter-architecture-guide.md) | Parameter-resolution background and historical Chipyard examples |
| [Naming conventions](practices/naming-conventions.md) | Directory mirroring, interfaces, and spec names |
| [Implementation techniques](practices/implementation-techniques.md) | RelayStation/token patterns; epoch recovery examples are superseded by ADR-019 |
| [Application guidelines](practices/application-guidelines.md) | Historical migration examples for applying UDA discipline |

## Process

| Document | Use |
| --- | --- |
| [Spec usage guideline](process/spec-usage-guideline.md) | Spec-first workflow and LocalSpec annotations |
| [Divide-and-conquer work instruction](process/divide-and-conquer-work-instruction.md) | Historical backend compliance process; use current AGENTS.md and skills for mandatory workflow |

## Session history

The [live handoff](../document/HANDOFF.md) owns current validation, limitations,
owner decisions, and next steps. The
[handoff archive](../document/archive/HANDOFF-2026-11-16-history.md) retains earlier
session evidence. Its pending work and machine-specific paths are historical.
Use git history for removed architecture narratives; current contracts remain
in the ADRs and spec tree.

When adding documentation, link it here and state whether it is current guidance
or a historical reference. Keep transient run results in the live handoff and
local verification reports rather than adding status boards to methodology pages.
