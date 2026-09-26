# ADR-019E: ADR-019 v0 Erratum 05 - Native CSR map contribution and debug return contract

Status: **accepted** (owner ruling, 2026-09-25).

Amends: ADR-017 D-17.3 (CSR contribution) for the ADR-019 machine; ADR-019 (the DecodeUnit,
CsrController, TrapController, CommitUnit, and parameter contracts); ADR-019D (the debug gaps
it left open).
Baseline: the `242feaf` freeze plus ADR-019A, ADR-019B, ADR-019C, and ADR-019D, all immutable
history. This erratum is applied on top of them; every DSL change it causes cites `ADR-019E`.

## Context

- HANDOFF C-4: funcCsrMapContribution merged CSR maps into `CsrAccess.readFromCsr`, which
  infers write intent from the runtime operand and mutates state in the access cycle. Both
  contradict ADR-019D E-2/E-4.
- ADR-019D left two debug gaps: the debug-entry address had no contract (a backend-private
  constant was used), and no v0 instruction could leave Debug Mode (CSRTrapWrite kind DRet
  had no producer).
- funcDecodeRv32im requires privilege-dependent legality (MRET, SRET, SFENCE.VMA, WFI) but the
  DecodeUnit had no input that carries the committed privilege or the mstatus trap bits.

## Decision

### E-1 - the native staged-write CSR map is authoritative (C-4)

The CSR access protocol of the ADR-019 machine separates:

1. decode-time encoded write intent (sysOp CsrWrite, ADR-019D E-2/E-3);
2. execute-time committed-state read and legality check;
3. computation and staging of the candidate new value;
4. architectural mutation only on the matching CommitGrant.

No CSR helper or library may infer whether an instruction writes from the runtime operand
value, and none may mutate architectural CSR state when the execution request is accepted.
The CsrController's native map is the v0 reference implementation.

ADR-017 D-17.3 is amended for the ADR-019 machine: base CSRs and extension CSRs contribute
declarative map entries (descriptors) into the single map the CsrController owns. A
descriptor may provide the CSR address, readable/writable properties, privilege/access
metadata, the read-value source, the WARL/legalization behavior, and the committed write
target (application function). It does not own Zicsr instruction semantics, write-intent
classification, request timing, CommitGrant timing, or a second mutation path: the
CsrController alone invokes a descriptor's application function, and only for the staged
write named by the matching CommitGrant. Duplicate addresses are rejected at elaboration.
The generic `common/system/csr` library may still supply storage fields and WARL helpers;
`CsrAccess.readFromCsr` is not the ADR-019 access protocol.

### E-2 - the debug entry address is platform-defined

`debugEntryAddr` is the platform / Debug Module supplied execution address used when the hart
enters Debug Mode. Its value is implementation-specific; the current verification platform
uses 0x800 as its default. The source of truth is the core/system integration contract:
`CoreContractParams.debugEntryAddr`, exposed through `CoreApiParams` for the parent/Debug
Module integration and mirrored into `BackendParams` for the TrapController. The TrapController
DebugEntry redirect targets this parameter.

### E-3 - DRET is part of v0 debug completion

`SysOp.Dret` is added; SysOp widens from 3 to 4 bits (no aliasing of an existing encoding).
DRET (0x7b200073) is legal only in Debug Mode and illegal (cause 2, tval = insn) otherwise.
It is fuType System, serialize, execution-free (done at ROB allocation, no RS allocation), and
a retiring architectural redirect:

- CommitUnit: DRET retires normally; its commit projections and ExceptionOut{SysOp, Dret} are
  atomic, and trapPending holds until the matching ArchRedirect (including the zero-latency
  case), exactly like MRET/SRET.
- TrapController: CSRTrapWrite{kind = DRet, privNext = dcsr.prv}, ArchRedirect target = dpc,
  cause = XRet.
- CsrController: applies DRet through its single trap-write path (priv := privNext, Debug Mode
  cleared).

### E-4 - the committed decode-privilege view

New rawNoDecoupled class-4 bundle `DecodePrivView` = {priv, debugMode, tvm, tw, tsr},
published by the CsrController from committed state (never from the instruction being
decoded) to the DecodeUnit: BackendTop edge `CsrController -. DecodePrivView .-> DecodeUnit`.
The DecodeUnit uses it for the privileged-instruction legality it already owns; Debug Mode
decodes as M. With p = debugMode ? M : priv:

| instruction | legal iff |
|---|---|
| MRET | p = M |
| SRET | p = M, or p = S and !TSR |
| SFENCE.VMA | p = M, or p = S and !TVM |
| WFI | p = M, or p = S and !TW (U-mode WFI is illegal: v0 has S-mode and a zero WFI time limit) |
| DRET | debugMode |

An illegal case becomes the existing precise illegal-instruction payload (cause 2, tval =
insn) and enters the ROB carrying it; nothing is postponed to the CommitUnit.

### E-5 - debug entry and resume keep separate meanings

On a halt-request DebugEntry, dpc is the virtual address of the next instruction that would
execute (in the CommitUnit sampling model, the current ROB head PC), dcsr records the previous
privilege and the debug cause, and execution redirects to debugEntryAddr. On DRET the target
is dpc, the privilege is restored from dcsr.prv, and Debug Mode clears. debugEntryAddr is
never dpc: dpc is the normal-execution resume address, debugEntryAddr is where debug-mode
code executes.

## Consequences

- funcCsrMapContribution is rewritten (declarative descriptors); ADR-017 carries a pointer.
- New `paramDebugEntryAddr` (core contract tier); `BackendParams.debugEntryAddr` mirrors it.
- `SysOp.Dret`, SysOp width 4; funcSerializingTag, funcSystemOpSequencing, funcXRet, and
  funcDebugCommitBoundary name DRET.
- New bundle `DecodePrivView`, CsrController `DecodePrivViewOut`, DecodeUnit
  `DecodePrivViewIn`, new DecodeUnit function `SystemPrivLegality`, BackendTop edge.

## Verification obligations

L1 tests, red before green: CSR contribution (synthetic contributed CSR read, staged write, no
mutation before CommitGrant, encoded write intent authoritative with operand 0, duplicate
address rejected, no second write owner); DebugEntry with the default and a non-default
debugEntryAddr, dpc stays the next normal PC; DRET decode legal/illegal, ROB-done without RS
allocation, CommitUnit atomic retirement with the SysOp Dret hand-off and the zero-latency
redirect, TrapController DRet to dpc, CsrController debug-mode clear and dcsr.prv restore,
end-to-end DebugEntry -> Debug Mode -> DRET -> normal execution; DecodePrivView legality with
mutants ignoring TVM/TW/TSR/debugMode.
