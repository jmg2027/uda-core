# Unified design principles for UDACore

This document is the enforcement sheet reviewers use when checking new work. It
collects the required policies and points to the specs or practice notes that
back them up.

## 1. Philosophy
- **Single dataflow graph:** treat the design as vertices exchanging tokens. The
  spec DSL defines the graph; the RTL mirrors it.
- **Spec-first habit:** update the relevant `*Specs.scala` before writing code.
  Tag implementations with `@LocalSpec` so the mapping stays obvious.
- **Shared vocabulary:** use `vertex`, `edge`, `epoch`, and related terms
  consistently across specs, code, and docs.
- **General design patterns over specific examples:** Document universal design 
  principles and implementation patterns rather than domain-specific details. 
  Use concrete domains (frontend, backend) only as illustrative examples of 
  broader architectural concepts.

## 2. Interfaces
- **Default rule:** vertex-to-vertex links use `DecoupledIO`. Exceptions are
  limited to clock/reset, boot enables, and third-party protocols. Call out each
  exception in the spec.
- **Naming:** producers expose `bundleOut`, consumers use `bundleIn`, and the
  bundle type stays direction neutral. Avoid "flush" or similar legacy wording.
- **Ready/valid discipline:** producers never gate `valid` on downstream `ready`.
  Consumers own `ready`. If an interface is always ready, justify it explicitly.

## 3. Raw-top shells
- Raw-top vertices instantiate children and wire them together. They do not hold
  behavioural logic. If you need extra glue, create a helper vertex with its own
  spec.
- Keep scalar fan-out and default assignments inside helper vertices, not in the
  raw top body.

## 4. Parameters
- **Three-tier architecture:** Maintain global configuration, domain parameters, 
  and per-vertex arguments as distinct layers. This pattern applies universally 
  across all domains - each domain implements Contract/Tuning/Private tiers 
  within their parameter namespace.
- **Domain isolation with controlled APIs:** Each domain resolves parameters 
  internally and exposes only contract-safe views through api/ packages. This 
  ensures clean boundaries regardless of specific domain implementation.
- **Spec-driven parameter design:** Document every parameter tier in specs before
  implementation, with clear impact descriptions for reviewers. The pattern of
  spec-first parameter definition applies to all domains.

## 5. Paradigm coverage
Every change should respect the four recurring paradigms:
- **ECA:** epoch split/filter rules guard speculation. Specs note how many
  contexts may exist and how they retire.
- **RAA:** shared resources describe their arbitration rules and release paths.
- **MDG:** memory ordering logic exposes its dependency tokens and replay policy.
- **FCL:** buffers document their depth and the path backpressure follows.

## 6. Review checklist
Before landing a change:
1. Specs updated and cross-linked from the docs you touched.
2. Interfaces still follow the naming and handshake rules above.
3. Parameter defaults elaborate without hidden dependencies.
4. Tests or analysis notes cover epoch transitions, resource release, memory
   ordering, and flow-control limits as applicable.

When a policy becomes obsolete, update this file, the matching spec, and the
supporting practice note in the same change so the guidance stays aligned.

## 7. Directory hygiene
- Keep `modules/` directories flat by default. Introduce a vertex-named
  subdirectory only when a vertex spans multiple source files, and mirror that
  structure across `design/`, `spec/`, and `test/` in the same change.
- Document the intent for every new subdirectory so reviewers can trace why the
  vertex warranted its own folder.
