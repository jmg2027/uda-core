# UDACore design constitution

This constitution captures the standing rules for vertices, edges, and the
infrastructure that stitches them together. Treat it as a checklist before you
add or reshape modules.

## 1. Core axioms
- **The design is a directed dataflow graph.** Vertices exchange tokens over
  typed `DecoupledIO` edges.
- **Control is encoded as data.** Redirects, interrupts, and flush-like events
  are captured through epoch and metadata fields, not side-band wires.
- **Contracts first.** Specs describe each vertex and its latency. RTL follows the
  spec and stays free to evolve behind that contract.
- **Graphs come from parameters.** The raw-top graph is generated from the active
  configuration; avoid hand-written one-off wiring.

## 2. What makes a vertex?
1. **Start with the spec.** A vertex must have a CONTRACT spec definition.
2. **Confirm the implementation.** Every vertex has a matching `*Specs.scala` file and implementation.
3. **Handshake boundary.** A vertex owns at least one ready/valid edge. If a
   split does not create a new handshake, keep the logic inside the parent.
4. **Update all artefacts together.** When you add or rename a vertex, adjust the
   spec and wiring in the same change.

### Behavioural rules for vertices
- Act as a single API: the spec lists the requests, responses, and latency
  expectations.
- Keep internal logic private: state machines and helpers stay inside the
  vertex's module.
- Separate stages when future pipeline cuts are likely. If two blocks may need a
  register between them, treat them as separate vertices now.

## 3. Edge guidelines
- Payloads carry all information the consumer needs. Avoid hidden global state.
- Producers drive `valid`; consumers drive `ready`.
- Optional features may add edges, but the base graph stays intact. Document any
  conditional edges in the spec alongside the feature flag.

## 4. Hierarchy rules
- Parents may contain child graphs, but all interaction still flows through the
  child's `DecoupledIO` interface.
- State the composite latency in the parent's spec when a vertex wraps other
  vertices.

## 5. Implementation anchors
- Parameter configuration files define global settings. When you add a parameter, document its
  effect here and in the relevant spec.
- Top-level modules own vertex instantiation and wiring. Keep the graph logic
  declarative and aligned with the constitution.

## 6. Verification duties
- **Graph level:** prove there is no deadlock (for example, show the rank
  function or equivalent evidence).
- **Vertex level:** unit tests hit the declared API contract.
- **System level:** representative benchmarks such as CoreMark must run on the
  supported configurations.

Keep this constitution concise and current. If a rule no longer reflects the
living design, update the spec and this document together so contributors have a
single, trusted reference.
