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

## Validation status (run this session)

- `bash verif/bin/build.sh`: 0 errors at each of the three commits.
- `python3 tools/spec-check.py`: 0 errors, 6 warnings (localspec-coverage on protected
  CSR/Decoder/Debug/Trigger and Util - pre-existing).
- Internal-edge reconciliation (scratch script, stronger than check 1): every labeled
  edge of FrontendTop (7), BackendTop (55), CoreTop (34) matches a producer *Out and a
  consumer *In interface; no orphan child interfaces.
- `verif/bin/run.sh verif.spectest.RunSpecTests`: 8/8 PASS (6 pre-existing + 2 params).
- `scn.sh run ooo_div_survives_mispredict.scn`: harness-not-ready (exit 3), as designed.
- Nothing SIMULATED against CoreTop (all ADR-019 vertices are shells).

## Remaining allowlist debt

- spec-test-allow: 54 names. SFENCE.VMA (flush funcs, propSfenceFlushesAll) - protected
  assembler has no mnemonic; uncacheable PMA paths - harness has no uncacheable region;
  predictor/FTQ internals and bus-adapter/cache monitors - need L1 SpecTests on RTL;
  doctrine/machine-check props; pre-ADR-019 carried names.
- spec-check-allow: 65 PROPERTYs, each pairs with its vertex's design assert when RTL lands.

## Open questions needing the OWNER

- OQ-E (ADR-004): waive AGENT: DO NOT TOUCH on `csr/CSR.scala`. It still carries the
  legacy epoch input/meta (BackendParams.legacyCsrEpochWidth keeps it compiling) and
  must be rewritten for M/S/U + TranslationContext.
- OQ-C: the ADR-008 N=1 PPA bar is superseded by ADR-019; confirm what (if any) PPA bar
  the v0 reference point should meet.
- OQ-D: single-hart, non-coherent memory model (no load-load ordering check, no
  memory-order replay) - confirm no second coherent agent or DMA is in v0 scope.
- OQ-G (new): uncacheable committed-store access faults are imprecise (store already
  retired). Choose: platform error interrupt, bus-error CSR, or require PMA to make
  writable device regions non-faulting.
- OQ-H (new): assembler protection blocks SFENCE.VMA / raw `.word` in `.scn`; allow adding
  mnemonics (or a `.word` directive) to `src/main/scala/assembler`?
- OQ-I (new): confirm the ADR-015 D-15.4/15.5 reinterpretation (ISA-model equivalence,
  one-axis configs without N) recorded in ADR-000 - or write a small ADR amendment.
- RV64 decode scheduling (carried; v0 is RV32 only).

## Unresolved architecture questions (engineering, not owner-gated)

- fence.i cost: D$ clean-all + I$ invalidate per fence.i (non-coherent I-side).
- DTLB single outstanding walk + fault record; multiple distinct-VPN misses serialize.
- One BTB-tracked CFI per fetch block; GHR shifts one bit per block with a tracked Branch.
- Predictor training at retirement only; no decode-time redirect for direct JAL.
- v0 serializes every CSR op (rename into empty ROB) and refetches after CSR writes.

## Next steps (in order)

1. Owner review of the ADR-019 spec tree (Work Order 07 section 9 acceptance).
2. RTL fill-in, each vertex starting from its red test: RenameUnit + ReorderBuffer +
   RecoveryController + CommitUnit (with the ADR-010 retire stream and CoreHarness
   binding) -> RS/Dispatch/PublishMux/PRF/FU wrappers -> LSQ/StoreBuffer -> DataCache +
   DataBusAdapter -> frontend (FetchPcGen/BranchPredictor/FTQ/FetchUnit/FetchBuffer) +
   InstructionCache/InstBusAdapter -> MMU (TLBs/PTW) -> Trap/Csr (after OQ-E).
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
- Protected: csr/CSR.scala, src/main/scala/assembler/*, src/test/scala/{cluster,assembler}.
