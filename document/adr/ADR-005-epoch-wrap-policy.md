# ADR-005: Epoch Wrap Policy

Status: **accepted**.

Depends on: ADR-011 (redirect at commit head). Constrains ADR-003 (store buffer),
ADR-009 (issue queue, slot-slicer carry, fetch outstanding).

## Context

`GlobalEpochUnit` increments a `RegInit(0.U(epochWidth.W))` on every
`redirectFire`, default `epochWidth=2` (`CoreParams.scala:33`). A token that
latched epoch E and is checked only at consumption aliases back to a live epoch
after `2^epochWidth` redirects and is wrongly accepted.

Three papers proposed three incompatible fixes (critique M1/BL-2): P01 s6/O8
(stall redirect issue), P05 D2 (eager filter, no stall), P03 G5
(`prefetchDepth <= 2^epochWidth-1`). Worse, P05's eager-filter safety proof
(`maxSurvivableGenerations=1`) is stated as universal but is violated by P03's
issue queue (drops at dequeue, not every cycle) and relies on an unenumerated
exemption for P02's committed store-buffer entries.

## Decision

**D-5.1 (normative, one mechanism = eager filtering).** Adopt eager per-cycle
filtering as the single wrap mechanism. Every element that STORES an epoch-tagged
token and USES the epoch for a correctness compare - RS entry, FU request latch,
edge register, fetch outstanding latch, issue-queue entry, slot-slicer carry,
speculative store-buffer entry - MUST evaluate `token.epoch === globalEpoch`
combinationally EVERY cycle it holds the token and self-invalidate on mismatch. It
MUST NOT defer the compare to consumption. Under this invariant a token dies at
the first redirect after it becomes wrong-path, so it never survives even one
wrap. P01's redirect stall and P03's prefetch bound are **withdrawn** as redundant.

**D-5.2 (normative, enumerate every epoch-holding vertex).** The critique's
central demand: `maxSurvivableGenerations` is not assumed, it is COMPUTED over an
enumeration. Each epoch-holding vertex is classified exactly one of:

- **eager-filter** (self-invalidates each cycle): RS entries, FU latches, edge
  registers, fetch outstanding latch, issue-queue entries, slot-slicer carry,
  speculative store-buffer entries. Contributes `1` to the bound.
- **epoch-exempt-by-construction** (holds an epoch but NEVER uses it for a
  correctness compare): committed store-buffer entries (ADR-003 D-3.5 - they are
  irrevocable and drain regardless of epoch), and any purely-observational field
  (e.g. the retire-token `epoch` cross-check field, ADR-010). Contributes `0`.

`maxSurvivableGenerations = max over eager-filter vertices of (cycles a token can
be held while its compare is deferred)`. Under D-5.1 this is `1`. The enumeration
is a committed artifact (a table in `DesignRuleSpecs.scala`, WP-D); adding a
vertex that defers its compare raises the number and forces `epochWidth` up.

**D-5.3 (normative, the require).**
`require((1 << epochWidth) > maxSurvivableGenerations + 1)`. With
`maxSurvivableGenerations=1` this gives `epochWidth >= 2`; the default satisfies
it with one generation of margin. Exact-match compare (`epoch === globalEpoch`),
NOT distance - eager filtering kills at the first mismatch, so tokens never carry
a legitimately "recent but not current" epoch, and a subtractor on the
fan-out-heavy compare would be dead cost.

## Alternatives rejected

- **Stall redirect issue (P01 O8).** Rejected: puts a stall on the mispredict
  path (the most IPC-sensitive event) and contradicts kill-everything - a redirect
  must fire immediately.
- **Widen epoch to cover the worst FU latency (~6 bits) with distance compare.**
  Rejected as primary: taxes N=1 with a wide tag and a subtractor for a hazard
  eager filtering removes for free. It survives only as the fallback the `require`
  selects if the eager invariant is ever broken (e.g. multi-context epochs,
  ADR-011 D-11.4).
- **Prefetch-depth bound (P03 G5).** Redundant under eager filtering; withdrawn.

## Consequences

- N=1: zero over today; `epochWidth=2` unchanged; the eager compare is a 2-bit
  equality already implied by "buffers check before they act."
- N=32: the eager compare fans `globalEpoch` to every enumerated vertex - this is
  exactly the broadcast load ADR-006 prices. `epochWidth` stays 2, so the tag is
  2 bits regardless of window.
- The committed store-buffer exemption (D-5.2) is the one place a token legally
  outlives many redirects; it is safe precisely because those entries never
  compare epoch. This resolves critique BL-2.

## Verification obligations

- `require((1<<epochWidth) > maxSurvivableGenerations+1)` at elaboration
  (structural - this one IS an elaboration require, ADR-015 typing).
- The enumeration table in `DesignRuleSpecs.scala` is reviewed against the design;
  a machine check (ADR-015) that every `Reg`/`Queue` holding an epoch is tagged
  either eager-filter or exempt.
- Directed test: a divide in flight across `>= 2^epochWidth` back-to-back
  redirects; assert its result is dropped, not committed (the exact wrap-alias
  scenario), passing at `epochWidth=2`.
- Runtime monitor at every consumption: `assert(!fire || token.epoch ===
  globalEpoch)`.
- Issue-queue eager-filter test (closes critique M1): a deep-queue entry stamped
  epoch E across `2^epochWidth` redirects is dropped, never issued.
