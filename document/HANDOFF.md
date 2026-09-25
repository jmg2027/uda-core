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
    priv; Debug Mode decodes as M); excepting uops are fuType System, sysOp None, no CFI or
    memory flags; op layouts AluOp/MulDivOp/BranchOp (Call/Ret/Jal/Jalr hints)/MemOp/CsrOp;
    unread registers reported as x0 (CSR immediate forms carry uimm in insn only); one held
    packet discarded by any RecoveryEvent, nothing accepted in an event cycle. New frontend
    design bundles FetchFault/FetchInst/FetchPacket. Asserts NoCompressedDecode,
    DisabledExtensionTraps, FetchPacket lane contiguity. 7 L1 tests (red: 7 PENDING); 18
    mutants red after strengthening (event-cycle acceptance into an empty stage, a
    fetch-faulted branch, Debug Mode ECALL); the explicit 16-bit check is equivalent (no
    table row has bits[1:0] != 11). spec-check-allow 40 -> 38.

## Validation status (run this session)

- `bash verif/bin/build.sh`: 0 errors at each of the three commits.
- Review rounds 1-3 re-ran all gates below after each fix; the numbers are from round 3.
- `python3 tools/spec-check.py`: 0 errors, 4 warnings (localspec-coverage on Decoder,
  DebugUnit, TriggerUnit, and Util - pre-existing; the CSR.scala warnings left with the file).
- Internal-edge reconciliation (scratch script, stronger than check 1): every labeled
  edge of FrontendTop (7), BackendTop (63), CoreTop (38) matches a producer *Out and a
  consumer *In interface; no orphan child interfaces.
- `verif/bin/run.sh verif.spectest.RunSpecTests`: 101/101 PASS after ADR-019E (94/94 after RTL block 12 (adds 1 CommitUnit
  serialize-head test, 14 CsrController, 6 TrapController, 2 seam integration); earlier: 55/55 PASS) (6 pre-existing + 2 params +
  9 RenameUnit + 9 ReorderBuffer + 1 SystemOpDecode + 4 RecoveryController + 15 CommitUnit +
  3 PhysicalRegisterFile + 6 ReservationStation + 2 DispatchUnit + 4 execution wrappers +
  10 BranchUnit/PublishMux) after RTL block 9.
- `scn.sh run ooo_div_survives_mispredict.scn`: harness-not-ready (exit 3), as designed.
- Nothing SIMULATED against CoreTop (all ADR-019 vertices are shells).

## Remaining allowlist debt

- spec-test-allow: 48 names (ADR-019D: funcDebugCommitBoundary and propTrapSingleWriter bound;
  ADR-019E: funcCsrMapContribution bound) (bound since the freeze: funcRobOlder, propArchRedirectWins,
  propRetireNonBlocking, funcHeadMemGrant, propPrfPortsFixed; the ADR-019C additions are all
  bound) (funcHeadMemGrant, propUncachedPerformedOnce, and the other uncacheable paths need an uncacheable harness region). SFENCE.VMA (flush funcs, propSfenceFlushesAll) - protected
  assembler has no mnemonic; uncacheable PMA paths - harness has no uncacheable region;
  predictor/FTQ internals and bus-adapter/cache monitors - need L1 SpecTests on RTL;
  doctrine/machine-check props; pre-ADR-019 carried names.
- spec-check-allow: 63 PROPERTYs (removed with their asserts: propPhysRegConservation,
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
   The execution backend (RS, Dispatch, ALU/MUL/DIV/AGU, BranchUnit, PublishMux) is green.
   ADR-019D order: CommitUnit serialize-head sampling fix, CsrController, TrapController,
   CSR/Trap integration, ADR-019E (all done) -> LSQ -> StoreBuffer -> DecodeUnit -> BackendTop wiring. RenameUnit spec ambiguities found
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
