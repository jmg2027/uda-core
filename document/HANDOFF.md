# Handoff: remote implementation integration (2026-10-05)

## Current integration update

Merged `origin/claude/spectest-validation-mutant-c7rz4l` at `cd6ef6a` into
`main` based on `ddbb04c`, preserving both histories and the real spec-framework
integration. The earlier session records below are historical.

- Integrated FetchPcGen, FetchBuffer, InstructionCache, InstBusAdapter,
  InstructionTlb, DataTlb, PageTableWalker, PMA helpers, and their L1/integration
  suites. Retained the remote LSQ all-waiter refill wakeup and readable,
  non-cacheable PTE contract corrections.
- Kept the globally unique spec IDs and `framework.spec.Spec` imports from main.
  Retired the remote branch's resolved assertion/test allowlist entries.
- Reconciled the independently implemented FTQs. Kept main's restore FIFO,
  pending-restore backpressure, last-accepted checkpoint fallback, and authoritative
  FtqCommit exit. Adapted the remote tests to these explicit DSL contracts instead
  of retaining incompatible expectations. Kept all 11 original tests and all 20
  remote cases in separate registered suites.
- The remote 2,144-scenario fetch rewind sweep exposed skipped unissued surviving
  entries. Clarified `funcFtqFetchIssue` before changing RTL: branch recovery keeps
  the current fetch cursor unless it lies beyond the surviving window's tail.
  Architectural recovery still restarts at the next allocation. Observed the
  failing baseline before the fix; the sweep covers every head position and
  occupancy, including wrap and simultaneous older commit.
- Refreshed README and AGENTS implementation status. No protected infrastructure
  files changed; standing owner questions and toolchain gotchas remain below.

Validation for this integration:
- Clean direct compile and spec artifact export passed: 688 specs, 594 source
  tags, and no unresolved references. Spec-check: 0 errors, 4 existing warnings.
- Framework macro/artifact/failure-propagation regression tests passed.
- Full merged L1 regression: 326 PASS, 0 FAIL, 2 PENDING (328 total). Both
  pending cases are the existing FetchUnit shell tests. All 31 FTQ cases pass,
  including the 2,144-scenario recovery sweep. No CoreTop simulation was claimed.
- `sbt scalafmtCheckAll test` was attempted. It stopped at scalafmt 3.7.17
  download failure and an Ivy lock write restriction under the user home;
  historical sbt tests did not run. Direct L1 simulation is the active RTL gate.

Next: implement FetchUnit, DataCache, and DataBusAdapter; connect FrontendTop and
CoreTop, then enable CoreHarness/L2 scenarios. Whole-core execution is still
unverified. The existing two FetchUnit pending tests remain explicit shell debt.

## Earlier local implementation update (2026-10-05)

Base: `main` at `60f689d`. The earlier branch/session narrative below is historical.
This update is included in the owner-requested main-branch integration.

- Restored the local Apple Silicon verification toolchain: Scala 2.13.12, Chisel
  6.2.0 and its matching plugin, firtool 1.62.0 through Rosetta, and a separate native
  Verilator 5.020 build. The installed Verilator 5.052 produced duplicate Ready
  protocol messages with Chisel 6.2.0; the older tool passes the same suites without
  changing the verification engine. SDKROOT selects the installed MacOSX26.5.sdk
  because the default MacOSX27.0.sdk failed to link with this machine's toolchain.
  Resolved paths are in ignored `verif/.toolchain.env`; binaries, dependency caches,
  and logs are under `/tmp` and must be reprovisioned if that directory is cleaned.
- Verified the previously unverified ADR-019H changes: BranchPredictor 17 PASS and
  decode-filter tests 9 PASS. Replacing prediction-time TAGE provider selection with
  a commit-time lookup makes `bp.trainIdentity` FAIL on the provider-identity races.
- Implemented FetchTargetQueue: circular prediction storage, ordered fetch cursor,
  atomic commit/training, selective branch truncation, architectural recovery, and
  an independently retained FIFO of HistoryRestore snapshots sized to
  BranchCheckpointCount + 1. Clarified the existing DSL before RTL: tail-checkpoint
  fallback, restore backpressure, and concurrent commit/recovery semantics.
- Spec-TDD: 10 FTQ tests were observed PENDING against the original shell before
  implementation. Reviewed the checks against lost/duplicated transfers, pointer
  wrap, preserved metadata, and event collisions. The completed suite has 11 PASS,
  including depth 2/4/8/32 configurations and a negative out-of-order commit test.
  The two FTQ PROPERTYs now have design assertions and were removed from the
  assertion allowlist. No new spec-test allowlist entries were needed.
- Five FTQ mutations each went red: unaligned restore PC, dropped stalled restore,
  lost training metadata, discarded survivors, and missing release assertion.
  Together with the TAGE training mutation, 6/6 mutation controls were detected.
- Full L1 regression: **165 PASS, 0 FAIL, 2 PENDING (167 total)**. The two PENDING
  tests are `fu.midBlockSlots` and `fu.midBlockFault`, both on the FetchUnit shell.
  Compile: 0 errors. Spec-check: 0 errors, 4 pre-existing coverage warnings.
  No protected files were changed.

Next at that point (superseded by the integration update above): FetchPcGen, FetchBuffer, InstructionCache + InstBusAdapter, InstructionTlb,
DataTlb, PageTableWalker, FetchUnit, DataCache, DataBusAdapter, FrontendTop, CoreTop,
then activate CoreHarness and the L2 scenarios. BranchPredictor and FTQ are complete
at L1; whole-core integration remains unverified.

## Spec framework integration and agent instructions (2026-10-05)

- Removed the in-tree no-op framework. Both build paths compile the sibling
  spec-framework core/macros before UDA specs and RTL; use SPEC_FRAMEWORK_HOME to
  select another checkout. Build errors propagate, and stale classes/metadata
  are cleaned before verification builds.
- Disambiguated 115 declarations across 46 colliding spec IDs without changing
  Scala val names or SpecTest bindings. Exported 688 specs and 573 source tags;
  every graph reference resolves. Artifact checks reject missing or malformed
  output, duplicate IDs, and unknown references.
- The accompanying spec-framework changes close SpecIndex file handles and
  preserve constructor-parameter annotations and explicit class companions.
  Its publish script preserves user-wide dependency caches.
- Converted the root Claude instruction entry point into AGENTS.md, retaining
  owner gates, protected paths, language policy, architecture, and skill links.
  Refreshed the build guidance for the real framework and active OoO test path.
- Validation from this work session: direct compile and artifact gates passed;
  macro integration regressions passed; L1 was 165 PASS, 0 FAIL, 2 PENDING;
  spec-check was 0 errors with 4 existing coverage warnings. Cached sbt compilation
  and export passed using the installed Chisel compiler plugin and excluding
  unavailable historical Test dependencies for that validation invocation.
- Pre-commit `sbt scalafmtCheckAll test` was attempted but stopped because
  scalafmt 3.7.17 could not be downloaded; the chained historical tests did not run.
- Full framework publish/golden validation could not run because sbt 1.10.0 was
  absent and dependency downloads were unavailable. Do not interpret generated
  formal scaffolding or implementation tags as completed formal proofs.
- Owner requested committing and integrating all current work into origin/main
  in both repositories. Setup details: `docs/tooling/spec-framework.md`.

## Historical session record

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
3. `62aed52` - deleted superseded non-ADR architecture papers/docs; README, the former Claude instruction file,
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
18. RTL block 9 - BranchUnit + PublishMux (ADR-019C E-4..E-7):
    - BranchUnit: resolve (BEQ..BGEU, JAL, JALR with bit 0 cleared, misaligned target ->
      exception + release), mispredict detect (Direction / Target / UnpredictedCfi; a taken
      conditional branch predicted not-taken is DirectionMispredict), one control token
      (BranchResolution XOR CheckpointRelease) and one result token held independently.
      BranchResolution.valid is registered state only. Asserts RecoveryOnlyOnMispredict,
      BranchCompletesOnce, BranchControlOnce.
    - PublishMux: oldest valid candidate by funcRobOlder, atomic PRF write + wakeup + ROB
      completion (withheld while a needed output is not ready; wen = 0 needs no PRF),
      losers held; SingleDrain asserts. No RecoveryEvent input (producers filter). The CSR
      input is never accepted until C-3 (asserted).
    - C-2 integration harness (BranchUnit + AluUnit + RecoveryController + PublishMux):
      the killed younger ALU result never publishes, the branch result survives; with the
      old "resolution waits for the result" rule firtool reports the combinational cycle
      bu.branchResolutionOut.valid <- ... <- rc.recoveryEventOut <- ... <- pm grant.
    - 10 L1 tests (3 reference-model runs); 14 mutants, all red except the assert-only
      mutant B1 (no input can reach the BranchUnit asserts from outside; B4 shows they fire).
19. ADR-019D (v0 Erratum 04, owner ruling on C-3): native CSR execution and trap-state seam.
    - Spec: IssuedUop gains insn and sysOp; Dispatch CsrReqOut is Decoupled[IssuedUop] and
      PublishMux CsrResultIn is Decoupled[FuResult]; CSRReq/CSRResult deleted; Interrupt is
      the one six-line core bundle; CSRTrapRead/CSRTrapWrite get field tables (kind selects M
      or S registers); CsrController gains funcCsrOpSemantics, funcCsrTrapWriteApply,
      funcInterruptCtrlPublish, propCsrWriteIntent, propCsrSingleOwner; funcInterruptSampling
      suppresses sampling at a presented serialize head (E-5).
    - Design: legacy CsrReq/CsrResult/meta/epoch classes, BackendParams.legacyCsrEpochWidth,
      BackendModule.epochWidth, and csr/CSR.scala deleted (the three trigger bundles moved
      verbatim into TriggerUnit.scala); new Interrupt/CsrTrapRead/CsrTrapWrite/
      TranslationContext classes, Priv/TrapWriteKind/CsrOp encodings; RS carries insn/sysOp;
      the CSR FuResult joins PublishMux oldest-live arbitration (C-3 assert removed).
    - Tests: dispatch.route checks the native CSR IssuedUop (robTag, operand, insn, sysOp);
      rs.wakeup checks insn/sysOp propagation; pm.arbitrate includes a CSR candidate.
      Mutants: RS insn dropped, RS sysOp dropped, CSR unrouted, CSR excluded from
      arbitration - all red.
    - Gaps reported: debug-entry target PC (design parameter until the owner fixes the ROM
      address); DRet kind has no v0 SysOp producer.
20. RTL block 10 - CommitUnit serialize-head sampling fix (ADR-019D E-5): sampling requires
    `!head.serialize`, so a presented CSR read/write, FENCE, FENCE.I, SFENCE.VMA, WFI, MRET, or
    SRET head is never preempted; the pending request is taken at the next boundary. New
    directed test commit.interrupt.serializeHead (interrupt + debug pending on cycle 0 of each
    class); red on the old RTL for all 8 classes; mutants (no suppression, CSR-only,
    redirect-only) all red.
21. RTL block 11 - CsrController (ADR-019D E-1..E-4, E-6, E-7): the sole committed CSR-state
    owner (plain registers, not the CsrAccess library: that path infers write intent from the
    runtime operand and writes at access time, which E-2/E-4 forbid). v0 map: M (mstatus WARL
    incl. MPP, misa RV32 IMSU, medeleg 0xb3ff, mideleg 0x222, mie, mtvec, mcounteren 0,
    mstatush 0, mscratch, mepc, mcause, mtval, mip = raw lines | soft SEIP/STIP/SSIP,
    mvendorid/marchid/mimpid/mconfigptr 0, mhartid = hartId), S (sstatus/sie/sip views,
    stvec, scounteren 0, sscratch, sepc, scause, stval, satp), D (dcsr, dpc, dscratch0, debug
    mode only). One-entry result register, ready = !staged && (!resValid || out.ready); legal
    writes staged and applied only on the matching CommitGrant. InterruptCtrl picks M-destined
    before S-destined, MEI MSI MTI SEI SSI STI within each. Asserts: CsrWriteIntent (sysOp vs
    encoding, CsrOp vs funct3), CommitGrant mismatch, NoSpeculativeCsrWrite (state-change
    monitor), CsrSingleOwner.
    - 14 L1 tests (csr.ops, readOnlyBoundaries, rdX0, metadata, accessCheck, backpressure,
      commitGating, writeIntent, singleOwner, supervisor, interruptView, trapWrite,
      translationContext, publish through PublishMux); red = PENDING on the typed shell.
    - 33 mutants (write-intent boundaries, access checks, staging/grant, result handling,
      trap-write kinds, interrupt view, WARL views, assert removals) all red; the first
      M-before-S mutant survived and the test was strengthened (SEI delegated vs STI in M).
22. RTL block 12 - TrapController + trap/CSR seam integration (ADR-019D E-6/E-7):
    - TrapController owns no CSR state; it maps each hand-off to one CSRTrapWrite and one
      ArchRedirect in a single transfer (each valid waits only for the other consumer's
      ready; ExceptionIn.ready is their conjunction). Sync/Interrupt -> TrapEntryM/S by
      medeleg/mideleg (never from M), xcause interrupt bit, tval 0 for interrupts, vectored
      target only for interrupts; MRET/SRET pops (MPRV cleared unless MRET to M); Debug ->
      DebugEntry (dcsr.cause/prv, other bits kept) to BackendParams.debugEntryPc (0x800,
      design parameter until the owner fixes it); Refetch -> pc + 4 without a CSRTrapWrite.
      Asserts TrapSingleWriter (write/redirect/hand-off are one transfer).
    - 6 L1 tests (trap.entryM, refetch, delegation, xret, debug, singleWriter), PENDING on the
      typed shell; 21 mutants all red.
    - Seam harness CommitUnit + TrapController + CsrController + RecoveryController (no
      combinational loop reported): committed CSR write via grant + Refetch without a
      CSRTrapWrite, MRET, TrapEntryM from U, TrapEntryS via medeleg, M interrupt from S,
      DebugEntry, TranslationContext after each; E-5 end to end (a staged CSR write whose
      head is presented with an interrupt pending retires first). Seam mutants (CommitUnit
      without E-5, TrapEntryS writing mepc) red.
23. ADR-019E (v0 Erratum 05, owner ruling on C-4 and the debug gaps):
    - E-1: the CsrController's native staged-write map is authoritative; ADR-017 D-17.3 is
      amended (pointer in ADR-017). Contributions are `CsrMapContribution` objects whose
      `entries()` return `CsrMapEntry` descriptors (address, read source, legalizing write
      target), built inside the CsrController; duplicates fail elaboration; a monitor asserts
      a contributed CSR never changes outside the grant/trap-write paths.
    - E-2: `CoreContractParams.debugEntryAddr` (paramDebugEntryAddr, default 0x800 for the
      verification platform), exposed in CoreApiParams, mirrored as
      `BackendParams.debugEntryAddr` (renamed from debugEntryPc).
    - E-3: `SysOp.Dret` (SysOp is 4 bits); SystemOpDecode classifies DRET (System, serialize);
      CommitUnit treats it as an intrinsic retiring redirect; TrapController emits
      CSRTrapWrite{DRet, privNext = dcsr.prv} and XRet to dpc; CsrController applies it.
    - E-4: `DecodePrivView` {priv, debugMode, tvm, tw, tsr} from the CsrController;
      `SystemOpDecode.privLegal` implements funcSystemPrivLegality (debug mode decodes as M;
      U-mode WFI illegal); BackendTop edge `csr -. DecodePrivView .-> dec`; the DecodeUnit
      shell carries the port until its RTL block.
    - Tests: decode.sysOpClassify (DRET row), decode.sysPrivLegality (all priv x debugMode x
      TVM x TW x TSR), rename/ROB DRET execution-free, commit.sysop.dret + zero-latency DRET,
      trap.dret, trap.debugEntryAddr (non-default 0x40000800), csr.decodePrivView,
      csr.mapContribution (read, staged legalized write, operand-0 intent, read-only
      contributed CSR, address privilege, collision rejection x2, rogue second write path),
      seam.debugDret (U -> DebugEntry at a non-default address -> debug code -> DRET -> U at
      dpc). Red: 10 FAIL before the RTL (trap.debugEntryAddr passed at once: the rename kept
      the existing parameter path).
    - Mutants (28, all red): privLegal ignoring TVM / TW / TSR / debugMode, debug mode not as M,
      U-mode WFI legal, MRET legal in S, DRET unclassified; DecodePrivView TVM/TW/TSR/debugMode
      dropped; contributions dropped, no duplicate check, no contribution monitor, contributed
      write applied at accept, contributed intent from the operand; DRET target mepc / entry
      address, DRET priv M, DRET kind missing, DRET not XRet, entry address hardcoded, dpc =
      entry address; CommitUnit DRET not redirecting / not intrinsic; seam DRET target mepc and
      priv M (the first seam run let "target mepc" survive because dpc equalled mepc; the test
      now retires one U instruction first so dpc != mepc).
    - Allowlist: funcCsrMapContribution bound and removed (spec-test-allow 49 -> 48).
24. RTL block 13 - LoadStoreQueue (ADR-019 D-19.5/D-19.12): circular LQ/SQ with wrap-bit
    pointers, per-entry generations for DTLB/D-cache transactions, store translation first
    then paired DtlbReq{Load}+DCacheLoadReq for the oldest Ready load whose older stores all
    have physical addresses, forwarding decided in the D-cache answer cycle (SQ bytes of
    older stores by paddr, youngest wins, then the StoreBuffer answer of the same cycle, then
    the cache word), WaitStoreDrain on a StoreBuffer partial, refill-before-miss races kept by
    a sawRefill flag, uncached loads/stores only after HeadMemGrant + StoreBufferEmpty and
    exactly once, StoreCommit hands the SQ head to the StoreBuffer in the same cycle (an
    uncached performed store is only released), LQ release at retirement via RobStatus,
    selective kill with tail rewind, one-entry MemResult holder (oldest pending first).
    New core design bundles (TranslateReq, Translation, WalkResp/TlbEntry/PmaAttr,
    DCacheLoadReq/Resp, Uncached*), backend CommittedStore/StoreForwardQuery/Data,
    BackendParams pAddrWidth and LSQ index/generation widths. Timing contracts this design
    relies on (spec-compatible, recorded here): the StoreBuffer answers a forwarding query
    combinationally in the query cycle, and a DCacheLoadResp's data reflects every drain
    completed before its response cycle. CommittedStore/UncachedStoreReq paddr is the byte
    address; data and mask are lane-aligned.
    - 14 L1 tests (allocate, addressCapture, translationWait, refillRace, staleAnswer,
      disambig, loadIssue, forward incl. synonym VAs, loadComplete, storeComplete, uncached,
      storeCommit, recovery, and a random reference model: two seeds x 300 retirements with
      out-of-order D-cache answers, replays, DTLB misses, synonyms, device accesses, faults,
      traps, and mispredicts, checked against sequential memory semantics and final memory).
      Red: 12 PENDING on the typed shell (the two race tests were added after the first
      mutant pass).
    - 31 mutants: all red after strengthening (first pass left the generation check, both
      refill races, and the drain-wait gate alive; new tests catch them). Equivalent under
      the protocol: dropping the event-cycle allocation guard (RenameUnit never allocates in
      an event cycle; now asserted) and moving a faulting uncached store's state (its
      exception still reports).
    - Allowlists: spec-test-allow 48 -> 46 (funcUncacheableAtHead, propUncachedPerformedOnce),
      spec-check-allow 49 -> 43 (six LSQ properties now asserted).
25. RTL block 14 - StoreBuffer (ADR-003/ADR-019 D-19.12): FIFO of committed cacheable stores;
    the head is offered whenever no drain is outstanding and freed only by StoreDrainResp
    (so a draining entry still forwards and StoreBufferEmpty stays low); forwarding answers in
    the query cycle, youngest entry per byte (partial never raised in v0: entries are aligned
    words with masks); drain fence answered when empty, a store during a pending fence
    asserts; asserts InOrderDrain, CommittedSurvivesRecovery (occupancy monitor),
    StoreBufferLiveness. 5 L1 tests (red: 5 PENDING); 9 mutants all red (the empty-while-
    draining mutant first survived; the test now checks StoreBufferEmpty during the last
    drain). Allowlists: spec-test-allow 46 -> 45, spec-check-allow 43 -> 40. The LSQ+SB
    forwarding contract is exercised end to end in the BackendTop integration block.
26. RTL block 15 - DecodeUnit (ADR-019 D-19.1/D-19.3, ADR-019E E-3/E-4): per lane the legacy
    DecodeCore table (RV32I + enabled M contribution; untouched) supplies ALU/branch/memory/M
    rows; SystemOpDecode classifies system/CSR rows and their DecodePrivView legality; fetch
    fault > illegal (unknown, 16-bit, privilege) > EBREAK (tval = pc) > ECALL (cause 8/9/11 by
    priv; Debug Mode ECALL was M here, illegal since ADR-019F E-5); excepting uops are fuType System, sysOp None, no CFI or
    memory flags; op layouts AluOp/MulDivOp/BranchOp (Call/Ret/Jal/Jalr hints)/MemOp/CsrOp;
    unread registers reported as x0 (CSR immediate forms carry uimm in insn only); one held
    packet discarded by any RecoveryEvent, nothing accepted in an event cycle. New frontend
    design bundles FetchFault/FetchInst/FetchPacket. Asserts NoCompressedDecode,
    DisabledExtensionTraps, FetchPacket lane contiguity. 7 L1 tests (red: 7 PENDING); 18
    mutants red after strengthening (event-cycle acceptance into an empty stage, a
    fetch-faulted branch, Debug Mode ECALL); the explicit 16-bit check is equivalent (no
    table row has bits[1:0] != 11). spec-check-allow 40 -> 38.
27. RTL block 16 - BackendTop (rawTop, wiring only): every vertex instantiated and every edge of
    the BackendTop graph connected (RecoveryEvent to all twelve holders and the boundary;
    usingRvvi retire stream and commit PRF read as Options; BitAluUnit absent in v0).
    9 end-to-end L1 programs through the whole backend (verif/suites/spectest/BackendTopSpecTests.scala: a
    two-pass assembler, a sequential no-prediction frontend recovered by RecoveryEvents, a
    Bare DTLB, a RAM D-cache written only through StoreBuffer drains, an MMIO uncached port):
    arith, loop (every taken CFI recovered), memory (SQ/SB/memory forwarding with byte/half
    extension), M extension (incl. divide-by-zero and overflow), calls (x1/x5 links), traps
    (ECALL + illegal, MRET, mstatus after two returns), interrupt (timer interrupt into a spin
    loop, MMIO store/load at head exactly once), debugDret (halt request -> code at
    debugEntryAddr -> DRET -> resume at dpc), fences (FENCE / FENCE.I / WFI). Red: 9 PENDING on
    a typed-IO BackendTop without vertices. 8 wiring mutants (Rename without wakeup, LSQ
    without RecoveryEvent, DecodePrivView tied to zero, interrupt lines / debug request /
    CommitGrant cut, StoreBufferEmpty forced true, RobStatus to the LSQ cut) all red; the
    StoreBufferEmpty mutant first survived and the interrupt program now checks, with slow
    drains, that the MMIO store waits for an older committed store to reach memory.
28. ADR-019F (v0 Erratum 06, owner ruling 2026-09-26; approval of the backend through ac48d53):
    the two timing facts the backend relied on are now contract. E-1: StoreBuffer forwarding
    is a same-cycle lookup; the LSQ decides a DCacheLoadResp only with its query accepted and
    StoreForwardData valid (new LSQ PROPERTY propForwardQueryConsumedOnce, paired assert, L1
    test lsq.forwardOnce with a 4-cycle forwarding-data delay, query held or accepted early,
    word and partial-mask loads). A later pipelined forwarding path needs a store version
    scheme and an ADR, never a bare register. E-2/E-3: StoreDrainResp is the committed-store
    visibility point (new DataCache PROPERTY propDrainResponseMakesStoreVisible, allowlisted
    until the DataCache block writes the directed load-miss / store-drain race with partial
    masks); same-cycle drain response and load answer need no stronger ordering. E-4: the WFI
    rule is unchanged. E-5: ECALL/MRET/SRET in Debug Mode decode to illegal instruction
    (cause 2, tval = insn); ECALL cause no longer looks at debugMode. Red first:
    decode.sysPrivLegality (mret/sret/ecall in dm) and decode.exceptions failed before the
    SystemOpDecode change.
29. ADR-019G (v0 Erratum 07, owner ruling 2026-09-26; ADR-019F approved at cd2b74b): the
    requested fetch PC (possibly mid-block) is carried by PredictReq/Prediction/FtqEntry/
    FetchRequest; blockBase = alignDown(fetchPc, FetchBytes) and startSlot = fetchPc[3:2] are
    derived (FetchPc helper), never stored. BTB/TAGE index and tag with blockBase, a BTB entry is
    eligible only at slot >= startSlot, fall-through = blockBase + 16, cfiPc = blockBase +
    4 * slot (RAS push, HistoryRestore.pc), PredictorTrain.fetchPc = blockBase, FetchUnit
    I-cache lookup at blockBase with valid slots startSlot..lastSlot and the fault on startSlot.
    Frontend Chisel bundles and typed IO shells for BranchPredictor/FTQ/FetchUnit landed with
    the E-8 directed tests: bp.midBlock* (6), ftq.midBlockRequest/RestorePc/Train (3),
    fu.midBlockSlots/Fault (2), all PENDING first. FrontendParams carries the BackendParams
    whose RecoveryEvent/FtqCommit it consumes (mirror equality required).
30. RTL block 17 - BranchPredictor (ADR-019 D-19.2/D-19.11, ADR-019G): combinational
    prediction from the request PC (request, Prediction, and NextPc transfer in one cycle,
    atomic fork); BTB 64x2 full tag with per-set LRU ranks, lowest eligible slot wins; TAGE =
    bimodal 1024 x 2b + 4 tagged tables (256 x {8b tag, 3b ctr, 2b useful}, histories
    8/16/32/64, folded-history index/tag), weak-and-not-useful provider defers to the alternate;
    speculative GHR shifts only for a tracked Branch, RAS circular with full snapshot;
    RecoveryEvent counts an outstanding restore and holds PredictReq until each HistoryRestore;
    restore = checkpoint then the resolved outcome (Branch shift, Call push pc+4, Ret pop);
    training only at PredictorTrain (BTB allocate/update for a committed taken exit, TAGE
    provider/useful update, allocation on a misprediction, useful decay otherwise); TAGE
    training recomputed the lookup on current tables (superseded by ADR-019H E-1, item 31).
    Asserts PredictFromPcOnly, OneTakenCfiPerBlock, HistoryRestoreExact, restore-counter
    overflow. 13 L1 tests (bp.*; red: 12 PENDING, plus bp.tageUseAlt added for a survivor);
    22 mutants all red after strengthening (event-cycle hold, LRU victim, useAlt first
    survived). ADR-019G mutants (fall-through and RAS push from the raw fetch PC) are red; the
    HistoryRestore.pc mutant belongs to the FTQ block. spec-test-allow 46 -> 36,
    spec-check-allow 39 -> 36.

31. ADR-019H (Erratum 08, owner ruling 2026-09-26) - pushed unverified as five WIP commits
    (0dc1aa7..16b3925, the shell was unavailable), verified on branch
    `claude/spectest-validation-mutant-c7rz4l`: TAGE training uses the prediction-time identity
    in PredictorTrain.meta (provider from meta, index/tag recomputed from fetchPc/ghr, update
    skipped when the provider entry was replaced, allocation above meta.provider); CfiType.CallRet
    = 6 (JALR, rd and rs1 both link, rd != rs1) classified before rd-link -> Call; CallRet
    predicts from the RAS top and pops then pushes cfiPc + 4 (prediction and restore); the
    restore counter is sized from BranchCheckpointCount + 1 with a bound assert. Gates: build 0
    errors, spec-check 0 errors. Red-first by reverting 9b5ead4's RTL (CfiType constant kept):
    bp.trainIdentity, bp.callRet, bp.restoreBound, decode.rv32im red; bp.backToBack stayed green
    (the pre-019H counter already held PredictReq per event; E-5 names that behavior), so it is
    covered by mutants instead. 15 mutants all red: commit-time lookup (whole pre-019H training
    block; provider choice only; no tag guard), CallRet (BTB target, speculative push / pop /
    ignored, restore ignored, restore as push with a consistent assert, top + 1 write, decode as
    Call, decode also for rd == rs1), restore release on any restore, events saturating at one,
    the pre-019H `=== 7` overflow assert.
32. RTL block 18 red-first - FetchTargetQueue: 8 new L1 tests (ftq.allocateFull,
    fetchIssueOrder, mispredictRestore, recoveryWrap, archRedirect, backToBack (ADR-019H E-5:
    three events with restores held, one event landing on a restore accept),
    commitTrainHandshake, inOrderRelease (negative: the design assertion text must contain
    `FtqInOrderRelease`)); with the 3 ADR-019G tests, 11 PENDING against the shell. They bind
    propFtqInOrderRelease and propFtqRecoveryKeepsOlder at L1 (still in spec-check-allow until
    their asserts land). Test assumptions the RTL must meet or the test must be revisited:
    PredictionIn may be accepted while restores are pending; after a BranchMispredict the tail
    rewinds to e.ftqIdx + 1 and after an ArchRedirect to the head. No RTL yet.

33. RTL block 18 - FetchTargetQueue (ADR-019 D-19.2/D-19.11, ADR-019G, ADR-019H E-5): FtqDepth
    circular queue; per entry valid, fetched, resolvedValid, resolved CfiOutcome and the whole
    Prediction (requested fetchPc, meta, full HistoryCheckpoint). Pointers head / tail / fetchPtr
    are ftqIdx {wrap, idx}, ordered only by ftqOlder (the funcRobOlder rule on the wrap and idx
    fields; next() = +1 mod 2 * FtqDepth). PredictionIn.ready = !full && !RecoveryEvent (a
    pending restore does not hold it). FetchRequest from fetchPtr (lastSlot = cfiSlot if taken,
    else FetchWidth - 1); in an event cycle that discards the fetchPtr entry the request's valid
    is lowered (q.valid = pending && !killed - the normal behavior, not a fired-then-cancelled
    transfer). BranchMispredict
    keeps e.ftqIdx and older, invalidates younger, records the resolved exit, tail = e.ftqIdx + 1,
    fetchPtr rewound to at most e.ftqIdx + 1; ArchRedirect discards all, tail = fetchPtr = head
    (after a same-cycle commit). Restore descriptors are captured in the event cycle into an
    ordered FIFO of BranchCheckpointCount + 1 entries (overflow assert), one per event.
    FtqCommit.ready = PredictorTrain.ready and PredictorTrain.valid = FtqCommit.valid (atomic);
    committed = resolved exit if recorded, else FtqCommit.exit; training fetchPc = blockBase.
    Asserts: FtqInOrderRelease (commit names the live head; release only with the train
    transfer; head moves only by one commit; live slots are exactly [head, tail)),
    FtqRecoveryKeepsOlder (next-cycle check against an event-cycle snapshot: kept entries valid
    with unchanged Prediction and monotonic fetched, resolved exit changed only on e.ftqIdx,
    younger entries gone, tail = e.ftqIdx + 1; no allocation in an event cycle), plus
    "BranchMispredict names a live entry". IO unchanged from the shell. Tests: the 11 red tests
    of item 32 went green unchanged; 4 added (ftq.resolvedExit, killedRequest (mispredict and
    ArchRedirect), allocateDuringRestore, recoveringStaysLive), all PENDING against the shell.
    Mutants: 17 all red (recovering entry killed, tail = e.ftqIdx, no tail rewind, no fetchPtr
    rewind, restore pc from the requested fetchPc, ArchRedirect applyOutcome, training fetchPc
    = requested fetchPc, committed always FtqCommit.exit, release without train ready, non-head
    commit allowed (assert removed), a dropped back-to-back restore, reversed restore order,
    pending restore holding PredictionIn, the killed request's valid left high in the event
    cycle, allocation
    in an event cycle, ArchRedirect always from the tail slot, one-entry restore storage). The
    six caught first by design asserts were rerun with every assert disabled: all red on test
    checks (the recovering-entry kill first survived; ftq.recoveringStaysLive was added for it).
    spec-check-allow 36 -> 34 (propFtqInOrderRelease, propFtqRecoveryKeepsOlder).
    Contract readings (no new ambiguity requiring an ADR): the ArchRedirect "current tail
    checkpoint" for a non-live e.ftqIdx is the checkpoint stored in the physical slot at
    tail.idx (deterministic, possibly stale; microarchitectural per D-19.11) - corrected in
    item 34 for a never-written slot; an FtqCommit in the same cycle as a RecoveryEvent is
    accepted and applied first. Two boundary defects of this block are fixed in item 34.

34. FetchTargetQueue boundary fixes (no ADR or spec change):
    - Restore FIFO enqueue index: `rq(rqWrap(rqHead + rqCount))` truncated the 3-bit sum at v0
      (rqDepth 5): rqHead 4 + rqCount 4 wrote slot 0 instead of 3. Now `rqHead +& rqCount`
      (and `rqHead +& 1.U` on dequeue) before modulo rqDepth; rqDepth stays 5 (not rounded to a
      power of two). Red on the pre-fix RTL: ftq.restoreFifoWrap (four warm-up restores move the
      read position to 4; then 4 progressively older BranchMispredicts + 1 ArchRedirect behind a
      low ready came out misordered with a lost payload) and ftq.restoreFifoModel (independent
      software FIFO; mismatch at cycle 39). The model test covers every read position x
      occupancy 0..4 at an enqueue, simultaneous enqueue/dequeue at full, and several wraps.
    - Full-queue fetchPtr rewind: `Mux(ftqOlder(r, fpAfter), next(r), fpAfter)` compared the tail
      sentinel with a live tag. Counterexample head 0, tail 16, fetchPtr 16, BranchMispredict
      ftqIdx 0: tail became 1, fetchPtr stayed 16, and the target block allocated at ftqIdx 1
      was never requested (ftq.fullRewind: no request). Now, from the pre-event head:
      keepCount = dist(r, head) + 1, fetchDist = dist(fpAfter, head), fetchPtr = head +
      min(fetchDist, keepCount), dist(a, b) = (a - b) mod 2 * FtqDepth; ftqOlder stays for live
      entries only. ftq.rewindSweep (one simulation, 2144 scenarios): every head position,
      occupancy 1..16, recovering entry at head / middle / tail - 1, fetchPtr at an unissued
      entry or the tail, a same-cycle surviving FetchRequest, a same-cycle older FtqCommit;
      48 scenarios failed on the pre-fix RTL (all full-queue; the test then had a wrong
      expectation for a recovery on the youngest entry of a full queue, which stays full until a
      commit - fixed in the test, after which the pre-fix clamp fails 16 scenarios).
    - ArchRedirect fallback: a never-written tail slot no longer supplies its (unreset)
      Prediction register; `written` bits (set at allocation) select the reset checkpoint (all
      zero, the BranchPredictor's reset history) instead; a written slot's stale checkpoint is
      still used. ftq.fallbackReset is a regression guard only: svsim runs the whole test inside
      the testbench initial block, so the DUT's RANDOMIZE_REG_INIT initializer runs after the
      test and Verilator starts unreset registers at 0 - a never-written slot is
      indistinguishable from the zero reset checkpoint in this simulator (an opt-in random-init
      knob was tried in CachedSimulator and reverted for that reason).
    - Mutants (FTQ suite, 20 tests): truncating enqueue index RED (restoreFifoWrap,
      restoreFifoModel), also with every assert disabled; pre-fix ftqOlder clamp RED (fullRewind,
      rewindSweep 16 scenarios), also with asserts disabled; keepCount without + 1 RED; fetchPtr
      rewind removed RED; fallback without the written check SURVIVED (the simulator limitation
      above); distances from the post-commit head SURVIVED - equivalent under legal inputs (both
      distances move by the same base, and they differ only if an unfetched head commits).
      The asserts-off control fails only ftq.inOrderRelease (it requires the assertion).
    - Validation on the restored source after deleting verif/out/classes and with a fresh
      simulation cache directory: build 0 errors, spec-check 0 errors (4 pre-existing warnings),
      RunSpecTests 174 PASS + 2 PENDING (FetchUnit shells).

35. RTL block 19 - FetchPcGen (FetchPcGenSpecs, ADR-019G E-1): typed IO first (BootAddrIn =
    Decoupled UInt as BootSequencer.bootOut, RecoveryEventIn, NextPcIn/PredictReqOut =
    Decoupled PredictReq); 7 L1 tests observed PENDING on the typed shell, then RTL. State: pc,
    pcValid, waiting (a request transferred, its NextPc not yet in), booted. Nothing is offered
    before boot; the boot token is accepted once (ready = !booted && !event). A RecoveryEvent
    wins in its cycle: PredictReq.valid, NextPc.ready and Boot.ready are low, pc = e.target,
    waiting cleared, booted set. The requested PC is offered exactly (never rounded to the block
    base) and held under backpressure. NextPcIn.ready = !event && (waiting || PredictReqOut.valid)
    - local state only, so the BranchPredictor's atomic fork (its request ready waits for NextPc
    ready) has no combinational cycle and the first request is accepted; a NextPc is applied only
    if awaited or with the same-cycle request transfer, any other token (a pre-recovery answer)
    is consumed and dropped. Assert FetchPcAligned. Tests (8): fpg.bootOnce, noBubble,
    backpressure, recoveryPriority, staleNextPc (delayed predictor), backToBack, aligned
    (negative), bootVsRecovery (added while designing the mutants). Mutants 13 all red plus the
    typed shell PENDING: NextPcIn.ready from the outstanding state only (deadlock, also in the
    loop), boot accepted twice, transfers in the event cycle, boot winning over a same-cycle
    event, PC rounded to the block base, PC dropped under backpressure, same-cycle NextPc not
    applied, any NextPc applied (stale overwrite), consecutive events keeping the first target,
    no wait for NextPc, offering before boot, assertion removed, NextPcIn.ready from
    PredictReqOut.fire (firtool: "detected combinational cycle ... fpg.io_nextPcIn_ready <-
    fpg.io_predictReqOut_ready <- bp.io_predictReqIn_ready <- bp.io_nextPcOut_ready"; run.sh
    shows only FirtoolNonZeroExitCode). spec-check-allow 34 -> 33 (propFetchPcAligned).
36. Prediction-loop integration (verif/suites/spectest/PredictLoopSpecTests.scala): a test-only
    PredictLoopHarness wires the real FetchPcGen, BranchPredictor and FetchTargetQueue as in
    FrontendTop (NextPc back to FetchPcGen, HistoryRestore/PredictorTrain FTQ -> BP, one
    RecoveryEvent), with FetchRequest/FtqCommit as ports and observation taps. loop.bootFlow
    (no prediction before boot, mid-block boot 0x1008, one prediction per cycle), loop.ftqFull
    (16 blocks then hold with the next PC offered, exactly one more after one commit),
    loop.recovery and loop.backToBack (no prediction through the cycle of the last
    HistoryRestore; resume exactly once at the last redirect target). firtool over the closed
    loop exits 0 (standalone CHIRRTL emit + firtool as well as the simulation build).
    Also: the FTQ restore-FIFO index is sliced to its width (removes a W004 dynamic-index
    width warning; values were already 0..rqDepth-1). The three W004 warnings left are in the
    BranchPredictor (provider - 1 on a 4-entry Vec), pre-existing from block 17.
    Validation (clean rebuild after deleting verif/out/classes, fresh simulation cache): build 0
    errors, spec-check 0 errors (4 pre-existing warnings), RunSpecTests 186 PASS + 2 PENDING
    (FetchUnit shells).

37. RTL block 20 - FetchBuffer (FetchBufferSpecs, ADR-019G E-6): typed IO first (FetchBlockIn =
    Flipped(Decoupled(FetchBlock)), FetchPacketOut = Decoupled(FetchPacket), raw RecoveryEvent);
    14 L1 tests PENDING on the typed shell, then RTL. An 8-entry FIFO of FetchInsts (circular
    head/tail + count). Each valid slot s of an accepted block goes to tail + PopCount(valid
    slots before s) (never the physical slot), with pc = basePc + 4 * s, its real slot,
    blockEnd and the exit prediction on the last valid slot (predictedTarget 0 elsewhere), and
    the block fault; a mid-block fault slot keeps its slot and PC. FetchBlockIn.ready = no
    RecoveryEvent and free >= PopCount(slotValid) (whole block or nothing); capacity is
    conservative - space a same-cycle packet transfer frees is usable next cycle (documented and
    tested in fb.simultaneous). FetchPacketOut offers the oldest min(DecodeWidth, occupancy) as a
    lane prefix, may span two blocks, and leaves whole on transfer. A RecoveryEvent lowers both
    handshakes in its cycle and resets head/tail/count. Found while designing the mutants and
    fixed red-first: a held one-lane packet widened to two lanes when a block arrived behind it
    (fb.backpressureGrow FAILED on that RTL: lane valids 10 -> 11); the presented lane count is
    now frozen until the transfer (heldValid/heldN). Asserts: FetchBlockSlots (an accepted
    non-fault block is one contiguous run, by slot index; a fault block has exactly one valid
    slot) and FetchBufferProgramOrder (from the output stream only: every transferred
    instruction follows the previous one - same FTQ block with slot + 1 and pc + 4 after a
    non-blockEnd, or the next wrap-aware ftqIdx after a blockEnd - lanes are a prefix, no
    transfer in an event cycle; history reset by RecoveryEvent). Tests (15): fb.basic,
    midBlock, takenExit, fault, crossBlock, backpressure, capacity, wrap (software queue,
    several wraps), recovery, recoveryInput, simultaneous, random (2500 blocks with random
    legal masks, 1-in-12 fault blocks, random backpressure, periodic RecoveryEvents; exact
    per-epoch sequence equality), malformed and orderAssert (negative), backpressureGrow.
    Mutants 17 all red (also with every assert disabled, except the two assertion-only ones):
    physical-slot enqueue, pc from the compact index, blockEnd always slot 3, predictedTaken
    on every slot, partial accept, one-lane dequeue, held packet widening, output or input in
    the event cycle, occupancy kept on recovery, fault forced to slot 0, cross-block lane
    suppressed, pointer wrap off by one, enqueue/dequeue count corruption, shape assertion
    removed, predictedTarget on non-taken slots, ProgramOrder check removed.
    spec-check-allow 33 -> 32 (propFetchBufferProgramOrder).
    Validation (restored source, verif/out/classes deleted, fresh simulation cache): build 0
    errors, spec-check 0 errors (4 pre-existing warnings), frontend suites bp 17, ftq 20,
    fpg 8, loop 4, fb 15 all PASS; RunSpecTests 201 PASS + 2 PENDING (FetchUnit shells).

38. RTL block 21 - InstBusAdapter + InstructionCache (ADR-016/019, D-19.4, D-19.12), no spec change.
    Design bundles InstMemReq{paddr, size (instBusParams.sizeBits)} and InstMemResp{data, last,
    denied}; CoreParams gains CoreFetchView(fetchBytes 16, fetchGenWidth 4), a mirror of the
    frontend widths like BackendFrontendView (whoever composes FrontendParams with CoreParams
    must require equality). Typed IO shells first: 8 iba.* and 16 ic.* tests PENDING, then RTL.
    - InstBusAdapter: one transaction, no buffering. InstMemReq -> one held A Get (opcode Get,
      param 0, source 0, full mask, data 0, corrupt 0; stable under backpressure); each D beat is
      one InstMemResp (D.ready = resp ready), denied = D.denied || D.corrupt, last derived from
      the request size and the D-fire count; the next request after the final beat. Asserts:
      InstBusD (AccessAckData, source 0, size = active Get, no D without an active Get, no extra
      beat); InstBusGetOnly (A opcode Get + elaboration require hasBCE = false, no B/C/E).
    - InstructionCache: valid/PLRU in registers, tags/data in Mem (masked per way). One-entry
      request/translation pairing register (a request is accepted in the cycle the held one's
      Translation is consumed: one hit per cycle, ic.steadyHits); a lookup needs no miss, no
      invalidate this cycle, and a free (or draining) answer holder. Index va[11:6], tag
      pa[33:12], block va[5:4]; PageFault/AccessFault answer at once without bus traffic. One
      miss context: cacheable miss Gets the 64-byte line (size 6, 16 beats), uncached Gets the
      16-byte block (size 4) and never installs; denied accumulates over all beats (fault answer,
      no install); install on the final beat into the invalid-first / tree-PLRU victim unless
      fillPoison (an invalidate accepted during the fill) or an invalidate in the final-beat cycle.
      Invalidate-all clears every valid bit in one cycle (ready always high, lookups blocked that
      cycle). Answers leave through one stable holder. Asserts: ICacheTranslation (reqId equals
      the paired request, no Miss status on the ITLB edge), ICacheLookup (at most one way hits),
      ICacheFill (last flag), ICacheInvalidate (op), ICacheReadOnly (only line/block Gets),
      ICacheResponseOrder (independent sequence numbers carried with each request; answers leave
      consecutively; at most 3 in flight).
    - Integration: ICacheBusHarness (real cache + adapter, TileLink memory in the driver):
      icbus.fillAndHits (one Get of 64 bytes, 16 D beats, four blocks then hit), uncached (16-byte
      Get each time, no later hit), denied (InstAccessFault, refill), invalidate (refill; an
      invalidate during an outstanding fill prevents installation); A carries only Get.
      Standalone CHIRRTL emit + firtool: exit 0 (no combinational cycle).
    - Tests: 8 iba, 16 ic (incl. a 1500-access random stream against a software cache with
      pages, cacheability, faults, denied lines, backpressure, translation delays and
      invalidates; exact answers and exact bus-request sequence), 4 icbus. While bringing up the
      random test two test bugs were fixed (a held answer released too late by the test; request
      queueing let the quiesce bound expire so an invalidate hit a fill in flight - the RTL
      poisoned it correctly and the model did not).
    - Mutants: 20 required + 1 equivalent probe, each also with every assert disabled: all red
      except C11a (removing !invFire from the install guard), equivalent because the invalidate's
      valid clear is connected after the install (last connect wins); the strengthened C11b
      (install actually wins) is red. With asserts off every mutant stays red on test checks,
      except C12 (reqId mismatch accepted), which is assertion-only by nature.
    - Allowlists: spec-check-allow 32 -> 29 (propICacheReadOnly, propICacheResponseOrder,
      propInstBusGetOnly); spec-test-allow 36 -> 32 (the same three + funcICacheUncachedFetch).
      propICacheFunctionTransparent stays (L3 retire equivalence, needs CoreTop).
    - Validation (restored sources, verif/out/classes deleted, fresh simulation cache): build 0
      errors, spec-check 0 errors (4 pre-existing warnings), RunSpecTests 229 PASS + 2 PENDING
      (FetchUnit shells), ASCII clean.

39. PMA + RTL block 22 - InstructionTlb (Sv32Specs, ADR-019 D-19.4/D-19.6), no ADR or spec change.
    - paramPmaMap implemented: PmaRegion{base, size, cacheable, executable, readable, writable} and
      PmaMap(regions) in core/design/shared/Pma.scala, owned by CoreContractParams.pma. Elaboration
      requires size > 0, base >= 0, no overlap, end <= 2^PAddrWidth; no default match (unmapped =
      access fault). PmaMap.verificationDefault is explicitly a VERIFICATION-PLATFORM default, not
      architectural: RAM [0, 0xF000_0000) cacheable/executable/RW, device [0xF000_0000,
      0x1_0000_0000) uncacheable/non-executable/RW, nothing above 4 GiB. One shared hardware helper
      PmaLookup(map, pa) -> (hit, PmaAttr) (@LocalSpec funcPmaCheck) for ITLB/DTLB/PTW/caches.
      Tests pma.legality (overlap, zero size, negative base, beyond 2^34 rejected; adjacent and
      exact-end accepted), pma.lookupDefault / lookupCustom (region first/last addresses, holes,
      above 4 GiB, against the software reference). Design bundle WalkReq{vpn, context =
      TranslationContext} added (bundle contract unchanged).
    - InstructionTlb: typed IO first; 19 tlb.* tests PENDING, then RTL. 16 fully associative entries,
      one held request + one stable Translation holder (request N+1 accepted in the cycle N resolves:
      one hit per cycle, tlb.hitStream), reqId echoed opaquely. Bare iff satp Bare or priv M (pa =
      va). Sv32 match (valid, global or ASID equal, 4 KiB vpn / 4 MiB vpn[19:10]); pa {PPN, va[11:0]}
      or {PPN[21:10], va[21:0]}; fetch permission X, A (Svade), U-mode needs U, S-mode never a U page
      (SUM/MXR/D ignored) -> PageFault; then PmaLookup on the actual pa (entry.pma is not trusted;
      the test walker fills it with all-false) -> AccessFault unless mapped and executable. Faults
      canonical (paddr 0, cacheable 0); Miss never reaches the I-cache (assert ItlbNoMiss).
      Miss: capture the committed context, one stable WalkReq, no new request; Leaf installs and the
      held request re-resolves through the normal lookup (so permission/PMA always apply);
      PageFault/AccessFault answer, no install; Retry re-walks with the current context. Refill
      invalidates every conflicting live entry (mapping overlap: 4K vpn equal, or any superpage and
      vpn[19:10] equal; address space: either global or ASIDs equal), reuses a conflicting slot, else
      invalid-first, else 16-way tree PLRU (touched on a successful hit and on refill only).
      Flush (v0 full): always accepted, clears all entries, blocks request acceptance/resolution
      that cycle; a walk overlapping a flush is stale and its result is neither installed nor
      answered - the held request re-walks with the post-flush committed context; flush wins over
      a same-cycle Leaf. Interpretation: a WalkReq still waiting for acceptance when the flush comes
      stays asserted and stable (ready/valid rule) and its result is discarded, rather than being
      withdrawn and re-issued. Asserts ItlbMatch (<= 1 match), ItlbWalkResp (VPN pairing),
      ItlbNoMiss, ItlbNoDuplicate (no two live entries can match one lookup).
    - Tests: tlb.bare, barePma, fourK, superpage, asid, perms, walkBackpressure (incl. a satp write
      while the WalkReq waits), walkFaults, retry (context change during the first walk), conflicts
      (global vs local same page; superpage over a contained 4K; global 4K under another ASID's
      superpage - an exact same-VPN same-ASID duplicate cannot arise from legal traffic because it
      would have hit), replacement (invalid-first, then the PLRU victim), flush, flushWalkReq,
      flushWaitResp, flushSameCycle, outBackpressure, hitStream, wrongVpn (negative), random (2500
      requests: ASIDs, U/S/M, Bare/Sv32, 4K/superpages, A/X/U/global, PMA RAM/device/unmapped,
      walk latency, output backpressure, 38 flushes; exact equality with the page-table/PMA
      reference). Integration ItlbICacheHarness (real ITLB -> real I-cache, FetchUnit-style atomic
      ITlbReq+ICacheReq, synthetic walker, backing memory): itic.bare, sv32 (miss -> walk ->
      translation -> fill; second fetch TLB+I-cache hit; page fault and PMA fault make no InstMem
      request; superpage fetches the right line), sfence (the VA re-walks but the physically tagged
      I-cache still hits). firtool on the harness: exit 0.
    - Mutants: 25 required + T22a equivalence probe + 2 PMA, each ITLB mutant also with asserts
      disabled: all red except T22a (removing !flushF from the install guard; equivalent because the
      flush clear is connected after the install), the strengthened T22b is red; the asserts-off
      control fails only tlb.wrongVpn. A first mutant run hung: unbounded wait loops in the tests
      (a mutant that never walks); all loops are now bounded and runs have a timeout.
    - Allowlists: spec-test-allow 32 -> 31 (funcItlbFlush bound). propNoFaultCaching,
      propSfenceFlushesAll, propGenerationTagScope stay in both lists (DTLB/PTW/FetchUnit parts).
    - Validation (restored sources, verif/out/classes deleted, fresh simulation cache): build 0
      errors, spec-check 0 errors (4 pre-existing warnings), RunSpecTests 254 PASS + 2 PENDING
      (FetchUnit shells), ASCII clean.
40. RTL block 23 - DataTlb (DataTlbSpecs, Sv32Specs, ADR-019 D-19.5/D-19.6) + LSQ wake erratum.
    - CoreLsqView{loadQueueDepth 8, storeQueueDepth 8, generationWidth 2} owned by
      CoreContractParams.lsq (reqIdWidth = 1 + max(lqIdx, sqIdx) + generation = 6), a core-domain
      mirror of the BackendParams LSQ geometry; the core domain does not import BackendParams.
      PENDING: CoreTop must require exact equality with BackendParams (LQ/SQ depth, lsqGenWidth,
      lsqReqIdWidth) when it is composed; the LsqDtlbHarness previews that require and
      lsqdtlb.params checks the defaults agree and a mismatch is rejected.
    - Shared TLB helpers extracted to core/design/shared/TlbLogic.scala: matches (@LocalSpec
      funcTlbMatch), compose (@LocalSpec funcSv32Decompose), conflicts / mapOverlap / asOverlap,
      plruVictim / plruTouch. The InstructionTlb now uses them (behavior unchanged; all tlb./itic.
      tests re-run green). Each TLB keeps its own state and translate/refill/flush functions.
    - DataTlb: typed IO first; 21 dtlb.* tests PENDING, then RTL. Non-blocking: DtlbReqIn.ready =
      !flush && (the request's own answer holder is free); it never depends on a walk. Two
      independent stable answer holders, routed by TranslateReq.access (Load -> DCacheTranslationOut,
      Store -> DtlbStoreRespOut) for every status incl. Miss and faults; reqId echoed opaquely, with
      asserts that access is Load/Store and that reqId's LSQ isStore bit agrees (tag "DtlbReq").
      Bare iff satp Bare or dataPriv M (not ctx.priv). Data permission with dataPriv: Load
      (R or (MXR and X)) and A; Store W and A and D (Svade); U mode needs U; S mode U = 0 or SUM; MXR
      has no effect on stores -> PageFault; then PmaLookup on the actual pa (Load: mapped and
      readable; Store: mapped and writable) -> AccessFault; PageFault has priority; cacheable only
      from the PMA map; faults canonical (paddr 0, cacheable 0). Miss: answered Miss at once (paddr
      0, cacheable 0), the request is not retained; a walk starts only if none is active (captured
      committed context, one stable WalkReq). One-entry walk-fault record {vpn, asid = the walk's
      captured ASID, status}: written by a non-stale PageFault/AccessFault walk, cleared by a
      non-stale Leaf/Retry result ("replaced by the next walk result") and by a flush; a later Sv32
      miss on the same VPN and live ASID answers that fault on its own route without walking.
      Retry, permission and PMA faults are never recorded. Walk completion: WalkResp is consumed
      only when the refill-notice holder can take the notice, so every completed walk (Leaf / PF /
      AF / Retry) yields exactly one stable DtlbRefillOut {walked vpn, status}; Leaf installs with
      the ITLB conflict rule (conflicting slot, else invalid-first, else 16-way tree PLRU; touched on
      a successful translated hit and on refill only) and never answers a request directly.
      SFENCE.VMA (v0 full): always accepted, clears all entries and the fault record, no request
      accepted that cycle; an overlapping walk is stale (a pending WalkReq stays asserted and
      stable): its result installs nothing, records nothing, and is announced as Retry so the LSQ
      re-walks with the post-flush context; flush + Leaf / flush + fault in one cycle behave the same.
      Asserts: DtlbMissLocal (@LocalSpec propDtlbMissLocal: ready high whenever no flush and the
      routed holder is free; every accepted request answered next cycle), DtlbReq, DtlbMatch,
      DtlbWalkResp (VPN pairing), DtlbNoDuplicate.
    - Erratum found by the integration harness (spec contradiction, fixed minimally): the DataTlb
      contract (intfDtlbRefillOut "retries every translation-pending entry"; funcDtlbMissNonBlocking
      "the retry after the next refill notice starts the next walk") and LoadStoreQueueSpecs
      funcTranslationWait ("a DtlbRefill whose vpn matches") disagreed. With the VPN-matched wake, a
      miss taken while another page is being walked starts no walk and its entry is never woken
      (lsqdtlb.parkOne and lsqdtlb.faults hung; the LSQ unit model had walked every page
      concurrently, so it never showed). funcTranslationWait / intfDtlbRefillIn now say any notice
      wakes every translation-pending entry (and a notice during an in-flight translation is
      remembered); the LSQ RTL drops the VPN compare (sawRefill, walkWait, TranslationPending).
      Red first: new lsq.wakeAny with a DataTlb-faithful single-walk knob in the LSQ unit model
      failed on the old LSQ, passes now; all lsq.* tests green. Cost: every pending entry retries
      after each notice (most miss again while the next walk runs) - acceptable for v0, revisit
      with the PPA work.
    - Tests: dtlb.bare (dataPriv M is Bare with satp on and priv S; device window cacheable 0),
      barePma, perms (R/W/D/A/X-only/MXR, MXR not for stores), privilege (U page / S page, SUM,
      dataPriv vs priv), match (superpage, ASID, global), nonBlocking (A misses and walks, B misses
      without a walk, C hits during the walk, ready never low, one notice for A, B retry walks),
      routing (each port backpressured, the other keeps answering; hold stability), faultRecord
      (load+store reuse without a second walk, other ASID walks, replacement, flush clears),
      faultRecordAsid (context switched during the walk: keyed by the captured ASID),
      retryNotCached, notices (all four outcomes, one stable notice each under backpressure),
      conflicts, replacement (PLRU), flush, flushWalkReq, flushWaitResp, flushSameLeaf,
      flushSameFault, flushWalkReqFault / flushWaitRespFault (an earlier flush: a stale PageFault
      records nothing), noticeHold (a walk completing while the previous notice is held waits; both
      notices arrive in order), walkCtx (the WalkReq keeps the captured ASID/root while the live
      context changes without a flush), outputs (both ports backpressured), negative (Store with a load reqId, Fetch
      access, wrong-VPN WalkResp), random (2500 completed requests with LSQ-style retry, in-flight
      context changes, flushes, Retry results, backpressure on all outputs; exact equality with the
      reference). Integration LsqDtlbHarness (real LoadStoreQueue -> real DataTlb; driver plays a
      minimal VIPT DataCache pairing each DCacheLoadReq with the next-cycle DTLB load answer, the
      PTW, StoreBuffer, uncached port, CommitUnit): lsqdtlb.params, parkOne, storeFirst,
      storeMiss, genDrop (late D-cache answer of a killed load; store killed in its answer cycle),
      wakeMany (two loads + a store, one walk, one notice), device (PMA device page -> cacheable 0
      -> uncached head-execute path), faults (Load/Store PageFault/AccessFault causes, tval, fault
      record), raceLoad / raceLoadLate / raceStore (the walk result lands in the cycle of the racing
      DtlbReq: Miss answered, notice next cycle, entry not stranded). Every test checks the atomic
      load DtlbReq + DCacheLoadReq fork and the answer pairing. firtool on the harness and on
      DataTlb alone: exit 0. The real DataCache is not implemented yet.
    - Mutants: 39 (D01-D38 incl. D08a/b), each also with asserts disabled, plus an asserts-off
      control: all red. The first pass left four survivors, each a test gap now closed: D17 (PMA
      checked before permission; no directed case failed both - perms now has an A = 0 page mapped
      above 4 GiB), D26 (a stale fault from an earlier flush writes the record; only the same-cycle
      flush was tested with a fault - two new fault variants), D28 (WalkResp consumed while the
      previous notice is held - noticeHold), D34 (live context on the WalkReq - walkCtx); D08b (U/S
      rule from priv) was caught only by the random test and now also by a directed privilege check.
      D06/D07 (the reqId/access and Fetch assertions removed) are red only through dtlb.negative,
      as intended for input-legality assertions; the asserts-off control fails only dtlb.negative.
      A first background run hit the 1 h limit mid-mutant; the sources were checked against the
      snapshot and restored before anything else.
    - Allowlists: spec-check-allow 29 -> 28 (propDtlbMissLocal paired with its @LocalSpec assert);
      spec-test-allow 31 -> 30 (funcDtlbFlush bound). propNoFaultCaching, propSfenceFlushesAll,
      propGenerationTagScope stay in both lists (PTW / FetchUnit parts pending).

    - Validation (restored sources, verif/out/classes deleted, fresh simulation cache): build 0
      errors, spec-check 0 errors (4 pre-existing warnings), RunSpecTests 291 PASS + 2 PENDING
      (FetchUnit shells), ASCII clean, firtool exit 0 on DataTlb and LsqDtlbHarness.

41. RTL block 24 - PageTableWalker (PageTableWalkerSpecs, Sv32Specs, ADR-019 D-19.6) + PtwMem/PMA seam.
    - Spec seam (text only, no ADR): funcDCachePhysicalRead now checks the PTE address with
      funcPmaCheck - unmapped / not readable -> accessFault with no array or bus access; readable
      and cacheable -> the normal physical lookup (a miss may allocate/join an MSHR and installs the
      line); readable and not cacheable -> one exact uncached 4-byte physical read that installs
      nothing (denied/corrupt -> accessFault). No HeadMemGrant (internal PTW read); it may share the
      uncached bus resource/source id but must keep PTW vs LSQ answer identity.
      funcPtwPhysicalAccess and the PTW contract text say the same (cacheable is not a legality
      condition for a PTE read). OBLIGATION for the DataCache block: implement exactly this,
      including the readable-uncacheable PTE path, with a directed L1 test.
    - Design bundles: Sv32Pte {ppn1 12, ppn0 10, rsw 2, d a g u x w r v; ppn = Cat(ppn1, ppn0)} in
      memory bit order, PtwMemReq {paddr}, PtwMemResp {pte, accessFault} (no VA, no translation
      metadata).
    - PageTableWalker: typed IO first; 19 ptw.* tests PENDING, then RTL. One walk at a time; accepts
      only when idle and no result is held; round-robin when both TLBs request (the pointer moves
      only on an accepted request); the loser stays backpressured (never both inputs ready, assert).
      Requester, VPN and the complete TranslationContext are captured at acceptance and used for the
      whole walk. Level 1 at {rootPpn, VPN[1], 00}, level 0 at {PPN, VPN[0], 00} (34-bit physical).
      PmaLookup before every read: unmapped / not readable -> AccessFault with no PtwMemReq; a
      readable uncacheable address is read. PtwMemReq stable until accepted; exactly one PtwMemResp
      per read; accessFault -> AccessFault without using the PTE. PTE rules: V = 0 or (R = 0, W = 1)
      -> PageFault; R or X -> leaf (level 1: superpage, PPN[0] != 0 -> PageFault); level-0 pointer ->
      PageFault. No R/W/X, U/S/SUM/MXR or A/D checks (TLB duties): X-only, A = 0, D = 0 leaves are
      Leaf. G is ORed over every PTE read (non-leaf G makes the refill global). Leaf entry = {vpn,
      superpage, leaf PPN, captured ASID, global, R/W/X/U/A/D, advisory PMA of the walked page:
      4K {PPN, 0}, 4M {PPN[21:10], VPN[0], 0}}; an unmapped target page is still a Leaf. One stable
      result holder, offered only on the requester's edge. SFENCE: atomic fork (each TLB flush is
      offered only when the other is ready; both fire in the SfenceVma transfer cycle, payload passed
      through); not accepted while a result is held (a presented result is never changed into Retry,
      and the response drains first even when both could transfer); wins over a new walk request in
      its cycle (the round-robin pointer is unchanged); a walk it overlaps completes every read it
      issued or presented (nothing retracted) and answers Retry. Asserts: PtwArbitrate (one grant),
      PtwFlush (both tokens fire with the SFENCE), PtwNoRecursiveTranslation (@LocalSpec: PtwMemReq
      4-byte aligned and equal to the expected physical PTE address from the captured root / pointer
      PPN), PtwMemResp (no unsolicited answer), WalkFaultTyping (@LocalSpec: the result status
      matches its cause; Leaf carries a valid entry).
    - Tests: ptw.arbitrate (ITLB-only, DTLB-only, 6 contended pairs alternate, never both ready),
      addresses (exact level-1/level-0 addresses, max VPN with a large root, max root PPN -> unmapped
      AF with no read, alignment), structure (11 PTE shapes), global (non-leaf G, leaf G, none,
      superpage G), entry (all fields, advisory PMA incl. device and unmapped targets), pma
      (readable uncacheable root table is read; non-readable and unmapped -> AF with no read; level-0
      address checked), denied (level 1, level 0, PTE data of a denied read ignored),
      memBackpressure (stable request, one read outstanding), outputs (blocked result stable, no new
      walk meanwhile, per-requester edge), flushIdle (atomic, payload, one TLB not ready -> neither
      offered), flushIssue / flushWait / flushBetween / flushFault (old Leaf / PageFault /
      AccessFault -> one Retry; every read completed once, none retracted), flushBlocked (a blocked
      Leaf stays Leaf; SFENCE after it drains), flushPriority, context, negative (unsolicited
      PtwMemResp), random (3000 walks from both TLBs over random two-level / superpage / malformed
      tables, three roots incl. a device-window root and an unmapped root, denied reads, latency,
      backpressure on memory and both result edges and both flush edges, SFENCE races; every
      accepted walk answered once to its requester, equal to the reference or Retry when overlapped).
      Integration MmuHarness (real ITLB + DTLB + PTW, synthetic physical PTE memory): mmu.concurrent
      (concurrent misses both walked and routed; ITLB 4K two-level + DTLB 4M; ITLB stream held, DTLB
      hits continue), mmu.global (level-1 G reaches both TLBs), mmu.sfence (remap + SFENCE: atomic
      flush, both re-walk the new mapping), mmu.sfenceOverlap (overlapped DTLB walk -> Retry notice,
      nothing stale installed; overlapped ITLB walk -> Retry, the ITLB re-walks), mmu.pmaFault
      (PMA-illegal level-0 table -> instruction / store access fault). firtool exit 0 on the harness
      and the PTW. The optional CommitUnit -> PTW seam harness was not built: CommitUnit leaves
      Step.Flush only on sfenceVmaOut.fire (its unit test backpressures it) and ptw.flushIdle /
      flushBlocked prove ready rises only in the cycle both TLB flushes fire.
    - Shared assertions added to close the Sv32 PROPERTYs: TlbLogic.assertNoFaultCaching (@LocalSpec
      propNoFaultCaching; called by both TLBs: a live entry appears or changes only the cycle after a
      non-stale Leaf result), TlbLogic.assertFlushed (@LocalSpec propSfenceFlushesAll: no valid entry
      the cycle after a flush token), and the PTW NoFaultCaching assert (every Leaf result is a legal
      Sv32 leaf: R or X, not W without R, superpage PPN[0] = 0). The leaf-legality half lives in the
      PTW because the TLB unit tests use synthetic walkers. Probe: DataTlb flush-skips-entry-0 and
      stale-Leaf-installs mutants abort the simulation on these asserts.
    - Mutants: 30 (P01-P30: fixed priority, both inputs ready, live context after acceptance, wrong
      VPN index / root shift / pointer shift, V ignored, W-without-R accepted, PPN[0] alignment
      ignored, level-0 pointer accepted, A = 0 / D = 0 as walk faults, non-leaf / leaf G dropped,
      wrong TLB routing, second walk while a result is held, PMA readable ignored / cacheable
      required, denied read as PageFault, one-sided or half-ready flush, SFENCE losing priority,
      stale not marked, stale Leaf / PageFault / AccessFault leaking, a presented PtwMemReq
      retracted, a blocked result changed to Retry, a blocked result not blocking SFENCE), each also
      with asserts disabled, plus an asserts-off control: all red. The first pass caught P03 (live
      context), P08 (V ignored) and P09 (W without R) only through ptw.random: the directed
      structure cases used R = X = 0 PTEs, which degrade to a pointer and still page-fault at level 0;
      they now use PTEs that would otherwise be legal leaves, and ptw.context adds back-to-back
      requests with different contexts - all three are red on directed tests. The asserts-off
      control fails only ptw.negative (the unsolicited-PtwMemResp assert).
    - Allowlists: spec-check-allow 28 -> 24 (propPtwNoRecursiveTranslation, propWalkFaultTyping,
      propNoFaultCaching, propSfenceFlushesAll); spec-test-allow 30 -> 28 (funcPtwFlush,
      propSfenceFlushesAll; funcPtwArbitrate / funcSv32Walk / funcPtwPhysicalAccess and the two PTW
      PROPERTYs were not listed and are now bound by ptw.* tests). propGenerationTagScope stays in
      both until the FetchUnit stale-response generation question is closed.
    - Validation (restored sources, verif/out/classes deleted, fresh simulation cache): build 0
      errors, spec-check 0 errors (4 pre-existing warnings), RunSpecTests 315 PASS + 2 PENDING
      (FetchUnit shells), ASCII clean, firtool exit 0 on PageTableWalker and MmuHarness.

## Validation status (run this session)

- `bash verif/bin/build.sh`: 0 errors at each of the three commits.
- Review rounds 1-3 re-ran all gates below after each fix; the numbers are from round 3.
- `python3 tools/spec-check.py`: 0 errors, 4 warnings (localspec-coverage on Decoder,
  DebugUnit, TriggerUnit, and Util - pre-existing; the CSR.scala warnings left with the file).
- Internal-edge reconciliation (scratch script, stronger than check 1): every labeled
  edge of FrontendTop (7), BackendTop (63), CoreTop (38) matches a producer *Out and a
  consumer *In interface; no orphan child interfaces.
- `verif/bin/run.sh verif.spectest.RunSpecTests`: 315 PASS + 2 PENDING after PageTableWalker + the MMU
  integration (clean rebuild, fresh simulation cache); 291 PASS + 2 PENDING after DataTlb + the LSQ wake
  erratum (clean rebuild, fresh simulation cache); 254 PASS + 2 PENDING after PMA + InstructionTlb
  (clean rebuild, fresh simulation cache); 229 PASS + 2 PENDING after InstBusAdapter +
  InstructionCache (clean rebuild, fresh simulation cache); 201 PASS + 2 PENDING after FetchBuffer (clean
  rebuild, fresh simulation cache); 186 PASS + 2 PENDING after FetchPcGen and the
  prediction-loop integration (clean rebuild, fresh simulation cache); 174 PASS + 2 PENDING after the FTQ boundary
  fixes (clean rebuild, fresh simulation cache); 169 PASS + 2 PENDING (FetchUnit shells) after RTL
  block 18 (FetchTargetQueue); 154 PASS + 13 PENDING (11 FTQ + 2 FetchUnit shells) after
  ADR-019H verification and the FTQ red-first tests; 150 PASS + 5 PENDING after RTL block 17; 137/137 PASS after ADR-019F (136/136 after RTL block 16; 101/101 after ADR-019E; 94/94 after RTL block 12 (adds 1 CommitUnit
  serialize-head test, 14 CsrController, 6 TrapController, 2 seam integration); earlier: 55/55 PASS) (6 pre-existing + 2 params +
  9 RenameUnit + 9 ReorderBuffer + 1 SystemOpDecode + 4 RecoveryController + 15 CommitUnit +
  3 PhysicalRegisterFile + 6 ReservationStation + 2 DispatchUnit + 4 execution wrappers +
  10 BranchUnit/PublishMux) after RTL block 9.
- `scn.sh run ooo_div_survives_mispredict.scn`: harness-not-ready (exit 3), as designed.
- Nothing SIMULATED against CoreTop (all ADR-019 vertices are shells).

## Remaining allowlist debt

- spec-test-allow: 28 names after the PageTableWalker; 30 after the DataTlb; 31 after the InstructionTlb; 32 after the I-cache/adapter; 36 after the BranchPredictor (its functions and three PROPERTYs bound;
  propPredictorStateMicroarchitectural stays, an L3 retire-equivalence check) (ADR-019F added propDrainResponseMakesStoreVisible, pending the
  DataCache block) (ADR-019D: funcDebugCommitBoundary and propTrapSingleWriter bound;
  ADR-019E: funcCsrMapContribution bound) (bound since the freeze: funcRobOlder, propArchRedirectWins,
  propRetireNonBlocking, funcHeadMemGrant, propPrfPortsFixed; the ADR-019C additions are all
  bound) (funcHeadMemGrant, propUncachedPerformedOnce, and the other uncacheable paths need an uncacheable harness region). SFENCE.VMA (flush funcs, propSfenceFlushesAll) - protected
  assembler has no mnemonic; uncacheable PMA paths - harness has no uncacheable region;
  predictor/FTQ internals and bus-adapter/cache monitors - need L1 SpecTests on RTL;
  doctrine/machine-check props; pre-ADR-019 carried names.
- spec-check-allow: 24 PROPERTYs after the PageTableWalker; 28 after the DataTlb; 29 after the I-cache/adapter; 32 after FetchBuffer; 33 after FetchPcGen; 34 after the FetchTargetQueue; 36 after the BranchPredictor (39 after ADR-019F; propDrainResponseMakesStoreVisible added;
  propForwardQueryConsumedOnce paired at once) (removed with their asserts: propPhysRegConservation,
  propCheckpointReleasedOnce, propRobRetireInOrder, propOlderSurvivesRecovery,
  propRobCompletionTargetsLive, propSingleRecoveryPerCycle, propArchRedirectWins,
  propCommitInOrder, propNoCommitPastException, propTrapHoldUntilRedirect,
  propRetireNonBlocking, propPrfPortsFixed, propRsIssueOnlyReady, propRsRecoveryKeepsOlder,
  propRsIssueStable, propRecoveryOnlyOnMispredict, propBranchCompletesOnce, propSingleDrain,
  and the ADR-019C propBranchControlOnce; 51 after block 9; ADR-019D removed
  propNoSpeculativeCsrWrite and propTrapSingleWriter: now 49) (propNoSpeculativeStoreVisible renamed propNoWrongPathStoreVisible), each pairs with its vertex's design assert when RTL lands.

## Open questions needing the OWNER

- OQ-C: the ADR-008 N=1 PPA bar is superseded by ADR-019; confirm what (if any) PPA bar
  the v0 reference point should meet.
- OQ-H (new): assembler protection blocks SFENCE.VMA / raw `.word` in `.scn`; allow adding
  mnemonics (or a `.word` directive) to `src/main/scala/assembler`?
- OQ-I (new): confirm the ADR-015 D-15.4/15.5 reinterpretation (ISA-model equivalence,
  one-axis configs without N) recorded in ADR-000 - or write a small ADR amendment.
- RV64 decode scheduling (carried; v0 is RV32 only).
- Decided 2026-09-25: OQ-E waived (CSR.scala protection lifted; the file is deleted by
  ADR-019D). OQ-D decided: v0 single-hart, non-coherent, no DMA.
  OQ-G closed: uncacheable accesses execute at the ROB head under HeadMemGrant (precise
  faults); cacheable regions are fill/writeback-fault-free by PMA contract (re-confirm when
  the SoC memory map is chosen).

## Open contradictions reported to the OWNER

- None open (C-4 resolved by ADR-019E; its record kept below).

## C-4 record (resolved by ADR-019E E-1)

- C-4 CsrMapContribution vs ADR-019D E-2/E-4:
  funcCsrMapContribution says the CSR map is "merged into the one CsrAccess.readFromCsr call
  this vertex owns", but CsrAccess infers write intent from the runtime operand
  (`isReadOnly = !isImm && in === 0 && (RS || RC)`) and applies the write in the access cycle
  (`csr.commit(sharedWrite, writeEnable && sel)`). Counterexample: CSRRS x0-free form
  `csrrs a0, mvendorid, t0` with t0 = 0 at run time - E-2 makes it a write attempt
  (sysOp CsrWrite, illegal on a read-only CSR), CsrAccess makes it a legal read; and any
  legal write through readFromCsr mutates state before its CommitGrant, violating E-4 and
  propNoSpeculativeCsrWrite. The CsrController implements the map natively (CsrMapEntry
  list, base + extension contributions, v0 has none) and funcCsrMapContribution stays in
  spec-test-allow. Needed ruling: amend funcCsrMapContribution to name the native map (and
  keep the common/system/csr library for extension CSR legalization only), or rework the
  library's access protocol to E-2/E-4.

## Contradictions resolved by owner ruling

- C-1 (RS select vs FU availability) and C-2 (BranchUnit/RC/PublishMux loop) are closed by
  ADR-019C.
- C-3 (legacy CSR request/result without robTag/prd) is closed by ADR-019D, which also
  fixes serialize-head interrupt sampling in the CommitUnit (E-5).
- C-4 (CsrMapContribution vs the staged-write protocol) and the two ADR-019D debug gaps are
  closed by ADR-019E.

## Unresolved architecture questions (engineering, not owner-gated)

- FetchUnit fetch generation (fetchGenWidth = 4): the generation wraps after 16 recoveries, so a
  response whose request is exactly 16 recoveries old aliases the current generation and could be
  accepted as current. The InstructionTlb and InstructionCache treat reqId as opaque and only echo
  it; the FetchUnit owns the generation and must close this when its RTL lands (for example: bound
  the in-flight window so no answer can be 16 generations old, widen the tag, or drain before
  reuse). Not changed in the ITLB block by owner instruction.

- Resolved by ADR-019A: RS allocation set, sysOp, RobStatus timing, robOlder domain,
  commit/ArchRedirect ordering. Still implementation choices (not contradictions):
  a CheckpointRelease whose owner the same-cycle RecoveryEvent kills is ignored;
  LsqAllocation size/signed come from insn[14:12]; enum and UopOp encodings live in
  BackendBundles.scala; the AllocateAtomic fork needs ROB/RS/LSQ ready independent of valid.
- RecoveryEvent is combinational from BranchUnit/TrapController to every holder (the spec
  requires publication in the request cycle); a registered stage would need an ADR and a
  BranchUnit hold/kill rule. FTQ derives HistoryRestore.pc as block base + 4 * slot.
- fence.i cost: D$ clean-all + I$ invalidate per fence.i (non-coherent I-side).
- DTLB single outstanding walk + fault record; multiple distinct-VPN misses serialize, and every
  translation-pending LSQ entry retries after each refill notice (item 40 erratum); the retry
  traffic is a PPA question, not a correctness one.
- CoreTop composition must require CoreLsqView == the BackendParams LSQ geometry (item 40;
  previewed in LsqDtlbHarness, still PENDING until CoreTop exists).
- One BTB-tracked CFI per fetch block; GHR shifts one bit per block with a tracked Branch.
- Predictor training at retirement only; no decode-time redirect for direct JAL.
- v0 serializes every CSR op (rename into empty ROB) and refetches after CSR writes.
- Full RAS snapshot per FTQ entry costs FtqDepth x RasDepth x 32 bits (4 Kbit at v0).
- Uncacheable access latency: each one waits for the ROB head, a StoreBuffer drain, and a
  bus ack, and blocks interrupt sampling while in flight.

## Next steps (in order)

0. After ADR-019F/G/H (owner order): BranchPredictor (done, ADR-019H verified), FetchTargetQueue
   (done, items 33-34), FetchPcGen (done, item 35; loop integration item 36), FetchBuffer (done,
   item 37), InstructionCache + InstBusAdapter (done, item 38), InstructionTlb (done, item 39), DataTlb (done, item 40), PageTableWalker (done, item 41), DataCache (next: real PtwMemReq service incl. the readable-uncacheable PTE path of item 41, 2 MSHRs / 2 targets, the ADR-019F store-visibility race), FetchBuffer,
   InstructionCache + InstBusAdapter, InstructionTlb, DataTlb, PageTableWalker, FetchUnit,
   DataCache (with the propDrainResponseMakesStoreVisible race), DataBusAdapter, FrontendTop,
   CoreTop; activate CoreHarness as soon as CoreTop elaborates (before any optimization) and
   rerun every .scn until each gives an architectural PASS/FAIL instead of harness-not-ready.
1. Spec frozen at 242feaf + ADR-019A + ADR-019B; RenameUnit, ReorderBuffer,
   RecoveryController, CommitUnit, PhysicalRegisterFile RTL are green. Next (owner order):
   The execution backend (RS, Dispatch, ALU/MUL/DIV/AGU, BranchUnit, PublishMux) is green.
   ADR-019D order: CommitUnit serialize-head sampling fix, CsrController, TrapController,
   CSR/Trap integration, ADR-019E, LSQ, StoreBuffer, DecodeUnit, BackendTop wiring - all
   done; the whole backend runs RV32IM_Zicsr programs end to end in L1. Next: the frontend
   (FetchPcGen/BranchPredictor/FTQ/FetchUnit/FetchBuffer), the MMU (ITLB/DTLB/PTW), the VIPT
   caches and bus adapters, then CoreTop wiring and the CoreHarness binding so the ADR-019
   .scn tests leave harness-not-ready. Spec ambiguities found during implementation are
   reported to the owner, not fixed in the frozen spec.
2. RTL fill-in, each vertex starting from its red test: RenameUnit + ReorderBuffer +
   RecoveryController + CommitUnit (with the ADR-010 retire stream and CoreHarness
   binding) -> RS/Dispatch/PublishMux/PRF/FU wrappers -> LSQ/StoreBuffer -> DataCache +
   DataBusAdapter -> frontend (FetchPcGen/BranchPredictor/FTQ/FetchUnit/FetchBuffer) +
   InstructionCache/InstBusAdapter -> MMU (TLBs/PTW) -> Trap/Csr (CSR.scala rewrite, OQ-E waived).
3. Replace each allowlisted PROPERTY with its design assert; add L1 SpecTests for the
   predictor history restore (ADR-019 obligation) and cache/bus monitors.
4. Harness: uncacheable PMA region, bus-stall knob, debugReq input, then SFENCE.VMA scn.

## Gotchas

- svsim runs the whole test inside the testbench initial block: the DUT's RANDOMIZE_REG_INIT
  initializer runs only after the test, so unreset registers read 0 in every L1 test.
- A firtool failure in a simulation build surfaces in run.sh only as FirtoolNonZeroExitCode; to
  read the message, emit the CHIRRTL (circt.stage.ChiselStage.emitCHIRRTL) and run firtool
  directly. Rebuild verif/out/classes after restoring a mutant before trusting any result.

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
- Both build paths now use Scala 2.13.12 and Chisel 6.2.0; see
  `docs/tooling/spec-framework.md`. Earlier Chisel 3 instructions are historical.
- Protected: src/main/scala/assembler/*, src/test/scala/{cluster,assembler}. csr/CSR.scala is
  no longer protected (OQ-E waived) and is rewritten with the Trap/Csr RTL.
