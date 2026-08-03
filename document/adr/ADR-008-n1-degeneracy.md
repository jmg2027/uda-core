# ADR-008: N=1 Degeneracy Criteria and PPA Bar

Status: **accepted** (the structural gate, the sweep, the +10%/-5% envelope) with
a **proposed** resolution of the rename-map degeneracy conflict (BL-3).

Depends on: ADR-012 (tag widths), ADR-014 (retire width in the sweep), ADR-015
(the synth/STA instrument and the grep test).

## Context

C4 says one RTL scales N=1..32 and the N=1 point is competitive with a plain
in-order core. The PPA critique (BL-3) exposed a real collision: under C3
(unified PRF, commit updates the map only, no data writeback) the arch->phys map
is NOT identity after the first instruction - it is a pointer swap - so a rename
map + free list must exist even at N=1. P01 s5's "drop the pointer storage at N=1"
is therefore architecturally impossible without a second (in-place-writeback)
commit path, which would void the single-mechanism claim. The critique also
established (MA-4 / V-MA-4) that the N=1 grep was backend-only and never named the
concrete forbidden modules.

## Decision

**D-8.1 (accepted, structural elaboration-away gate).** At `SpeculativeRegNum=1`
the emitted netlist MUST contain ZERO instances of: the wakeup CAM / RS match
matrix, the free-list allocation encoder, a multi-entry map CAM, the publish
arbiter tree beyond a single producer, the store-buffer byte-mask forwarding CAM,
a multi-entry issue queue beyond the irreducible skid depth, and the load
outstanding table; and MUST reduce `seqTag`/`physRegId` to their N=1 widths
(ADR-012). This EXTENDS P05 D5 to the memory subsystem and frontend (critique
MA-4). The forbidden-structure list is a single canonical artifact owned here
(a table in the N=1 acceptance spec), named with the concrete emitted-Verilog
module/cell identifiers; ADR-001/P01 and ADR-015/P06 `.uses` it rather than
restating it (closes critique V-MA-4's three-divergent-lists finding).

**D-8.2 (proposed, rename-map degeneracy).** The "zero pointer map at N=1" clause
of P05 D5 is RELAXED. Interim ruling: the N=1 netlist MAY carry a degenerate
**direct-indexed** map (32 entries x physRegIdWidth) + a single free bit, because
map-only commit (C3) genuinely requires a pointer even at N=1. The forbidden list
(D-8.1) forbids the CAM and the multi-entry free-list encoder, NOT the degenerate
direct-indexed map. The +10% DFF envelope (D-8.3) is sized to absorb this
(~32 x 6 = ~192 map flops + 1 free bit). The harder alternative - an `if(N==1)`
in-place-writeback commit path (a second commit mechanism) - is REJECTED unless
the N=1 PPA bar (D-8.3) fails, which the sweep decides. This resolves BL-3 without
voiding the single-mechanism identity.

**D-8.3 (accepted, PPA bar).** Reference baseline: the shipped `main` core with
its 64-entry TNP CSR bank (219,129 um^2 of 532,234) and RVVI stripped, re-measured
in the same yosys 0.33 + sky130 HD OOC flow (~310-330k um^2, ~8,000 DFF, ~46 MHz).
The N=1 elaboration of the rebuild MUST be: area within +10%, DFF within +10%,
OOC worst-slack frequency within -5%, AND pass the D-8.1 structural grep (a hard
gate independent of the percentages). Include the M-extension (mul/div, ~60k um^2)
in the reference so the percentage is tight. The irreducible N=1 frontend floor
(the issue-queue skid buffer, P03 s4.7) is stated explicitly and included in the
reference envelope so it is not double-counted as tax (critique MA-4).

## Alternatives rejected

- **No bar / "trust the parameter."** Rejected: the untested claim the review
  distrusts.
- **Area-only bar.** Rejected: an OoO netlist can hit area parity while missing
  frequency (wakeup/broadcast cones) or retaining a CAM as dark logic; the
  structural hard-gate and the freq clause close both loopholes.
- **"Within parity" (0%) bar.** Rejected as the interim: it likely forces the map
  to a literal direct-index mux with extra elaboration branching; +10% is the
  honest generality margin. Carried as OQ-C for the owner.
- **In-place-writeback commit path at N=1 (BL-3 Option B).** Rejected: a second
  commit mechanism voids single-RTL and needs its own N-equivalence proof. Only
  reconsidered if D-8.3 fails.

## Consequences

- N=1 carries a small structural map/free tax over a pure scoreboard core; the
  +10% envelope covers it. This is the accepted honest cost of unified-PRF
  generality.
- The grep test's forbidden list now spans backend + memory + frontend, so a
  regression that carries a forwarding CAM or a deep issue queue into N=1 fails the
  gate (it previously would have passed).

## Verification obligations

- Synth-CI gate: N=1-vs-reference experiment; a regression past +10% area/DFF or
  a structural-grep hit is a build failure. The grep pattern is itself a tested
  artifact: a deliberately-broken N=1 build that keeps a CAM MUST trip the gate
  (critique V-MA-4).
- N-sweep curves (area/DFF/cells/slack at N in {1,2,4,8,16,32}) are a committed,
  regenerated artifact; monotone non-decreasing area is a sanity check; retire
  width (ADR-014) and any superscalar PRF ports are attributed to N so the curve
  is explainable.

## Experiment (settles D-8.2 and D-7.4)

Port the main synth/STA flow (`verif/suites/EmitCore`, yosys+sky130 OOC,
`verif/bin/sta.sh`). Steps: (1) N-sweep area/freq curves; (2) N=1-vs-reference
percentages + structural grep (the pass/fail gate that decides D-8.2 interim vs
in-place-writeback); (3) epoch-broadcast STA feeding ADR-006; (4) wakeup-select
STA feeding ADR-007 D-7.4 (`maxWindowAtFreq(F)`).
