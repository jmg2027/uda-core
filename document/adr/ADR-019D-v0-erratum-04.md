# ADR-019D: ADR-019 v0 Erratum 04 - Native CSR execution and trap-state seam

Status: **accepted** (owner ruling, 2026-09-25).

Amends: ADR-019 (the CSR execution edge, the CsrController/TrapController contracts, the
CommitUnit interrupt-sampling rule) and ADR-004 as re-based by ADR-019.
Baseline: the `242feaf` freeze plus ADR-019A, ADR-019B, and ADR-019C, all immutable
history. This erratum is applied on top of them; every DSL change it causes cites
`ADR-019D`.

## Context

HANDOFF C-3: the backend CSR edge still used the legacy `CSRReq`/`CSRResult` bundles of
the superseded machine (csr/CSR.scala). They carry `meta{rd, epoch}` and no robTag or
prd, so a CSR result cannot complete its ROB entry, cannot write its physical
destination, and cannot be ordered by PublishMux. The rest of the CSR/trap seam
(`Interrupt{e,t,s}`, `CsrTrapRead{mtvec,mstatus}`, `CsrTrapWrite{mepc,...}`) is the same
M-mode-only legacy and does not match the M/S/U trap contract. Patching robTag/prd onto
the legacy bundles would keep an epoch-based boundary alive; this erratum replaces the
whole boundary instead. No compatibility alias of the old boundary survives.

A second defect was found while specifying the CSR commit protocol: funcInterruptSampling
samples interrupts and debug requests "at a retire boundary", which includes the first
cycle a serialize head is presented. An interrupt taken there kills a CSR uop whose write
is already staged in the CsrController (and, in general, re-executes a serializing
instruction after it had already been the only live uop). That contradicts the staged
write protocol below; E-5 fixes the existing contract.

## Decision

### E-1 - native CSR edge

`DispatchUnit -> CsrController` carries `IssuedUop`, and `CsrController -> PublishMux`
carries `FuResult`. Dispatch `CsrReqOut` is `Decoupled[IssuedUop]`; PublishMux
`CsrResultIn` is `Decoupled[FuResult]` and joins the oldest-live arbitration exactly like
every other result input. The backend no longer uses `CSRReq`/`CSRResult`; both bundles
are deleted, together with every epoch field and the legacy csr/CSR.scala machine. The
PublishMux assertion "a CSR result cannot be published before C-3" is removed.

### E-2 - IssuedUop metadata

`IssuedUop` (and the RS entry and RS output that produce it) gains `insn` (the 32-bit
instruction word) and `sysOp`. For a CSR uop:

- csrAddr = insn[31:20]; uimm = zext(insn[19:15]); the illegal-instruction tval = insn;
- `sysOp == CsrWrite` means write semantics and `sysOp == None` means a read-only access.
  Write intent is never inferred from the runtime operand value (a CSRRS whose rs1 holds
  zero at run time still writes if rs1 != x0 in the encoding).

### E-3 - native CsrOp layout

`CsrOp` is a unit-local layout under `UopOp` with the values RW, RS, RC, RWI, RSI, RCI,
set by the DecodeUnit. The operand is `src1` for the register forms and zext(insn[19:15])
for the immediate forms; the CSR address is insn[31:20].

Write-intent rule (new PROPERTY `propCsrWriteIntent`, an assertion on every accepted CSR
uop that sysOp == CsrWrite agrees with it):

- CSRRW and CSRRWI always write;
- CSRRS and CSRRC write iff the encoded rs1 != x0;
- CSRRSI and CSRRCI write iff uimm != 0;
- CSRRW[I] with rd = x0 need not read but still writes;
- the rs1 = x0 / uimm = 0 forms of CSRRS[I]/CSRRC[I] are true read-only accesses.

### E-4 - CsrController execution protocol

The CsrController holds one execution context (CSR uops are serialized). On an accepted
IssuedUop it checks existence, privilege, read-only/write legality, and the TVM/satp rule,
and reads the committed value when required. It returns one FuResult:

| field | value |
|---|---|
| robTag, prd | copied from the IssuedUop |
| wen | hasDest && legal |
| data | the old CSR value |
| exception | illegal instruction (cause 2, tval = insn) on an illegal access, else none |
| cfiOutcome | None |

A legal write stages {robTag, csrAddr, newValue} without mutating state. The staged write
applies only when `CommitGrant.valid && CommitGrant.robTag == staged.robTag`; a CommitGrant
that names a different robTag while a write is staged is an assertion failure. An illegal
access stages nothing and returns exception.valid = 1, cause illegal instruction,
tval = insn, wen = 0. A read-only access stages nothing; its CommitGrant writes nothing.
`propNoSpeculativeCsrWrite` becomes a simulation assertion.

### E-5 - serialize-head interrupt/debug suppression (CommitUnit bug fix)

A presented head with `serialize` set suppresses interrupt and debug sampling until that
head retires or traps. This covers CSR read, CSR write, FENCE, FENCE.I, SFENCE.VMA, WFI,
MRET, and SRET. The pending interrupt or debug request is sampled at the next retire
boundary. This fixes the existing funcInterruptSampling contract; it is not a new
architectural choice.

### E-6 - native trap/CSR seam bundles

- `Interrupt` = {meip, mtip, msip, seip, stip, ssip}: raw lines, one definition (the core
  bundle is the same payload).
- `CsrTrapRead` = {mstatus, mepc, mcause, mtval, mtvec, medeleg, mideleg, mie, mip, sepc,
  scause, stval, stvec, priv, dpc, dcsr}.
- `CsrTrapWrite` = {kind, xepc, xcause, xtval, mstatusNext, privNext, dpc, dcsrNext}, with
  kind in TrapEntryM | TrapEntryS | MRet | SRet | DRet | DebugEntry. kind selects the M or
  S trap registers: TrapEntryM writes mepc/mcause/mtval, TrapEntryS writes
  sepc/scause/stval; both write mstatus := mstatusNext and priv := privNext. MRet and SRet
  write mstatus and priv only. DebugEntry writes dpc, dcsr := dcsrNext, priv := privNext,
  and enters debug mode. DRet writes priv := privNext and leaves debug mode.

Every legacy epoch-based trap/CSR field is deleted.

### E-7 - CsrController is the sole committed CSR-state owner

The CsrController owns every committed CSR register, the privilege, and debug mode. The
TrapController computes transitions but owns no CSR register. Exactly two mutation paths
exist:

1. a software write, applied only by a CommitGrant that matches the staged robTag;
2. `CSRTrapWrite.fire`.

There is no third path. Both paths in one cycle is an assertion failure unless they are
part of one retiring transition; in v0 no retiring transition produces both (a retiring
CsrWrite requests Refetch, which carries no CSRTrapWrite), so the assertion is
unconditional. `TranslationContext`, `InterruptCtrl`, and `CsrTrapRead` are combinational
views of that single state. New PROPERTY `propCsrSingleOwner`.

## Consequences

- Deleted: `CSRReq`, `CSRResult`, their meta/epoch classes, `BackendParams.legacyCsrEpochWidth`,
  `BackendModule.epochWidth`, and csr/CSR.scala. DebugUnit/TriggerUnit (pure-function leaf
  IP, ADR-004 D-4.7) stay.
- `bndIssuedUop` gains insn and sysOp; DispatchUnit, PublishMux, CsrController, and
  TrapController interfaces use the native bundles; CoreBundlesSpecs and BackendBundlesSpecs
  describe the same six-line Interrupt.
- New PROPERTYs `propCsrWriteIntent` and `propCsrSingleOwner`; funcCsrExecuteAtCommit and
  funcInterruptSampling are rewritten.

## Gaps left explicit (not decided here)

- The debug-entry target PC is not specified by any contract. v0 uses a design parameter
  (`debugEntryPc`) until the owner fixes the debug-module ROM address.
- DRet is a CSRTrapWrite kind, but no v0 SysOp produces it (ADR-019A E-2 lists None,
  Fence, FenceI, SfenceVma, Wfi, Mret, Sret, CsrWrite). Leaving debug mode therefore has no
  v0 producer; the CsrController implements the DRet application for when one is added.

## Verification obligations

L1 tests, red before green:

- CSR execution: CSRRW register operand; CSRRS/CSRRC; CSRRWI/CSRRSI/CSRRCI; the rs1 = x0
  and uimm = 0 read-only boundaries; rd = x0 CSRRW[I] write-without-read; robTag/prd/wen
  survive request -> FuResult; illegal/nonexistent CSR; privilege violation; read-only CSR
  write attempt; satp + TVM violation; result backpressure stability; a CSR result through
  PublishMux creates the PRF write, wakeup, and ROB completion; no CSR result loses robTag.
- Commit gating: result publication never mutates CSR state; a matching CommitGrant
  applies the staged write exactly once; a wrong-robTag grant asserts; a read-only CSR
  CommitGrant writes nothing; an illegal CSR creates no staged write; a CsrWrite commit
  changes TranslationContext where applicable.
- Trap/CSR seam: raw M/S interrupt views; CsrTrapRead field correctness; TrapEntryM and
  TrapEntryS applications; MRET/SRET application; DebugEntry; TranslationContext after
  trap/xRET; software CSR write and trap write remain single-owner paths.
- CommitUnit regression: an interrupt and a debug request pending on cycle 0 of every
  serialize-head class do not preempt it.
- Mutants for the write-intent boundaries.
