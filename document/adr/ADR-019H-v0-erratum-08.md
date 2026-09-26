# ADR-019H: ADR-019 v0 Erratum 08 - Prediction-time TAGE training identity and coroutine RAS action

Status: **accepted** (owner ruling, 2026-09-26).

Amends: ADR-019 D-19.2/D-19.11 (BranchPredictor training, RAS, HistoryRestore), the CfiType
enumeration of bndCfiOutcome/bndPrediction, the DecodeUnit control-flow classification, and the
FetchTargetQueue HistoryRestore obligation.
Baseline: the `242feaf` freeze plus ADR-019A..G, all immutable history. This erratum is
applied on top of them; every DSL change it causes cites `ADR-019H`.

## Context

The BranchPredictor RTL at 9b6ec04 re-ran the TAGE lookup at commit to choose the entry to
train. Counterexample: prediction P is made by provider table T2; before P commits, an older
block's training allocates a T3 entry at P's index/history; P's commit then trains T3 (a
provider that never made P's prediction) and leaves T2 untouched. Separately, the RISC-V JALR
hint with rd and rs1 both link registers and rd != rs1 (pop then push) had no CfiType, and
the "rd is link -> Call" priority classified it as Call.

## Decision

### E-1 - TAGE training uses the prediction-time provider identity

PredictorTrain.meta is the prediction-time training identity; commit never re-runs the lookup
to choose a provider. For a committed tracked Branch: the provider table is meta.provider; its
index and expected tag are recomputed from PredictorTrain.fetchPc (blockBase) and
PredictorTrain.ghr. provider = 0 updates the prediction-time bimodal entry. provider > 0 updates
the indexed entry only if it is still valid with the expected tag; if it was replaced
meanwhile, that provider counter/useful update is skipped. A different, currently visible
provider is never trained for this prediction. Useful-counter decisions use prediction-time
facts: the provider prediction from meta.providerCtr, the alternate prediction from
meta.altPred, and their disagreement. Misprediction allocation searches the tables longer than
meta.provider, with the prediction-time (fetchPc, ghr) indexes and tags.

### E-2 - explicit coroutine CFI type

CfiType gains CallRet = 6 (still 3 bits): the JALR hint with rd in {x1, x5}, rs1 in {x1, x5},
and rd != rs1. DecodeUnit classification: JAL with link rd -> Call; JALR rd link, rs1 not link
-> Call; JALR rd not link, rs1 link -> Ret; JALR rd link, rs1 link, rd == rs1 -> Call; JALR rd
link, rs1 link, rd != rs1 -> CallRet; otherwise Jal / Jalr. The rd-is-link -> Call priority
must not swallow CallRet.

### E-3 - CallRet RAS semantics

A predicted CallRet uses the current RAS top as its target (like Ret), pops it, then pushes
cfiPc + 4 (like Call): exactly pop-then-push. With the circular RAS this is net-zero top
movement that replaces the top entry with cfiPc + 4. HistoryRestore applies the same operation
for a recovering CallRet outcome (with HistoryRestore.pc + 4). The GHR is unchanged. Training
may record CallRet as the committed BTB type; its BTB target is unused while RAS prediction is
available for the type.

### E-4 - contract-derived restore-counter width

The BranchPredictor's outstanding-restore counter is sized from the maximum pre-restore
recovery burst, BranchCheckpointCount + 1 (every checkpointed branch plus one ArchRedirect),
derived from BackendParams (v0: 4 + 1 = 5, 3 bits). An assertion checks that outstanding
restores never exceed that bound.

### E-5 - FTQ HistoryRestore obligation

The FetchTargetQueue emits exactly one HistoryRestore per RecoveryEvent, in RecoveryEvent
order. The BranchPredictor resumes prediction only after every RecoveryEvent observed so far
has had its HistoryRestore accepted. Back-to-back RecoveryEvents are tested explicitly.

## Consequences

- DSL: bndCfiOutcome and bndPrediction list CallRet; funcRasPredict, funcHistoryRestore,
  funcSpeculativeHistoryUpdate, funcPredictorTraining, intfPredictReqIn (BranchPredictor),
  funcFtqRecovery and intfHistoryRestoreOut (FTQ), funcDecodeRv32im classification text cite
  ADR-019H.
- Tests: a directed training-identity race (a newer provider appears between P's prediction
  and P's training and is not trained), CallRet decode cases (x1/x5, x5/x1, x1/x1 Call, x0/x1
  Ret), CallRet prediction and restore, back-to-back RecoveryEvents on the BranchPredictor side
  (and on the FTQ side with its block). A mutant restoring the commit-time lookup must be red.
