# UDACore documentation summary

This digest collects the key information from active UDACore documentation so you can reorganise files without losing intent. Hardware parameter values and register-level detail remain in the Scala spec sources under `src/main/scala/udacore/**/spec` to honour the spec-first workflow.


## Terminology Baseline
- **Vertex** - Any node defined in spec DSL CONTRACT blocks. These are the graph checkpoints that own spec contracts and terminate edges. Modules without CONTRACT specs keep their module naming and are not renamed to vertices.
- **Edge** - A connection between vertices. Unless explicitly labelled scalar, treat every edge as a ready/valid handshake path.
- **Raw top (domain)** - A vertex tagged with `raw top` that instantiates child vertices and wires their edges without embedding behaviour.

> **Implementation note:** Chisel classes still extend `Module`; only the classes with CONTRACT specs are elevated to vertex terminology in documentation.

## 1. How to use this digest
- **Start here before touching anything in `docs/`**. Each subsection distils the governing rules from one or more canonical documents.
- **Use it as a refactoring map**: when two documents share the same bullet below, they are candidates for merging; when a bullet only appears here, the referenced file holds unique content you must preserve somewhere.
- **Keep the links intact** while you reorganise folders so future contributors can trace the authoritative source quickly.

## 2. Foundational doctrine (keep or replicate verbatim)
- **Unified dataflow worldview** - All behaviour is represented as tokens flowing through an acyclic graph of autonomous vertices. This doctrine is the root of every other rule and is expanded in `docs/foundations/core-design-principles.md` and `docs/foundations/unified-design-principles.md`.
- **Control as data** - Redirects, exceptions, and grants ride on token fields (`epoch`, `tag`, metadata) instead of side-band wires. Epoch equality (`token.epoch === globalEpoch`) is the single correctness predicate. Deep dive: `docs/foundations/dataflow-execution-model.md` and the surviving backend specs referenced in section 5.
- **Latency-insensitive edges only** - `DecoupledIO`-style ready/valid channels are the default; exceptions (clock/reset, hart enable, external protocols) must be explicitly justified. Implementation patterns live in `docs/practices/decoupled-io-guide.md` and `docs/practices/ready-valid-design-patterns.md`.
- **Intentional cycle breaking** - Strongly connected components are banned. Relay vertices and other cuts are inserted deliberately and tracked as debt until removal is proven safe. Refer to `docs/practices/implementation-techniques.md` for placement recipes.
- **Single source of truth** - Specs lead, design follows, documentation narrates. `docs/process/spec-usage-guideline.md` formalises the spec-first governance, and `docs/practices/naming-conventions.md` mirrors the directory and file layout rules for every vertex.

## 3. Architectural snapshot
- **Project baseline** - UDACore is a parametric two-stage RV32IMC core written in Chisel 6.7.0. Source directories split cleanly into domain roots (frontend, backend, memory, ...) that mirror paired `design/` and `spec/` trees. Each tree carries `top/`, `modules/`, `shared/`, and `api/` subdirectories; `shared/` holds reusable contracts, `api/` publishes contract-safe subsets for parent tops, and `@LocalSpec` tags bind implementations to their specs. See repository `README.md` and `ONBOARDING.md` for ISA, feature matrix, and contributor orientation.
- **Documentation topology** - `docs/README.md` remains the authoritative index for evergreen methodology. Architectural narratives and diagrams now live in the spec DSL (`src/main/scala/udacore/**/spec`).

## 4. Frontend essentials to preserve
- **Pipeline sequence** - The frontend raw top vertex composes `CoreEnableSequencer -> NextPcSelector -> PcRegister -> InstructionFetcher -> ReqTable -> AlignSlice -> RvcExpander -> IssueQueue`. All internal edges are Ready/Valid; the only scalar escape hatch is `hartEn`. Source: `src/main/scala/udacore/frontend/spec/top/FrontendTopSpecs.scala`.
- **Redirect philosophy** - Epoch flips replace flushes. Redirects are modelled as epoch transitions that ripple through the fetch domain. Keep this invariant when rewriting onboarding or architecture material. Details: `docs/foundations/dataflow-execution-model.md` and the frontend specs noted above.
- **Interface contract** - The frontend domain exposes `instructionIssueOut`, `redirectOut`, prediction hints, and consumes `globalEpochIn`. The edge semantics and handshake guarantees are documented in the surviving specs.

## 5. Backend essentials to preserve
- **Epoch S/B/K boundaries** - Speculation is partitioned into Split/Buffer/Keep regions. Tokens carry `{epoch, tag, rd, data}` end-to-end, and only `valid` generation is gated by epoch comparison (ESFC rule). Normative description: `docs/foundations/dataflow-execution-model.md` and `src/main/scala/udacore/backend/spec/top/BackendTopSpecs.scala`.
- **Renamer-RS-vGPR triad** - Register rename, reservation stations, and the virtual GPR file operate as a single invariant loop of vertices: renamer allocates architectural intent, RS enforces dependency readiness, and vGPR holds architected state.
- **Store buffer fairness & memory ordering** - The load-first arbitration risk and mitigation backlog are summarised in `docs/process/divide-and-conquer-work-instruction.md`.
- **Trap and interrupt convergence** - Outstanding work items call for a unified trap controller vertex that merges interrupts and exceptions before commit. The open questions are catalogued in `AGENTS.md`; keep them visible during reorganisation.

## 6. Parameterisation, naming, and reuse rules
- **Spec mirroring** - Every design file has a matching `*Specs.scala` file inside a mirrored directory tree. Spec objects live inside `*Specs` Scala objects, and ports follow the `In`/`Out` suffix convention for each vertex. Source: `docs/practices/naming-conventions.md`.
- **Parameter pivots** - Raw-top or intermediate shells (tile, cluster, SoC, board) materialise domain parameter bundles inside `design/shared/` and publish contract-safe views through `design/api/`. Every consumer imports the explicit API bundle; no implicit propagation or CDE lookups remain. Defaults and relationships live in the spec files; documentation explains rationale only. See `docs/practices/parameter-architecture-guide.md` for the tiering workflow and further details.
- **Local-first bundles** - Define bundles and interfaces inside each domain's `shared/` directory and only re-export them through `api/` when another domain needs access. The rule set is spelled out in `docs/practices/naming-conventions.md` and `docs/practices/parameter-architecture-guide.md`.
- **Top-specific interfaces** - Even when two tops share identical fields, duplicate the interface in each domain's `api/` directory so the owning top controls upgrades independently. `docs/practices/naming-conventions.md` captures the boilerplate policy.

## 7. Spec-first workflow and governance
- **Author specs before RTL** - Update or add specs inside `spec/` before editing `design/`. Bind every vertex implementation with `@LocalSpec`. Workflow checklists and DSL usage live in `docs/process/spec-usage-guideline.md`.
- **Review cadence** - Foundations and Architecture documents require architecture-owner approval; Practices and Process require team-lead sign-off. The combined compliance playbook (`docs/process/divide-and-conquer-work-instruction.md`) documents the audit flow.
- **Documentation maintenance** - Capture consolidation notes inside working logs tied to each initiative; keep `docs/` limited to stable guidance.

## 8. Testing, tooling, and onboarding anchors
- **Mandatory commands** - `sbt scalafmtCheckAll`, `sbt test`, and `sbt 'testOnly *SingleCoreMulDivClusterTest* -- -z "Store test 0"'` are the required local gates before submitting changes. They appear in both `AGENTS.md` and the backend compliance playbook.
- **Elaboration & exploration** - `sbt compile` for rapid syntax checks and `sbt runMain UDACoreElab` to materialise RTL for waveform/debug flows. These commands are reiterated in `README.md`, `ONBOARDING.md`, and this digest to keep contributors aligned.
- **Command reference** - `docs/tooling/sbt-commands-reference.md` consolidates the daily `sbt` workflows, targeted rerun syntax, and logging habits expected in review notes.
- **Five-day onboarding arc** - `ONBOARDING.md` prescribes the reading and hands-on sequence (`Specs -> Design -> Waveforms`). Maintain its order when reorganising onboarding material.
