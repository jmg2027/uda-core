# Design principles quick checklist

Run through this page before you send a review or land a patch. Each line points
to the detailed reference that explains the rule.

| Topic | What to confirm | Where to read more |
| --- | --- | --- |
| Dependency handling | Every feedback loop has a registered cut. `ready` is never computed from `valid` directly. | [`../foundations/uda-methodology.md`](../foundations/uda-methodology.md) |
| Ready/valid wiring | Links use `DecoupledIO`. Producers drive verbs, consumers handle nouns. | [`implementation-techniques.md`](implementation-techniques.md#decoupledio-interface-design) |
| Event delivery | Epoch and tag travel in bundles instead of "flush" wires. | [`../foundations/dataflow-execution-model.md`](../foundations/dataflow-execution-model.md) |
| Spec alignment | Specs change first, RTL follows, `@LocalSpec` ties them together. | [`../process/spec-usage-guideline.md`](../process/spec-usage-guideline.md) |
| Naming | Ports end in `Out` or `In`; epoch consumers use `globalEpochIn`. | [`naming-conventions.md`](naming-conventions.md) |

Keep this sheet current-when a new hard rule appears, add it here and link the
source so future reviewers know where it came from.
