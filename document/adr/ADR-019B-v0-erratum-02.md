# ADR-019B: ADR-019 v0 Erratum 02 - Commit, Debug, and Retire semantics

Status: **accepted** (owner ruling, 2026-09-25).

Amends: ADR-019 (D-19.7, D-19.8, D-19.9) and ADR-010 (D-10.1, D-10.4) as applied to the
ADR-019 machine. Baseline: the `242feaf` spec freeze plus ADR-019A. Neither is rewritten;
this erratum is applied on top of them and every DSL change it causes cites `ADR-019B`.

## Context

Before the CommitUnit RTL, six contract gaps had to be closed:

1. `bndException.source` names Debug, but `RecoveryCause` and `bndArchRedirect.cause` had no
   Debug value, so a debug entry had no legal ArchRedirect cause.
2. `funcSystemOpSequencing` let WFI "wait for a pending interrupt", which needs a sleep state
   and a wake-up rule that no vertex specified.
3. `bndRetireToken.wdata` is a "commit-time PRF read" and the PRF reserves a commit read port
   under usingRvvi, but no CommitUnit <-> PRF interface existed.
4. `bndRetireToken.priv` said "privilege after the retirement", which would make the
   CommitUnit read the TrapController's post-transition state back.
5. ADR-010 says trap entry is a retire token, while `bndRetireToken` said tokens are
   "emitted only for tokens that actually retire" - a trapping head never retires.
6. For redirects whose instruction itself retires (xRET, Refetch), the relation between the
   retirement and the ExceptionOut hand-off was not atomic, so a TrapController stall could
   separate them.

## Decision

### E-1 - RecoveryCause.Debug

`RecoveryCause` gains `Debug = 7` (fits the 3-bit encoding). `bndArchRedirect.cause` is one
of `Trap | Interrupt | Debug | XRet | Refetch`. Debug entry is its own recovery cause and is
never aliased to Trap. The RecoveryController cause-legality check accepts it.

### E-2 - v0 WFI is a serializing architectural NOP

WFI is `fuType System`, `serialize = 1`, `sysOp = Wfi`, done at ROB allocation, and retires
at the head like an ordinary uop: no sleep state, no wake-up wait, no ArchRedirect. Pending
interrupts and debug requests are taken at the next precise retire boundary by the ordinary
`funcInterruptSampling` rule. Clock-stop / sleep WFI belongs to a later power-management ADR.

### E-3 - commit-time PRF read path (usingRvvi only)

New verification-only interfaces: `CommitPrfReadReq {prd}` CommitUnit -> PRF and
`CommitPrfReadResp {data}` PRF -> CommitUnit (ready/valid; the PRF is always ready and
answers combinationally in the same cycle; the CommitUnit is always ready for the answer).

- usingRvvi = false: the two ports and every related mux, register, and counter are absent
  from the elaborated design.
- v0 CommitWidth = 1: one read lane.
- The read never backpressures retirement.
- A read of a prd written in the same cycle returns the new value.
- The CommitUnit reads the retiring head's newPrd to form `RetireToken.wdata`; when
  `wen = 0` (no destination or rd = x0) no read is issued and `wdata = 0`.

### E-4 - RetireToken.priv is the executing privilege

`priv` is the architectural privilege the instruction executed in - the privilege before
any transition it causes:

| Event | priv |
|---|---|
| ordinary retirement | current privilege |
| trapping instruction | the privilege that raised the trap |
| MRET / SRET | the privilege before the xRET |
| interrupt / debug entry | the privilege before the entry |

The new privilege is observed from the next token on. The CsrController provides it through
`InterruptCtrl.priv` (the committed privilege view), so the CommitUnit never reads
TrapController state back. `InterruptCtrl` is fixed as
`{interruptPending, interruptCause, debugMode, priv}`, where interruptPending already
applies mip & mie, mideleg, mstatus.MIE/SIE and priv.

### E-5 - the verification stream is a stream of observation events

ADR-010's "trap entry is a retire token" stays, but a trap-entry token is not an
architectural retirement. The stream carries two event kinds in program order:

- a normal retirement event (`trap = 0`), and
- a precise trap-entry event (`trap = 1`, with `source = Sync | Interrupt | Debug`).

A synchronous-exception head fires no RenameCommit, StoreCommit, FtqCommit, or CommitGrant;
it does not retire from the ROB (the hand-off locks the head); it emits only
`RetireToken{trap = 1}`; the ArchRedirect that follows removes it and the younger window.
Interrupt and debug entry emit a trap-entry token for the head they are taken in front of.
`order` increases by one per stream event. The Debug hand-off uses `cause = 3`
(debug-spec haltreq).

### E-6 - retiring architectural redirects are atomic with their hand-off

FenceI Refetch, SfenceVma Refetch, CsrWrite Refetch, predictionFault Refetch, and Mret/Sret
XRet retire the instruction itself. In the final cycle every required normal projection and
the ExceptionOut must be ready together:

`RobHeadIn.fire`, `RenameCommit`, `StoreCommit` (if a store), `FtqCommit` (if blockEnd),
`CommitGrant` (if a CSR uop), `RetireToken`, and `ExceptionOut{SysOp}` are one retirement.

If the TrapController cannot accept the ExceptionOut, the instruction does not retire yet.
After the transfer the CommitUnit holds (trapPending) and commits nothing until the
ArchRedirect RecoveryEvent naming that robTag. Non-retiring redirects - synchronous trap,
interrupt, debug - send ExceptionOut without retiring the head, as before.
`Exception.sysOp` carries the head's SysOp: Mret/Sret request XRet; FenceI, SfenceVma,
CsrWrite, and None (predictionFault) request Refetch.

## Consequences

- RecoveryController: Debug is a legal ArchRedirect cause.
- CommitUnit: WFI has no wait state; new commit PRF read ports under usingRvvi; the final
  cycle of every retiring redirect is one atomic transfer with its ExceptionOut.
- PhysicalRegisterFile: the commit read port is the E-3 interface.
- BackendTop: new edges `CommitUnit -- CommitPrfReadReq --> PRF` and
  `PRF -- CommitPrfReadResp --> CommitUnit`.
- ADR-010 D-10.1 "emitted only for tokens that actually retire" reads, for the ADR-019
  machine, as E-5.

## Verification obligations

L1 CommitUnit tests for ordinary atomic commit, per-consumer backpressure without partial
commit, blockEnd, read-only CSR, CsrWrite, FENCE, FENCE.I, SFENCE.VMA, MRET/SRET, WFI,
synchronous exception, interrupt/debug priority, trap hold under TrapController
backpressure, unrelated RecoveryEvents, HeadMemGrant once and grantInFlight suppression,
the uncached completion paths, the usingRvvi read/wdata/order path, and the structural
absence of the retire machinery at usingRvvi = false; plus @LocalSpec asserts for
propCommitInOrder, propNoCommitPastException, propTrapHoldUntilRedirect,
propRetireNonBlocking.
