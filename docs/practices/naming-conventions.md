# Naming conventions

Follow these rules so specs, design files, and documentation stay in sync.

## 1. Directory layout
```
src/
|--- design/    # RTL
`--- spec/      # Specs that mirror design/
```
- Every domain maintains matching `design/` and `spec/` trees.
- Within a domain, organise files into `top/`, `modules/`, `shared/`, and `api/`
  on both sides.
- Define shared bundles in `shared/` first. Copy the contract-safe subset into
  `api/` only when another domain needs it. Duplicate top-to-top interfaces so
  each top controls its own version.

## 2. File names
- Design file `Foo.scala` pairs with `FooSpecs.scala` in the spec tree.
- Spec definitions live inside an object named `FooSpecs`.
- Interface files in `api/` follow the same rule.

## 3. Spec identifiers (Spec IDs)
"Spec ID" refers to the literal string passed to any DSL constructor such as
`CONTRACT`, `INTERFACE`, `BUNDLE`, or `RAW`. Treat these identifiers as the
canonical names that appear in diagrams, documentation, and generated artefacts.

**Ground rules**

- Use PascalCase without prefixes or suffixes. Use descriptive names that
  clearly identify the vertex's role and responsibility.
- **Acronym/Abbreviation Rule**: Treat acronyms and abbreviations as words in PascalCase
  - `ALU` → `Alu`, `CSR` → `Csr`, `GPR` → `Gpr`, `UDACore` → `Klase32`  
  - `BitALU` → `BitAlu`, `CSRController` → `CsrController`
  - Examples: `AddressGenerationUnit`, `VirtualGpr`, `CsrController`
- Apply the same rule across *every* domain (frontend, backend, memory, core,
  ...). The spec tree is global and should not contain subsystem-specific naming
  dialects.
- When renaming a spec ID, update all design references in the same
  patch so they remain consistent.

### 3.1 Vertex-driven contracts
- Every contract identifier defines the canonical vertex name (for example
  `BackendTop`, `MemorySubsystemTop`, `AddressGenerationUnit`).
- Each vertex owns exactly one contract. Hidden helper modules stay documented
  inside the owning vertex and do **not** create extra contracts.

### 3.2 Edge-driven bundles
- Bundle spec IDs define the canonical edge names. One bundle definition
  can serve both ends of the edge.
- Share bundles in `shared/` and re-export through `api/` only when a parent top
  requires the type. Never introduce variant names for the same edge.

### 3.3 Interface identifiers
- Interface spec IDs derive directly from their bundle spec ID:
  `<BundleSpecId>In` for sinks and `<BundleSpecId>Out` for sources.
- Interfaces advertise the bundle they carry and list their handshake raw spec
  (for example `rawReadyValidIntf`).
- Avoid appending the owning contract name to the bundle or interface identifier.
  The spec DSL already scopes each edge to a single pair of vertices, so
  repeating the vertex name just adds noise without improving traceability. Use
  the edge name alone unless two distinct edges genuinely share the same word
  in the spec—in that rare case, rename the edges first so the bundles stay
  unique.

### 3.4 Shared categories
- `RAW`, `PROPERTY`, `FUNCTION`, and any other DSL categories follow the same
  PascalCase spec ID rule. Derive the identifier from the concept being defined
  (`RawTop`, `ReadyValidInterface`, ...). Avoid legacy uppercase-with-underscore
  forms.
- If the DSL requires a secondary grouping token, keep it aligned with the spec
  ID so tools emit consistent documentation.

## 6. Scala identifiers in specs
- Use lowerCamelCase for Scala vals: `contBackendTop`, `propEpochFilter`.
- Use descriptive doc strings so design owners can trace intent back to the
  graph.

## 7. `@LocalSpec`
- Tag every implementation with the matching spec object. Avoid string literals
  or ad-hoc tags.

When a rule changes, update this file and the affected specs in the same patch so
reviewers always see the current policy.
