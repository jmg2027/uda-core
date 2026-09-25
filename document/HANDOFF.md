# Handoff: ADR-019 conventional OoO v0 - spec-DSL migration (2026-09-25)

Branch: `claude/adr-019-spec-dsl-8orhnc` (on top of `main` 5c73eda, which merged
`architecture/ooo-v0-spec-dsl`). Supersedes the 2026-07-06 handoff; the earlier session
history (verif/PPA port, ADR-016/017/018, verified FU IP port) lives in git history.

## Goal

UDACore is a personal, conventional out-of-order RV32IM core (ADR-019): PC-indexed
BTB+TAGE+RAS frontend with FTQ, explicit data-less ROB, sRAT/rRAT + free list +
checkpoints, RS, LSQ, execute-time selective recovery via one RecoveryEvent, VIPT L1
caches, ITLB/DTLB + shared Sv32 PTW, TileLink boundary. This session executed Work Order
07 at the specification level only: no new-architecture RTL.

## Done this session

1. `ff19cf1` - WP-0..WP-8 spec migration (clean-break).
   - Doctrine: RecoveryEvent is rawNoDecoupled class 6; epoch survives only as a local
     transaction generation (propGenerationTagScope); rawSpeculativeHolder stance
     template; propIsaRetireEquivalence replaces N-equivalence.
   - One order rule: BackendParamsSpecs.funcRobOlder + funcRecoveryKills. robTag is
     allocated by RenameUnit; after any event the next tag is e.robTag + 1.
   - Frontend: FetchPcGen, BranchPredictor (BTB/TAGE/RAS RAW subcores), FetchTargetQueue
     (checkpoints, commit-time training), FetchUnit (parallel ITLB + VIPT I$, fetch
     generation), FetchBuffer, FrontendTop graph. Deleted BranchPredecoder, SlotSlicer,
     RvcExpander (290-line RTL), NextPcGen, IssueQueue.
   - Backend: ReorderBuffer, RecoveryController (only RecoveryEvent producer; ArchRedirect
     wins), RenameUnit (sRAT/rRAT/FIFO free list with spec/arch heads/checkpoints/busy
     table), RS (oldest-ready), BranchUnit, CommitUnit (fence/fence.i/sfence sequencing),
     TrapController (M/S delegation, sole ArchRedirect producer), CsrController
     (TranslationContext view), LoadStoreQueue, StoreBuffer (moved from the dissolved
     memorysubsystem domain), FU wrappers, BackendTop graph. Deleted RedirectUnit.
   - Core: Sv32Specs (shared semantics, A/D policy = fault, no hardware update),
     InstructionTlb, DataTlb (non-blocking miss + fault record), PageTableWalker
     (physical PTE reads through D$, SFENCE.VMA distribution, Retry on overlap),
     InstructionCache, DataCache (2 MSHRs x 2 targets, write-back/allocate, clean-all),
     InstBusAdapter, DataBusAdapter, CoreTop graph. Deleted GlobalEpochUnit.
   - Design: every new/changed vertex is a generated `???` shell (PENDING on elaboration).
     Implemented-but-epoch-based FU wrappers (AluUnit..DividerUnit) became shells; the
     external IP is untouched. Params case classes carry the v0 defaults plus two paired
     requires (propPrfSizingCoversRob, propViptGeometryLegal). misa default IMAFDCSU ->
     IMSU. spec-check check 5 re-based to `reg-queue-recovery`.
2. `8cf8815` - red/PENDING bindings: 21 new L2 `.scn` (all assemble, all < 0x200 bytes;
   they answer harness-not-ready today) + L1 ParamSpecTests (FAIL observed with the
   requires disabled, then PASS). spec-test-allow shrank to 54 entries.
3. `62aed52` - deleted superseded non-ADR architecture papers/docs; README, CLAUDE.md,
   AGENTS.md, skills, docs index, ADR-000 migration record updated.

4. Review round 1 (owner review of 6d7ce25), all spec-level:
   - P0 trap hand-off: ReorderBuffer headLocked on an exception hand-off; CommitUnit
     funcTrapHold/propTrapHoldUntilRedirect (+ RecoveryEventIn) holds RobHeadIn until the
     ArchRedirect with that robTag; propRobRetireInOrder rewritten (interrupts skip a tag).
   - P0 RAS: HistoryCheckpoint carries the full RAS contents (rasEntries), making
     propHistoryRestoreExact true under wrong-path pushes/pops/wrap.
   - P0 Svade: Sv32 A/D policy named Svade; identity RV32IM_Zicsr_Zifencei + Svade.
   - P0/P1 precise MMIO stores (first form, replaced in round 2 below); PMA contract makes
     cacheable writebacks fault-free. OQ-G closed.
   - P1 checkpoints: freed at resolution via BranchUnit CheckpointRelease -> RenameUnit
     (bitmask pool with owner robTag), or after the recovering restore; not at commit.
   - P1/P2 PTW: globalSeen |= PTE.G over the whole walk.
5. Review round 2 (owner review of 8f118a4):
   - P0 uncacheable head execution separated from commit: the round-1 form fired StoreCommit
     (an irrevocable commit projection) before the store could fault and had no vaddr for
     tval. Now an uncacheable load/store reports headExecute (not done); CommitUnit sends
     HeadMemGrant once for the head; the LSQ waits for StoreBufferEmpty, performs the single
     bus access (stores via the new LSQ -> DataCache UncachedStoreReq/Resp port, keeping the
     SQ entry and its vaddr), and completes done or with a precise access fault. Retirement
     is the ordinary atomic commit; StoreCommit of an uncachedPerformed store only releases
     the SQ entry. StoreBuffer holds committed cacheable stores only; drains never fault.
     While a grant is in flight no interrupt/debug is taken in front of the head, so an
     MMIO access is never killed and replayed (propUncachedPerformedOnce).
   - P1 debug boundary: DebugReq now enters the CommitUnit and is sampled with the
     interrupt rules at a precise retire boundary (Exception{Debug}); the TrapController
     no longer reads debugReq.
   - Owner decisions recorded: OQ-E waived (csr/CSR.scala unprotected, rewritten with the
     RTL); OQ-D decided (single-hart, non-coherent, no DMA/coherent agent in v0).

6. Review round 3 (owner review of 8647d26) - spec freeze:
   - P0 head presentation: RobHeadOut.valid = head.valid && (done || headExecute); a
     headExecute && !done head is observed by the CommitUnit (ready held low) so it can send
     HeadMemGrant; only a done head is ever transferred. Round 2 would have deadlocked.
   - P0 uncached load path: symmetric to stores - LSQ -> DataCache UncachedLoadReq{lqIdx,
     lqGen, paddr, size} / UncachedLoadResp{data, accessFault}; the paddr comes from the
     first cached lookup's translation, so no DTLB pairing is needed. DCacheLoadReq is now
     the speculative cached path only (its uncached field is removed).
   - P1: propNoSpeculativeStoreVisible renamed propNoWrongPathStoreVisible and restated for
     the cacheable (commit-time) and granted-uncacheable (head-time) visibility rules.
   - The owner declared spec freeze at 242feaf; the next work is RTL with assertions/tests.
7. RTL block 1 - RenameUnit (frozen spec unchanged):
   - L1 SpecTests `verif/suites/spectest/RenameUnitSpecTests.scala` (7 tests) bind
     funcRenameMap, funcFreeListAllocate, funcBranchCheckpoint, funcBranchRecoveryRestore,
     propPhysRegConservation, propCheckpointReleasedOnce; observed PENDING x7 against the
     typed-IO shell before any RTL.
   - RTL `backend/design/modules/RenameUnit.scala`: sRAT/rRAT, FIFO free list
     (FreeSlots = PRF - 32, tail = archHead + FreeSlots implicit), 4-slot checkpoint
     pool, busy table, serialize gate, atomic ROB/RS/LSQ fork, both recovery kinds.
     ADR-019 design bundles + RobOrder.{robOlder, recoveryKills} in
     `backend/design/shared/BackendBundles.scala`; BackendFrontendView mirrors the
     frontend widths (decodeWidth, fetchWidth, ftqDepth, vAddrWidth).
   - @LocalSpec asserts for propPhysRegConservation and propCheckpointReleasedOnce; the
     negative SpecTest checks require the named assertion text in the simulation log
     (CachedSimulator.lastSimulationLog). Mutation controls (asserts off, snapshot before
     own dest, kill not freeing, no head restore, commit pushing newPrd, rename in the
     event cycle) each turned at least one test red.
   - Implemented but only L2-bound (ooo_rename_checkpoint.scn, PENDING until CoreTop):
     funcRobTagAllocate, funcBusyTable, funcAllocateAtomic, funcSerializeGate,
     funcRetirementMapUpdate, funcArchRecoveryRestore, propCheckpointRestoreExact,
     propRobTagConsecutive.

## Validation status (run this session)

- `bash verif/bin/build.sh`: 0 errors at each of the three commits.
- Review rounds 1-3 re-ran all gates below after each fix; the numbers are from round 3.
- `python3 tools/spec-check.py`: 0 errors, 6 warnings (localspec-coverage on protected
  CSR/Decoder/Debug/Trigger and Util - pre-existing).
- Internal-edge reconciliation (scratch script, stronger than check 1): every labeled
  edge of FrontendTop (7), BackendTop (59), CoreTop (38) matches a producer *Out and a
  consumer *In interface; no orphan child interfaces.
- `verif/bin/run.sh verif.spectest.RunSpecTests`: 15/15 PASS (6 pre-existing + 2 params +
  7 RenameUnit) after RTL block 1.
- `scn.sh run ooo_div_survives_mispredict.scn`: harness-not-ready (exit 3), as designed.
- Nothing SIMULATED against CoreTop (all ADR-019 vertices are shells).

## Remaining allowlist debt

- spec-test-allow: 56 names (funcHeadMemGrant, propUncachedPerformedOnce, and the other uncacheable paths need an uncacheable harness region). SFENCE.VMA (flush funcs, propSfenceFlushesAll) - protected
  assembler has no mnemonic; uncacheable PMA paths - harness has no uncacheable region;
  predictor/FTQ internals and bus-adapter/cache monitors - need L1 SpecTests on RTL;
  doctrine/machine-check props; pre-ADR-019 carried names.
- spec-check-allow: 66 PROPERTYs (propPhysRegConservation and propCheckpointReleasedOnce
  removed with the RenameUnit asserts) (propNoSpeculativeStoreVisible renamed propNoWrongPathStoreVisible), each pairs with its vertex's design assert when RTL lands.

## Open questions needing the OWNER

- OQ-C: the ADR-008 N=1 PPA bar is superseded by ADR-019; confirm what (if any) PPA bar
  the v0 reference point should meet.
- OQ-H (new): assembler protection blocks SFENCE.VMA / raw `.word` in `.scn`; allow adding
  mnemonics (or a `.word` directive) to `src/main/scala/assembler`?
- OQ-I (new): confirm the ADR-015 D-15.4/15.5 reinterpretation (ISA-model equivalence,
  one-axis configs without N) recorded in ADR-000 - or write a small ADR amendment.
- RV64 decode scheduling (carried; v0 is RV32 only).
- Decided 2026-09-25: OQ-E waived (CSR.scala protection lifted for the ADR-019 rewrite; it
  still carries the legacy epoch input/meta until then, kept compiling by
  BackendParams.legacyCsrEpochWidth). OQ-D decided: v0 single-hart, non-coherent, no DMA.
  OQ-G closed: uncacheable accesses execute at the ROB head under HeadMemGrant (precise
  faults); cacheable regions are fill/writeback-fault-free by PMA contract (re-confirm when
  the SoC memory map is chosen).

## Unresolved architecture questions (engineering, not owner-gated)

- RenameUnit implementation assumptions awaiting a spec ruling (reported, frozen spec
  untouched): RobStatus.empty is a same-cycle view that already includes the previous
  cycle's allocation; a CheckpointRelease whose owner the same-cycle RecoveryEvent kills
  is ignored; an ArchRedirect restores sRAT from the rRAT including a same-cycle commit;
  LsqAllocation size/signed come from insn[14:12]; enum and UopOp encodings are fixed in
  BackendBundles.scala; the AllocateAtomic fork needs ROB/RS/LSQ ready independent of valid.

- fence.i cost: D$ clean-all + I$ invalidate per fence.i (non-coherent I-side).
- DTLB single outstanding walk + fault record; multiple distinct-VPN misses serialize.
- One BTB-tracked CFI per fetch block; GHR shifts one bit per block with a tracked Branch.
- Predictor training at retirement only; no decode-time redirect for direct JAL.
- v0 serializes every CSR op (rename into empty ROB) and refetches after CSR writes.
- Full RAS snapshot per FTQ entry costs FtqDepth x RasDepth x 32 bits (4 Kbit at v0).
- Uncacheable access latency: each one waits for the ROB head, a StoreBuffer drain, and a
  bus ack, and blocks interrupt sampling while in flight.

## Next steps (in order)

1. Spec frozen at 242feaf; RenameUnit RTL is green. Next: ReorderBuffer ->
   RecoveryController -> CommitUnit, each red-first. RenameUnit spec ambiguities found
   during implementation are reported to the owner, not fixed in the frozen spec.
2. RTL fill-in, each vertex starting from its red test: RenameUnit + ReorderBuffer +
   RecoveryController + CommitUnit (with the ADR-010 retire stream and CoreHarness
   binding) -> RS/Dispatch/PublishMux/PRF/FU wrappers -> LSQ/StoreBuffer -> DataCache +
   DataBusAdapter -> frontend (FetchPcGen/BranchPredictor/FTQ/FetchUnit/FetchBuffer) +
   InstructionCache/InstBusAdapter -> MMU (TLBs/PTW) -> Trap/Csr (CSR.scala rewrite, OQ-E waived).
3. Replace each allowlisted PROPERTY with its design assert; add L1 SpecTests for the
   predictor history restore (ADR-019 obligation) and cache/bus monitors.
4. Harness: uncacheable PMA region, bus-stall knob, debugReq input, then SFENCE.VMA scn.

## Gotchas

- Coursier download is blocked (GitHub 403 via proxy), so setup.sh cannot resolve jars.
  Workaround used: a Maven pom resolving chisel_2.13:6.2.0 + scala 2.13.12 into
  ~/.m2, copied to /tmp/chisel_cp.txt, /tmp/chisel_plugin.txt, /tmp/scalac/*.jar, and
  written into the gitignored verif/.toolchain.env.
- The spec-check hook checks the working tree, not the index: validate a partial commit
  yourself before committing it.
- genshell (session scratch) generated the design shells from `.has(...)`; regenerate the
  same way when a CONTRACT's interface list changes, or edit by hand.
- In `.scn`, the assembler accepts `jalr x0, xN, 0` (not `0(xN)`) and decimal load/store
  offsets; unwritten memory reads 0x13, so PTE tables must write explicit zeros.
- Chisel 3 vs 6 duality (sbt default chisel 3, verif gate 6.2.0) is unchanged.
- Protected: src/main/scala/assembler/*, src/test/scala/{cluster,assembler}. csr/CSR.scala is
  no longer protected (OQ-E waived) and is rewritten with the Trap/Csr RTL.
