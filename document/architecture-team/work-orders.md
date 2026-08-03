# Spec-Change Work Orders (implementing the accepted ADRs)

Binding source: `document/adr/ADR-000-index.md` and ADR-001..015. Every entry
below is a spec-DSL change to a named `*Specs.scala` file, written against the
stub DSL (`src/main/scala/framework/specs/Spec.scala`:
CONTRACT/INTERFACE/FUNCTION/PROPERTY/PARAMETER/BUNDLE/COVERAGE +
desc/note/table/markdownTable/draw/code/entry/is/has/uses/status/build).

Rules honored: (1) spec-first - write these before filling shell RTL; (2) place
normative graphs inside `.draw("mermaid", ...)` of the rawTop CONTRACT, not prose
(ADR-015 D-15.2); (3) ship each PROPERTY as a pair with an `@LocalSpec` design
assert, typed per ADR-015 D-15.3; (4) NO assertion text inside `.code`/`.note`.

## Work-package partition (NO file appears in two packages)

| File | Package |
|------|---------|
| backend/spec/modules/RenameUnitSpecs.scala | **A backend** |
| backend/spec/modules/CommitUnitSpecs.scala | **A backend** |
| backend/spec/modules/ReservationStationSpecs.scala | **A backend** |
| backend/spec/modules/PublishMuxSpecs.scala | **A backend** |
| backend/spec/modules/PhysicalRegisterFileSpecs.scala | **A backend** |
| backend/spec/modules/RedirectUnitSpecs.scala | **A backend** |
| backend/spec/modules/DecodeUnitSpecs.scala | **A backend** |
| backend/spec/modules/DispatchUnitSpecs.scala | **A backend** |
| backend/spec/shared/BackendBundlesSpecs.scala (SHARED - here only) | **A backend** |
| backend/spec/shared/BackendParamsSpecs.scala | **A backend** |
| backend/spec/shared/EdgeBudgetSpecs.scala (NEW) | **A backend** |
| backend/spec/top/BackendTopSpecs.scala | **A backend** |
| backend/design/shared/BackendParams.scala (hartId fix) | **A backend** |
| memorysubsystem/spec/modules/StoreBufferSpecs.scala (NEW) | **B memory** |
| memorysubsystem/spec/modules/MemoryDispatcherSpecs.scala | **B memory** |
| memorysubsystem/spec/modules/LoadUnitSpecs.scala | **B memory** |
| memorysubsystem/spec/modules/StoreUnitSpecs.scala | **B memory** |
| memorysubsystem/spec/modules/MemoryControllerSpecs.scala | **B memory** |
| memorysubsystem/spec/modules/ResponseArbiterSpecs.scala | **B memory** |
| memorysubsystem/spec/shared/MemorySubsystemBundlesSpecs.scala | **B memory** |
| memorysubsystem/spec/shared/MemorySubsystemParamsSpecs.scala | **B memory** |
| memorysubsystem/spec/top/MemorySubsystemSpecs.scala | **B memory** |
| memorysubsystem/design/shared/MemorySubsystemParams.scala | **B memory** |
| frontend/spec/modules/FetchUnitSpecs.scala | **C frontend** |
| frontend/spec/modules/NextPcGenSpecs.scala | **C frontend** |
| frontend/spec/modules/SlotSlicerSpecs.scala | **C frontend** |
| frontend/spec/modules/BranchPredecoderSpecs.scala | **C frontend** |
| frontend/spec/modules/IssueQueueSpecs.scala | **C frontend** |
| frontend/spec/shared/FrontendBundlesSpecs.scala | **C frontend** |
| frontend/spec/shared/FrontendParamsSpecs.scala | **C frontend** |
| frontend/spec/top/FrontendTopSpecs.scala | **C frontend** |
| frontend/design/shared/FrontendParams.scala | **C frontend** |
| backend/spec/modules/CsrControllerSpecs.scala | **D csr+core** |
| backend/spec/modules/TrapControllerSpecs.scala | **D csr+core** |
| core/spec/modules/GlobalEpochUnitSpecs.scala | **D csr+core** |
| core/spec/modules/BootSequencerSpecs.scala | **D csr+core** |
| core/spec/shared/CoreBundlesSpecs.scala | **D csr+core** |
| core/spec/shared/CoreParamsSpecs.scala | **D csr+core** |
| core/spec/top/CoreTopSpecs.scala | **D csr+core** |
| core/design/shared/CoreParams.scala | **D csr+core** |
| common/spec/DesignRuleSpecs.scala | **D csr+core** |
| common/spec/ProductSpecs.scala | **D csr+core** |

Cross-package data flow is by READ-ONLY consumption of another package's
published spec val (via `api/` params), never by editing its file. Key shared
definitions and their sole owner: canonical bundles + `seqTag`/`physRegIdWidth` +
`CommitBroadcast` = WP-A (BackendBundlesSpecs/BackendParamsSpecs);
`SpeculativeRegNum`/`usingRvvi`/`epochWidth` + N=1 forbidden-structure list +
epoch-vertex enumeration + machine-check + N-equivalence = WP-D
(CoreParamsSpecs/DesignRuleSpecs/ProductSpecs); `StoreBufferDepth`/`LoadOutstanding`
= WP-B; `memDataWidth`/`fetchWidth` = WP-C.

Sequencing note: WP-A must publish `bndCommitBroadcast`, `seqTag`, and
`bndArchMapSnapshot` first (ADR-012/001/002); WP-B/WP-C/WP-D consume them. WP-D
must publish `paramSpeculativeRegNum`, `paramUsingRvvi`, and the N=1 forbidden
list; all packages consume them.

---

## WP-A - BACKEND (implements ADR-001, 002, 007, 012, 013, 014; backend side of 004/010)

### RenameUnitSpecs.scala (ADR-001)
Add `intfArchMapRestoreIn` (INTERFACE, `.uses(bndArchMapSnapshot).is(rawNoDecoupled)`),
`funcRenameRecovery` (FUNCTION, restore speculative map from arch map + bulk
free-list reconstruct, same-cycle, `.uses(intfArchMapRestoreIn,
paramSpeculativeRegNum)`). Extend `contRenameUnit.has(...)` with both. Add
`propN1MapDegenerate` (PROPERTY): at N=1 speculative map == arch map always,
restore mux elaborates away; `.uses(paramSpeculativeRegNum)`; pair with a design
assert.

### CommitUnitSpecs.scala (ADR-002, 001 source, 004 interrupt, 010 retire)
Add `funcArchMapMaintain`, `intfArchMapRestoreOut` (`rawNoDecoupled`),
`propCommitInOrder` (PROPERTY, `.uses(intfDecodedUopAllocIn, intfCommitResultIn,
intfExceptionOut)`), `funcCompletionScoreboard` (`.uses(paramSpeculativeRegNum)`),
`funcInterruptSampling` (`.uses(intfInterruptCtrlIn, intfCommitResultIn,
intfExceptionOut)`), `intfInterruptCtrlIn` (`rawNoDecoupled`, `.uses(bndInterruptCtrl)`),
`intfCommitBroadcastOut` (drives the unified broadcast), `intfRetireStreamOut`
(`rawReadyValidIntf`, `.uses(bndRetireToken)`, gated by `paramUsingRvvi`),
`propRetireNonBlocking`, `bndRetireToken` markdownTable per ADR-010. Extend
`contCommitUnit.has(...)`.

### ReservationStationSpecs.scala (ADR-007, 005)
Add `propWakeupEpochQualified` (PROPERTY), `funcSelectOldestReady` (FUNCTION,
name the IPC-critical loop, `.uses(intfWakeupBroadcastIn, intfDispatchedUopOut)`),
`propRsEagerFilter` (PROPERTY: every held entry compares epoch each cycle,
self-invalidates; enumerated eager-filter vertex, ADR-005 D-5.2). Pair each with a
design assert.

### PublishMuxSpecs.scala (ADR-014)
Add `propSingleDrain` (PROPERTY: <=1 PRF write / <=1 wakeup / cycle in the base
build). Note that the publish->PRF-write edge carries real back-pressure (strike
the always-ready claim). Reference `paramSpeculativeRegNum` for lane count = 1
(base); multi-lane is proposed (ADR-014 D-14.4).

### PhysicalRegisterFileSpecs.scala (ADR-002, 010, 012)
Add `funcReadAtSelect` (FUNCTION, ports = issueWidth*2 + commitWidth-when-usingRvvi),
`propPrfPortsVsN` (PROPERTY: depth = 33+N; read ports a function of issue width +
verif commit ports, not N; `.uses(paramSpeculativeRegNum, paramPhysicalRegNum)`).

### RedirectUnitSpecs.scala (ADR-013, 011)
Add `funcRedirectPriority` (FUNCTION: trap/interrupt > branch > memory-order),
`propSingleRedirectPerEpoch` (PROPERTY), `propRedirectAtCommit` (PROPERTY, ADR-011:
all redirects originate at the commit head). Pair with design asserts.

### DecodeUnitSpecs.scala / DispatchUnitSpecs.scala (ADR-004 backend mechanics)
Decode: add `funcSerializingTag` (FUNCTION: tag CSR/mret/dret/wfi/fence/fence.i/
ecall/ebreak `serializing`). Dispatch: add `funcSerializingDispatchGate`
(FUNCTION: withhold CSR/system edge ready while a serializing uop is in flight;
1-bit scoreboard, FCL backpressure).

### BackendBundlesSpecs.scala - SHARED, WP-A OWNS (ADR-012, 001, 002, 004, 010)
Define `bndArchMapSnapshot`, `bndCommitBroadcast` (keyed `seqTag`; projected views
StoreCommit `{seqTag,epoch}`, commitGrant `{seqTag,epoch,valid}`, map-update+free,
retire trigger), `bndInterruptCtrl`, `bndRetireToken`, and extend `bndException`
with `{source(Sync|Interrupt), cause, pc, epoch}`. REMOVE the separate 32-bit
`uopId`/`seq` fields; replace with the single `seqTag`. Rewrite `bndCsrTrapRead`
and `bndCsrTrapWrite` per ADR-004 (complete trap/return application packet;
`kind(TrapEntry|MRet|DRet|DebugEntry)`).

### BackendParamsSpecs.scala - SHARED WIDTH LAWS, WP-A OWNS (ADR-012)
Add `physRegIdWidth = log2Ceil(archRegNum + N)`, `seqWidth = log2Ceil(maxInFlight)`,
`maxInFlight = allocFifoDepth + storeBufferDepth + serializingStageDepth` (reads
`storeBufferDepth` from WP-B api and `serializingStageDepth` from WP-D api,
read-only). Add `propTagUniqueness` (PROPERTY over all horizons). Add
`funcSeqOlder` (wrap-aware modular age compare helper contract).

### EdgeBudgetSpecs.scala - NEW, WP-A (ADR-007)
The six-row IPC-critical registry as pure budget functions + one PROPERTY per row
(`propWakeupSelectBudget` etc.), plus the `ipcCriticalEdge` helper contract
(pre-PD `require(stages<=budget)`; post-PD STA artifact co-owned with WP-D).

### BackendTopSpecs.scala (ADR-013, 012, 003 cross-edge)
Update the `.draw("mermaid", ...)` graph: wire branch-mispredict into `ru`; add
`intfStoreCommitOut` (BackendTop output, `bndCommitBroadcast` StoreCommit view);
add `intfArchMapRestoreOut`. Update `.has(...)`.

### BackendParams.scala (design, ADR-015 D-15.6)
Fix `require(hartId > 0)` to `>= 0`.

---

## WP-B - MEMORY (implements ADR-003; consumes WP-A bundles + WP-D params read-only)

### StoreBufferSpecs.scala - NEW (ADR-003 M1/M2)
`contStoreBuffer` (CONTRACT, `.has(intfStoreDispatchIn, intfStoreCommitIn,
intfLoadFwdQuery, intfLoadFwdData, intfControllerWriteOut, intfStoreCompleteOut,
intfGlobalEpochIn).uses(paramStoreBufferDepth)`; notes: speculative never writes;
only committed head drains in seqTag order; epoch kill invalidates uncommitted
only, committed irrevocable). Interfaces `intfStoreCommitIn` (consumes WP-A
`bndCommitBroadcast` StoreCommit view), `intfLoadFwdQuery`, `intfLoadFwdData`,
`intfStoreCompleteOut`. PROPERTYs: `propNoSpeculativeWrite`, `propInOrderDrain`,
`propCommittedSurvivesKill`, `propLoadForwardExact` (wrap-aware `entry.seqTag <
load.seqTag`, ADR-012 D-12.3), `propStoreBufferLiveness` (bus eventually accepts a
committed write -> buffer drains -> commit advances; ADR-003 verif, critique m3).
`propCommittedEntryEpochExempt` (enumerated exempt vertex, ADR-005 D-5.2). Pair
each with a design assert.

### MemoryDispatcherSpecs.scala (ADR-003 M3 - REWRITE)
Rewrite `contMemoryDispatcher` as a SPLITTER (`.has(intfMemOpIn, intfLoadOut,
intfStoreOut)`; note exclusivity `not(isLoad && isStore)`; backpressure
`memOpIn.ready = isLoad ? loadOut.ready : storeOut.ready`). Add the three
interfaces. Add `propSplitExclusive` (PROPERTY).

### LoadUnitSpecs.scala (ADR-003 M2)
Add `intfLoadFwdQuery`/`intfLoadFwdData` to `.has`; add `funcLoadForward`
(FUNCTION), `propLoadNoOrderingGate` (loads free, filtered at PublishMux). Add
`funcConservativeDisambig` (base `MemDisambig=false`: no read past unresolved older
store) and note the proposed speculative path (ADR-003 D-3.11) elaborates away at
base.

### StoreUnitSpecs.scala (ADR-003 M3)
Keep as token-shaping / mask-generation front of the buffer (`captureIssue`);
`funcStoreShaping`. Note StoreUnit-vs-StoreBuffer merge is an open question
(ADR-003 Q-M3a) - keep separate.

### MemoryControllerSpecs.scala (ADR-003 M4)
Add `funcRequestArbiter` (the MERGE: multiplexes `LoadOutstanding` reads + one
committed write; enforces NO program order), `funcTxnIdTagging` (assign/reunite by
`txnId`), `propOutstandingBound` (<=LoadOutstanding reads, <=1 write). Note fence =
commit-gate on `StoreBuffer.empty` (no external txn); AMO/LR-SC reserved encodings,
never emitted.

### ResponseArbiterSpecs.scala (ADR-003 M4)
Add `funcResponseReassoc` (loads by `txnId`, StoreComplete by `seqTag`; responses
UNORDERED to the backend). `propNoOrphanResponse`.

### MemorySubsystemBundlesSpecs.scala (ADR-003)
Define `bndLoadFwdQuery {addr,size,olderThan(seqTag)}`, `bndLoadFwdData
{data,mask,hit}`, `bndStoreComplete {seqTag,fault}`, `bndDispatcherLoad`,
`bndDispatcherStore`. Extend `MemoryOpResp.meta` to carry `txnId`/`seqTag`.

### MemorySubsystemParamsSpecs.scala + design/shared/MemorySubsystemParams.scala (ADR-003 M5)
Define `paramStoreBufferDepth = max(1, min(N, StoreBufferDepthMax))` (Max default
8), `paramLoadOutstanding` (default 1, derives txnId width), `paramMemDisambig`
(default `N>1`). Publish `storeBufferDepth`/`serializing`-independent value via
`api/` for WP-A `maxInFlight`. Set `addressWidth=32`, `memOpWidth=32`,
`memoryPorts=1`; remove `loadUnitCount`/`storeUnitCount` base knobs; remove the
`>=4` lower-bound requires; re-base/delete the 64-bit presets; `minimal` -> the
RV32-TCM default.

### MemorySubsystemSpecs.scala (top, ADR-003 M3)
Rewrite the `.draw("mermaid", ...)` graph to add StoreBuffer, StoreCommit input,
FwdQuery/FwdData edges, WriteAck edge; `.has` gains `intfStoreCommitIn`. Add
`propGraphConsistency` note referencing ADR-015 check 1.

---

## WP-C - FRONTEND (implements ADR-009; consumes WP-D epochWidth + WP-A/WP-C params)

### FetchUnitSpecs.scala (ADR-009 D1/D2 - REWRITE body)
`contFetchUnit` owns exactly `intfNextPcIn, intfProgMemReqOut, intfProgMemRespIn,
intfFetchResponseOut` + `funcOutstandingTracking, funcEpochTagging`. DELETE
`intfBootIn`/`intfInstructionOut`. `funcEpochTagging` = latch epoch at request,
drop response on mismatch (enumerated eager-filter vertex, ADR-005).

### NextPcGenSpecs.scala (ADR-009 D1/D3/D6 - REWRITE body)
`contNextPcGen.has(intfBootIn, intfRedirectIn, intfPredictionIn, intfNextPcOut,
funcPcSelect, funcEpochStamp)`. `intfBootIn`/`intfRedirectIn` reference the
FrontendTop-level edges (not re-minted). `funcPcSelect` markdownTable (redirect >
prediction > sequential). `funcEpochStamp` stamps the combinational post-increment
epoch (ADR-006 D-6.3). Add `propNextPcEpoch` (PROPERTY, critique MI-1).

### SlotSlicerSpecs.scala (ADR-009 D4 - REWRITE body)
`contSlotSlicer.has(intfInputIn, intfOutputOut, funcStraddleCarry, funcSlotScan,
funcMisalignTag)`. FUNCTIONs carry INV-S1..S5 as `.table("invariant", ...)`.
`funcMisalignTag` = tag `instrAddrMisaligned`/`programMemFault` + access-fault
handoff bundle field `{exc, excCause}` (backend raises at commit, ADR-009 D-4).
`funcStraddleCarry` = enumerated eager-filter vertex (carry dropped on epoch
change).

### BranchPredecoderSpecs.scala (ADR-009 D5)
Add `funcFirstTakenPrune` (markdownTable of JAL/BR/JALR classes), 
`funcPredictionFeedback` (epoch-tagged `PredictorOutput`, consumed only if
`srcEpoch===GlobalEpoch`). Extend `contBranchPredecoder.has(...)`.

### IssueQueueSpecs.scala (ADR-009 D7, ADR-005)
Add `funcOnlyValidSlots` (INV-Q1/Q4), `funcEpochDrain` (INV-Q2/Q3; MUST
eager-filter ALL held entries each cycle, not only at dequeue - closes critique M1;
enumerated eager-filter vertex), `paramIssueQueueDepth` (`iqDepth =
max(prefetchDepth*slotsPerBeat, redirectRecoveryCycles*fetchWidth)`, base 4). Add
`propIssueQueueLiveness` (rank function on occupancy). Extend `contIssueQueue`.

### FrontendBundlesSpecs.scala (ADR-009 D4/D5/D8, s6 table)
Fill the placeholder bundles: `BootAddr, NextPcIssue{pc,epoch}, Redirect{target,
cause,epoch}, ExternalProgramMemoryReq{addr,epoch(,size?)}, ExternalProgramMemoryResp
{data,exception}, FetchResponse{data,pc,epoch}, SlotGroup{slots:Vec(fetchWidth,
Slot),count}` where `Slot={bits(32),pc,len(2),valid,exc,excCause,epoch}`,
`PredictorOutput{valid,taken,target,srcPc,srcEpoch,indirectStall},
IssueBackend{slots:Vec(issueWidth,Slot),count}`. Add `bndSlotGroup`, `bndFetchResponse`.

### FrontendParamsSpecs.scala + design/shared/FrontendParams.scala (ADR-009 D8)
Add `paramMemDataWidth` (contract tier, bus width; recommend shared `api/`),
`paramSlotsPerBeat` (derived = MemDataWidth/16), split `fetchWidth` (instructions,
`<= slotsPerBeat`). Add requires: `memDataWidth % 16 == 0`, `fetchWidth <=
memDataWidth/16`. G5 wrap require is WITHDRAWN (subsumed by ADR-005 eager filter).

### FrontendTopSpecs.scala (ADR-009 D3/D4)
Correct the `.draw("mermaid", ...)` graph so it matches child `.has` sets
edge-for-edge (boot->npc, one redirect edge, fu owns 4 edges, prediction feedback
edge, epoch broadcast to all). Add `propGraphConsistency` note (ADR-015 check 1).

---

## WP-D - CSR + CORE (implements ADR-004, 005, 006, 010 param, 011, 013 epoch, 015)

### CsrControllerSpecs.scala (ADR-004 - REWRITE trap policy out)
`intfCommitGrantIn` (consumes WP-A `bndCommitBroadcast` commitGrant view),
`funcCsrExecuteAtCommit` (FU visit = pure read + staged intent; write gated by
commitGrant, `.uses(intfCsrReqIn, intfCsrResultOut, intfCommitGrantIn)`),
`funcSerializingClass`. State that the CSR node computes NO `exception` and
performs NO trap-CSR self-write (deleted per ADR-004 D-4.3, owner sign-off OQ-E).
Retain only the commit-gated CSRRW/RS/RC datapath. PROPERTY `propNoSpeculativeCsrWrite`.

### TrapControllerSpecs.scala (ADR-004 - the trap owner)
`funcTrapSingleOwner` (sole producer of trap-CSR + privilege transitions; consumes
`intfExceptionIn, intfCsrTrapReadIn`; produces one `intfCsrTrapWriteOut` + one
`intfRedirectOut`; covers TrapEntry/MRet/DRet/DebugEntry), `funcSerializingOps`
(mret/dret/fence/fence.i/wfi at head; fence gates on `StoreBuffer.empty`; fence.i +
mret/dret emit Redirect), `funcDebugCommitBoundary` (trigger/step/ebreak
commit-gated; debug entry via the single trap-write owner), `propPreciseCommit`,
`propTrapSingleWriter` (one writer per trap CSR/cycle; simulation monitor +
structural single-writer check per ADR-015 D-15.3). Pair with design asserts.

### GlobalEpochUnitSpecs.scala (ADR-005, 006, 013)
Add `propEpochWrapBound` (PROPERTY: `require((1<<epochWidth) >
maxSurvivableGenerations + 1)`; elaboration-require type), `propEpochDistribution`
(commit/redirect 0 stages forever; speculative consumers registerable within
`edgeRedirectToFetch` budget), `propSingleIncrementPerRedirect` (ADR-013 D-13.4).
Keep exact-match compare (no distance).

### CoreParamsSpecs.scala + core/design/shared/CoreParams.scala (ADR-015, 005, 003, 010)
Define `paramSpeculativeRegNum` (the window axis; N=1 folds OoO away),
`paramUsingRvvi` (verif retire path, default false, gates port AND counter),
retie `paramDataMemoryTxnIdWidth` note to "derived = ceil(log2(LoadOutstanding)); 0
when single-outstanding" (LoadOutstanding defined in WP-B). Keep `epochWidth`
default 2 (ADR-005). Publish `serializingStageDepth` via `api/` for WP-A `maxInFlight`.

### CoreBundlesSpecs.scala (ADR-004 interrupt, 003 bus)
Ensure `bndInterruptCtrl` view and the external bus bundles
(`ExternalDataMemoryReq/Resp`) carry the `txnId`/`seqTag` meta (ADR-003 D-3.13).

### CoreTopSpecs.scala (ADR-003 cross-edge, ADR-013)
Wire `BackendTop.storeCommitOut -> MemorySubsystem.storeCommitIn` as a Decoupled
`:<>=` edge in the `.draw("mermaid", ...)` graph and `.has`. Confirm boot/redirect
edges to FrontendTop match ADR-009.

### BootSequencerSpecs.scala (ADR-009 D1)
Confirm boot is a Decoupled pulse to NextPcGen (not FetchUnit); no change beyond
documenting the sink per ADR-009 (settles P03 Q1.2).

### DesignRuleSpecs.scala (ADR-005, 007 post-PD, 015 machine checks)
Add the epoch-holding-vertex enumeration table (each vertex: eager-filter |
exempt; ADR-005 D-5.2) as `propEpochVertexEnumeration`. Add the five machine-check
PROPERTYs: `propGraphConsistency`, `propPropertyToAssertion`, `propLocalSpecCoverage`,
`propRawTopWiringOnly`, `propRegQueueTagged` (ADR-015 D-15.2). Add `propNEquivalence`
(compare set `order/pc/rd/wdata/trap/cause`, epoch excluded; deterministic
interrupt-free corpus; ADR-015 D-15.4).

### ProductSpecs.scala (ADR-008, 015)
Add the SINGLE canonical N=1 forbidden-structure list `propN1ForbiddenStructures`
(concrete emitted-Verilog module/cell identifiers: wakeup CAM, RS match matrix,
free-list encoder, map CAM, multi-producer publish arbiter, store-buffer forwarding
CAM, multi-entry issue queue, load-outstanding table, 32-bit seq/uopId, 64b retire
counter) - backend/memory/frontend all `.uses` it (ADR-008 D-8.1, critique
V-MA-4/MA-4). Add `contConfigRegistry` (the `n1/n8/n32/n1_rvvi/n8_rvvi/verif`
named axis, `.uses(paramSpeculativeRegNum)`), `propN1PpaBar` (+10% area/DFF, -5%
freq, structural gate; ADR-008 D-8.3), `propN1FoldsOoO`.

---

## Verification-artifact work orders (co-owned, tracked in WP-D unless noted)

- Interim source-reflection spec checker (ADR-015 D-15.1) with its acceptance test
  (a deliberately-broken spec trips each of the five machine checks). WP-D.
- N=1 structural grep test with a deliberately-broken CAM-retaining build (ADR-008
  D-8.3). WP-D drives; consumes WP-A/B/C module names.
- N-equivalence harness over the retire stream (ADR-010/015 rung 4), deterministic
  corpus + order-keyed interrupt injection. WP-D.
- IPC-critical post-PD STA registered-depth report (ADR-007 D-7.3). Co-owned WP-A
  (EdgeBudgetSpecs) + WP-D (STA flow).
