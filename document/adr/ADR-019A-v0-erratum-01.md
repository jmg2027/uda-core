# ADR-019A: ADR-019 v0 Erratum 01 - RS allocation set, explicit sysOp, recovery ordering

Status: **accepted** (owner ruling, 2026-09-25).

Amends: ADR-019 (D-19.7 explicit ROB, D-19.8 rename and precise state, D-19.9 recovery).
Baseline: the ADR-019 v0 spec freeze at commit `242feaf`. That snapshot is not rewritten;
this erratum is applied on top of it, and every DSL change it causes cites `ADR-019A` in the
changed spec val.

## Context

Implementing the RenameUnit and ReorderBuffer RTL against the frozen spec exposed two
contradictions and three under-specified points:

1. `funcAllocateAtomic` forks every uop to the ReservationStation, but `funcFuRoute` has no
   execution unit for `fuType System`, and the RS selects only uops whose unit can accept.
   Counterexample: a FENCE takes an RS entry, is done at ROB allocation, and retires; its RS
   entry is never freed. Eight FENCEs fill the v0 RS and rename deadlocks. A decode-exception
   uop has the same leak, and if it were ever issued its completion would target an entry
   that is already done (violating `propRobCompletionTargetsLive`).
2. `funcSerializingTag` sets `sysOp` and `funcRobAllocate` copies it, but `bndDecodedUop` has
   no `sysOp` field. The ReorderBuffer RTL at `24fb240` worked around this by reading
   `DecodedUop.op` as a SysOp for System uops, which overloads an execution-unit-local opcode
   with commit-time semantics.
3. "Uops that need no execution" (`funcRobAllocate`) was given only by example.
4. The timing of `RobStatus.empty` relative to allocation and retirement was not stated.
5. `funcRobOlder` did not state that it is not valid against a tail sentinel, and the
   order of a same-cycle RenameCommit and an ArchRedirect was not stated.

## Decision

### E-1 - required destination set of an allocation (resolves 1 and 3)

ROB allocation is required for every uop. RS allocation is required exactly when

    needsRs = !uop.exception.valid && uop.fuType != System

and LSQ allocation exactly when `needsLsq = needsRs && (uop.isLoad || uop.isStore)`.

| Uop | ROB | RS | LSQ |
|---|---|---|---|
| ALU / MUL / DIV / Branch | yes | yes | no |
| Load / Store | yes | yes | yes |
| CSR (`fuType Csr`, serialize) | yes | yes | no |
| FENCE, FENCE.I, SFENCE.VMA, WFI, MRET, SRET (`fuType System`) | yes | no | no |
| fetch/decode exception, ECALL, EBREAK, illegal instruction | yes | no | no |

`funcAllocateAtomic` is atomic over the required set only: an output that is not required
never raises valid and its ready never blocks rename. A uop is execution-free exactly when
`exception.valid || fuType == System`; the ROB allocates it done. `predictionFault` is not
an exception: the uop executes on the normal RS path and its commit triggers the Refetch.

### E-2 - explicit sysOp (resolves 2)

`bndDecodedUop` carries `sysOp`, passed unchanged through `RenameAllocation.uop` into the
ROB entry. `op` stays an execution-unit-local opcode and never carries commit semantics.

SysOp values: `None | Fence | FenceI | SfenceVma | Wfi | Mret | Sret | CsrWrite`.

- CSR instructions stay `fuType Csr` with `serialize = 1` and execute through the RS.
- `sysOp = CsrWrite` only for a CSR instruction with write semantics: CSRRW and CSRRWI
  always; CSRRS and CSRRC when rs1 != x0; CSRRSI and CSRRCI when zimm != 0. A read-only CSR
  instruction has `sysOp = None` and keeps `serialize = 1`.
- A uop with a fetch or decode exception has `sysOp = None`.

### E-3 - RobStatus.empty timing (resolves 4)

`RobStatus` is the current-cycle registered occupancy of the ROB: an allocation or
retirement in cycle t is reflected from cycle t + 1, with no same-cycle lookahead. The
resulting conservative one-cycle bubble at a serialization boundary is accepted in v0.

### E-4 - domain of funcRobOlder (resolves 5, first half)

`robOlder(a, b)` compares the program order of two live ROB tags inside one live window of
at most RobDepth entries. It is not used against a tail sentinel or for full-window
membership; those use the head-relative modular distance of the {wrap, idx} counter
(`(t - head) mod 2*RobDepth < (tail - head) mod 2*RobDepth`).

### E-5 - same-cycle commit and ArchRedirect (resolves 5, second half)

- Retiring architectural redirects - XRET, the Refetch of FENCE.I and SFENCE.VMA, the
  Refetch after a CsrWrite, and the predictionFault Refetch - retire the instruction
  itself, so its commit state update is logically first: RenameUnit's ArchRecoveryRestore
  restores from rRATNext / archHeadNext, which include a same-cycle RenameCommit.
- Non-retiring redirects - synchronous traps, interrupts, debug entry - have no RenameCommit
  for the head; recovery restores from the committed rRAT and architectural head.
- When an execute-time BranchMispredict and an ArchRedirect coincide, the ArchRedirect wins
  (unchanged: `propArchRedirectWins`).

### E-6 - editorial

`intfRenameCommitIn` no longer lists `checkpointId` (stale; commit never frees checkpoints,
per `funcBranchCheckpoint`).

## Consequences

- RenameUnit: the RS and LSQ fork outputs are gated by needsRs / needsLsq; repeated
  execution-free uops rename with the RS full.
- DecodeUnit: classifies sysOp per E-2 (`SystemOpDecode` in the design tree pins the table
  ahead of the DecodeUnit RTL).
- ReorderBuffer: records `uop.sysOp`; the `op`-as-SysOp reading of `24fb240` is removed.
- CommitUnit: `sysOp` names `Mret`/`Sret` instead of xRET and `CsrWrite` instead of
  "csr-with-side-effect"; a read-only CSR retires as an ordinary head with its CommitGrant.

## Verification obligations

- L1: RenameUnit tests show execution-free uops never raise RsAllocOut/LsqAllocOut valid
  and rename with those outputs not ready; a mutant forking every uop to the RS is red.
- L1: the sysOp classification table, including every CSR write/read-only boundary.
- L1: ReorderBuffer records `uop.sysOp` and treats CSR and predictionFault uops as not done.
- L1: RenameUnit ArchRedirect with and without a same-cycle RenameCommit.
