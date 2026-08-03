# ADR-004: CSR Serialization and Single-Owner Trap State

Status: **accepted**.

Depends on: ADR-011 (redirect at commit head), ADR-002 (commit in order),
ADR-012 (commitGrant is a view of the unified commit broadcast), ADR-013
(redirect merge). Requires owner sign-off on OQ-E (waive DO NOT TOUCH).

## Context

The accepted review names CSR/exception serialization as a blocking hole:
`CSRCore` commits side effects the cycle a request arrives (`csr/CSR.scala:173-175,
282-366`), which corrupts architectural state if a CSR uop is dispatched
speculatively from the RS; and trap CSRs have a double-fire seam (external
`trapWriteFire` vs an internal `exception`/`mret` writer,
`csr/CSR.scala:481-524`) with an empty `TrapController` shell
(`modules/TrapController.scala:10-14`). The BackendTop graph already draws the
correct edges (`BackendTopSpecs.scala:114-118`); the problem is that the CSR IP
does trap work the graph assigns to TrapController.

The correctness critique (M2) flagged that P01 s2 ("CSR never visits an FU,
executes at commit") and P04 s1 ("CSR FU visited from the RS, read old value,
stage the write") describe different machines. This ADR ratifies P04's
read-in-FU / write-at-commit as the single contract and strikes P01's "never from
the RS" language.

## Decision

**D-4.1 (normative, execute-at-commit).** A CSR uop flows through rename, RS, and
dispatch as an ordinary uop. Its FU visit is a **pure read plus a staged intent**:
it returns the OLD CSR value on the publish bus (the `rd` writeback CSRRW/RS/RC
owe) and emits the intended write `{addr, wdata}` as commit-pending payload. NO
CSR register is mutated during the FU visit. The architectural mutation is
qualified by the **commitGrant** strobe (ADR-012, keyed by canonical seqTag) for
the retiring uop. At N=1 the FU visit and commitGrant coincide; the cost is one
AND term on the existing `wen`. This resolves critique M2 in favor of P04; P01 s2
"never speculatively from the RS" is struck - the CSR FU exists and the
old-value `rd` rides the publish bus.

**D-4.2 (normative, serializing class).** Decode tags CSR, `mret`, `dret`, `wfi`,
`fence`, `fence.i`, `ecall`, `ebreak` with a `serializing` bit. Dispatch grants
the CSR/system edge `ready` only when no serializing uop is in flight (1-bit
scoreboard set at dispatch, cleared at commitGrant). Ready backpressure (FCL edge
property), never a node-internal stall counter. Effect: at most one un-committed
CSR/system uop exists, so a CSR read never observes a not-yet-committed CSR write,
and program order among context-changing ops is preserved.

**D-4.3 (normative, single trap owner).** Delete the CSR-internal trap writer
(`csr/CSR.scala:405-408, 494-524`) - requires owner sign-off (OQ-E). TrapController
is the SOLE producer of trap-CSR and privilege transitions. CommitUnit detects the
trap at the commit head and emits `Exception`; TrapController reads the live
snapshot over `CSRTrapRead`, computes `{mepc, mcause, mtval, mstatus', privNext,
dpc, dcsr'}` and the vector target, and drives exactly one `CSRTrapWrite` and one
`Redirect`. `mret`/`dret` privilege restore and debug entry are also
TrapController-emitted `CSRTrapWrite`+`Redirect` pairs. The CSR retains only the
commit-gated software CSRRW/RS/RC datapath.

**D-4.4 (normative, mutual exclusion).** Because commit is strictly in order with
a single head (ADR-002/011), a trap application and a retiring software write to
the same trap CSR cannot both be the head in the same cycle; the residual mux at
`csr/CSR.scala:484` is a proven one-hot select, not a seam.

**D-4.5 (normative, interrupts at commit).** Async interrupts join program order
only at the commit boundary. The pending/enable compute stays in the CSR
(`interruptCtrl`); the decision to take moves to CommitUnit, which emits an
`Exception{source=Interrupt, cause, mepc=PC of next uncommitted uop}` when
enabled+pending at a retire boundary with no synchronous exception and not in
debug mode. The interrupt's Redirect increments the global epoch; younger work
self-squashes. Because it is consumed only at the head, `epoch===globalEpoch`
holds by construction, so interrupts never alias across the epoch wrap.

**D-4.6 (normative, serializing-op semantics).** `fence` gates retire on
`StoreBuffer.empty` (ADR-003 D-3.13; the drain-complete predicate is a memory
subsystem backpressure condition, MDG). `fence.i` = `fence` + a `Redirect`
(target `pc+4`) after older stores drain, forcing refetch. `wfi` parks the commit
head (no retire, `mcycle` continues), waking on any `mip & mie` or debug request;
interrupts are masked in debug mode.

**D-4.7 (normative, debug/trigger commit-gated).** Trigger/step/ebreak fire is
qualified by the commit head; debug entry is a `CSRTrapWrite{kind=DebugEntry}` +
`Redirect` through the single trap-write owner, keeping `dpc`/`dcsr`/`priv` under
one-writer discipline. TriggerUnit/DebugUnit remain pure-function leaf IP; only
their effect is commit-gated.

Spec to write (`CsrControllerSpecs.scala`, `TrapControllerSpecs.scala`,
`CommitUnitSpecs.scala` for interrupt sampling; bundles in `BackendBundlesSpecs.scala`
owned by WP-A) - CSR/trap portions are WP-D, the commit-side interrupt sampling
and bundle definitions are WP-A. See work orders.

## Alternatives rejected

- **Drain-then-execute (hold dispatch until pipe empty).** Rejected: draining
  requires a node-internal stall counter (forbidden); a full drain per CSR op is a
  large IPC cliff. Single-in-flight serialize gives the same correctness with one
  flag.
- **Execute-in-RS with CSR checkpoint/rollback.** Rejected: CSR state is large and
  un-renamed; checkpointing is absurd area and violates no-writeback-on-commit.
- **Keep the CSR-internal trap path (make CSR the owner).** Rejected: pushes trap
  policy into leaf IP shared with debug/trigger, contradicts the BackendTop graph,
  leaves TrapController meaningless. Two writers cannot be verified one-hot without
  a global argument; single ownership makes it structural.

## Consequences

- N=1: one AND on `wen`, one in-flight bit, one interrupt comparator; deletes the
  speculative-write undo path and the internal exception encoder - net negative
  area.
- N=32: identical - a 1-bit scoreboard and a dispatch-ready gate; trap encode is
  combinational and window-independent; CSR/system ops are rare.
- Unifies synchronous and asynchronous traps on one `Exception` edge with one
  owner (TrapController), so precise exceptions rest entirely on commit ordering.

## Verification obligations

- Assert `csr_reg_write_enable |-> commitGrant.valid && commitGrant.seqTag ===
  req.seqTag && req.epoch === globalEpoch` (simulation monitor; no speculative CSR
  write). Typed as simulation-assert, not "SVA/formal" (ADR-015 fixes the
  mislabeling critique V-MA-2/6).
- Assert `$onehot0(serializing_in_flight_count)`.
- Assert per trap CSR: at most one write-enable/cycle, and any trap-driven enable
  originates from `CSRTrapWrite.fire`; the deleted internal path is gone
  (structural check).
- Directed: CSRRW on a mispredicted path leaves the target CSR unchanged; nested
  trap; `mret` restores `mstatus.mie` from `mpie`; illegal-CSR raises with
  `mtval=instruction bits`; MTIP mid-stream between two stores yields both stores
  committed and `mepc`=un-retired PC; `fence.i` after a self-modifying store
  observes new bytes; single-step retires exactly one instruction then enters
  debug.
