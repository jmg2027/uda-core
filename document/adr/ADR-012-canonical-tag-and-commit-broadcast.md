# ADR-012: Canonical In-Flight Tag and Unified Commit Broadcast

Status: **accepted**.

Depends on: ADR-002 (CommitUnit owns the broadcast), ADR-003 (store buffer horizon),
ADR-004 (commitGrant). Owned by the backend (P01 is the canonical author).

## Context

The correctness and PPA critiques (B3, M4, MA-3, V-MA-5) converge on one hole:
four papers spawn four commit-notification bundles keyed on different fields
(`seq` in P02, `uopId` in P04, map/free in P01, `order` in P06) for ONE in-order
commit event; and P05 proposes to merge `uopId==seq` into a small wrapping counter
whose width law (`maxInFlight`) P01 declines to pin. The store buffer holds
committed-but-undrained entries AFTER the backend has recycled their seq to a new
uop, so a naive tag can alias across the store-buffer horizon and commit the wrong
store / wrong CSR / lost forward. This is a correctness seam, not a PPA nit.

## Decision

**D-12.1 (normative, one canonical identifier).** There is ONE canonical in-flight
identifier, `seqTag`, of width `seqWidth = log2Ceil(maxInFlight)`. `uopId` and
`seq` are UNIFIED into `seqTag`; the design bundles' separate 32-bit `uopId`/`seq`
fields (`BackendBundles.scala:86,91,97,102,145,148,155,159`) are replaced. The
verif-only `order` (ADR-010) stays a separate 64b counter and is NOT a hardware
key. Physical register id is `physRegIdWidth = log2Ceil(archRegNum + N)` (N=1 -> 6,
N=32 -> 7). Neither is ever a fixed 32.

**D-12.2 (normative, `maxInFlight` closed form - the missing law).** The backend
pins `maxInFlight` as the maximum number of simultaneously LIVE seqTags, computed
over EVERY horizon that keys on it, not just the rename window:

```
maxInFlight = allocFifoDepth            // backend in-flight window (ADR-002)
            + storeBufferDepth          // committed-but-undrained stores (ADR-003 D-3.5)
            + serializingStageDepth      // staged CSR/system writes (ADR-004)
```

`seqTag` MUST NOT be reused until every horizon has released it (in particular a
store's seqTag is not recycled until the store buffer has drained that entry).
This is the tag-uniqueness PROPERTY the critique demands (B3): the tag width covers
every live token simultaneously, including committed store-buffer entries and
staged CSR writes. It closes the P05<->P01 circular dependency (MA-3): P01 owns
this formula; P05 D4's width-shrink and the P02/P04 cross-domain keys then freeze
against it.

**D-12.3 (normative, wrap-aware age compare).** Because `seqTag` is a small
wrapping counter, all age comparisons (`entry.seqTag < load.seqTag` for
forwarding, ADR-003 D-3.9) are MODULAR relative to the bounded live range
[oldest-live, newest-live], never a naive unsigned `<`. A helper
`seqOlder(a, b, oldestLive)` defines the order; forwarding and any age logic use
it.

**D-12.4 (normative, one unified commit broadcast).** The single in-order commit
event produces ONE `CommitBroadcast` bundle, owned and driven by CommitUnit,
keyed by `seqTag`, fanned to all consumers as field-projected views:

- StoreCommit view `{seqTag, epoch}` -> StoreBuffer (ADR-003 D-3.3).
- commitGrant view `{seqTag, epoch, valid}` -> CSR (ADR-004 D-4.1).
- map-update + physical-free view -> RenameUnit free list (ADR-001/002).
- retire-token trigger -> retire stream (ADR-010, verif-gated).

All consumers key on the SAME `seqTag`, so the store and CSR paths cannot disagree
about which uop retired (closes M4/V-MA-5).

Spec home: `BackendBundlesSpecs.scala` (bundle) and `BackendParamsSpecs.scala`
(width derivations), both WP-A. `maxInFlight` references `storeBufferDepth`
(memory, ADR-003) and the serializing-stage depth (CSR, ADR-004) as read-only
inputs via the `api/` params - WP-A defines the formula, WP-B/WP-D expose their
depths through `api/` without editing `BackendParamsSpecs.scala`.

## Alternatives rejected

- **Keep 32-bit tags for simplicity.** Rejected: the exact fixed tax the review
  names; lands in every RS entry and edge register and regresses the N=1 bar.
- **`maxInFlight = allocFifoDepth` only (P05's framing).** Rejected: aliases
  across the store-buffer horizon (B3) - a `StoreCommit` could match the wrong
  entry after seq recycling.
- **Keep `seq` and `uopId` distinct.** Rejected: two keys for one event invite a
  wrong-field match (V-MA-5); a single-issue machine's uop identity and program
  order are the same rolling counter.

## Consequences

- N=1: tags shrink from 64 bits (uopId+seq) to `seqWidth`+`physRegIdWidth` ~ small;
  provably-dead `ready` trees (P05 D4 always-ready audit) elaborate away with named
  invariants. Net negative area.
- N=32: `seqWidth ~ log2(allocFifoDepth+storeBufferDepth+serializingStages)`,
  `physRegIdWidth`=7; sized to the true horizon, no over-provision.
- If ADR-011 D-11.4 (multi-context epochs) or ADR-014 multi-lane retire is adopted,
  `maxInFlight` and `seqWidth` are recomputed from the new horizons; the formula,
  not the constant, is the contract.

## Verification obligations

- Elaboration: `seqWidth`/`physRegIdWidth` derive from N and the horizon depths; a
  sweep N in {1,8,32} yields monotone non-decreasing widths, never 32 (part of the
  ADR-008 grep - a 32-bit seq/uopId at N=1 is a failure).
- PROPERTY `propTagUniqueness` (design assert): no two live tokens share a seqTag,
  across backend + store-buffer + CSR-stage horizons.
- Cross-domain key test (V-MA-5): every accepted CommitBroadcast marks exactly one
  store-buffer entry / one CSR staged write, and that entry's seqTag equals the
  commit head's.
- Forwarding wrap test: a store and a younger load straddling the seqTag wrap
  boundary forward correctly under the modular compare.
