# Parameter architecture guide

Use this guide to keep parameter handling predictable across the project.

## 1. Separation of concerns
- Chipyard or TestHarness code sets the global defaults.
- Each domain (`frontend`, `backend`, `memory`, etc.) resolves those defaults once
  into a local bundle defined under `design/shared/`.
- Export only the contract-safe fields through `design/api/` for parents to
  consume.

## 2. Tiering model
| Tier | Audience | Purpose | Universal Pattern |
| --- | --- | --- | --- |
| Contract | Parent integrators | Interface agreements | Width specifications, protocol requirements |
| Tuning | Parent + subsystem leads | Performance optimization | Buffer sizes, algorithm parameters |
| Private | Subsystem owners | Implementation details | Internal flags, debug features |

**Universal application:** This tiering pattern applies to all domains - each 
subsystem (whether frontend, backend, memory, or future domains) implements 
this same three-tier structure within their parameter namespace. The specific 
parameters vary by domain, but the organizational pattern remains constant.

Declare the tier in the spec before exposing it in Scala. Contract items belong
in `CONTRACT` blocks, tuning knobs in tagged `PARAMETER` entries, and private
notes in `RAW` sections that stay local to the subsystem.

## 3. Implementation tips
- **Domain-specific parameter bundles:** Build helper case classes for each domain
  inside `shared/`. The pattern is `<Domain>Params` with builder methods that
  reflect domain concerns - for example, cache-related domains would have
  `withCacheBlockBytes`, prediction domains might have `tunePredictorSize`.
- **Contract/Tuning separation:** Keep contract setters mandatory while tuning 
  overrides use `Option` types so callers can omit domain-specific optimizations.
  This pattern enables domains to provide sensible defaults while preserving
  integration flexibility.
- **API boundary enforcement:** Never leak private fields outside the domain;
  expose resolved views instead. This maintains clean abstractions regardless
  of internal implementation complexity.

## 4. Review checklist
1. Spec updated with the new tier and cross-linked via `@LocalSpec`.
2. `design/shared/` mirrors the spec layout and resolves the same values.
3. `design/api/` exports only the documented contract/tuning fields.
4. Tests cover the new parameter combinations or presets.

If a parameter no longer belongs in the public contract, move it down a tier and
update both the spec and this file in the same change.
