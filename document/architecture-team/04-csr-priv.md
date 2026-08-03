# Position Paper 04: CSR, Privilege, and Trap Architecture

Author: CSR / privilege / trap architect
Status: Normative proposal for panel ratification
Scope: CSR serialization, single-owner trap state, interrupt sampling,
exception precision, debug/trigger integration -- for the parametric
in-order-to-OoO (N=1..32+) unified-PRF backend.

This paper is normative. Each section states a contract, the alternatives
rejected, cost at N=1 (must be ~zero) and N=32, verification obligations, and
open questions for the panel. Spec-DSL snippets are written against the stub
DSL in `src/main/scala/framework/specs/Spec.scala:6-31` and are ready to paste
into the relevant `*Specs.scala` files.

---

## 0. The seam we are closing (grounding)

The accepted review names CSR/exception serialization as one of the three
blocking spec holes: "CSRCore commits side effects the cycle a request
arrives; dispatched speculatively from an RS this corrupts architectural state
with no undo... a single owner for mepc/mcause writes (the current
trapWrite-vs-internal priority mux is a double-fire seam)."
(`document/rebuild_branch_architecture_review.md:133-138`).

Both defects are live in the surviving CSR IP:

1. **Speculative side effects.** `CSRCore` asserts `io.req.ready := true.B`
   and `io.resp.valid := io.req.valid` (`.../csr/CSR.scala:173-175`), and the
   write block `when(wen){ ... csr.mstatus.write(wdata) ... }`
   (`.../csr/CSR.scala:282-366`) mutates architectural CSR registers the same
   cycle a request is valid. Any CSR uop dispatched from the reservation
   station applies its write with no commit qualification and no undo.

2. **Double-fire trap writer.** Trap CSRs (`mepc`, `mcause`, `mtval`,
   `mstatus`) have two independent writers muxed by priority: the external
   trap edge `when(trapWriteFire){ csr.mepc.write... }`
   (`.../csr/CSR.scala:481-493`) and a CSR-internal exception path
   `.elsewhen(exception){ csr.mstatus.reg.mpie := ...; csr.mepc.write... }`
   (`.../csr/CSR.scala:494-524`), where `exception` is recomputed inside the
   CSR from `io.sys.ecall/ebreak`, `illegalAccess`, and `io.trap.exception`
   (`.../csr/CSR.scala:405-408`). `mret` privilege restore is a third
   in-CSR writer (`.../csr/CSR.scala:507-524`). The `TrapController` shell
   that is supposed to own this (`.../modules/TrapController.scala:10-14`) is
   empty, so the authoritative trap logic lives in the wrong vertex.

The `BackendTop` graph already draws the correct edges we will make load
bearing: `com -- Exception --> trap`, `csr -- CSRTrapRead --> trap`,
`trap -- CSRTrapWrite --> csr`, `trap -- Redirect --> ru`
(`.../backend/spec/top/BackendTopSpecs.scala:114-118`). The problem is purely
that the CSR IP does trap work the graph assigns to the TrapController. This
paper makes the graph normative and empties the CSR of trap policy.

Paradigm mapping (per `docs/foundations/unified-microarchitectural-paradigms.md`):
CSR/trap policy is an **ECA** (execution-context algebra, section 1) concern --
privilege mode and the trap stack are execution context; the commit-gated
write-enable is an **FCL** (flow-control lattice, section 4) backpressure
property; fence/fence.i drain is **MDG** (memory-dependence, section 3).

---

## 1. CSR serialization: execute-at-commit, single-in-flight serializing class

### Contract (normative)

**C1.1 -- No speculative architectural effect.** A CSR uop flows through
rename, reservation station, and dispatch as an ordinary uop. Its functional-
unit visit is a **pure read plus a staged intent**: it returns the *old* CSR
value onto the publish bus (this is the `rd` writeback that CSRRW/CSRRS/CSRRC
owe), and it emits the *intended* write (`{addr, wdata}`) as commit-pending
payload. **No CSR register is mutated during the FU visit.**

**C1.2 -- Write applies at the commit grant.** The architectural CSR mutation
is qualified by a one-cycle `commitGrant` strobe keyed to the uop's `uopId`
and `epoch`, produced by the in-order commit point for the retiring
instruction. Because the base configuration may legally execute the whole
backend in one cycle (`dontcommit.md` items 5-6: "Cycle 1: Decode, Rename, RS
wakeup/issue, FU execute, Commit"), "execute-at-commit" collapses at N=1 to
"the same cycle, but `wen` is ANDed with `commitGrant`." The serialization is
an **edge property** (a commit-grant edge into the CSR node), never stall
logic inside the node -- honoring "no stall logic or pipeline registers in
node" (`dontcommit.md` item 6).

**C1.3 -- CSR/system ops are a serializing class.** Decode tags CSR, `mret`,
`dret`, `wfi`, `fence`, `fence.i`, `ecall`, `ebreak` with a `serializing`
bit. Dispatch grants the CSR/system edge `ready` only when **no serializing
uop is in flight** (in-flight tracked by a 1-bit scoreboard set at dispatch,
cleared at commit). This is ready backpressure -- an FCL edge property -- not a
counter-stall inside a vertex. The effect: at most one un-committed CSR/system
uop exists, so CSR reads never observe a not-yet-committed CSR write, and
program order among context-changing ops is preserved by construction.

### Spec-DSL to add to `CsrControllerSpecs.scala`

```scala
val intfCommitGrantIn = spec {
  INTERFACE("CommitGrantIn")
    .desc("Per-uop commit strobe qualifying every architectural CSR write.")
    .uses(bndCommitGrant)
    .note("commitGrant.valid pulses for exactly the retiring uopId; the CSR " +
          "node ANDs it into every register write-enable. No grant, no write.")
    .build()
}

val funcCsrExecuteAtCommit = spec {
  FUNCTION("CsrExecuteAtCommit")
    .desc("CSR uop reads architectural value at FU visit and returns it as " +
          "rd writeback; the intended write is staged and applied only on " +
          "the matching commitGrant strobe.")
    .uses(intfCsrReqIn, intfCsrResultOut, intfCommitGrantIn)
    .note("At N=1 the FU visit and commitGrant occur in the same cycle; the " +
          "cost is one AND term on the existing write-enable.")
    .entry("rule", "csr_write_enable := decoded_wen AND commitGrant.valid " +
                   "AND (commitGrant.uopId === req.meta.uopId) AND epoch_match")
    .build()
}

val funcSerializingClass = spec {
  FUNCTION("SerializingClass")
    .desc("CSR/mret/dret/wfi/fence/fence.i/ecall/ebreak are serializing; at " +
          "most one is in flight. Dispatch withholds ready on the CSR/system " +
          "edge while a serializing uop is outstanding.")
    .note("Single-bit in-flight scoreboard: set on dispatch fire, cleared on " +
          "commitGrant. Backpressure only; no node-internal stall counter.")
    .build()
}
```

The `bndCommitGrant` bundle (add to `BackendBundlesSpecs.scala`): fields
`uopId`, `epoch`, `valid`. This edge is the same commit strobe CommitUnit
already needs for map-table update; CSR reuses it.

### Alternatives considered and rejected

- **Drain-then-execute** (hold dispatch until the pipe is empty, then let the
  CSR write freely). Rejected: draining requires counting in-flight uops and
  stalling a node until zero -- forbidden by `dontcommit.md` item 6. At N=32 a
  full drain per CSR op is a large IPC cliff, and "pipe empty" is not
  expressible as a local edge predicate. Our single-in-flight serialize gives
  the same correctness with one flag.

- **Execute-in-RS with CSR checkpoint/rollback.** Rejected: CSR state is large
  and un-renamed; checkpointing every CSR to allow speculative writes is
  absurd area at any N and violates the "no data copy on commit" philosophy
  (`BackendTopSpecs.scala:134-137`).

- **Speculative CSR read, non-speculative write, multiple CSR reads in
  flight.** Deferred, not rejected -- see open question OQ1. It buys nothing at
  N=1 and complicates the read-old-value semantics of CSRRW.

### Cost

- **N=1:** one AND gate on the pre-existing `wen` and a 1-bit in-flight flag.
  The commit and execute cycles coincide. Net area is *negative* because C1.1
  lets us delete the speculative write path's need for any undo machinery.
- **N=32:** a 1-bit scoreboard and one dispatch-ready gate. CSR/system ops are
  rare; the serialize bubble touches only those ops while the window keeps
  flowing for surrounding non-serializing uops up to the CSR op.

### Verification obligations

- SVA: `csr_reg_write_enable |-> commitGrant.valid && (commitGrant.uopId ==
  req_uopId) && (req_epoch == globalEpoch)`. Proves no speculative CSR write.
- SVA: `$onehot0(serializing_in_flight_count)` -- never two serializing uops.
- Directed test (reuse the `SingleCoreMulDivClusterTest` harness pattern):
  place a CSRRW on a mispredicted path; assert the target CSR (e.g.
  `mscratch`) is unchanged after the squash.

### Open questions

- **OQ1:** Do we permit concurrent CSR *reads* (serialize only writes), or
  strict single-in-flight for the whole class? Strict is proposed for N=1
  simplicity.

---

## 2. Single ownership of mepc/mcause/mstatus: TrapController is the only writer

### Contract (normative)

**C2.1 -- Delete the CSR-internal trap writer.** The CSR node must not compute
`exception` (`.../csr/CSR.scala:405-408`) and must not self-write trap CSRs on
`exception` or `mret` (`.../csr/CSR.scala:494-524`). This deliberately edits
logic marked `AGENT: DO NOT TOUCH CORE LOGICS` (`.../csr/CSR.scala:158`); it is
the sanctioned spec-first change and requires owner sign-off (OQ5).

**C2.2 -- TrapController owns all trap-stack and privilege transitions.**
CommitUnit detects the trap condition at the in-order commit head and emits the
`Exception` edge (already in graph, `BackendTopSpecs.scala:116`). TrapController
reads the live control snapshot over `CSRTrapRead`
(`BackendTopSpecs.scala:114`), computes the full trap application packet
`{mepc, mcause, mtval, mstatus'}` and the vector target, and drives exactly one
`CSRTrapWrite` edge into the CSR (`BackendTopSpecs.scala:115`) and exactly one
`Redirect` edge into the RedirectUnit (`BackendTopSpecs.scala:117`,
`RedirectUnitSpecs.scala:23-28`). The CSR applies `CSRTrapWrite` as its only
trap-driven mutation (`trapWriteFire`, `.../csr/CSR.scala:484-493`).

**C2.3 -- mret/dret return is also TrapController-owned.** The privilege
restore (`mstatus.mie <- mpie`, `mpie <- 1`, `priv <- mpp`, target `<- mepc`;
dret: `priv <- dcsr.prv`, target `<- dpc`) is emitted by TrapController as a
`CSRTrapWrite` + `Redirect` pair when CommitUnit flags an `mret`/`dret` at the
commit head. This makes TrapController the *single owner* of every
`mstatus.mie/mpie`, `mpp`, and `priv` transition -- trap entry and trap return
alike. The CSR node retains only the software CSRRW/RS/RC datapath
(`.../csr/CSR.scala:282-366`), now commit-gated per Section 1.

**C2.4 -- Provable mutual exclusion.** Because commit is strictly in order with
a single commit head, a trap application and a retiring software write to the
same trap CSR cannot both be the commit head in the same cycle. The remaining
two writers of each trap CSR (TrapController via `CSRTrapWrite`, and software
CSRRW via the commit-gated datapath) are therefore mutually exclusive by
construction, and the residual mux at `.../csr/CSR.scala:484` becomes a proven
one-hot select, not a seam.

### Rewritten edge bundles

The current `CsrTrapWrite`/`CsrTrapRead` bundles are close but must be pinned
as the complete trap contract. Replace the bundle specs in
`BackendBundlesSpecs.scala:182-194`:

```scala
val bndCsrTrapRead = spec {
  BUNDLE("CSRTrapRead")
    .desc("Live control snapshot the TrapController needs to encode a trap.")
    .note("Fields: mstatus, mtvec, mepc, dpc, dcsr, priv, medeleg " +
          "(read-only view; combinational, non-Decoupled snapshot).")
    .build()
}

val bndCsrTrapWrite = spec {
  BUNDLE("CSRTrapWrite")
    .desc("Complete trap/return application packet: the sole trap-driven " +
          "mutation of the trap CSR stack.")
    .note("Fields: kind (TrapEntry|MRet|DRet|DebugEntry), mepc, mcause, " +
          "mtval, mstatusNext, privNext, dpc, dcsrNext, epoch. " +
          "CSR applies this verbatim under trapWriteFire; no recomputation.")
    .build()
}
```

Matching design bundles supersede `.../backend/design/shared/BackendBundles.scala:207-218`.
Rewrite `TrapControllerSpecs` to make ownership explicit:

```scala
val funcTrapSingleOwner = spec {
  FUNCTION("TrapSingleOwner")
    .desc("TrapController is the sole producer of trap-CSR and privilege " +
          "transitions. It consumes Exception + CSRTrapRead and produces one " +
          "CSRTrapWrite and one Redirect per accepted trap or return.")
    .uses(intfExceptionIn, intfCsrTrapReadIn, intfCsrTrapWriteOut, intfRedirectOut)
    .note("Covers trap entry, mret, dret, and debug entry. The CSR node " +
          "performs no trap encoding and computes no 'exception' signal.")
    .entry("target(TrapEntry)", "mtvec.base<<2 (direct) or +cause<<2 (vectored)")
    .entry("target(MRet)", "mepc")
    .entry("target(DRet)", "dpc")
    .build()
}
```

### Alternatives considered and rejected

- **Keep the CSR-internal path, delete the external one** (make CSR the owner).
  Rejected: it pushes trap policy into a leaf IP shared with debug/trigger,
  contradicts the `BackendTop` graph (`BackendTopSpecs.scala:114-118`), and
  leaves the empty `TrapController` shell meaningless.

- **Two owners with a documented priority mux** (status quo). Rejected: this is
  exactly the double-fire seam the review flags
  (`rebuild_branch_architecture_review.md:138`). Two writers of architectural
  state cannot be verified one-hot without a global argument; single ownership
  makes it structural.

### Cost

- **N=1:** strictly *less* logic than today -- deletes the internal exception
  encode (`.../csr/CSR.scala:405-449, 494-524`) and moves it to a combinational
  TrapController that already exists in the graph. Same gate count, relocated.
- **N=32:** identical; trap encode is combinational and window-independent.

### Verification obligations

- SVA one-hot: for each of `mepc/mcause/mtval/mstatus`, at most one
  write-enable asserted per cycle, and any trap-driven enable originates from
  `trapWrite.fire`.
- SVA: `exception_taken |-> trap.CSRTrapWrite.fire` in the same trap event and
  no CSR-internal trap write exists (checked structurally, i.e. the deleted
  code path is gone).
- riscv-arch-test / directed: nested trap (trap during handler prologue),
  `mret` restores `mstatus.mie` from `mpie`, illegal-CSR raises with correct
  `mtval = instruction bits`.

### Open questions

- **OQ5:** Waive `AGENT: DO NOT TOUCH` on `CSRCore` for the C2.1 deletion?
  This is the load-bearing change; the rest is spec.

---

## 3. Interrupt sampling point: the in-order commit boundary

### Contract (normative)

**C3.1 -- Sample at commit.** Async interrupts (`meip/mtip/msip` via
`io.interrupt`, currently latched into `mip` at `.../csr/CSR.scala:453-462`)
join program order at exactly one place: the boundary between two retiring
instructions at the in-order commit head. The *pending/enable* computation
(`mie & mip & mstatus.mie`, today combinational at `.../csr/CSR.scala:468-470`)
stays in the CSR and is exported as `interruptCtrl`
(`.../csr/CSR.scala:591-592`), but the *decision to take* moves to CommitUnit.

**C3.2 -- Interrupts are traps on the Exception edge.** CommitUnit consumes the
`interruptCtrl` snapshot; when an interrupt is pending-and-enabled at a retire
boundary and the committing instruction has no synchronous exception and we are
not in debug mode, CommitUnit emits an `Exception` token tagged
`source = Interrupt` with `mepc = PC of the next (not-yet-retired)
instruction` and the interrupt `cause`. This unifies synchronous and
asynchronous traps into one edge with one owner (TrapController, Section 2).

**C3.3 -- Epoch interaction.** Taking an interrupt is not a mispredict of an
existing token; it *creates* a redirect. The `Redirect` TrapController emits
increments the global epoch, and younger fetched-but-uncommitted work squashes
through the normal `token.epoch === globalEpoch` mechanism
(`rebuild_branch_architecture_review.md:41-46`). The interrupt Exception token
carries the *current* (pre-increment) epoch sampled at the commit boundary;
because it is consumed only at the commit head, `epoch === globalEpoch` holds
by construction, so interrupts never alias across the 2-bit epoch wrap the
review flags (`rebuild_branch_architecture_review.md:100-105`).

**C3.4 -- WFI and debug-halt.** `wfi` (reg at `.../csr/CSR.scala:571-577`)
parks the commit head: no instruction retires, `mcycle` continues. The hart
wakes on any `mip & mie` bit regardless of `mstatus.mie`, or on a debug
request -- matching the existing wake condition
(`.../csr/CSR.scala:572`). While in debug mode (`regDebugMode`,
`.../csr/CSR.scala:400`), interrupts are masked: CommitUnit must not sample an
interrupt trap when `debug.mode` is set (dcsr governs step/ebreak behavior
instead).

### Spec-DSL to add to `CommitUnitSpecs.scala`

```scala
val intfInterruptCtrlIn = spec {
  INTERFACE("InterruptCtrlIn")
    .desc("mie/mip enable+pending snapshot from CSR for commit-boundary " +
          "interrupt sampling.")
    .uses(bndInterruptCtrl)
    .is(rawNoDecoupled)
    .build()
}

val funcInterruptSampling = spec {
  FUNCTION("InterruptSampling")
    .desc("CommitUnit samples enabled+pending interrupts only at a retire " +
          "boundary with no synchronous exception and not in debug mode, and " +
          "emits them as an Exception token (source=Interrupt) to the " +
          "TrapController.")
    .uses(intfInterruptCtrlIn, intfCommitResultIn, intfExceptionOut)
    .note("mepc = PC of the next not-yet-retired instruction (interrupts are " +
          "taken between instructions).")
    .note("Epoch: TrapController's Redirect increments global epoch; younger " +
          "in-flight work self-squashes. No selective squash needed.")
    .build()
}
```

Extend `bndException` (`BackendBundlesSpecs.scala:196-201`) with fields
`source (Sync|Interrupt)`, `cause`, `pc`, `epoch`.

### Alternatives considered and rejected

- **Sample at fetch/decode (async-poll in frontend).** Rejected: interrupts
  would not be precise w.r.t. in-flight instructions; a partially executed
  younger uop could have leaked state.
- **Sample inside CSR combinationally (status quo).** Rejected: there is no
  program-order anchor; with a rename/PRF window the "current instruction" is
  ambiguous. Commit is the only in-order point.

### Cost

- **N=1:** one comparator at the (single) commit head plus routing
  `interruptCtrl` to CommitUnit. Effectively free.
- **N=32:** identical -- interrupt sampling is a single decision at the commit
  head regardless of window width; wider windows just squash more younger uops
  via the existing epoch mechanism.

### Verification obligations

- SVA: `exception.source == Interrupt |-> commit_boundary && !debug.mode &&
  (interruptCtrl pending & enabled)`.
- SVA: `mepc_on_interrupt == pc_of_next_uncommitted_uop`.
- Directed: raise MTIP mid-stream between two stores; assert both older stores
  committed, `mepc` = the un-retired instruction PC, handler entered once.

### Open questions

- **OQ2:** Debug interrupt (cause 14 at `.../csr/CSR.scala:473`) priority
  relative to standard M-interrupts and to synchronous exceptions at the same
  boundary -- ratify the priority ladder.

---

## 4. Exception precision and serializing ops

### Contract (normative)

**C4.1 -- Precision by construction.** The in-order commit point yields precise
architectural state because: (a) per Section 1, no CSR write, and per the
memory-ordering paper no store, and per the rename-recovery paper no map-table
commit, occurs before the commit head reaches the uop; (b) commit is strictly
program-ordered; (c) on a trap at uop U, all older uops have committed (arch
state current) and all younger uops squash via epoch increment. No token older
than U can be in flight, and no token younger than U has architectural effect.

**C4.2 -- Serializing ops.** `mret`, `dret`, `fence`, `fence.i`, `wfi`, and all
CSR ops carry `serializing` (Section 1, C1.3). Semantics at the commit head:

- **mret/dret:** TrapController-owned state restore + Redirect (Section 2, C2.3).
- **fence:** a serializing no-op for a single-hart visible memory model; it must
  not retire until the store path is drained. The drain-complete predicate is a
  backpressure condition supplied by the memory subsystem (MDG paradigm,
  `docs/foundations/unified-microarchitectural-paradigms.md:43`); coordinate
  the shared edge with the memory-ordering architect (OQ4).
- **fence.i:** serializing + emits a `Redirect` (target `pc+4`) after older
  stores drain, forcing a refetch so instruction fetch observes the stores.

**C4.3 -- Trap-CSR write budget.** A PROPERTY spec bounds architectural trap
writes: at most one `CSRTrapWrite.fire` per cycle, and trap CSRs are written
only by `CSRTrapWrite` or the commit-gated software datapath, never both in the
same cycle (Section 2, C2.4).

### Spec-DSL (add to `CommitUnitSpecs.scala` / `TrapControllerSpecs.scala`)

```scala
val propPreciseCommit = spec {
  PROPERTY("PreciseCommit")
    .desc("At a trap on uop U: all uops older than U have applied " +
          "architectural state; no uop younger than U has applied any; the " +
          "trap sees exactly the state after U-1.")
    .note("Guaranteed by commit-gated CSR writes (C1.2), drain-at-commit " +
          "stores (memory paper), and commit-time map update (rename paper).")
    .note("Elaboration assertion: monotone commit uopId; single trap-CSR " +
          "writer; write budget one-per-cycle.")
    .build()
}

val funcSerializingOps = spec {
  FUNCTION("SerializingOps")
    .desc("mret/dret/fence/fence.i/wfi/CSR retire one-at-a-time at the commit " +
          "head; fence/fence.i gate retire on memory-drain; fence.i and " +
          "mret/dret produce a Redirect.")
    .uses(intfExceptionIn, intfRedirectOut)
    .build()
}
```

### Alternatives considered and rejected

- **Reorder buffer with speculative CSR/store and rollback.** Rejected: a full
  ROB with speculative architectural undo is the opposite of the map-table /
  no-writeback philosophy and pays OoO cost at N=1
  (`rebuild_branch_architecture_review.md:112-116`).
- **fence as a hard pipeline drain.** Rejected: same node-stall objection as
  Section 1; expressed instead as a commit-head backpressure edge.

### Cost

- **N=1:** the whole backend is one cycle; "serialize" means the single
  in-flight instruction commits that cycle. `fence`/`fence.i` redirect is a
  combinational target select. ~Zero.
- **N=32:** rare serializing ops cost a short commit-head bubble; precision is
  window-independent because it rests on commit ordering, not window size.

### Verification obligations

- Elaboration assertion: commit `uopId` monotonic; trap-write budget one/cycle.
- Directed: `fence.i` after a self-modifying store observes the new bytes;
  `fence` orders a store before a subsequent load across the boundary.

### Open questions

- **OQ4:** The fence/fence.i drain-complete handshake -- is it a dedicated
  memory-subsystem->commit edge, or folded into the existing MemoryOpResp
  accounting? Shared with the memory-ordering architect.

---

## 5. Debug and trigger integration at the commit boundary

### Contract (normative)

**C5.1 -- Fire in program order.** Trigger matches, single-step, and
ebreak-to-debug decisions are qualified by the committing instruction. Today
`TriggerUnit`/`DebugUnit` fire combinationally off `io.trigSrc`/`io.sys`
(`.../csr/CSR.scala:376-403`, `.../csr/DebugUnit.scala:43-49`), with no
commit anchor. The contract keeps `TriggerUnit`/`DebugUnit` as pure-function
leaf IP but gates their *effect* (`debugFire`, `trigFire`) with the commit head
so a trigger fires on the instruction that actually retires, not a squashed or
speculative one.

**C5.2 -- Debug entry is a trap; single owner.** Debug-mode entry state writes
(`dpc`, `dcsr.cause`, `dcsr.prv`, `priv <- M`; today at
`.../csr/CSR.scala:527-567`) route through the single trap-write owner as a
`CSRTrapWrite` with `kind = DebugEntry`, and the redirect to the debug ROM /
park PC is the associated `Redirect`. This keeps `dpc`/`dcsr`/`priv` under the
same one-writer discipline as `mepc`/`mstatus` (Section 2).

**C5.3 -- Trigger timing collapse.** "Before" (execute-time) vs "after"
(store-data) trigger timing both evaluate against the committing uop's
architectural snapshot; in the one-cycle base config they coincide. The
contract states triggers are evaluated at commit, so timing is a retiming
detail, not a functional fork.

### Spec-DSL (add to `TrapControllerSpecs.scala`)

```scala
val funcDebugCommitBoundary = spec {
  FUNCTION("DebugCommitBoundary")
    .desc("Trigger/step/ebreak fire is qualified by the commit head; debug " +
          "entry is emitted as a CSRTrapWrite(kind=DebugEntry) + Redirect to " +
          "the debug handler, sharing the single trap-write owner.")
    .uses(intfDebugReqIn, intfCsrTrapReadIn, intfCsrTrapWriteOut, intfRedirectOut)
    .note("TriggerUnit/DebugUnit remain pure-function leaf IP; only their " +
          "effect is commit-gated. dpc/dcsr/priv have a single writer.")
    .build()
}
```

### Alternatives considered and rejected

- **Fire triggers at FU execute (status quo).** Rejected: fires on
  speculative/squashed instructions; not precise; step-count wrong under
  misprediction.
- **Second trap-write owner for debug CSRs.** Rejected: reintroduces the
  double-fire seam for `dpc`/`dcsr`.

### Cost

- **N=1:** commit gate is one AND on the existing `debugFire`/`trigFire`;
  DebugUnit/TriggerUnit unchanged. ~Zero.
- **N=32:** identical; a single decision at the commit head.

### Verification obligations

- Directed (RISC-V debug spec): single-step retires exactly one instruction
  then enters debug; an mcontrol6 trigger on a load fires only when that load
  commits; ebreak with `dcsr.ebreakm` enters debug, without it raises a
  breakpoint exception.
- SVA: `dpc`/`dcsr.cause`/`priv` debug-entry writes originate only from
  `CSRTrapWrite(kind=DebugEntry)`.

### Open questions

- **OQ3:** Does the trigger "after" (store-data) case need a distinct
  commit-plus-one anchor when store data is only known post-memory, or is the
  commit snapshot sufficient for RV32IMC's supported trigger types?
- **OQ6:** Debug park/handler address source -- `dcsr`-derived ROM address vs a
  parameter; align with BootSequencer's reset/halt story.

---

## 6. Summary of normative contracts

| ID | Contract | N=1 cost |
|----|----------|----------|
| C1 | CSR writes are commit-gated; CSR/system ops are single-in-flight serializing | one AND + 1 bit |
| C2 | TrapController is the sole writer of mepc/mcause/mtval/mstatus/priv and mret/dret | negative (deletes code) |
| C3 | Interrupts sampled only at the commit boundary, emitted as Exception tokens | one comparator |
| C4 | Precise state by commit ordering; mret/fence/fence.i are serializing | ~zero |
| C5 | Debug/trigger fire commit-gated; debug entry via the single trap-write owner | one AND |

All five rest on one structural move: **the in-order commit head is the only
place architectural context (CSR, privilege, trap stack, debug) changes**, and
**the TrapController is the only vertex that writes trap state.** Everything
upstream (rename, RS, dispatch, FUs, publish) stays speculative and
side-effect-free, which is what makes the N=1..32 window parameter safe for
privilege architecture.

## 7. Consolidated open questions for the panel

- OQ1: Serialize CSR writes only (allow concurrent reads) vs strict
  single-in-flight for the whole class?
- OQ2: Interrupt priority ladder, including debug interrupt (cause 14) vs
  synchronous exceptions at the same commit boundary.
- OQ3: Trigger "after"/store-data timing -- commit snapshot vs commit-plus-one.
- OQ4: fence/fence.i drain-complete handshake edge (shared with memory
  architect).
- OQ5: Waive `AGENT: DO NOT TOUCH` on `CSRCore` to delete the internal
  exception/mret writers (`.../csr/CSR.scala:405-408, 494-524`).
- OQ6: Debug park/handler address source and alignment with BootSequencer.
