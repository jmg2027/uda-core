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
8. RTL block 2 - ReorderBuffer (frozen spec unchanged):
   - L1 SpecTests `verif/suites/spectest/ReorderBufferSpecTests.scala` (8 tests) bind
     funcRobAllocate, funcRobComplete, funcRobHeadOffer, funcRobRecovery, funcRobOlder,
     propRobRetireInOrder, propOlderSurvivesRecovery, propRobCompletionTargetsLive;
     observed PENDING x7 against the typed-IO shell (rob.order bound the already
     implemented RobOrder.robOlder; its red is the robOlder mutant below).
   - RTL `backend/design/modules/ReorderBuffer.scala`: RobDepth data-less entries, head/tail
     RobTag pointers, headLocked trap hand-off, two-phase headExecute completion,
     selective BranchMispredict kill with tail rewind and blockEnd, ArchRedirect reset.
     Uops with a decode exception or fuType System are done at allocation; sysOp comes
     from DecodedUop.op for System uops and is Csr for every Csr uop.
   - Asserts: window integrity and transfer legality (RobRetireInOrder), survivor state
     preservation and live recovering tag (OlderSurvivesRecovery), completion targets a
     live, not-done entry (RobCompletionTargetsLive), allocation at the tail.
   - Mutation controls (10): 9 turned tests red; the same-cycle-killed-completion filter
     mutant is equivalent (a killed entry's done bit is unobservable and reallocation
     re-initializes the entry).
9. ADR-019A (v0 Erratum 01, owner ruling) applied on top of the 242feaf freeze:
   RS/LSQ allocation only for uops that need execution (needsRs = !exception &&
   fuType != System); explicit DecodedUop.sysOp (None/Fence/FenceI/SfenceVma/Wfi/Mret/
   Sret/CsrWrite) carried to the ROB; RobStatus is the registered occupancy view;
   funcRobOlder's domain excludes the tail sentinel; a retiring ArchRedirect restores
   from rRATNext/archHeadNext, a non-retiring one from the committed state. DSL changes
   cite ADR-019A. New L1: rename.allocateAtomic, rename.archRecovery, rob.allocate.sysOp,
   decode.sysOpClassify (SystemOpDecode pins the table ahead of the DecodeUnit RTL);
   13 mutants all red.
10. RTL block 3 - RecoveryController (spec 242feaf + ADR-019A):
   - L1 SpecTests `verif/suites/spectest/RecoveryControllerSpecTests.scala` (4 tests) bind
     funcRecoverySelect, funcRecoveryPublish, propSingleRecoveryPerCycle,
     propArchRedirectWins; PENDING x4 against the typed-IO shell.
   - RTL: both inputs always ready; the event is formed combinationally in the request
     cycle (published or discarded, never queued); ArchRedirect wins; checkpointId and
     cfiOutcome are driven 0 on an ArchRedirect. BranchResolution/ArchRedirect bundles added.
   - Asserts check the output port every consumer observes: event valid iff a request,
     the event equals the selected request (SingleRecoveryPerCycle), ArchRedirect kind on
     a collision (ArchRedirectWins); plus request-cause legality.
   - Mutation controls (7) all red; four of them stopped by the property asserts, and the
     registered-publish mutant too once the asserts read the output port.
11. ADR-019B (v0 Erratum 02, owner ruling) on top of 242feaf + ADR-019A: RecoveryCause.Debug
    (7); v0 WFI is a serializing NOP; usingRvvi-only CommitPrfReadReq/Resp between the
    CommitUnit and the PRF (new BackendTop edges); RetireToken.priv is the executing
    privilege (InterruptCtrl.priv); trap-entry tokens are observation events, not
    retirements; retiring redirects are atomic with their ExceptionOut.
12. RTL block 4 - CommitUnit:
   - L1 SpecTests `verif/suites/spectest/CommitUnitSpecTests.scala` (13 tests) bind every
     CommitUnit FUNCTION (incl. funcRetireStreamEmit) and propCommitInOrder,
     propNoCommitPastException, propTrapHoldUntilRedirect, propRetireNonBlocking;
     PENDING x13 against the typed-IO shell (commit 2dcbb40).
   - RTL: priority interrupt/debug (latched once offered) > precise trap hand-off >
     HeadMemGrant > maintenance step > atomic retirement; FENCE/FENCE.I/SFENCE.VMA
     sequencer; trapPending released only by the matching ArchRedirect; retire stream,
     commit PRF read, and the 64-bit order counter exist only when usingRvvi.
   - CSR uops are recognized as serialize && sysOp in {None, CsrWrite} (the ROB entry has
     no fuType); SfenceVma operands are not available at commit (TlbFlush valid bits 0).
   - Review fixes: a matching ArchRedirect in the ExceptionOut transfer cycle (zero-latency
     TrapController) satisfies the hold at once; ExceptionOut{SysOp}.sysOp keeps only the
     intrinsic redirects (FenceI/SfenceVma/CsrWrite/Mret/Sret) and is None for a
     predictionFault-only Refetch (asserted). 15 L1 tests; 19 mutants all red.
13. RTL block 5 - PhysicalRegisterFile: storage for p1..p(N-1) only (p0 is a constant zero),
    one always-ready write port, combinational RS operand reads and (usingRvvi only) the
    ADR-019B commit read port, both with the same-cycle write bypass; p0 write asserted;
    port counts width-derived (propPrfPortsFixed, checked on the normalized io type across
    PRF/ROB depths). 3 L1 tests, 7 mutants all red (the p0 mutant became observable only
    after removing p0 storage: Verilator zero-fills registers).
14. ADR-019C (v0 Erratum 03, owner ruling): FuAvailability view (rawNoDecoupled class 7),
    oldest ready among available FU classes, RS output stability, BranchUnit control and
    result channels independent (CheckpointRelease XOR BranchResolution), RecoveryEvent kept
    combinational. Commit 411a4d8; the new names sat in the allowlists until verified.
15. RTL block 6 - ReservationStation: unordered entries with ready bits (rename capture and
    allocation-cycle wakeup), oldest eligible among available classes by funcRobOlder, held
    offer stable until transfer or kill, PRF read and entry release at the issue transfer,
    selective kill; asserts RsIssueOnlyReady, RsRecoveryKeepsOlder, RsIssueStable, and
    needsRs-only allocation. 6 L1 tests (3 reference-model runs), 10 mutants all red.
16. RTL block 7 - DispatchUnit: stateless router (one edge per fuType, IssuedUopIn.ready =
    availability of the presented class, same-cycle killed token not routed), FuAvailability
    = per-class downstream readiness. 2 L1 tests (incl. a 200-step request-independence
    sweep), 7 mutants all red. The CSR edge still uses the legacy bndCsrReq, which carries no
    robTag and a 5-bit rd (reported: C-3).
17. RTL block 8 - ALU / AGU / MUL / DIV wrappers: shared one-entry ResultHolder
    (backend/design/shared/ExecUnits.scala; canAccept = !held || drained, held result dropped
    in the event cycle, a request killed in its accept cycle never loads), unit-local op
    layouts (AluOp, MulDivOp, MemOp, BranchOp; UopOp widened to 8 bits), MUL/DIV hold one
    in-flight operation with its operands (the external cores need them stable until done)
    and a killed flag, so an uncancelable core result of a killed uop is discarded. 4 L1
    tests; 17 mutants: 15 red, 2 equivalent (MUL sliceWidth 32 completes in its start cycle,
    so it is never busy and the in-flight kill flag is never read; the same mutants are red
    on DIV).

## Validation status (run this session)

- `bash verif/bin/build.sh`: 0 errors at each of the three commits.
- Review rounds 1-3 re-ran all gates below after each fix; the numbers are from round 3.
- `python3 tools/spec-check.py`: 0 errors, 6 warnings (localspec-coverage on protected
  CSR/Decoder/Debug/Trigger and Util - pre-existing).
- Internal-edge reconciliation (scratch script, stronger than check 1): every labeled
  edge of FrontendTop (7), BackendTop (59), CoreTop (38) matches a producer *Out and a
  consumer *In interface; no orphan child interfaces.
- `verif/bin/run.sh verif.spectest.RunSpecTests`: 55/55 PASS (6 pre-existing + 2 params +
  9 RenameUnit + 9 ReorderBuffer + 1 SystemOpDecode + 4 RecoveryController + 15 CommitUnit +
  3 PhysicalRegisterFile + 6 ReservationStation + 2 DispatchUnit + 4 execution wrappers)
  after RTL block 8.
- `scn.sh run ooo_div_survives_mispredict.scn`: harness-not-ready (exit 3), as designed.
- Nothing SIMULATED against CoreTop (all ADR-019 vertices are shells).

## Remaining allowlist debt

- spec-test-allow: 53 names (bound since the freeze: funcRobOlder, propArchRedirectWins,
  propRetireNonBlocking, funcHeadMemGrant, propPrfPortsFixed, propRsIssueStable; the ADR-019C
  placeholders funcFuAvailability and propBranchControlOnce remain until their vertices land) (funcHeadMemGrant, propUncachedPerformedOnce, and the other uncacheable paths need an uncacheable harness region). SFENCE.VMA (flush funcs, propSfenceFlushesAll) - protected
  assembler has no mnemonic; uncacheable PMA paths - harness has no uncacheable region;
  predictor/FTQ internals and bus-adapter/cache monitors - need L1 SpecTests on RTL;
  doctrine/machine-check props; pre-ADR-019 carried names.
- spec-check-allow: 63 PROPERTYs (removed with their asserts: propPhysRegConservation,
  propCheckpointReleasedOnce, propRobRetireInOrder, propOlderSurvivesRecovery,
  propRobCompletionTargetsLive, propSingleRecoveryPerCycle, propArchRedirectWins,
  propCommitInOrder, propNoCommitPastException, propTrapHoldUntilRedirect,
  propRetireNonBlocking, propPrfPortsFixed, propRsIssueOnlyReady, propRsRecoveryKeepsOlder,
  propRsIssueStable; now 55 incl. the ADR-019C placeholder propBranchControlOnce) (propNoSpeculativeStoreVisible renamed propNoWrongPathStoreVisible), each pairs with its vertex's design assert when RTL lands.

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

## Open contradictions reported to the OWNER

- C-3 CSR request bundle: DispatchUnit.CsrReqOut and CsrController.CSRReqIn use the legacy
  bndCsrReq {csr, op, data, meta{rd(5), epoch}}. It has no robTag and cannot name a
  physical destination, so a CSR uop's CsrResult cannot complete its ROB entry or write
  its prd. It needs an ADR-019 CSR request shape with the CsrController/CSR.scala rewrite.

## Contradictions resolved by owner ruling

- C-1 (RS select vs FU availability) and C-2 (BranchUnit/RC/PublishMux loop) are closed by
  ADR-019C.

## Unresolved architecture questions (engineering, not owner-gated)

- Resolved by ADR-019A: RS allocation set, sysOp, RobStatus timing, robOlder domain,
  commit/ArchRedirect ordering. Still implementation choices (not contradictions):
  a CheckpointRelease whose owner the same-cycle RecoveryEvent kills is ignored;
  LsqAllocation size/signed come from insn[14:12]; enum and UopOp encodings live in
  BackendBundles.scala; the AllocateAtomic fork needs ROB/RS/LSQ ready independent of valid.
- RecoveryEvent is combinational from BranchUnit/TrapController to every holder (the spec
  requires publication in the request cycle); a registered stage would need an ADR and a
  BranchUnit hold/kill rule. FTQ derives HistoryRestore.pc as block base + 4 * slot.
- fence.i cost: D$ clean-all + I$ invalidate per fence.i (non-coherent I-side).
- DTLB single outstanding walk + fault record; multiple distinct-VPN misses serialize.
- One BTB-tracked CFI per fetch block; GHR shifts one bit per block with a tracked Branch.
- Predictor training at retirement only; no decode-time redirect for direct JAL.
- v0 serializes every CSR op (rename into empty ROB) and refetches after CSR writes.
- Full RAS snapshot per FTQ entry costs FtqDepth x RasDepth x 32 bits (4 Kbit at v0).
- Uncacheable access latency: each one waits for the ROB head, a StoreBuffer drain, and a
  bus ack, and blocks interrupt sampling while in flight.

## Next steps (in order)

1. Spec frozen at 242feaf + ADR-019A + ADR-019B; RenameUnit, ReorderBuffer,
   RecoveryController, CommitUnit, PhysicalRegisterFile RTL are green. Next (owner order):
   DispatchUnit, ALU/MUL/DIV/AGU wrappers, BranchUnit, PublishMux (RS done); each red-first
   with asserts, mutant controls, and allowlist shrink. RenameUnit spec ambiguities found
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
