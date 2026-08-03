# Unified Dataflow Architecture methodology

This note explains how to apply the Unified Dataflow Architecture (UDA) when you
extend or review UDACore. Pair it with the spec files for the vertices you are
touching; the specs remain the single source of truth.

## 1. Core ideas
- **Everything is a vertex and a channel.** Break the design into focused
  vertices that exchange tokens over `DecoupledIO` links.
- **Control rides with the data.** Epochs, redirects, and traps show up as
  metadata on the tokens so recovery becomes a comparison, not a side channel.
- **Latency insensitivity by default.** Assume every channel may stall. Remove a
  stall only when the owning spec explains why the receiver can never block.
- **Intentional cycle breaking.** Relay stations or queues appear only when a
  strongly connected component would otherwise form. Track each relay as design
  debt until the timing data proves it unnecessary.

## 2. Daily workflow
1. **Start with the specs.** Draft or update the spec entries before touching
   RTL. Note which bundles and epochs the change affects.
2. **Sketch the graph.** List the vertices and edges involved. Confirm that each
   edge is still ready/valid and that the metadata matches the spec.
3. **Map control to data.** Check that redirects, exceptions, and interrupts only
   use the epoch machinery and that wrong-path work is filtered by epoch
   comparisons.
4. **Add or remove relays deliberately.** When you add a buffer for timing, write
   down why it exists and when it can be removed. When removing one, confirm the
   specs no longer promise the extra latency.
5. **Verify the flow.** Tests should cover epoch transitions, credit exhaustion,
   and dependency replay. Capture the command you ran and keep it with the
   change record for future reference.

## 3. Pattern reminders
- **Rename -> issue -> vGPR loop.** These vertices act together; never change one
  without checking the other two specs.
- **Store buffer guards.** Ensure enqueue, dequeue, and commit guards honour the
  epoch field and the dependency tokens.
- **Parameter propagation.** Use three layers: global config, domain parameters,
  then per-vertex arguments. Defaults such as `txnIdWidth = 1` must elaborate
  without extra wiring.
- **Ready/valid discipline.** `valid` never depends on downstream `ready`. If a
  channel is permanently ready, justify it with a named invariant instead of
  hard-wiring `true.B`.

## 4. Keeping the docs honest
- Link every new technique back to the spec file that governs it.
- Move transient checklists and audit notes into a working log; keep this file
  for stable guidance only.
- When you update terminology (for example, replacing "flush" with "redirect"),
  sweep the affected docs so the language stays consistent.

UDA is a workflow, not a marketing slogan. If a change does not trace back to a
spec, or if the documentation claims behaviour that the RTL cannot prove,
revisit the design until the graph, specs, and prose match.
