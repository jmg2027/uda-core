# Work Order 07: OoO v0 Spec-DSL Authoring

Status: READY
Authority: ADR-019 > ADR-001..018 where ADR-019 explicitly supersedes/amends them.
Scope: SPECIFICATION FIRST. Do not implement the new microarchitecture RTL in this work order.

## 1. Goal

Translate ADR-019 into the repository's Scala spec-framework DSL so a later coding
agent can implement the core against machine-readable named contracts.

The deliverable is not a prose architecture paper. The deliverable is a coherent
set of `spec { ... }` objects under `src/main/scala/udacore/**/spec/`, with
normative rawTop graphs, bundles, parameters, functions, properties, and test
bindings/allowlist entries required by ADR-015/018.

## 2. Mandatory reading order

1. `document/adr/ADR-019-conventional-ooo-root-architecture.md`
2. `AGENTS.md`
3. `.claude/skills/spec-first/SKILL.md`
4. `document/adr/ADR-015-spec-enforcement-and-nequiv.md`
5. `document/adr/ADR-018-spec-tdd.md`
6. Existing spec files for the domain being rewritten.

When an older spec conflicts with ADR-019, ADR-019 wins. Do not preserve an old
contract merely to reduce diff size.

## 3. Non-negotiable architecture

### ISA / privilege

- RV32IM only for v0.
- No C extension and no RVC representation anywhere in the new frontend contract.
- U/S privilege and Sv32.
- M-mode remains the machine root as required by RISC-V privilege architecture.

### Frontend

- 16-byte fetch block / 4 aligned 32-bit instructions.
- PC-indexed prediction before instruction bytes.
- BTB + TAGE + RAS.
- FTQ with predictor/recovery metadata.
- Fetch buffer.
- ITLB + VIPT I-cache parallel lookup.
- Branch mispredict redirects at execute, not at commit.

### Backend

- explicit data-less ROB, depth 16 v0;
- sRAT + rRAT + free list;
- 48-entry integer PRF v0;
- 8-entry integer RS v0;
- 8-entry LQ + 8-entry SQ v0;
- OoO issue/completion, in-order commit;
- branch checkpoints and selective younger-than recovery;
- single result/commit lane may remain the v0 point.

### Memory / MMU

- 16 KiB, 4-way, 64-byte-line VIPT I-cache and D-cache reference configs;
- 16-entry ITLB and 16-entry DTLB;
- shared Sv32 PTW;
- two D-cache MSHRs and hit-under-miss;
- physical-address memory-dependence correctness;
- conservative unknown-store disambiguation;
- SFENCE.VMA may full-flush both TLBs in v0;
- PTW accesses are physical and may be cached by D-cache.

## 4. DSL authoring rules

Every spec file is English ASCII.

Canonical form:

```scala
package udacore.<domain>.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

object ExampleSpecs {
  val contExample = spec {
    CONTRACT("Example")
      .desc("One precise paragraph stating ownership and purpose.")
      .has(
        intfRequestIn,
        intfResponseOut,
        funcBehavior,
        propInvariant
      )
      .uses(paramDepth, bndRequest)
      .build()
  }

  val intfRequestIn = spec {
    INTERFACE("RequestIn")
      .desc("Transfer semantics, producer, consumer, and backpressure rule.")
      .uses(bndRequest)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcBehavior = spec {
    FUNCTION("Behavior")
      .desc("Observable behavior, including ordering and recovery semantics.")
      .build()
  }

  val propInvariant = spec {
    PROPERTY("Invariant")
      .desc("One falsifiable invariant.")
      .note("State whether this is an elaboration require or simulation assertion.")
      .build()
  }
}
```

Use:

- `.has(...)` for objects owned by the CONTRACT;
- `.uses(...)` for shared bundles, parameters, or external contracts;
- `.is(rawReadyValidIntf)` for token transfer;
- `.is(rawNoDecoupled)` only for sanctioned broadcast facts/statics;
- `.draw("mermaid", ...)` inside every rawTop CONTRACT;
- `.markdownTable` for field and state-transition tables;
- `.note` for rationale/constraints, never to hide normative behavior.

Do not put executable assertions inside `.code` or `.note`. PROPERTY binding is
handled by design `@LocalSpec` assertions or the interim allowlist.

## 5. Spec migration plan

### WP-0: shared design doctrine

Modify:
- `src/main/scala/udacore/common/spec/DesignRuleSpecs.scala`

Required change:
- add speculative RecoveryEvent to the sanctioned `rawNoDecoupled` classes;
- rewrite epoch-only wording so program-order speculative state is classified by
  selective recovery ownership, while epoch/generation remains permitted for
  uncancelable transaction responses;
- do not weaken ready/valid as the default transfer protocol.

### WP-1: shared backend bundles and parameters

Modify:
- `backend/spec/shared/BackendBundlesSpecs.scala`
- `backend/spec/shared/BackendParamsSpecs.scala`
- `core/spec/shared/CoreParamsSpecs.scala`

At minimum define/spec:
- `bndRobTag`
- `bndRecoveryEvent`
- `bndBranchCheckpointId`
- `bndRobEntry`
- `bndRenameAllocation`
- `bndBranchResolution`
- `bndLoadQueueEntry`
- `bndStoreQueueEntry`
- `paramRobDepth`
- `paramIntegerPrfEntries`
- `paramRenameWidth`
- `paramCommitWidth`
- `paramLoadQueueDepth`
- `paramStoreQueueDepth`
- `paramBranchCheckpointCount`

Every age comparison must reference one shared wrap-aware ROB-order function.
Do not duplicate sequence-order definitions in RS/ROB/LSQ.

### WP-2: frontend shared contracts

Rewrite:
- `frontend/spec/shared/FrontendBundlesSpecs.scala`
- `frontend/spec/shared/FrontendParamsSpecs.scala`

Remove architectural dependence on:
- 16-bit slots;
- instruction length fields used for RVC;
- RVC expansion bundles;
- first-taken predecoder feedback.

Add at minimum:
- `bndFetchBlock`: base PC, 4 x 32-bit instructions, per-slot valid/exception metadata;
- `bndPrediction`: predicted CFI slot/type/taken/target plus FTQ id;
- `bndPredictorMeta`: TAGE provider/alternate and history metadata needed for training;
- `bndFtqEntry`: fetch PC, predicted next PC, GHR/RAS checkpoint, predictor metadata;
- `bndFetchRedirect`: target and recovery identity;
- `paramFetchBytes = 16` reference;
- `paramFetchWidth = 4` reference;
- `paramDecodeWidth = 2` reference;
- BTB/TAGE/RAS/FTQ depth parameters.

### WP-3: conventional frontend vertices

Create or rewrite CONTRACT specs for:
- `FetchPcGenSpecs.scala`
- `BranchPredictorSpecs.scala`
- `FetchTargetQueueSpecs.scala`
- `FetchUnitSpecs.scala`
- `FetchBufferSpecs.scala`
- `FrontendTopSpecs.scala`

`BranchPredictor` may own BTB, TAGE, and RAS as internal RAW subcores rather than
making each one a rawTop vertex. If they are separate CONTRACTs, the FrontendTop
graph must show every edge explicitly. Pick one decomposition and use it consistently.

Required predictor functions/properties include:
- PC-indexed BTB lookup;
- TAGE longest-history provider and alternate selection;
- RAS call/return behavior;
- speculative GHR update;
- GHR/RAS checkpoint/restore through FTQ;
- deterministic predictor-training point;
- no prediction dependence on same-block fetched instruction bits;
- one-taken-CFI-per-fetch-block v0 limitation stated explicitly.

Remove from the new FrontendTop CONTRACT:
- BranchPredecoder;
- SlotSlicer;
- RvcExpander;
- indirect-stall-until-backend-commit behavior;
- first-taken predecode feedback.

Do not delete old files until all imports/references are mapped. It is acceptable
for the migration commit to delete them once their references reach zero.

### WP-4: ROB / rename / selective recovery

Create:
- `backend/spec/modules/ReorderBufferSpecs.scala`
- `backend/spec/modules/RecoveryControllerSpecs.scala` if recovery ownership is
  not cleanly contained by ROB + RenameUnit.

Rewrite:
- `RenameUnitSpecs.scala`
- `ReservationStationSpecs.scala`
- `CommitUnitSpecs.scala`
- `BranchUnitSpecs.scala`
- `BackendTopSpecs.scala`

Required contracts:

ROB:
- allocate in program order;
- completion may arrive out of order;
- retire only from head;
- record precise exception state;
- invalidate only entries younger than RecoveryEvent;
- expose branch/FTQ metadata needed for recovery.

Rename:
- sRAT/rRAT/free-list ownership must be explicit;
- checkpoint allocation/release;
- branch recovery from branch checkpoint;
- architectural recovery from rRAT;
- physical-register conservation.

RS:
- wakeup/select independent of program order;
- selective recovery by common younger-than rule;
- oldest-ready arbitration policy must be stated if used.

Branch:
- resolved outcome/target;
- mismatch detection against prediction metadata;
- execute-time RecoveryEvent generation.

Recovery merge:
- an older commit-head architectural redirect (trap/interrupt/xRET/debug/system
  serialization as applicable) wins over a same-cycle execute branch recovery;
- v0 selects at most one branch RecoveryEvent producer per cycle;
- every consumer sees the same selected recovery identity.

Commit:
- in-order architectural update;
- rRAT update and old-PRD free;
- precise trap/interrupt boundary;
- committed-store handoff.

### WP-5: LSQ and committed stores

Create:
- `backend/spec/modules/LoadStoreQueueSpecs.scala`

Amend existing StoreBuffer specs rather than conflating the two structures.

LSQ responsibilities:
- speculative LQ/SQ allocation;
- address/translation pending state;
- physical-address order checks;
- store-to-load forwarding;
- conservative block behind unresolved older stores;
- selective recovery of younger entries;
- load completion into PRF/ROB.

Committed StoreBuffer responsibilities:
- receives only committed stores from commit/SQ;
- drains to D-cache in program order;
- survives branch recovery;
- never owns speculative store visibility.

### WP-6: MMU / TLB / PTW

Create under `core/spec/modules/`:
- `InstructionTlbSpecs.scala`
- `DataTlbSpecs.scala`
- `PageTableWalkerSpecs.scala`

Amend:
- `core/spec/top/CoreTopSpecs.scala`
- privilege/CSR parameter specs as necessary.

Required functions/properties:
- Sv32 VPN/PPN decomposition;
- TLB hit/miss and ASID/global matching;
- permission check for fetch/load/store;
- 4 KiB page and Sv32 superpage handling;
- A/D-bit policy stated explicitly;
- shared PTW arbitration;
- PTW physical memory request contract;
- TLB refill;
- instruction/load/store page-fault generation;
- SFENCE.VMA full-invalidate v0 behavior;
- PTW must not recursively use DTLB.

### WP-7: VIPT L1 caches

Rewrite:
- `core/spec/modules/InstructionCacheSpecs.scala`
- `core/spec/modules/DataCacheSpecs.scala`
- `core/spec/shared/CoreParamsSpecs.scala`

Reference configuration:
- 16 KiB, 4 ways, 64-byte line, 64 sets.

I-cache:
- VIPT;
- ITLB lookup in parallel;
- physical tag compare;
- one miss context v0;
- wrong-path fills may complete/install.

D-cache:
- VIPT;
- DTLB lookup in parallel with index/data access;
- two MSHRs;
- hit-under-miss;
- tagged/reassociable responses;
- PTW reads accepted as physical requests;
- wrong-path load fills may remain;
- speculative stores cannot update cache.

Coherence enable is separate from cache enable. V0 may be non-coherent TL-UH.
Do not make `dcache.nonEmpty` imply `hasBCE=true`.

### WP-8: CoreTop graph

Rewrite the normative mermaid graph in:
- `core/spec/top/CoreTopSpecs.scala`

The graph must show at least:
- frontend;
- ITLB;
- I-cache;
- shared PTW;
- backend;
- DTLB;
- LSQ/memory subsystem path;
- D-cache;
- instruction/data TileLink adapters;
- execute-time recovery edge back to frontend/backend speculative structures.

Keep rawTop wiring-only.

## 6. Required supersession cleanup

The spec authoring change must remove normative references that claim:

- RVC is part of the v0 ISA;
- BranchPredecoder is the prediction source;
- branch redirect waits for commit head;
- global epoch mismatch is the universal speculative kill condition;
- no ROB exists;
- N=1 degeneracy is a product requirement for the new core;
- D-cache presence necessarily enables TL-C;
- the base L1 cache is PIPT.

Historical ADR files may remain. Active spec objects must reflect ADR-019.

## 7. Spec-TDD handling

Every new FUNCTION/PROPERTY name must satisfy ADR-018.

Preferred:
1. create a red/PENDING L1 SpecTest or L2 scenario naming it;
2. keep design unimplemented;
3. observe red/PENDING.

If a test substrate cannot yet instantiate the new contract, add the name to
`tools/spec-test-allow.txt` with the work package noted in a comment. For every
new PROPERTY without a concrete design assertion, also add it to
`tools/spec-check-allow.txt`.

Do not run either `--update-allow` command blindly; that would mask unrelated
coverage regressions. Add only the names introduced by the current work package.

## 8. Commit granularity

Spec migration should be split by dependency, not by arbitrary file count:

1. ADR-019 doctrine/shared bundles/params.
2. Frontend predictor + FTQ + frontend graph.
3. ROB/rename/recovery.
4. LSQ/store lifecycle.
5. MMU/TLB/PTW.
6. VIPT caches/CoreTop graph.
7. Red/PENDING tests and allowlist shrink.

Each commit must keep Scala compilation and `tools/spec-check.py` at the
documented expected state.

## 9. Acceptance criteria for the spec-authoring phase

The phase is complete when:

- no active v0 spec claims RVC support;
- no active frontend graph contains BranchPredecoder/SlotSlicer/RvcExpander;
- FrontendTop has a PC-indexed BTB+TAGE+RAS+FTQ prediction contract;
- BackendTop contains an explicit ROB and selective RecoveryEvent contract;
- older uops survive a younger branch mispredict by specification;
- ITLB/DTLB/shared PTW/Sv32 are explicit contracts;
- I$/D$ are VIPT in the v0 contract;
- D-cache has at least two MSHRs and hit-under-miss in the reference config;
- LSQ and committed StoreBuffer ownership are distinct;
- every rawTop mermaid reconciles with its interfaces;
- every new FUNCTION/PROPERTY has a test binding or explicit narrow allowlist entry;
- no design RTL for the new architecture is added before these specs are reviewed.
