# ADR-019C: ADR-019 v0 Erratum 03 - FU availability and branch resolution/publication decoupling

Status: **accepted** (owner ruling, 2026-09-25).

Amends: ADR-019 (D-19.9 selective recovery; the RS/Dispatch/BranchUnit/PublishMux contracts).
Baseline: the `242feaf` freeze plus ADR-019A and ADR-019B, all immutable history. This
erratum is applied on top of them; every DSL change it causes cites `ADR-019C`. The
RecoveryEvent stays combinational (it is not registered by this erratum).

## Context

Two contradictions blocked the execution backend (HANDOFF C-1, C-2):

- C-1: `funcSelectOldestReady` selects only entries "whose target FU class can accept", but
  the ReservationStation had no FU-availability input. `IssuedUopOut.ready` is a function of
  the offered uop's fuType, so choosing with it is a combinational loop; without it an older
  ready DIV behind a busy divider blocks an idle ALU for the whole divide.
- C-2: `funcBranchRecoveryRequest` fired BranchResolution together with the branch's
  completion. With a combinational RecoveryEvent, producers dropping killed tokens in the
  event cycle, and PublishMux granting among the remaining valids, this closes the loop
  BranchResolution -> RecoveryController -> RecoveryEvent -> producer valid -> PublishMux
  grant -> BranchResolution.

## Decision

### E-1 - FuAvailability view; oldest ready among available classes

DispatchUnit publishes `FuAvailability` to the ReservationStation, one bit per FU class:
`alu, mul, div, branch, mem (AGU), csr`, and `bitAlu` when that extension is elaborated.
System uops never enter the RS (ADR-019A E-1) and have no bit.

`FuAvailability[c] = 1` means: if an IssuedUop of class c were presented in the current
cycle, DispatchUnit and that execution wrapper would accept it.

The RS selects, by funcRobOlder, the oldest entry whose two sources are ready AND whose
class bit is set. A ready entry of an unavailable class never blocks a younger ready entry
of an available class (a busy divider never stalls an idle ALU); within one class the
choice stays oldest-first.

The view is a new sanctioned rawNoDecoupled class:

| Class | Sanctioned use | Example |
|---|---|---|
| 7 | execution-capacity views derived only from registered state and the drain of already-held tokens, never from the request they gate | FuAvailability |

### E-2 - no combinational loop through availability

FuAvailability may be built from downstream request readiness, but that readiness never
depends on the current request's valid or payload. The loop
RS selection -> IssuedUop fuType/valid -> FU ready -> FuAvailability -> RS selection is
forbidden. Every execution wrapper's request ready (canAccept) is a function of its
registered state and of whether its already-held output token drains this cycle; for a
one-entry result holder `canAccept = !resultValid || resultReady` is allowed. A DispatchUnit
that holds a routed token drives that class unavailable while it holds it and never
accepts a hidden second token. `IssuedUopIn.ready` equals the availability bit of the
presented uop's class.

### E-3 - RS output stability and issue atomicity

If IssuedUopOut is valid while not ready, its bits and the selected entry stay unchanged
until the transfer or until a RecoveryEvent kills that entry. The PRF operand read and the
RS entry release coincide with the issue transfer: an entry is never freed for a uop that
was not accepted. A selected entry killed by a same-cycle RecoveryEvent is not issued and
is freed in that cycle.

### E-4 - branch control resolution and result publication are independent channels

The BranchUnit has two independent responsibilities for every branch:

1. control resolution: BranchResolutionOut for a mispredicted, non-faulting branch;
   CheckpointReleaseOut for a correctly predicted or faulting branch;
2. execution result: BranchResultOut to PublishMux.

Neither channel waits for the other; they may fire in the same cycle or in different
cycles. `BranchResolutionOut.valid` never depends on `BranchResultOut.ready`, on the
PublishMux grant, or on the RecoveryEvent its own resolution produces.

### E-5 - the checkpoint lifetime ends at control resolution

A correctly predicted or faulting branch sends one CheckpointRelease as soon as it is
resolved, independent of its result publication. A mispredicted non-faulting branch sends
one BranchResolution and never a CheckpointRelease (RenameUnit restores and frees that
checkpoint when it applies the RecoveryEvent). Every live branch has exactly one control
side effect: CheckpointRelease XOR BranchResolution. New PROPERTY `propBranchControlOnce`.

### E-6 - the recovering branch survives its own recovery

funcRecoveryKills never kills the recovering branch, so its BranchResult may be published
in the event cycle or held for later. An older same-cycle ArchRedirect still wins and kills
the branch: its unpublished BranchResult and any unfired control token are discarded. A
control token that already fired is not undone.

### E-7 - PublishMux recovery doctrine unchanged

PublishMux holds no tokens; producers drop killed results in the event cycle; PublishMux
grants the oldest live candidate remaining in that cycle. The RecoveryEvent is not
registered.

## Consequences

- New bundle `FuAvailability`, DispatchUnit `FuAvailabilityOut`, ReservationStation
  `FuAvailabilityIn`, BackendTop edge `DispatchUnit -. FuAvailability .-> RS`.
- DispatchUnit gains `funcFuAvailability`; the RS gains `propRsIssueStable`; the BranchUnit
  gains `propBranchControlOnce` and its recovery-request function is rewritten.
- Execution wrappers (ALU, MUL, DIV, AGU, BranchUnit, CSR) state the E-2 ready rule.

## Verification obligations

L1 tests for the RS (source capture, same-cycle wakeup, oldest ready among available
classes, busy older DIV vs younger ALU, per-class oldest-first, no available class, output
stability, selective kill, older/recovering survival, same-cycle kill of the selected
entry, no entry loss), DispatchUnit (routing, availability vs downstream capacity,
structural independence from the current request, held-token kill, no duplicate/lost
route), the wrappers (canAccept independence, output stability, drain-and-accept, killed
result discard including uncancelable MUL/DIV cores, older survival), the BranchUnit
(control XOR, resolution under result backpressure, same-cycle firing, own-event survival,
older ArchRedirect kill), and PublishMux (oldest live, losers held, atomic fanout, wen=0,
same-cycle filtering including the directed younger-ALU vs recovering-branch case,
SingleDrain assertion).
