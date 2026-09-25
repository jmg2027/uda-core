# ADR-010: Commit-Stream Verification Port

Status: **accepted**.
Amended for the ADR-019 machine by ADR-019B E-5 (trap-entry tokens are observation events, not
retirements) and E-3/E-4 (commit PRF read path, executing-privilege priv).

Depends on: ADR-002 (commit is the authoritative order point), ADR-012 (retire
token uses the canonical seqTag; `order` is a separate verif counter), ADR-015
(N-equivalence uses this stream).

## Context

The main harness grades a run by capturing stores plus a retired-instruction
stream (RVVI). The rebuild must expose an equivalent retire stream at CommitUnit -
the one in-order serialization point and the only place the architectural map is
authoritative. Bolting a trace port on later means re-deriving program order from
a dataflow graph, the exact bolt-on failure to avoid. This port and the
`SpeculativeRegNum` config axis (ADR-015) are the two hooks the entire
verification ladder hangs from and MUST be in the contracts before the backend
shells are filled.

## Decision

**D-10.1 (normative, retire stream).** CommitUnit gains `intfRetireStreamOut`
(`rawReadyValidIntf`, bundle `bndRetireToken`), one token per in-order
retirement, fields: `order` (64b monotone RVVI sequence), `pc`, `insn`
(RVC-expanded), `rd`, `wdata` (from a commit-time PRF read), `wen`, `trap`,
`cause`, `epoch` (cross-check only). Emitted only for tokens that actually retire
(epoch-matched, in order). Payload carries `wdata` (self-contained, ISS-comparable)
per P06 recommendation.

**D-10.2 (normative, observation-only).** The retire stream is observation-only:
its ready is tied high in the harness and commit progress is independent of it.
PROPERTY `propRetireNonBlocking`: `assert(!io.retireStreamOut.valid ||
io.retireStreamOut.ready)`.

**D-10.3 (normative, gated by `usingRvvi`).** The port, the commit-time `wdata`
PRF read, AND the 64b `order` counter itself are all inside an
`if(usingRvvi)` elaboration branch (default false). Production folds the entire
retire path away, INCLUDING the counter (critique MI-2/V-MI-5: a 64b monotone
counter surviving at N=1 production is a failure and is in the ADR-008 forbidden
grep list). `usingRvvi` is a `CoreParams` verification knob (WP-D).

**D-10.4 (normative, N-equivalence compare set).** The token-for-token
N-equivalence diff (ADR-015 rung 4) compares `order/pc/rd/wdata/trap/cause`.
`epoch` is EXCLUDED from the compare - it legitimately differs between N=1 and N=8
(different redirect counts reach the same instruction); it is a same-run
cross-check field only (critique V-MI-6). Trap ENTRY is a retire token with
`trap=1` (settling P06 O1.2), so the golden model's trap accounting includes it.

## Alternatives rejected

- **Full RVVI Vec-of-regfile bundle (main's `RvviBundle`).** Rejected as the
  standing contract: `Vec(regNum,xLen)` + `Vec(4096,xLen)` CSR is ~16.6% area when
  on - a fine debug mode, a bad contract to build around. Lean per-token stream is
  the contract; full snapshot is an optional `usingRvviFull` debug elaboration.
- **Reconstruct order in the harness from PublishMux/PRF write bus.** Rejected:
  the publish bus is out-of-order and speculative; reconstructing order there
  re-implements CommitUnit in the testbench and disagrees at N>1.
- **Grade on stores only.** Rejected as sole mechanism: cannot see register-only
  computation, cannot distinguish a correctly-squashed wrong-path store from a
  missing store, gives N-equivalence no per-instruction anchor. Stores stay a
  coarse check; the retire stream is the fine one.

## Consequences

- N=1: ~zero with `usingRvvi=false`; identical to main's production baseline.
- N=32 verif build: one extra PRF read-port group per commit lane (bounded by
  retire width, ADR-014, not window size) and a commitWidth-wide retire fan. Paid
  only in verif configs. The `propPrfPortsVsN` property (P01) is restated: read
  ports = issue-width + commitWidth-when-usingRvvi (closes critique m1).

## Verification obligations

- `propRetireNonBlocking` (design assert) - the port never stalls commit.
- `RetireStreamMonotone` runtime assert: `order` strictly increments by the number
  of lanes that fired; no gaps, no reorder.
- Golden binding test: for every scn, `retire.pc/rd/wdata` matches the ISS golden
  (ADR-015) token-for-token; this is the acceptance test for the port itself.
- Elaboration: at `usingRvvi=false` the N=1 netlist contains no `order` counter and
  no commit-time `wdata` read port (part of the ADR-008 grep).
