# ADR-019G: ADR-019 v0 Erratum 07 - Mid-block fetch-PC semantics

Status: **accepted** (owner ruling, 2026-09-26).

Amends: ADR-019 D-19.2/D-19.3/D-19.11 (the frontend bundles Prediction, FtqEntry,
FetchRequest, FetchBlock, HistoryRestore, PredictorTrain and the BranchPredictor, FTQ, and
FetchUnit functions that read them).
Baseline: the `242feaf` freeze plus ADR-019A..F, all immutable history. This erratum is
applied on top of them; every DSL change it causes cites `ADR-019G`.

## Context

A fetch may start at any 4-byte slot of a FetchBytes block (after a taken branch or a
RecoveryEvent target). The frozen texts named the same `fetchPc` field "block start PC" in
some bundles (Prediction, FtqEntry, PredictorTrain) and "start PC" in others (FetchRequest),
while funcBtbLookup and funcFetchBlockAssembly need the requested start slot. Counterexample:
a request at 0x1008 whose Prediction carries 0x1000 loses startSlot = 2 before the FTQ and
FetchUnit, so slots 0 and 1 would be delivered, and a BTB entry for slot 1 of block 0x1000
could be chosen as the block exit although it precedes the fetch PC.

## Decision

### E-1 - requested fetch PC versus block base

PredictReq.fetchPc is the exact architectural fetch start PC: 4-byte aligned, and possibly any
slot inside a FetchBytes block. Define blockBase = alignDown(fetchPc, FetchBytes) and
startSlot = (fetchPc - blockBase) / 4. For v0 (FetchBytes = 16): blockBase = fetchPc with the
low 4 bits cleared, startSlot = fetchPc[3:2].

### E-2 - Prediction.fetchPc carries the requested fetch PC

bndPrediction.fetchPc carries the original PredictReq.fetchPc, not the block base. The first
eligible slot is preserved through FetchPcGen -> BranchPredictor -> FTQ -> FetchUnit. No new
field is added; blockBase is derived combinationally by alignment wherever it is needed (a
separate field needs implementation evidence that deriving it is materially harmful).

### E-3 - predictor tables use blockBase; slot eligibility

BTB and TAGE index and tag with blockBase. A BTB entry keeps an absolute block slot cfiSlot in
[0, FetchWidth-1]. On lookup only an entry with cfiSlot >= startSlot is eligible; an entry for
an earlier slot is ignored. Example: request 0x1008 (startSlot 2): a BTB CFI at slot 1 is
ignored, a BTB CFI at slot 3 is eligible.

### E-4 - fall-through is the next block boundary

Without a predicted-taken eligible CFI, nextPc = blockBase + FetchBytes (not
fetchPc + FetchBytes). Request 0x1008 in block 0x1000 falls through to 0x1010.

### E-5 - CFI PC reconstruction

Whenever the PC of a tracked or recovering CFI is needed, cfiPc = blockBase + 4 * cfiSlot:
the speculative RAS push of a Call is cfiPc + 4; HistoryRestore.pc = blockBase +
4 * RecoveryEvent.cfiOutcome.slot; branch/call/return history repair uses that PC. Never
requestedFetchPc + 4 * slot (the slot is absolute within the block).

### E-6 - FTQ and FetchRequest

The FTQ entry fetchPc stores the original requested fetch PC, and FetchRequest.fetchPc carries
it. The FetchUnit sends the ITLB translation for that fetch PC, indexes the I-cache with
blockBase, receives the whole FetchBytes block, and marks valid only slots
[startSlot .. lastSlot]. FetchBlock.basePc stays the aligned blockBase. On a fetch fault the
sole valid (faulting) slot is startSlot, not necessarily slot 0.

### E-7 - predictor training uses blockBase

PredictorTrain.fetchPc is the table-training block address: alignDown(FTQ.fetchPc,
FetchBytes). The prediction-time checkpoint stays the state captured before the prediction
issued from the original requested fetch PC.

### E-8 - directed tests before the BranchPredictor RTL

Red-first directed tests: (1) a slot-0 request behaves ordinarily; (2) a slot-2 request with
no CFI fetches only slots 2/3 and nextPc is the next block; (3) a slot-2 request ignores a BTB
CFI at slot 1; (4) a slot-2 request selects a BTB CFI at slot 3; (5) a Call at slot 3 from a
slot-2 request pushes blockBase + 16, not requestedPc + 16; (6) a RecoveryEvent for CFI slot 3
gives HistoryRestore.pc = blockBase + 12; (7) a fetch fault on a slot-2 request has only slot
2 valid and faulting; (8) PredictorTrain uses the aligned blockBase when the prediction began
mid-block. Mutants that replace blockBase with the raw fetch PC in the fall-through, RAS, or
history calculations must turn these tests red.

## Consequences

- DSL: bndPredictReq, bndPrediction, bndFtqEntry, bndFetchRequest, bndFetchBlock,
  bndHistoryRestore, bndPredictorTrain; funcBtbLookup, funcTageDirection, funcRasPredict,
  funcBlockExitSelect, funcHistoryRestore, funcFtqFetchIssue, funcFtqRecovery,
  funcFtqCommitTrain, funcParallelFetchLookup, funcFetchBlockAssembly cite ADR-019G.
- Each directed test binds the function it exercises and stays PENDING until that vertex's
  RTL lands (BranchPredictor: 1-5 prediction side, 8 training side; FTQ: 6 and the
  FetchRequest/PredictorTrain projection; FetchUnit: 2 and 7 delivery side).
