# Documentation index

This index maps the `docs/` tree. Each entry explains what the document covers,
who needs it, and how often it is reviewed so you can quickly tell whether it
belongs in your workflow. Start with ADR-019 and the repository README, then use
the *Immediate essentials* table while gathering references for a new task.

> **Authority** - `document/adr/ADR-019-conventional-ooo-root-architecture.md` is the
> root architecture. These documents are UDA methodology background written for the
> earlier epoch-centered machine; where a document describes global-epoch squash,
> commit-head-only branch redirect, ROB-less retirement, compressed instructions, or
> an N=1-to-N=32 scaling axis, ADR-019 and the spec tree win. The ready/valid edge
> discipline, rawTop wiring rule, spec-first workflow, and naming rules still apply.

> **Information model** - Architectural intent, diagrams, and interface
> contracts now live exclusively in the spec DSL (`src/main/scala/udacore/**/spec`).
> `docs/` hosts evergreen methodology and governance guidance only; transient
> analyses, status boards, or checklists should live in separate working notes.

## Terminology Baseline
- **Vertex** - A labelled node defined in spec DSL CONTRACT blocks. It represents the contract-level unit that terminates edges and always has a dedicated spec. Modules without CONTRACT specs stay described as internal helpers.
- **Edge** - A connection between vertices. Edges manifest as ready/valid channels or explicitly called out scalar broadcasts.
- **Raw top (domain)** - A vertex tagged with `raw top` that instantiates child vertices and wires edges without embedding behavioural logic.

> **Implementation note:** Source code still uses `chisel3.Module`. Only the modules with CONTRACT specs are promoted to vertex terminology; the rest stay unnamed implementation blocks inside their parent vertex.

## Immediate Essentials

| Document | Purpose | Primary Audience | Status / Review Cadence |
| --- | --- | --- | --- |
| **[ADR-019](../document/adr/ADR-019-conventional-ooo-root-architecture.md)** | Root architecture of the conventional OoO core. | All contributors | Binding |
| **[Work Order 07](../document/architecture-team/07-ooo-v0-spec-work-order.md)** | Spec-authoring plan for the ADR-019 v0 core. | Spec authors | Active |
| **[Spec Usage Guideline](process/spec-usage-guideline.md)** | Canonical reference for writing `LocalSpec` artifacts before design edits. | All implementers | Active - review with every spec evolution |

## Foundations (`docs/foundations/`)

| Document | Purpose Snapshot | Audience | Status |
| --- | --- | --- | --- |
| [core-design-principles.md](foundations/core-design-principles.md) | Defines UDA worldview, five invariants, and how they keep flow, validation, optimisation aligned. | Architects, reviewers | Stable - audit annually |
| [unified-design-principles.md](foundations/unified-design-principles.md) | Policy bundle for mandatory conventions and enforcement matrix. | Architecture, governance | Active - update with policy changes |
| [design-constitution.md](foundations/design-constitution.md) | Graph axioms for treating vertices and edges consistently across the design. | Raw top designers | Stable reference |
| [unified-microarchitectural-paradigms.md](foundations/unified-microarchitectural-paradigms.md) | Defines ECA/RAA/MDG/FCL paradigms and interplay rules. | All subsystem leads | Stable reference |
| [uda-methodology.md](foundations/uda-methodology.md) | Consolidated UDA playbook covering concepts, workflows, and v2 roadmap. | Tooling + methodology owners | Active - review each release |

### Foundations cleanup radar
- Validate that `unified-microarchitectural-paradigms.md` still adds material
  beyond the new UDA playbook; merge or cross-link if redundancy grows.
- Keep `unified-design-principles.md` and `core-design-principles.md` aligned;
  update references if policy names change.

## Implementation Practices (`docs/practices/`)

| Document | Purpose Snapshot | Audience | Status |
| --- | --- | --- | --- |
| [design-principles-primer.md](practices/design-principles-primer.md) | Quick enforcement checklist for ready/valid and dependency closure. | Daily implementers | Active |
| [decoupled-io-guide.md](practices/decoupled-io-guide.md) | Deep dive on handshake semantics, buffering, verification hooks. | Frontend + backend engineers | Active |
| [ready-valid-design-patterns.md](practices/ready-valid-design-patterns.md) | Pattern catalogue for producers/consumers and adapters. | RTL authors | Stable |
| [role-based-interface-parameterization.md](practices/role-based-interface-parameterization.md) | Guidance for sizing bundles by producer/consumer responsibility. | Architects | Stable |
| [parameter-architecture-guide.md](practices/parameter-architecture-guide.md) | English appendix covering Chipyard pivots and zero-friendly defaults. | Architects, tooling | Active |
| [naming-conventions.md](practices/naming-conventions.md) | Directory mirroring and spec naming standards. | All contributors | Active |
| [implementation-techniques.md](practices/implementation-techniques.md) | RelayStation usage and token structure (its epoch-management section is superseded by ADR-019 RecoveryEvent recovery). | Backend implementers | Active |
| [application-guidelines.md](practices/application-guidelines.md) | Migration steps for legacy pipelines into UDA. | Migration task forces | Stable |

### Practices cleanup radar
- Periodically audit the Quick Checklist vs. Implementation Techniques to keep
  terminology aligned.
- Review the archived Korean guide each release; delete once the English appendix
  covers all required deltas.

## Process & Governance (`docs/process/`)

| Document | Purpose Snapshot | Audience | Status |
| --- | --- | --- | --- |
| [divide-and-conquer-work-instruction.md](process/divide-and-conquer-work-instruction.md) | Backend compliance playbook defining the standard operating procedure. | Backend programme mgr | Active |
| [spec-usage-guideline.md](process/spec-usage-guideline.md) | Spec-first workflow, `LocalSpec` tagging rules, review cadence. | All implementers | Active |

### Process cleanup radar
- Keep the backend compliance workboard and audit checklist in the shared
  tracker; update this index only when the underlying process changes.

## Tooling (`docs/tooling/`)

| Document | Purpose Snapshot | Audience | Status |
| --- | --- | --- | --- |
| [riscv-builtin-assembler-with-rvc.md](tooling/riscv-builtin-assembler-with-rvc.md) | Operating notes for the bundled (owner-protected) assembler, which can emit RVC; the v0 core does not execute compressed instructions. | Tooling maintainers | Stable |
| [sbt-commands-reference.md](tooling/sbt-commands-reference.md) | Quick reference for required sbt workflows and troubleshooting tips. | Contributors running builds/tests | Active |

## Archives & Historical References

Archive-grade material now belongs in dedicated archives or external snapshots.
Use git history to recover older narratives that the cleanup removed; recreate
them only when an active effort needs the context.

---

### How to keep this index healthy
1. **Add new documents here immediately.** Include audience + cadence so future
   maintainers can plan reviews.
2. **Move archive-grade material to the archives directory (temporary home) and
   tag it as Archive.** Update links once a dedicated archives directory returns.
3. **Update the cleanup radar bullets** when de-duplication work is completed or
   new redundancy is spotted. Capture consolidation decisions, checklists, and
   snapshots in working notes tied to the relevant initiative.

For architecture, read the ADRs and the spec tree; this directory is methodology only.
