# Frontend Architecture Position Paper (UDACore rebuild)

Author: Frontend architect, UDACore architecture team
Branch: claude/rebuild-architecture-review-jg0grl
Status: NORMATIVE proposal for panel ratification. Spec snippets below are written
to be pasted into the named `*Specs.scala` files after review.

---

## 0. Scope, sources, and standing constraints

This paper fixes the frontend contracts for the parametric in-order-to-OoO core.
It is grounded in the current tree:

- Foundations: `docs/foundations/uda-methodology.md`, `dataflow-execution-model.md`,
  `design-constitution.md`, `unified-microarchitectural-paradigms.md`.
- Owner constraints: `dontcommit.md:6-9` ("Cycle 0: Fetch; Cycle 1: Decode..Commit";
  "no stall logic or pipeline registers in node ... edge should have responsible").
- Accepted review holes: `document/rebuild_branch_architecture_review.md:141-151`
  (the spec-rot list that names the FetchUnit contradiction explicitly).
- Frontend RTL/spec: `src/main/scala/udacore/frontend/**`.
- Redirect/epoch fan-out: `src/main/scala/udacore/core/spec/top/CoreTopSpecs.scala:79-102`,
  `src/main/scala/udacore/core/design/modules/GlobalEpochUnit.scala`,
  `src/main/scala/udacore/core/design/modules/BootSequencer.scala`.

Two constraints bind every decision here:

1. **Node purity / edge-owned timing.** Per `dontcommit.md:9` and
   `design-constitution.md:25-31`, a vertex contains no pipeline register and no
   stall FSM. Everything I specify as "state" (straddle carry, outstanding-request
   epoch latch, queue storage) must be justified as *architectural* state the
   function genuinely needs, not as retiming. Where I need a register purely for
   timing I say so and mark it edge debt.
2. **N=1 must fold to zero tax.** Recommendation 6 of the review
   (`rebuild_branch_architecture_review.md:172-173`) demands the in-order point
   elaborate away OoO structure. Every contract below carries an explicit
   "cost at N=1" line, and the rule is: at `fetchWidth==1` and single outstanding
   fetch, the slot-group machinery, the multi-way predecode prune, and the
   multi-entry issue queue must degrade to wires + one skid buffer.

Note on the window parameter `N`. The speculative-window parameter lives in the
backend (`SpeculativeRegNum`, per the review). The frontend does not own `N`, but
it is downstream-coupled to it: the frontend's job is to *supply* enough correct-path
slots per cycle to keep an N-wide backend fed, and to *recover* in bounded cycles
when the backend redirects. I therefore parameterize the frontend by `fetchWidth`
(bandwidth) and treat backend `N` as the sizing driver for `fetchWidth` and issue
queue depth. This is stated normatively in sections 4 and 5.

---

## 1. Contradiction resolution: FetchUnit contract vs top graph

### 1.1 The defect (verified against the tree)

The review (`rebuild_branch_architecture_review.md:146-148`) flags "FetchUnit's
module contract contradicts the top-level graph (boot edge destination, missing
memory ports)". Confirmed, and it is actually a three-way inconsistency:

- **Boot edge destination.** `FetchUnitSpecs.scala:14-28` declares FetchUnit
  `.has(intfBootIn, intfInstructionOut)` with `intfBootIn` "Boot command entering
  the fetch unit." But the FrontendTop graph routes boot to NextPcGen:
  `FrontendTopSpecs.scala:57` (`boot ---> npc`), and CoreTop routes
  `BootSequencer --BootAddr--> fe` (`CoreTopSpecs.scala:81`). NextPcGen's own
  contract (`NextPcGenSpecs.scala:14-19`) has `intfRedirectIn` and `intfIssueOut`
  but **no boot input at all**. So the boot address has a producer (BootSequencer,
  `BootSequencer.scala:20`) and two disagreeing sinks, and its intended sink
  (NextPcGen) does not declare the port.

- **Missing memory ports.** The FrontendTop graph gives FetchUnit both external
  memory edges: `epmresp ---> fu` (`FrontendTopSpecs.scala:59`) and
  `fu ---> epmreq` (`FrontendTopSpecs.scala:73`). The FetchUnit contract
  (`FetchUnitSpecs.scala:14-19`) declares neither. It has an `intfInstructionOut`
  that the top graph does not use (the top edge out of `fu` is `FetchResponse` into
  `align`/SlotSlicer, `FrontendTopSpecs.scala:66`).

- **Redirect path.** NextPcGen's `intfRedirectIn` (`NextPcGenSpecs.scala:22-27`) is
  declared as a module-local port, but redirect is a FrontendTop-level input
  (`intfRedirectIn`, `FrontendTopSpecs.scala:124-130`) fanned in from the backend
  (`CoreTopSpecs.scala:83`). The two must be declared as the same edge crossing the
  top boundary, not two independent `RedirectIn` specs.

### 1.2 Normative resolution

**Decision D1 (boot lands at NextPcGen).** Boot is a *PC seed*, not a fetch. The
address must enter the PC-generation vertex so the first `NextPc` token is produced
with a valid epoch tag and flows through the normal `npc -> fu` edge. FetchUnit
never sees boot. This matches both mermaids and the constitution's "control rides
with data" rule: boot is just the first PC token.

**Decision D2 (FetchUnit owns exactly four edges).** FetchUnit is the memory bridge
vertex (`funcMemoryBridge`, `FrontendTopSpecs.scala:186-195`). Its contract is:
- in: `NextPc` (from NextPcGen)
- out: `ExternalProgramMemoryReq` (to external, crosses top boundary)
- in: `ExternalProgramMemoryResp` (from external, crosses top boundary)
- out: `FetchResponse` (to SlotSlicer)

The stray `intfBootIn`/`intfInstructionOut` are deleted.

**Decision D3 (one redirect edge, declared at top, referenced by NextPcGen).**
The frontend has exactly one redirect sink. The top-level `intfRedirectIn`
(`FrontendTopSpecs.scala:124`) is the canonical spec; NextPcGen references it rather
than minting `NextPcGenSpecs.intfRedirectIn`. Same for boot: NextPcGen references
the top-level `intfBootAddrIn`.

**Decision D4 (the corrected top mermaid is authoritative and must match ports
edge-for-edge).** The review's closing remedy (`:150-151`) is to machine-check
"module CONTRACT ports vs top mermaid edges". Until that checker exists, this paper
makes the mermaid and the per-module `.has(...)` lists agree by construction. The
corrected FrontendTop graph:

```
boot(BootAddr)      ---> npc.BootIn
redirect            ---> npc.RedirectIn
prediction          ---> npc.PredictionIn        (internal, from predecoder)
npc  -- NextPc      --> fu.NextPcIn
fu   -- ProgMemReq  --> (top) ExternalProgramMemoryReqOut
(top) ProgMemResp   --> fu.ProgMemRespIn
fu   -- FetchResp   --> slicer.In
slicer -- Slots     --> rvc.In
rvc  -- Expanded    --> predec.In
predec -- Predecoded--> iq.In
predec -- Prediction--> npc.PredictionIn
iq   -- Issue       --> (top) InstructionIssueOut
epoch (GlobalEpoch) -.-> {npc, fu, slicer, predec, iq}   (rawNoDecoupled, all)
```

### 1.3 Concrete spec-DSL (paste into `FetchUnitSpecs.scala`, replacing the body)

```scala
object FetchUnitSpecs {
  val contFetchUnit = spec {
    CONTRACT("FetchUnit")
      .desc(
        "FetchUnit is the frontend memory bridge vertex. It consumes NextPc " +
        "tokens, issues epoch-tagged program-memory requests, accepts responses, " +
        "and emits FetchResponse beats to the slot slicer. It holds no pipeline " +
        "register: outstanding-request bookkeeping is architectural state."
      )
      .has(
        intfNextPcIn,
        intfProgMemReqOut,
        intfProgMemRespIn,
        intfFetchResponseOut,
        funcOutstandingTracking,
        funcEpochTagging
      )
      .note("Boot does NOT enter FetchUnit; boot seeds NextPcGen (see D1).")
      .build()
  }

  val intfNextPcIn = spec {
    INTERFACE("NextPcIn")
      .desc("Next fetch address (epoch-tagged) entering the fetch unit.")
      .is(rawReadyValidIntf)
      .uses(bndNextPcIssue)
      .build()
  }

  val intfProgMemReqOut = spec {
    INTERFACE("ProgMemReqOut")
      .desc("Program-memory read request leaving the fetch unit to the core boundary.")
      .is(rawReadyValidIntf)
      .uses(bndExternalProgramMemoryReq)
      .build()
  }

  val intfProgMemRespIn = spec {
    INTERFACE("ProgMemRespIn")
      .desc("Program-memory response entering the fetch unit from the core boundary.")
      .is(rawReadyValidIntf)
      .uses(bndExternalProgramMemoryResp)
      .build()
  }

  val intfFetchResponseOut = spec {
    INTERFACE("FetchResponseOut")
      .desc("One fetched beat (aligned to the memory data width) toward the slot slicer.")
      .is(rawReadyValidIntf)
      .uses(bndFetchResponse)
      .build()
  }

  val funcOutstandingTracking = spec {
    FUNCTION("OutstandingTracking")
      .desc(
        "Tracks in-flight program-memory requests so responses can be matched to " +
        "the PC and epoch that requested them. At the base config exactly one " +
        "request may be outstanding (prefetchDepth==1); depth is a tuning knob."
      )
      .note("Backpressure: NextPcIn.ready is deasserted while the outstanding set is full.")
      .build()
  }

  val funcEpochTagging = spec {
    FUNCTION("EpochTagging")
      .desc(
        "Each request latches the GlobalEpoch at issue time. On response, if the " +
        "latched request epoch != current GlobalEpoch the beat is dropped locally " +
        "(valid gated low) instead of buffered. This is the Mul/Div kill pattern " +
        "applied to fetch."
      )
      .note("No flush wire: staleness is a comparison, per dataflow-execution-model.md:8-12.")
      .build()
  }
}
```

### 1.4 Concrete spec-DSL (paste into `NextPcGenSpecs.scala`, replacing the body)

```scala
object NextPcGenSpecs {
  val contNextPcGen = spec {
    CONTRACT("NextPcGen")
      .desc(
        "NextPcGen is the frontend's single PC-selection vertex. It seeds from " +
        "boot, then each cycle selects the next fetch PC from, in priority order: " +
        "redirect (backend), first-taken prediction (predecoder), sequential fall-through."
      )
      .has(
        intfBootIn,
        intfRedirectIn,
        intfPredictionIn,
        intfNextPcOut,
        funcPcSelect,
        funcEpochStamp
      )
      .build()
  }

  val intfBootIn = spec {
    INTERFACE("BootIn")
      .desc("Boot address seed from BootSequencer (crosses the frontend top boundary).")
      .is(rawReadyValidIntf)
      .uses(bndBootAddr)
      .note("Same edge as FrontendTop.intfBootAddrIn; referenced, not re-minted.")
      .build()
  }

  val intfRedirectIn = spec {
    INTERFACE("RedirectIn")
      .desc("Backend redirect (crosses the frontend top boundary). Highest PC priority.")
      .is(rawReadyValidIntf)
      .uses(bndRedirect)
      .note("Same edge as FrontendTop.intfRedirectIn.")
      .build()
  }

  val intfPredictionIn = spec {
    INTERFACE("PredictionIn")
      .desc("First-taken branch prediction feedback from the predecoder (frontend-internal).")
      .is(rawReadyValidIntf)
      .uses(bndPredictorOutput)
      .build()
  }

  val intfNextPcOut = spec {
    INTERFACE("NextPcOut")
      .desc("Selected epoch-tagged next fetch address toward FetchUnit.")
      .is(rawReadyValidIntf)
      .uses(bndNextPcIssue)
      .build()
  }

  val funcPcSelect = spec {
    FUNCTION("PcSelect")
      .desc("Priority mux: redirect > prediction > sequential. Boot seeds the initial value.")
      .markdownTable(
        List("Source", "Priority", "Condition", "Next PC"),
        List(
          List("Redirect", "0 (highest)", "redirect.valid", "redirect.target"),
          List("Prediction", "1", "prediction.valid && taken", "prediction.target"),
          List("Sequential", "2 (lowest)", "otherwise", "pc + fetchStride")
        )
      )
      .note("fetchStride = memory data width in bytes (see section 5).")
      .build()
  }

  val funcEpochStamp = spec {
    FUNCTION("EpochStamp")
      .desc(
        "Every emitted NextPc carries the GlobalEpoch observed this cycle. A redirect " +
        "consumed this cycle stamps the POST-increment epoch (see section 3 guardrails)."
      )
      .build()
  }
}
```

### 1.5 Alternatives considered / rejected

- **Boot enters FetchUnit (keep the current FetchUnit contract).** Rejected: it
  forces FetchUnit to own PC state, duplicating the priority mux NextPcGen already
  owns, and it breaks the single-PC-selector invariant that the redirect/prediction
  guardrails (section 3) depend on. Two vertices selecting PC is two epoch-stamp
  owners: a double-fire seam analogous to the mepc seam the review calls out
  (`rebuild_branch_architecture_review.md:136-138`).
- **Separate "FetchController" vertex between NextPcGen and memory.** Rejected at
  N=1: it inserts an edge (a cycle) on the redirect->fetch recovery path for no
  functional gain. FetchUnit already is that controller.

### 1.6 Cost / verification / open questions

- **Cost at N=1:** zero. FetchUnit at `prefetchDepth==1` is: one epoch-latch
  register (architectural, needed to tag the single outstanding request) + a
  comparator + req/resp wires. NextPcGen is a 3-input priority mux + PC register.
- **Cost at N=32:** FetchUnit's outstanding set grows to `prefetchDepth` entries
  (small CAM keyed by memory txn id); NextPcGen is unchanged (still one PC/cycle;
  wide fetch changes stride, not selector count, see section 5).
- **Verification obligations:**
  - Elaboration assertion: FrontendTop's instantiated edge set == the union of child
    `.has(intf*)` port sets (this is the graph-consistency check the review asks for,
    `:150-151`). Until the plugin lands, a hand-written `require` in FrontendTop that
    lists expected edges.
  - Unit test: boot pulse -> first `NextPcOut` equals boot address with epoch 0.
  - Unit test: a response whose latched epoch != GlobalEpoch produces no
    `FetchResponseOut.valid` (kill-on-mismatch).
- **Open questions for the panel:**
  - Q1.1 Does the external program memory port carry a txn id, or is fetch strictly
    single-outstanding at every config? (Sizing of `funcOutstandingTracking`.)
  - Q1.2 Boot as a Decoupled pulse vs a level `hartEn` + address: BootSequencer today
    emits Decoupled (`BootSequencer.scala:20`). I assume Decoupled; confirm.

---

## 2. SlotSlicer contract: RVC straddle, slot validity, misalignment

### 2.1 Problem statement

RVC (`RvcExpander.scala:23`, `io.in(1,0) =/= 3.U` marks a 16-bit instruction) means
instruction boundaries are 16-bit-granular while memory returns fixed-width beats
(32/64/128-bit, section 5). A 32-bit instruction can **straddle** a beat boundary:
its low 16 bits are the last half-word of beat K, its high 16 bits are the first
half-word of beat K+1. The current SlotSlicer contract (`SlotSlicerSpecs.scala`) is
two placeholder ports (`intfInputIn`, `intfOutputOut`) with no straddle rule, no
validity rule, no misalignment rule. This section makes it a checkable state machine.

### 2.2 Definitions (normative)

- **Beat.** One `FetchResponse` payload: `memDataWidth` bits of instruction memory
  plus the base PC of its low half-word plus the request epoch.
- **Half-word (hw).** 16 bits. A beat holds `memDataWidth/16` half-words indexed
  `0..H-1` from low address.
- **Slot.** One candidate instruction position, 32 bits wide after slicing, tagged
  with its own PC, a `len` field (2 for RVC, 4 for RVI), and a `valid` bit. The
  slot payload is always 32 bits; RvcExpander downstream turns a 16-bit RVC slot
  into a 32-bit RV32I (`RvcExpander.scala:23-35`), so SlotSlicer emits the raw
  16-or-32 bits right-packed and marks `len`.
- **Carry.** The single trailing half-word of a beat that could not be paired,
  held for the next beat. This is the only state SlotSlicer owns; it is
  **architectural** (the function cannot reconstruct a straddling instruction
  without it), not a pipeline register.

### 2.3 The straddle state machine (normative)

SlotSlicer is a Mealy machine with one state bit `carryValid` and a 16-bit
`carryData` and a `carryPc`/`carryEpoch`:

```
State: carryValid : Bool, carryData : UInt(16), carryPc, carryEpoch

On each accepted input beat B (epoch B.epoch):
  1. If carryValid && carryEpoch != B.epoch:
        drop carry (carryValid := false)      # stale straddle across a redirect
  2. Form the working half-word vector:
        hws = (if carryValid) [carryData] ++ B.halfwords  else B.halfwords
        basePc = (if carryValid) carryPc else B.pc
  3. Scan hws left-to-right from the entry offset:
        - if hw[i][1:0] != 11  -> RVC slot, len=2, consumes 1 hw
        - else                 -> RVI slot, len=4, consumes 2 hw
     Emit one slot per decoded instruction with pc = basePc + 2*i.
  4. If the scan reaches the last half-word and it begins an RVI (hw[H-1][1:0]==11):
        carryData := hw[H-1]; carryValid := true; carryPc := basePc+2*(H-1)
        carryEpoch := B.epoch
     else carryValid := false.
```

**Invariants (must be elaboration/assert-checkable):**

- **INV-S1 (no lost bytes).** Over any run with no redirect, the concatenation of
  emitted slot bytes in PC order equals the concatenation of consumed beat bytes.
  (Conservation: every fetched byte is either emitted in a slot or held as carry.)
- **INV-S2 (carry is at most one half-word).** `carryValid` implies exactly 16 bits
  held. There is never a multi-hw carry, because RVI is at most 2 hw and a straddle
  splits it exactly 1+1.
- **INV-S3 (carry epoch monotonic).** A carry is only ever consumed by a beat of the
  same epoch; on epoch change the carry is dropped (step 1). This prevents fusing a
  correct-path tail with a wrong-path head after a redirect.
- **INV-S4 (slot PC alignment).** Every emitted slot PC is 2-byte aligned. A slot PC
  that is 4-byte-misaligned for an RVI that the ISA requires aligned is *not* a
  SlotSlicer error under RVC (RVC permits 2-byte alignment); misalignment exceptions
  are handled per INV-S5.
- **INV-S5 (misalignment exception).** If RVC is disabled (`fetchWidth` config with
  `usingCompressed=false`, cf. `RvcExpander.scala:20`) and a control transfer targets
  a 2-byte-but-not-4-byte address, SlotSlicer marks the *first* slot of that fetch
  with an `instrAddrMisaligned` exception flag and sets `valid=false` for it and all
  following slots in the beat. The exception rides the slot metadata to the backend;
  it is not raised in the frontend (frontend has no trap machinery). This is the MDG/
  precise-exception boundary: the frontend only *tags*.

### 2.4 Concrete spec-DSL (paste into `SlotSlicerSpecs.scala`, replacing the body)

```scala
object SlotSlicerSpecs {
  val contSlotSlicer = spec {
    CONTRACT("SlotSlicer")
      .desc(
        "SlotSlicer converts fixed-width fetch beats into 16-bit-granular " +
        "instruction slots, resolving RVC straddle across beats via a single " +
        "half-word carry. It owns exactly one architectural state element (the " +
        "carry); it holds no pipeline register."
      )
      .has(intfInputIn, intfOutputOut, funcStraddleCarry, funcSlotScan, funcMisalignTag)
      .note("Carry is the only legal state: it is required by the function, per section 2.2.")
      .build()
  }

  val intfInputIn = spec {
    INTERFACE("SlotSlicerInputIn")
      .desc("One fetch beat (memDataWidth bits + base PC + epoch) from FetchUnit.")
      .is(rawReadyValidIntf)
      .uses(bndFetchResponse)
      .build()
  }

  val intfOutputOut = spec {
    INTERFACE("SlotSlicerOutputOut")
      .desc("A slot group (up to slotsPerBeat entries), each 32b payload + pc + len + valid + exc.")
      .is(rawReadyValidIntf)
      .uses(bndSlotGroup)
      .build()
  }

  val funcStraddleCarry = spec {
    FUNCTION("StraddleCarry")
      .desc("Holds the trailing half-word of a beat when it begins an RVI, to pair with the next beat.")
      .table("invariant", "INV-S2: carry is exactly one half-word. INV-S3: carry dropped on epoch change.")
      .note("On redirect (epoch change) the carry is discarded so wrong-path tails never fuse.")
      .build()
  }

  val funcSlotScan = spec {
    FUNCTION("SlotScan")
      .desc("Left-to-right half-word scan classifying RVC (len=2) vs RVI (len=4) and emitting per-slot PC.")
      .table("invariant", "INV-S1 byte conservation; INV-S4 2-byte slot PC alignment.")
      .build()
  }

  val funcMisalignTag = spec {
    FUNCTION("MisalignTag")
      .desc("When RVC disabled, tags instrAddrMisaligned on the offending slot and invalidates it and successors.")
      .table("invariant", "INV-S5: frontend tags, never traps.")
      .build()
  }
}
```

### 2.5 Alternatives considered / rejected

- **Carry-free (re-fetch the straddling instruction).** Rejected: doubles fetch
  bandwidth on every beat boundary and re-issues a memory request on the critical
  redirect path. The one-half-word carry is strictly cheaper.
- **Two-beat window buffer (hold last full beat).** Rejected: it is a `memDataWidth`
  register vs a 16-bit one, and it is a pipeline register (retiming), which
  `dontcommit.md:9` forbids inside a node. The carry is the minimal *functional*
  state.
- **Handle straddle in FetchUnit.** Rejected: mixes memory-protocol concerns with
  instruction-boundary concerns in one vertex; SlotSlicer is the boundary owner.

### 2.6 Cost / verification / open questions

- **Cost at N=1 (fetchWidth=1, memDataWidth=32):** the beat is 2 hw. The scan is a
  2-way combinational classify; carry is 16 bits + 1 valid + epoch. Effectively a
  small combinational block plus a 16-bit register. No dependence on backend N.
- **Cost at N=32 / wide fetch (memDataWidth=128):** the scan is 8-way; it becomes a
  prefix computation (each slot's start offset depends on the running sum of prior
  lengths) - an O(H) prefix / O(log H) parallel-prefix network. Carry is still one
  half-word. This is the only part of SlotSlicer that scales with fetch width.
- **Verification obligations:**
  - Property test INV-S1 over random byte streams: emitted bytes reassemble input.
  - Directed test: an RVI straddling every beat boundary decodes identically to the
    same stream fetched with an offset that avoids straddle.
  - Directed test INV-S3: inject a redirect (epoch bump) mid-straddle; assert the
    carry is dropped and no fused instruction is emitted.
  - Assert INV-S2 in RTL (`assert(!carryValid || carryWidth==16)`).
- **Open questions for the panel:**
  - Q2.1 Slot group vs single slot on the output edge: I specify a *group* (up to
    `slotsPerBeat`) so the edge matches fetch width. Confirm the group is the edge
    payload rather than serializing slots (serializing would need an internal FIFO =
    a queue the review warns against, `uda-methodology.md:14-16`).
  - Q2.2 Where does `instrAddrMisaligned` get *raised*? I place raising in the backend
    trap unit; the frontend only tags. Confirm the backend owns this exception.
  - Q2.3 RVC half of a straddle that lands exactly at end-of-beat when RVC is disabled:
    is that a misalign exception or a benign 4-byte fetch? (I treat len purely by
    `bits[1:0]`; with RVC off, a beat can still start mid-RVI only via a redirect to a
    2-byte address, which INV-S5 already tags.)

---

## 3. First-taken predecode discipline, prediction feedback, epoch guardrails

### 3.1 What "first-taken" means (normative)

The FrontendTop contract already states the policy in prose: "Based on first taken
rule, BranchPredecoder marks valid and invalid entry" (`FrontendTopSpecs.scala:23`)
and `funcPredictionDiscipline` "First-taken policy marks only the leading taken
branch as valid, pruning later speculative entries" (`:208-217`). I make it exact.

Within one predecoded slot group (in PC order, slots `0..K-1`):

- The predecoder classifies each slot as one of: not-a-branch, direct-jump (JAL/C.J/
  C.JAL - target known from the instruction, cf. the RVC J/JAL immediate decode in
  `RvcExpander.scala:154-179`), conditional-branch (BR/C.BEQZ/C.BNEZ - target known,
  direction predicted), indirect-jump (JALR/C.JR/C.JALR - target not known in
  frontend).
- **PRUNE RULE (normative).** Let `t` be the index of the first slot that is a taken
  control transfer, where "taken" =
  - direct-jump: always taken; or
  - conditional-branch: predicted-taken by the direction predictor; or
  - indirect-jump: treated as taken with unknown target (see guardrail G4).
  Then: slots `0..t` are `valid=true`; slots `t+1..K-1` are `valid=false` (pruned),
  because they are past the taken transfer and are not on the predicted path.
  If no slot is taken, all `0..K-1` are valid and the group falls through
  sequentially.

- **PREDICTION FEEDBACK EDGE (normative).** The predecoder drives one
  `PredictorOutput` token per group back to NextPcGen
  (`FrontendTopSpecs.scala:69`, `predecbp -- BranchPrediction --> npc`), carrying:
  `{ valid, taken, target, srcPc, srcEpoch }`. Semantics:
  - if a taken slot `t` exists with a known target (direct or predicted-taken cond):
    `valid=1, taken=1, target=<computed>, srcPc=slot[t].pc`;
  - if no taken slot: `valid=1, taken=0` (tells NextPcGen to fall through to
    `lastSlot.pc + lastSlot.len`);
  - indirect-jump at `t`: `valid=1, taken=1, target=DONTCARE` with an
    `indirectStall` flag (guardrail G4).

### 3.2 Epoch guardrails - operational meaning (normative)

`funcPcResolution` says NextPcGen "honours the most recent redirect with epoch
guardrails before falling back to sequential prediction" (`FrontendTopSpecs.scala:181`).
"Epoch guardrails" is currently undefined prose. Here is the operational contract,
grounded in the actual epoch unit (`GlobalEpochUnit.scala`):

- **G1 (redirect wins, same cycle).** The GlobalEpochUnit exposes the *post*-increment
  epoch the same cycle a redirect fires (`GlobalEpochUnit.scala:36-38`,
  `epochOut := Mux(redirectFire, epochIncrement, epoch)`). NextPcGen's `funcPcSelect`
  priority mux (section 1.4) therefore, on a redirect cycle, both (a) selects
  `redirect.target` and (b) stamps the *new* epoch on the emitted NextPc. The
  prediction feedback arriving the same cycle is lower priority and is ignored.
- **G2 (in-flight fetch race).** A redirect does not need to "flush" the FetchUnit,
  SlotSlicer, or predecoder. Each carries the request epoch: FetchUnit drops
  responses whose latched epoch mismatches (`funcEpochTagging`, section 1.3);
  SlotSlicer drops its carry on epoch change (INV-S3); the predecoder and IssueQueue
  gate `valid` on `slot.epoch === GlobalEpoch`. So a NextPc issued in epoch e, whose
  memory response returns *after* a redirect to epoch e+1, is silently discarded at
  the FetchUnit response boundary. No token from epoch e can reach the backend after
  the redirect.
- **G3 (prediction is epoch-tagged, self-cancelling).** The `PredictorOutput` carries
  `srcEpoch`. NextPcGen consumes a prediction only if `srcEpoch === GlobalEpoch`.
  A prediction generated on the wrong path (older epoch) is dropped, not acted on.
  This closes the race where a wrong-path predecoder still drives a prediction edge
  into NextPcGen after a redirect.
- **G4 (indirect jumps stall fetch, do not mispredict-by-default).** For an indirect
  jump with unknown target, NextPcGen has no correct-path PC to fetch. Base config
  rule: **stop issuing new NextPc after an indirect until the backend resolves it and
  redirects.** Concretely `NextPcOut.valid` is deasserted for the shadow of the
  indirect (the predecoder sets `indirectStall`, NextPcGen latches "waiting for
  redirect", cleared by the next redirect or by an epoch change). This trades fetch
  bandwidth for zero wrong-path work on indirects at N=1; a BTB/RAS is a tuning
  addition at high N (open question Q3.2).
- **G5 (epoch wrap hazard - bounded generations).** The review flags the 2-bit epoch
  wrap (`rebuild_branch_architecture_review.md:100-105`): a fetch surviving 4
  redirects aliases back to the current epoch. Frontend obligation: the frontend must
  guarantee that no fetch request outlives `2^epochWidth - 1` redirects. Because
  FetchUnit is single-outstanding at base config and drops on mismatch, an in-flight
  fetch is killed at the *first* redirect, so it can never survive to wrap. Normative
  rule: **max outstanding fetch generations <= 2^epochWidth - 1**; `prefetchDepth`
  must satisfy this against `epochWidth` (a `require` in `FrontendParams`). This is
  the frontend's half of the wrap fix; the backend owns the other half.

### 3.3 Concrete spec-DSL additions

Add to `BranchPredecoderSpecs.scala`:

```scala
  val funcFirstTakenPrune = spec {
    FUNCTION("FirstTakenPrune")
      .desc(
        "Within a slot group, marks slots 0..t valid and t+1..K-1 invalid, where t " +
        "is the first taken control transfer (direct-jump always; cond-branch if " +
        "predicted taken; indirect always with unknown target)."
      )
      .markdownTable(
        List("Class", "Target known in FE?", "Taken?", "Effect at slot t"),
        List(
          List("JAL/C.J/C.JAL", "yes", "always", "predict target, prune successors"),
          List("BR/C.BEQZ/C.BNEZ", "yes", "predictor", "predict dir; if taken prune successors"),
          List("JALR/C.JR/C.JALR", "no", "always", "indirectStall, prune successors, await redirect")
        )
      )
      .note("Direct/branch targets are computed here from the immediate (see RvcExpander J/B imm).")
      .build()

  val funcPredictionFeedback = spec {
    FUNCTION("PredictionFeedback")
      .desc("Emits one epoch-tagged PredictorOutput per group to NextPcGen: {valid,taken,target,srcPc,srcEpoch,indirectStall}.")
      .note("NextPcGen consumes it only if srcEpoch === GlobalEpoch (guardrail G3).")
      .build()
  }
```

Extend `contBranchPredecoder.has(...)` with `funcFirstTakenPrune, funcPredictionFeedback`.

Add to `FrontendTopSpecs.funcPcResolution` a `.table` making the guardrails normative:

```scala
      .table("guardrail",
        "G1 redirect wins same-cycle (post-inc epoch stamped); " +
        "G2 in-flight fetch dropped on epoch mismatch at FetchUnit resp; " +
        "G3 prediction consumed only if srcEpoch==GlobalEpoch; " +
        "G4 indirect jumps stall fetch until backend redirect; " +
        "G5 outstanding fetch generations <= 2^epochWidth-1.")
```

### 3.4 Alternatives considered / rejected

- **Predict-not-taken always (no direction predictor at base).** Viable at N=1 and
  cheaper, but it makes every taken branch a backend redirect (2-3 cycle bubble per
  the older review, `response/microarchitecture-review.md:116`). I keep the
  *interface* for a direction predictor (the `PredictorOutput.taken` field) but allow
  the base config to hardwire `taken=0` for conditionals (a `branchPredictorEntries`
  degenerate). This keeps N=1 cheap without changing the contract.
- **Speculate through indirects with a default (PC+len).** Rejected at base: indirect
  targets are almost never sequential; default-speculation is ~always wrong-path work,
  which the single global epoch then has to squash wholesale
  (`rebuild_branch_architecture_review.md:99-101`). G4 (stall) is strictly better at
  N=1. Revisit with RAS/BTB at high N.
- **Multiple taken branches valid per group (trace-style).** Rejected: violates the
  first-taken single-path contract and the single-global-epoch model.

### 3.5 Cost / verification / open questions

- **Cost at N=1 (fetchWidth=1):** the group is one slot; the prune rule is trivial
  (K=1, t in {none, 0}); prediction feedback is one comparator + target adder. No
  CAM, no BTB. `branchPredictorEntries` predictor elaborates to a constant `taken=0`
  if set to its degenerate. Zero backend-N coupling.
- **Cost at N=32 / fetchWidth>1:** the prune is a priority-encode over K slots
  (find-first-taken) - O(K) / O(log K). Prediction feedback still one token per group.
  A BTB/RAS (tuning) would attach here to remove the G4 indirect stall.
- **Verification obligations:**
  - Directed test: group with a taken direct jump at slot t; assert slots > t are
    `valid=false` and `PredictorOutput.target` equals the decoded immediate target.
  - Race test (G2/G3): issue fetch in epoch e, redirect to e+1, return the epoch-e
    response and drive an epoch-e prediction; assert neither reaches the backend and
    neither is consumed by NextPcGen.
  - Wrap test (G5): with `epochWidth=2`, drive 4 back-to-back redirects while a fetch
    is outstanding; assert the fetch was killed at redirect 1 (never aliases).
  - Assert in NextPcGen: `redirect.valid` implies emitted `NextPc.epoch == epochIncrement`.
- **Open questions for the panel:**
  - Q3.1 Is the prediction feedback a Decoupled edge or a same-cycle wire? The
    FrontendTop mermaid draws it as a Decoupled edge (`--BranchPrediction-->`,
    `FrontendTopSpecs.scala:69`). A Decoupled edge here adds a cycle to the
    predict->fetch loop (an IPC-critical edge per review rec 5,
    `rebuild_branch_architecture_review.md:169-171`). I recommend naming it IPC-critical
    and allowing the base config to collapse it to a same-cycle wire (fetch-in-cycle-0
    per `dontcommit.md:6-8`), pipelined only post-PD. Confirm.
  - Q3.2 Does the panel want a BTB/RAS contract now (so the port exists at N=1 as a
    degenerate) or deferred? I lean: define the `PredictorOutput` fields now, defer the
    predictor storage to a tuning module.
  - Q3.3 G4 stall vs the "whole backend in one cycle" model (`dontcommit.md:7`): if the
    indirect resolves in cycle 1, the stall is one cycle and moot at base. Confirm G4 is
    the base rule and BTB is the only thing that removes it.

---

## 4. IssueQueue: depth, invariants, wide-issue role

### 4.1 Role (normative)

`IssueQueueSpecs.scala:11` says "buffers frontend instructions and dispatches ready
groups." The FrontendTop contract adds the key constraint: "Issue queue must contain
valid slots for program flow (even it was speculatively) ... slots validity should be
checked during previous nodes" (`FrontendTopSpecs.scala:24-27`). So the IssueQueue is
**not** a validator - validity is decided upstream (SlotSlicer INV-S5, predecoder
prune). The IssueQueue is a **rate-matching skid buffer with an only-valid-slots
invariant** on the frontend/backend boundary.

Why it must exist at all (it is the one intentional cycle, `FrontendTopSpecs.scala:86`,
"only memory latency and issue queue are cyclic"): the backend can stall (redirect
recovery, RS full at high N). Without a buffer, backend backpressure propagates
combinationally up the whole frontend chain into the memory request, coupling fetch
timing to execute timing. The IssueQueue is the FCL relay
(`unified-microarchitectural-paradigms.md:60-74`) that breaks that combinational
strongly-connected component. It is legitimate cycle-breaking, documented as design
debt per `uda-methodology.md:14-16`.

### 4.2 Invariants (normative)

- **INV-Q1 (only valid slots enqueued).** `enqueue` only accepts slots with
  `valid=true`. Pruned/invalid slots (section 2, 3) are dropped at the enqueue mux,
  never stored. Consequence: everything in the queue is on the (speculative) program
  path.
- **INV-Q2 (epoch-coherent dequeue).** On dequeue, a slot with `slot.epoch !=
  GlobalEpoch` is dropped (not issued). Combined with G2/G3, a redirect makes the
  queue self-drain of wrong-path entries without a flush wire. The queue is NOT
  cleared on redirect; entries age out by epoch mismatch. (This is the epoch-guard-on-
  buffer rule, `dataflow-execution-model.md:10-12`.)
- **INV-Q3 (in-order issue).** Slots issue in PC order (frontend is in-order at the
  issue boundary regardless of backend N). Backend rename/RS is where OoO begins.
- **INV-Q4 (no partial group tearing at wide issue).** At `fetchWidth>1`, a slot group
  enqueues atomically (all valid members or none), so a group is never split across a
  redirect boundary inside the queue.

### 4.3 Depth (normative justification)

Depth `iqDepth` must cover the two independent pressures:

1. **Fetch burst absorption.** With `prefetchDepth` outstanding fetches and
   `slotsPerBeat` slots per beat, a burst can deliver up to
   `prefetchDepth * slotsPerBeat` slots before the backend drains one. At base
   (`prefetchDepth=1`, `slotsPerBeat=2` for 32-bit beat + RVC) that is 2.
2. **Backend stall coverage.** The queue must not backpressure fetch during a
   *transient* backend stall shorter than the redirect-recovery latency, or fetch
   bandwidth collapses. Budget: cover the redirect round-trip
   (`response/microarchitecture-review.md:116`, "2-3 cycles").

Normative sizing rule:

```
iqDepth = max(prefetchDepth * slotsPerBeat, redirectRecoveryCycles * fetchWidth) rounded up
base config: iqDepth = 4   (covers 2-slot burst and ~2-3 cycle backend stall at fetchWidth=1)
```

`iqDepth=4` also matches the existing `prefetchDepth=4` default
(`FrontendParams.scala:34`); I reuse that intuition but bind it to a stated formula
rather than a magic number.

### 4.4 Concrete spec-DSL (extend `IssueQueueSpecs.scala`)

```scala
  val funcOnlyValidSlots = spec {
    FUNCTION("OnlyValidSlots")
      .desc("Enqueue mux admits only slots with valid=true; invalid/pruned slots are dropped, never stored.")
      .table("invariant", "INV-Q1 only-valid enqueue; INV-Q4 atomic group enqueue at fetchWidth>1.")
      .build()
  }

  val funcEpochDrain = spec {
    FUNCTION("EpochDrain")
      .desc("Dequeue drops slots whose epoch != GlobalEpoch; the queue is never flushed, wrong-path entries age out.")
      .table("invariant", "INV-Q2 epoch-coherent dequeue; INV-Q3 in-order issue.")
      .note("This is the intentional frontend cycle (FrontendTop note: 'issue queue is cyclic').")
      .build()
  }

  val paramIssueQueueDepth = spec {
    PARAMETER("IssueQueueDepth")
      .desc("Entries. iqDepth = max(prefetchDepth*slotsPerBeat, redirectRecoveryCycles*fetchWidth). Base=4.")
      .note("Tuning tier. At N=1 a depth-2 skid buffer is the functional minimum; 4 is the perf default.")
      .build()
  }
```

Extend `contIssueQueue.has(...)` with `funcOnlyValidSlots, funcEpochDrain` and
`.uses(paramIssueQueueDepth)`.

### 4.5 Wide-issue role (fetchWidth>1 slot groups)

At `fetchWidth>1` the enqueue payload is a *slot group* (INV-Q4). The queue stores
groups but the dequeue interface to the backend is parameterized: it can dequeue up
to `issueWidth` slots per cycle. At N=1, `issueWidth=1` (backend is single-issue) so
the queue degenerates to a serializing skid buffer of depth `iqDepth`. At high N,
`issueWidth = min(iqHeadGroupSize, backendDispatchWidth)` and the queue feeds a wide
rename. The queue never reorders (INV-Q3); wide *issue* to an OoO backend is the
backend's rename/RS job, not the frontend's.

### 4.6 Alternatives considered / rejected

- **No queue (gate valid, backpressure straight through).** This is the review's
  "gate valid instead of queue" preference (`CLAUDE.md` pitfall 5). Rejected *here*
  specifically because the FrontendTop contract already declares the issue queue as
  the one intentional cycle (`FrontendTopSpecs.scala:86`); removing it re-couples
  fetch timing to backend stall combinationally. This is the documented exception, not
  a gratuitous queue.
- **Flush-on-redirect (clear the queue).** Rejected: violates control-as-data; epoch
  drain (INV-Q2) achieves the same with a comparator and no clear wire.
- **Validity check inside the queue.** Rejected: FrontendTop mandates validity checked
  upstream (`:24-27`); duplicating it in the queue is dead logic and a second source of
  truth.

### 4.7 Cost / verification / open questions

- **Cost at N=1:** a depth-2..4 register FIFO of slot payloads + per-entry epoch
  comparator on dequeue. `issueWidth=1`. No group logic (group size 1). This is the
  irreducible skid buffer; it is the *only* frontend structure that cannot elaborate to
  zero (it is the intentional cycle), and it is tiny.
- **Cost at N=32:** depth grows with `redirectRecoveryCycles * fetchWidth`; dequeue
  becomes multi-slot; still in-order, still no CAM.
- **Verification obligations:**
  - INV-Q1: property test - inject invalid slots; assert they never appear at dequeue.
  - INV-Q2: fill the queue in epoch e, redirect to e+1; assert every epoch-e entry is
    dropped on dequeue and none issues to the backend.
  - INV-Q3: assert dequeue PC order is monotonic within an epoch.
  - Deadlock/liveness (constitution `design-constitution.md:52-54`): with backend
    ready eventually true, the queue always drains (rank function on occupancy).
- **Open questions for the panel:**
  - Q4.1 Confirm `iqDepth=4` base, or set by a measured `redirectRecoveryCycles` once
    the backend redirect latency is pinned (it depends on backend pipelining choices,
    review rec 5).
  - Q4.2 Is `issueWidth` a frontend param or derived from backend `dispatchWidth`? I
    propose derived (single source of truth in backend), frontend reads it via `api/`.
  - Q4.3 Does epoch drain need to drop a *partial* group (some members already issued
    before the redirect)? INV-Q4 atomic enqueue plus in-order dequeue makes a group
    contiguous; a redirect mid-group drops the un-issued tail by epoch. Confirm this is
    acceptable (the already-issued head is wrong-path but self-filters in the backend by
    the same epoch rule).

---

## 5. Fetch width parameterization and the 32/64/128-bit scaling story

### 5.1 The two widths (normative distinction)

The current `fetchWidth` (`FrontendParams.scala:70`, "Number of instructions fetched
per cycle") conflates two independent quantities. I split them:

- **`memDataWidth`** (bits): the physical program-memory data-port width. Determines
  beat size and `slotsPerBeat = memDataWidth / 16`. Contract-tier (parents wire the
  bus). The external program-memory req/resp bundles are sized by this.
- **`fetchWidth`** (instructions): the *maximum* number of instruction slots the
  frontend produces and the issue queue can enqueue per cycle. Derived, bounded by
  `slotsPerBeat` (you cannot emit more instructions than half-words in a beat).

Normative relation: `fetchWidth <= slotsPerBeat = memDataWidth / 16`. At the base
config `memDataWidth=32` -> `slotsPerBeat=2` -> `fetchWidth in {1,2}`. The current
single scalar cannot express "32-bit port, one instruction/cycle" vs "128-bit port,
eight instructions/cycle"; the split does.

### 5.2 What fetchWidth means for the 32-bit port (base)

At `memDataWidth=32`, a beat is 2 half-words. With RVC a beat holds 1 RVI, or 2 RVC,
or 1 RVC + half of a straddling RVI (carry, section 2). So even a 32-bit port yields
up to 2 slots/cycle. Base config choice: `fetchWidth=1` (single-issue backend, N=1),
so SlotSlicer emits at most 1 slot/beat and holds the rest as look-ahead. This keeps
the whole frontend a straight pipe with a depth-2 skid queue - the in-order point.

### 5.3 Scaling to 64/128-bit fetch at high N

The scaling story is entirely in `memDataWidth`, and it touches exactly three
vertices:

- **FetchUnit:** req/resp payload widens to `memDataWidth`; outstanding tracking
  unchanged. One request still fetches one beat.
- **SlotSlicer:** the scan becomes `slotsPerBeat`-wide (section 2.6). This is the
  parallel-prefix length network. The carry stays one half-word regardless of width
  (INV-S2). This is the *only* structure whose logic grows with width, and it grows as
  O(slotsPerBeat) area / O(log slotsPerBeat) delay.
- **Predecoder / IssueQueue:** prune becomes a `fetchWidth`-wide find-first-taken;
  queue enqueues a group of up to `fetchWidth`. Both scale linearly.

NextPcGen does **not** widen: it still selects one fetch PC per cycle (one beat
address). Wide fetch changes the sequential stride (`fetchStride = memDataWidth/8`
bytes, section 1.4 `funcPcSelect`), not the number of PC selectors. A taken branch
mid-beat truncates the group via first-taken prune (the slots past the branch are
pruned, and NextPcGen redirects fetch to the target next cycle). This is the standard
"fetch to the first taken branch" bandwidth limit
(`response/microarchitecture-review.md:98,109`) and it is acceptable: at N high, a BTB
(Q3.2) can enable fetch-past-not-taken; at N=1 it is moot.

### 5.4 Concrete spec-DSL (parameters)

Add to `FrontendParamsSpecs.scala`:

```scala
  val paramMemDataWidth = spec {
    PARAMETER("MemDataWidth")
      .desc("Program-memory data-port width in bits (32/64/128). Sets slotsPerBeat = MemDataWidth/16.")
      .note("Contract tier - the physical bus width parents must wire.")
      .build()
  }
  val paramSlotsPerBeat = spec {
    PARAMETER("SlotsPerBeat")
      .desc("Derived: MemDataWidth/16. Upper bound on fetchWidth.")
      .note("Derived tier - not independently set.")
      .build()
  }
```

Amend `FrontendParams` (`FrontendParams.scala`):

```scala
// Contract tier gains memDataWidth; fetchWidth is bounded by it.
case class FrontendContractParams(
    fetchWidth: Int,
    memDataWidth: Int,          // NEW: physical program-memory port width
    instructionCacheSize: Int,
    instAddrWidth: Int
) {
  require(fetchWidth > 0)
  require(memDataWidth % 16 == 0, "memDataWidth must be a whole number of half-words")
  require(fetchWidth <= memDataWidth / 16, "fetchWidth cannot exceed slotsPerBeat")
  // ... existing requires ...
}
// derivations:
def memDataWidth: Int = contract.memDataWidth
def slotsPerBeat: Int = contract.memDataWidth / 16
def fetchStrideBytes: Int = contract.memDataWidth / 8
```

And the wrap-hazard `require` from guardrail G5:

```scala
require(prefetchDepth <= (1 << epochWidth) - 1,
  "outstanding fetch generations must not exceed 2^epochWidth-1 (epoch wrap, G5)")
```

### 5.5 Alternatives considered / rejected

- **Keep one `fetchWidth` scalar = instructions AND drive the bus from it.** Rejected:
  it cannot express a 32-bit bus feeding a 1-wide frontend vs an 8-wide one, and it
  couples the physical bus to the ILP target. The review's whole thesis is one RTL
  scaling by parameter; two orthogonal knobs (`memDataWidth`, `fetchWidth`) are needed
  to place both the in-order point and the wide point on the same source.
- **Fetch multiple beats/cycle (banked I-fetch).** Rejected at this stage: multi-beat
  fetch needs multiple outstanding + a beat reorder buffer (a queue). Widen the bus
  first; bank later only if a measured IPC target (review rec, `:116`) demands it.
- **Byte-granular fetch address.** Rejected: RISC-V instruction addresses are 2-byte
  aligned minimum; half-word granularity (section 2) is sufficient and cheaper.

### 5.6 Cost / verification / open questions

- **Cost at N=1:** `memDataWidth=32`, `fetchWidth=1`, `slotsPerBeat=2`. All derived
  widths collapse to the base numbers; the new params add no gates (they are elaboration
  constants). The `require`s are compile-time.
- **Cost at N=32:** `memDataWidth=128`, `fetchWidth` up to 8. Cost is the SlotSlicer
  prefix network + wide prune + wider queue group, all O(slotsPerBeat). No new *kinds*
  of structure appear - the graph is identical, only edge widths and the scan grow.
- **Verification obligations:**
  - Elaboration test: instantiate `{32/1, 64/4, 128/8}` and assert
    `fetchWidth <= slotsPerBeat` and the SlotSlicer conservation property (INV-S1) holds
    at each width.
  - Equivalence test: the same instruction stream fetched at 32-bit and 128-bit widths
    produces the identical issued slot sequence (width is transparent to function -
    this is the LI/edges-own-timing claim, `dontcommit.md:8`, made concrete).
  - `require` tests: `fetchWidth > slotsPerBeat` and `prefetchDepth > 2^epochWidth-1`
    must fail elaboration.
- **Open questions for the panel:**
  - Q5.1 Is `fetchWidth` set independently or forced equal to `min(slotsPerBeat,
    backendIssueWidth)`? I lean: independent contract knob, `require`-bounded, so a
    narrow frontend on a wide bus is legal (useful for area-limited high-freq configs).
  - Q5.2 Does the external program-memory port need a byte-enable/size field, or is it
    always a full-beat read? (Affects `bndExternalProgramMemoryReq`, currently a
    placeholder, `FrontendBundlesSpecs.scala:69-73`.)
  - Q5.3 Should `memDataWidth` live in a shared `api/` param (memory subsystem also
    needs the bus width) rather than frontend-private? I lean shared api.

---

## 6. Bundle contracts to fill (blocking dependency)

Every interface above `.uses` a bundle, and today the bundles are placeholders
(`FrontendBundlesSpecs.scala:62-139`) with TODO field stubs
(`FrontendBundles.scala:14-85`). The contracts above imply these concrete fields;
I list them so the panel can ratify the payloads in one place. (Widths reference the
params in section 5.)

| Bundle | Fields (normative) | Notes |
|---|---|---|
| `BootAddr` | `bootAddr: UInt(instAddrWidth)` | matches BootSequencer `bootOut` (`BootSequencer.scala:20`) |
| `NextPcIssue` | `pc: UInt(instAddrWidth)`, `epoch: UInt(epochWidth)` | epoch-stamped by NextPcGen (funcEpochStamp) |
| `Redirect` | `target: UInt(instAddrWidth)`, `cause: UInt(redirectCauseWidth)`, `epoch: UInt(epochWidth)` | epoch is the POST-increment value (G1) |
| `ExternalProgramMemoryReq` | `addr: UInt(instAddrWidth)`, `epoch: UInt(epochWidth)` | epoch carried so response can be matched (G2); size field TBD Q5.2 |
| `ExternalProgramMemoryResp` | `data: UInt(memDataWidth)`, `exception: Exception` | width from section 5 |
| `FetchResponse` | `data: UInt(memDataWidth)`, `pc: UInt(instAddrWidth)`, `epoch: UInt(epochWidth)` | one beat |
| `SlotGroup` (`bndSlotGroup`, NEW) | `slots: Vec(fetchWidth, Slot)`, `count: UInt` | Slot = `{bits:UInt(32), pc, len:UInt(2), valid:Bool, exc:Bool, epoch}` |
| `PredictorOutput` | `valid, taken: Bool`, `target: UInt(instAddrWidth)`, `srcPc: UInt(instAddrWidth)`, `srcEpoch: UInt(epochWidth)`, `indirectStall: Bool` | G3/G4 |
| `IssueBackend` | `slots: Vec(issueWidth, Slot)`, `count: UInt` | to backend; issueWidth per Q4.2 |

`bndSlotGroup` and `bndFetchResponse` are new and must be added to
`FrontendBundlesSpecs.scala`. `Exception` remains a cross-team placeholder
(`FrontendBundlesSpecs.scala:63-67`); the frontend only sets `instrAddrMisaligned`
and `programMemFault` bits (INV-S5, funcEpochTagging), so the frontend's minimal
requirement on `Exception` is those two flags.

---

## 7. Summary of blocking decisions for the panel

1. **D1-D4:** boot -> NextPcGen (not FetchUnit); FetchUnit owns
   {NextPcIn, ProgMemReqOut, ProgMemRespIn, FetchResponseOut}; one redirect edge
   declared at top; corrected FrontendTop mermaid must match `.has(...)` port sets
   edge-for-edge. (Section 1.)
2. **SlotSlicer** is a one-half-word-carry straddle machine with INV-S1..S5;
   frontend tags misalignment, never traps. (Section 2.)
3. **First-taken** = valid up to and including the first taken transfer, prune the
   rest; epoch guardrails G1-G5 are now operational (redirect wins same-cycle,
   in-flight fetch drops on epoch mismatch, prediction epoch-gated, indirects stall,
   generations bounded by epoch width). (Section 3.)
4. **IssueQueue** is a rate-matching skid buffer (the one intentional frontend cycle),
   only-valid-slots (INV-Q1), epoch-drain not flush (INV-Q2), in-order (INV-Q3),
   `iqDepth=4` base by a stated formula. (Section 4.)
5. **fetchWidth split into `memDataWidth` (bus) and `fetchWidth` (instructions)**,
   `fetchWidth <= memDataWidth/16`; scaling to 64/128-bit touches only SlotSlicer's
   scan; NextPcGen never widens. Wrap hazard fixed by `prefetchDepth <= 2^epochWidth-1`.
   (Section 5.)

All five fold to ~zero incremental cost at N=1 (single-issue, 32-bit bus,
depth-2..4 skid queue, one epoch latch), and scale on one RTL to the wide point by
widening `memDataWidth`/`fetchWidth`/`iqDepth` without introducing new *kinds* of
structure. The open questions Q1.1-Q5.3 are the items I want settled before shell RTL.
