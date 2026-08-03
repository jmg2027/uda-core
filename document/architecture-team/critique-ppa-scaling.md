# Adversarial Critique: PPA and the N=1..32 Scaling Claim

Reviewer lens: PPA / elasticity / the "one RTL scales N=1 in-order to N=32 OoO"
claim. Mandate: find every proposal whose N=1 cost is not actually zero, whose
N=32 story breaks (single global epoch, publish-bus fan-in, port counts), or
whose cycle budgets are wishful. Read against all six position papers (01..06)
and the tree.

Grounding facts re-verified in the repo for this critique:

- Single global epoch, combinational same-cycle exposure:
  `GlobalEpochUnit.scala` (`epochOut := Mux(redirectFire, epochIncrement, epoch)`),
  `epochWidth = 2` default (`CoreParams.scala:33`).
- PublishMux is ONE arbitrated write bus, not per-producer ports:
  `PublishMuxSpecs.scala:11-13` ("arbitrates FU results, writes physical register
  file, broadcasts wakeup"), 7 FU inputs + memory (`PublishMuxSpecs.scala:36-78`).
- Unified PRF, 33+N entries, mapping owned by RenameUnit, no arch/phys split:
  `PhysicalRegisterFileSpecs.scala:27-33`.
- Tags hardcoded 32-bit: `uopId`/`seq` at `BackendBundles.scala:86,91,97,102,145,
  148,155,159`.

Severity key: BLOCKER = a contract that is internally impossible or breaks a
sister paper's contract; must be resolved before any shell RTL. MAJOR = a
quantitative claim (cost, budget, port count) that is false or unpriced. MINOR =
a gap or a cross-paper inconsistency that needs an owner but does not sink a
contract.

---

## BLOCKERS

### BL-1. Selective memory-ordering replay is impossible under one global epoch

- Papers: 02 (M2 rule 4, "Replay is selective"; M1.5) vs 01 (Sec 1.3 / O1) vs the
  RTL (`GlobalEpochUnit.scala`, one counter).
- Flaw: Paper 02 M2 rule 4 specifies that a late-arriving store address triggers a
  memory-ordering mispredict by which "the StoreBuffer ... emits a memory-ordering
  mispredict that advances the epoch for that load's tag ... ONLY that load and its
  dependents replay, not the pipeline." There is exactly ONE global epoch. Advancing
  it is kill-everything: every token younger than the redirect point dies, not "that
  load and its dependents." There is no per-tag or per-region epoch in the
  architecture. Paper 01 O1 explicitly leaves selective (branch-tag) squash as an
  open question and defaults to "global-epoch kill-everything is the permanent
  contract." So paper 02's central N>1 load-speculation mechanism has no substrate.
  Either memory-order replay is a FULL squash of all younger work (a large,
  unpriced IPC event that grows with N, exactly the wrong scaling), or the machine
  needs branch-tag / regional epochs, which destroys the single-epoch simplicity and
  the `epochWidth=2` story that papers 05 and 06 rest on.
- Fix / question the panel must answer: Settle O1 BEFORE freezing the memory and
  backend contracts. If the answer is "one global epoch forever," paper 02 M2 rule 4
  must be rewritten as a full squash and its N=32 IPC cost quantified (mispredict on
  a memory-order violation flushes the whole window). If selective replay is
  required for the N=32 target, the panel must adopt a multi-context epoch scheme and
  re-price the tag width, the compare fan-out, and paper 05 Decision 2/3 in full.

### BL-2. Three mutually inconsistent epoch-wrap fixes; the invariant the cheap one relies on is already violated

- Papers: 05 (Sec 2: eager filter, keep `epochWidth=2`, exact-match, EXPLICITLY
  rejects redirect stall) vs 01 (Sec 6 / O8: bounded-generations = stall redirect
  issue) vs 03 (G5: `prefetchDepth <= 2^epochWidth-1` require) vs 06 (Rung 2
  `covEpochWrap`, tests only).
- Flaw: These are three different contracts for the same hazard, and two of them
  contradict each other head-on. Paper 01's preferred fix (O8: "stall redirect issue
  when the oldest in-flight epoch is `2^epochWidth-1` generations behind") is
  precisely the mechanism paper 05 Sec 2.4 rejects ("puts a stall on the mispredict
  path ... contradicts kill-everything"). Worse, paper 05's eager-filter invariant --
  the sole reason `epochWidth=2` is claimed safe (`maxSurvivableGenerations = 1`) --
  requires that EVERY epoch-holding element compares `epoch === globalEpoch` every
  cycle and self-invalidates. Paper 02 M1 rule 5 defines committed store-buffer
  entries that "SHALL survive any epoch change" and never self-invalidate: a token
  that holds an epoch but defers/omits the compare. Paper 05 Sec 2.2 says such a
  "deferring vertex raises `maxSurvivableGenerations` to its latency and forces a
  wider epoch." A committed store can sit in the buffer for many redirects while it
  drains, so the eager-filter bound does not hold subsystem-wide, and `epochWidth=2`
  is not proven safe. (It is arguably fine because committed entries never USE the
  epoch for a correctness compare -- but paper 05's invariant is stated as universal
  and its `require((1<<epochWidth) > maxSurvivableGenerations+1)` will not see the
  store-buffer exemption unless every such vertex is enumerated.)
- Fix / question: Pick ONE wrap mechanism (recommend eager-filter, no redirect
  stall, per 05) and make paper 01 O8 withdraw the stall variant. Then enumerate
  EVERY epoch-holding vertex (RS entry, FU latch, fetch outstanding latch, slot-slicer
  carry, issue queue, store buffer committed and speculative entries) and prove each
  either self-invalidates each cycle OR is epoch-exempt-by-construction (never
  compares). The `require` in 05 Sec 2.2 must be fed the true `maxSurvivableGenerations`
  computed over that enumeration, not the assumed `1`.

### BL-3. The unified-PRF rename map does NOT elaborate to zero at N=1 -- one-RTL vs C3 collide

- Papers: 01 (Sec 1.4 cost, Sec 5 degeneracy table, `propN1Degenerate`) and 05
  (Decision 5.2 structural hard-gate: "netlist contains ZERO ... map-CAM ... pointer
  map at N=1") vs C3 (`PhysicalRegisterFileSpecs.scala:27-33`, "commit updates the
  map only, no data writeback").
- Flaw: Paper 01 claims that at N=1 "the speculative map equals the architectural
  map at all times ... elaborate the map as a direct arch->fixed-phys identity, drop
  the pointer storage." This is architecturally impossible under C3. Map-only commit
  means an instruction's result lives in a freshly allocated physical register and
  commit makes the architectural register POINT at it -- a pointer swap. After the
  first instruction the arch->phys map is no longer identity; it must be stored
  (32 entries x log2(33+N) bits) plus a free list, even at N=1. You cannot
  simultaneously (a) avoid data writeback (C3) and (b) drop the pointer map. The only
  way to "drop the pointer storage" at N=1 is to write results in place into a fixed
  register file -- i.e. a DIFFERENT commit mechanism (data writeback) than N>1 uses,
  which breaks the "one RTL, one parameter" identity. So either the N=1 netlist
  carries a rename map + free list that a plain in-order scoreboard core does not
  (a real several-percent DFF tax: ~192 map flops + free-list + alloc encoder on top
  of the RF, against paper 05's +10% DFF bar), failing paper 05's structural
  hard-gate ("zero pointer map"); or the architecture must sanction a writeback-commit
  degeneracy at N=1, which is a second commit path and voids the single-mechanism
  claim.
- Fix / question: The panel must choose. Option A: accept a persistent map at N=1,
  delete the "drop pointer storage" claim from paper 01 Sec 5, and RELAX paper 05
  Decision 5.2 to permit a degenerate direct-indexed map (and re-measure the N=1 bar
  including it). Option B: sanction an `if (N==1)` in-place-writeback commit path,
  and accept that commit is NOT one mechanism across N (document it and add an
  N-equivalence test that both paths retire identically). Silent "the map just folds
  away" is false.

---

## MAJORS

### MA-1. PublishMux is a single arbitrated write port -> paper 05's always-ready proof is false, and N=32 peak IPC is capped at 1 and unpriced

- Papers: 05 (Decision 4.3, table row "PublishMux -> PRF write (per producer): YES
  [always-ready], iff one write port per result producer") vs 01 (O4, "single vs
  multi result-lane ... unresolved") vs RTL (`PublishMuxSpecs.scala:11-13`, one
  arbitrated write bus for 7 FUs + mem).
- Flaw: The tree already commits to a single arbitrated result bus. There is NOT one
  write port per producer, so paper 05's stated pre-condition for declaring the
  publish->PRF-write edge "always-ready" is false: producers structurally contend for
  the one write port, the edge CAN back-pressure, and the "elaborated-away ready tree"
  is not sound. More consequentially for the scaling claim: a single publish/commit
  bus caps PEAK throughput at 1 result/cycle at EVERY N. So N=32 buys deeper latency
  hiding but its peak IPC ceiling is identical to N=8's and N=1's. The area, however,
  grows with N (32-entry wakeup CAM x 8 producer lanes, 65-entry PRF, 32-wide select).
  No paper states peak-IPC(N). The reader is left believing "wide OoO" delivers ILP it
  cannot deliver through a 1-wide publish bus. This is the core unpriced N=32 story.
- Fix / question: Resolve 01 O4 now. If single-bus, add a normative
  `peakIssue = peakPublish = 1` statement and re-frame the N=32 value as
  MLP/latency-hiding only, not IPC>1; then justify 32-entry structures against a
  1-wide drain (is a 32-deep window even reachable behind a 1/cycle publish?). If
  multi-lane is intended, paper 05 Decision 4.3 must size PRF write ports = lanes and
  drop the always-ready claim for that edge, and paper 01 Sec 3.3 CAM width and PRF
  read/write ports must be re-derived.

### MA-2. edgeWakeupSelect budget = 0 forever, on top of "whole backend in one cycle," is a wishful cycle budget at N=32

- Papers: 05 (Decision 1.2, `edgeWakeupSelect` B(N)=0 at all N; Decision 1.5 "N=32:
  zero") vs 01 (Sec 3.1, names wakeup->select->PRF read->execute->publish as the
  IPC-critical loop with a 1-cycle budget) vs C1 (`dontcommit.md` 5-6, whole backend
  may execute in one cycle) vs 05 Sec 0.1 (shipped in-order core is ~46 MHz on a
  simpler cone).
- Flaw: At N=32, holding `edgeWakeupSelect` at 0 registered stages while C1 keeps
  wakeup, select, PRF read, and FU execute in a single cycle requires ONE cycle to
  contain: a 32-entry x (up to 8-lane) tag-match CAM, a 32-wide oldest-ready priority
  select, a 65-entry PRF read, and an ALU/AGU. The shipped single-issue core already
  only makes ~46 MHz on a narrower load-use->JALR->TNP cone. Paper 05's own escape
  hatch ("if this edge cannot close at N=32, the answer is a smaller window or a
  banked PRF, not a register") concedes the N=32 point may be un-buildable at
  competitive frequency under this budget -- i.e. the budget is aspirational, not a
  contract. Also 05 and 01 disagree on the loop partition: 05 puts `edgePublishToWakeup`
  at B(32)=1 (a register between publish and wakeup -> dependent chains issue every 2
  cycles at N=32, half rate), while 01 Sec 3.1 names a single 1-cycle loop including
  publish (dependent chains every cycle). Both cannot be the N=32 dependent-issue rate.
- Fix / question: Make `edgeWakeupSelect = 0` CONDITIONAL on a stated target Fmax and
  max window; publish an explicit `maxWindowAtFreq(F)` curve from the STA experiment
  (05 Decision 5.4 step 4). Reconcile 05 Decision 1 with 01 Sec 3.1 on where the loop
  register sits and therefore the N=32 back-to-back dependent-issue rate (1/cycle vs
  1/2 cycles). Until the STA runs, "0 forever" is unverified.

### MA-3. Tag identity is a three-way muddle with a circular cross-paper dependency

- Papers: 05 (Decision 4.2: `seqWidth = log2(maxInFlight)`, merge `uopId == seq`;
  O4.7: "`maxInFlight` needs a closed-form from paper 01") vs 01 (O7: declines to fix,
  keeps RS-depth and FIFO-depth independent) vs 02 (M1: keys StoreCommit by `{seq}`)
  vs 04 (C1.2: keys commitGrant by `uopId`) vs 06 (Sec 1: retire stream `order`, 64b
  "regardless") vs RTL (`BackendBundles.scala:86,91`: uopId AND seq both 32b).
- Flaw: Paper 05's N=1 tag-shrink (its Decision 4 win, "~4-6 bits total instead of
  64") is unenforceable because its width law depends on a `maxInFlight` that paper 01
  refuses to pin. Meanwhile three different identifiers (`seq`, `uopId`, `order`) are
  used as cross-domain keys, and 05 proposes to MERGE two of them while 06 hardcodes a
  third at 64b. The cross-domain edges this whole review introduces -- StoreCommit
  (keyed `{seq}`) and commitGrant (keyed `{uopId}`) -- may end up keying on
  different-width fields of a bundle that currently carries both at 32b. This is a
  correctness-adjacent hole, not just PPA: if `seq` and `uopId` are unified but a
  consumer matches the wrong one, commit/store gating breaks.
- Fix / question: One owner (backend, paper 01) must define: (a) the single canonical
  in-flight identifier, (b) whether `uopId`, `seq`, `order` collapse to it, (c) a
  closed-form width law `f(N, rsDepth, fuLatencies)`. Only then can 05 Decision 4 and
  the 02/04 cross-domain keys be frozen. Recommend uopId==seq==in-flight-index of
  width `log2(maxInFlight)`; `order` stays a verif-only 64b counter (usingRvvi).

### MA-4. The N=1 / N-sweep PPA bar covers only backend OoO structures; memory and frontend N-scaled costs are outside it

- Papers: 05 (Decision 5.2, structural grep for wakeup-CAM / free-list / map-CAM /
  multi-producer arbiter) vs 02 (M2: forwarding CAM over `StoreBufferDepth`; M1:
  depth = f(N)) vs 03 (Sec 4.7: the issue queue "is the only frontend structure that
  cannot elaborate to zero").
- Flaw: Paper 05's hard-gate and N-sweep are defined purely over backend rename/RS/
  publish structures. But two other N-scaled costs exist: the store-buffer byte-mask
  forwarding CAM (02 M2, width = `StoreBufferDepth = f(N)`) and the frontend issue
  queue skid FIFO + per-entry epoch comparators (03 Sec 4, `iqDepth` grows with
  `redirectRecoveryCycles * fetchWidth`). Neither appears in paper 05's structural
  grep or its N=1 bar, so a regression that carries a store forwarding CAM or a deep
  issue queue into the N=1 build would PASS paper 05's gate while violating the
  in-order-competitiveness intent. Also paper 03 concedes the frontend N=1 point is
  NOT zero (irreducible skid queue) -- honest, but it means the project-wide "N=1 folds
  to zero tax" claim is a backend-only claim.
- Fix / question: Extend paper 05 Decision 5.2 structural grep and the N-sweep to
  include the memory subsystem (forwarding CAM, store-buffer depth, load-outstanding
  table) and the frontend (issue-queue depth, outstanding-fetch tracking). State the
  irreducible N=1 frontend floor (skid queue) explicitly and include it in the +10%
  envelope reference so it is not double-counted as tax.

---

## MINORS

### MI-1. Registered speculative-epoch copy (05 D3) vs same-cycle epoch consumers (03 G1, 04 C3.3)

Paper 05 Decision 3 registers the epoch copy to speculative consumers at N>=8
(+1 mispredict cycle). Papers 03 (G1: "redirect wins same cycle, stamps
post-increment epoch on NextPc") and 04 (C3.3: interrupt Exception carries the
commit-boundary epoch) both depend on same-cycle epoch. Paper 05 keeps the
commit/redirect path combinational (D3 clause 1), so this is probably safe, but no
one has shown that a fetch consuming the REGISTERED (one-cycle-stale) speculative
epoch cannot emit a NextPc stamped with a stale epoch that then aliases. Panel:
cross-check 03 G1/G5 against 05 D3 and add an assertion that emitted NextPc.epoch
always equals the combinational post-increment epoch on a redirect cycle.

### MI-2. 06's 64-bit `order` counter contradicts 05 D4's no-fixed-wide-tag ethos

Paper 06 Sec 1 hardcodes retire `order` at 64b "regardless." Defensible because
`usingRvvi=false` is supposed to fold the whole retire path, but a monotone 64b
counter feeding a gated port can leave a counter in the netlist unless the counter
ITSELF (not just the port) is elaboration-gated. Panel: confirm the retire path,
counter included, is fully `if (usingRvvi)`-gated, and add it to paper 05's N=1
structural grep (a 64b counter at N=1 production is a failure).

### MI-3. SlotSlicer parallel-prefix scan is on the fetch path but absent from the IPC-critical edge registry

Paper 03 Sec 5.3 puts an O(log H) parallel-prefix length network in SlotSlicer at
wide `memDataWidth`. Paper 05's IPC-critical registry (Decision 1) lists no frontend
edge except `edgeRedirectToFetch`, and 05 O1.7 asks whether fetch-to-decode under a
taken predict is IPC-critical. If it is (03 Q3.1 flags the predict->fetch loop as
IPC-critical), the registry is incomplete on the frontend side. Panel: settle 03
Q3.1 / 05 O1.7 and, if the predict->fetch loop is IPC-critical, add it to the
registry with a budget.

### MI-4. Superscalar retire at large N (01 O2) adds PRF read ports not in the bar

Paper 01 O2 leaves retire-width vs issue-width open at large N. Paper 06 Sec 1 notes
the retire stream costs "one extra read port group on the PRF per commit lane." Paper
05's N=1 bar and monotonic-area sweep implicitly assume fixed commit width; a
superscalar retire at N=32 would add PRF read ports that neither the bar nor the
sweep attributes to N. Panel: pin retire width as a function of N (01 O2) so the
sweep's area-vs-N curve is attributable.

---

## Cross-paper coverage assessment (what IS handled well)

- The global-epoch broadcast FAN-OUT at N=32 is the one N=32-breaks concern that IS
  priced: paper 05 Decision 3 splits commit/redirect (0 stages) from
  speculative-consumer (registerable) and quantifies the mispredict-cycle cost. This
  is correct AND contingent on BL-1/BL-2 -- if selective replay or a wider epoch is
  adopted, Decision 3's fan-out accounting must be redone.
- Paper 06's N=1-vs-N=8 differential equivalence (Rung 4) is the right instrument to
  CATCH the BL-3 map-degeneracy and BL-1 replay bugs at the architectural level, and
  paper 05 Decision 5's structural grep is the right instrument for the netlist level
  -- provided MA-4 extends its scope to memory and frontend.

---

## The four questions the panel must settle before shell RTL

1. (BL-1) One global epoch forever, or multi-context epochs? This single answer
   determines whether paper 02's selective replay, paper 01's O1, paper 05's
   `epochWidth=2`, and the N=32 mispredict-cost story are even coherent.
2. (BL-2) One wrap mechanism, chosen and enumerated over every epoch-holding vertex,
   with `maxSurvivableGenerations` computed not assumed.
3. (BL-3) Does the N=1 point carry the rename map (relax paper 05's grep) or switch to
   in-place writeback (a second commit path)? "The map folds away" is false as written.
4. (MA-1) Single publish bus (peak IPC = 1 at all N; re-frame N=32 as MLP-only) or
   multi-lane (re-size ports, drop the always-ready claim)?

Everything else (MA-2 timing budget, MA-3 tag law, MA-4 bar scope, the minors) is
downstream of these four.
