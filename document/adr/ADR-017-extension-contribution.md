# ADR-017: Extension Contribution Convention (the Feature Pattern, Re-based on UDA)

Status: **accepted** (owner directive, 2026-07-06).

Depends on: ADR-004 (CSR ownership), ADR-016 (accommodation seams), ADR-015
(enforcement). Imports the lessons of the main line's `xxxFeature` pattern
(FpuFeature/ClicFeature/BitAluFeature/RvviFeature/TnpFeature) without its
mechanism.

## Context

The main line packages each optional extension as a `sealed class XxxFeature`
whose `CoreImpl/CSRImpl/DecoderImpl` objects weave IO creation, host-signal
wiring, decode-table rows, and CSR-map entries into a monolithic core, gated by
`Option.when(usingX)(XxxFeature())`. Expert review (this session) judged: the
problems it solves are real and proven - extension locality, fold-away gating,
contribution-as-data, and the F-3 lesson (a disabled extension must be ABSENT
from decode so its instructions trap, never compute silently) - but the
mechanism is aspect-weaving into a monolith. `FpuFeature.connectIo` takes 14
host-internal signals including `flush` and `stallSig`; hardware is constructed
as a side effect of method calls whose placement in the host body is
load-bearing; the feature/host seam is a Scala argument list invisible to the
spec/graph machinery; and `def xxxFeature = Option.when(...)` re-instantiates
on every mention. None of that can enter a codebase whose philosophy is
vertices, spec'd edges, and no flush.

## Decision

**D-17.1 (extensions are vertices, never weaves).** An optional extension
contributes hardware ONLY as: (a) an optional vertex (or vertex family)
instantiated conditionally in a rawTop, wired with `:<>=` over INTERFACE-spec'd
edges; and/or (b) data contributions to the two designated merge points below.
There is no `connectIo`-style function that reaches into another vertex's
internal signals. Anything an extension needs from the machine must be an edge.

**D-17.2 (decode contribution).** The decode table is assembled from per-
extension `Seq[InstPattern]` contributions, each gated by its enable parameter
at elaboration. The rule that made F-3 impossible is now a PROPERTY
(`propDisabledExtensionTraps` on DecodeUnit): a disabled extension's
instructions are ABSENT from the assembled table, so they decode to the
illegal-instruction default - silently computing a result is a specification
violation, not a quality issue.

**D-17.3 (CSR contribution).** Extension CSRs contribute `Map[Int, Csr[_]]`
entries (and optional contiguous banks) merged into the single
`CsrAccess.readFromCsr` map owned by the CSR-owning vertex (ADR-004 unchanged:
the library is mechanism, TrapController/CsrController own WHEN). No extension
instantiates its own Zicsr access path.

**D-17.4 (instantiation discipline).** Conditional elaboration uses
`Option[Params]`/`Boolean` parameters consumed ONCE at vertex-instantiation
sites (`val`, not `def` - the main line's re-instantiating `def` handle is
forbidden). Contributions are pure data (tables, maps) or Module instantiation;
never hardware constructed as a side effect of a method call whose call-site
position matters.

**D-17.5 (seams are specs).** Every extension's boundary is INTERFACE/BUNDLE
specs like any other edge, so it participates in graph reconciliation and the
ADR-015 checks. The ADR-016 capabilities (caches, TLBs, privilege modes) are
the first conforming examples; future FPU/CLIC-class extensions follow the
same shape: a functional-unit or controller vertex + decode rows + CSR map
entries.

## Consequences

- `DecodeUnitSpecs` gains `funcExtensionDecodeContribution` +
  `propDisabledExtensionTraps`; `CsrControllerSpecs` gains
  `funcCsrMapContribution`. The doctrine lives in
  `DesignRuleSpecs.rawExtensionContribution`.
- The ported CSR decorator library (`common/system/csr`) is the D-17.3
  mechanism; its `readFromCsr(csrMap, blocks)` signature already supports
  per-extension merge and contiguous banks.
- When the main line's FPU/CLIC functionality is ever wanted here, it is
  re-derived as vertices against this ADR - the Feature classes themselves are
  not portable and MUST NOT be copied.
