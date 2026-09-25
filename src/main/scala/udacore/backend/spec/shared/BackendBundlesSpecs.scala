package udacore.backend.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.backend.spec.shared.BackendParamsSpecs._

/** Backend shared bundles (ADR-019 WP-1).
  *
  * Owned here, consumed read-only by the frontend (RecoveryEvent, FtqCommit
  * views) and by the CoreTop-level MMU/cache vertices through the core shared
  * bundles. Field tables are normative payloads; widths come from
  * BackendParamsSpecs and the frontend/core parameter specs they name.
  */
object BackendBundlesSpecs {

  // ---- Ordering and recovery identity -------------------------------------

  val bndRobTag = spec {
    BUNDLE("RobTag")
      .desc("Canonical program-order identity of an in-flight uop, compared only through funcRobOlder.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("wrap", "Bool", "Phase bit of the allocation pointer."),
          List("idx", "UInt(log2(RobDepth))", "ROB slot.")
        )
      )
      .uses(paramRobTagWidth, funcRobOlder)
      .build()
  }

  val bndBranchCheckpointId = spec {
    BUNDLE("BranchCheckpointId")
      .desc("Rename checkpoint allocated to a control-flow uop at rename; names the sRAT/free-list snapshot taken immediately after renaming that uop.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("id", "UInt(CheckpointIdWidth)", "Checkpoint slot."))
      )
      .uses(paramCheckpointIdWidth)
      .build()
  }

  val bndCheckpointRelease = spec {
    BUNDLE("CheckpointRelease")
      .desc("BranchUnit to RenameUnit: a control-flow uop completed without requesting recovery, so its rename checkpoint is no longer needed.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("checkpointId", "BranchCheckpointId", "Checkpoint to free."),
          List("robTag", "RobTag", "Owner uop (cross-check against the checkpoint's recorded owner).")
        )
      )
      .uses(bndRobTag, bndBranchCheckpointId)
      .build()
  }

  val bndRecoveryEvent = spec {
    BUNDLE("RecoveryEvent")
      .desc(
        "The single selective-recovery fact (ADR-019 D-19.9). Produced only by the " +
        "RecoveryController, broadcast rawNoDecoupled to every speculative holder in the " +
        "frontend and backend, observed by all of them in the same cycle."
      )
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("valid", "Bool", "An event is published this cycle (at most one per cycle)."),
          List("kind", "RecoveryKind", "BranchMispredict (execute-time, selective) | ArchRedirect (commit-head, whole window)."),
          List("robTag", "RobTag", "Recovery point. BranchMispredict: the recovering branch, which survives. ArchRedirect: the commit-head uop that caused it."),
          List("checkpointId", "BranchCheckpointId", "Checkpoint of the recovering branch (BranchMispredict only)."),
          List("target", "UInt(vAddrWidth)", "Redirect target PC, 4-byte aligned."),
          List("ftqIdx", "FtqIdx", "FTQ entry of the recovering instruction (frontend history restore and truncation)."),
          List("cfiOutcome", "CfiOutcome", "Resolved type/direction/pc-slot of the recovering control-flow instruction, applied to GHR/RAS after restore (BranchMispredict only)."),
          List("cause", "RecoveryCause", "DirectionMispredict | TargetMispredict | UnpredictedCfi | Trap | Interrupt | XRet | Refetch.")
        )
      )
      .uses(bndRobTag, bndBranchCheckpointId, funcRecoveryKills)
      .note(
        "There is no separate redirect edge: the frontend consumes the same event (bndFetchRedirect " +
        "is its field projection). Refetch covers every commit-head serialization that resumes " +
        "at pc+4 (fence.i, sfence.vma, CSR writes with translation or privilege effect, " +
        "prediction-fault correction)."
      )
      .build()
  }

  val bndCfiOutcome = spec {
    BUNDLE("CfiOutcome")
      .desc("Resolved control-flow facts of one instruction, used for recovery repair and predictor training.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("cfiType", "CfiType", "None | Branch | Jal | Jalr | Call | Ret (Call/Ret per the RISC-V link-register hint rules on x1/x5)."),
          List("slot", "UInt(log2(FetchWidth))", "Instruction slot within its fetch block."),
          List("taken", "Bool", "Resolved direction (always true for jumps)."),
          List("target", "UInt(vAddrWidth)", "Resolved target when taken.")
        )
      )
      .build()
  }

  // ---- Rename / ROB --------------------------------------------------------

  val bndDecodedUop = spec {
    BUNDLE("DecodedUop")
      .desc("One decoded RV32IM_Zicsr_Zifencei uop leaving DecodeUnit (a decode packet carries up to DecodeWidth of these).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("pc", "UInt(vAddrWidth)", "Instruction PC (4-byte aligned)."),
          List("insn", "UInt(32)", "Instruction word (retire stream / tval)."),
          List("fuType", "FuType", "Alu | Mul | Div | Branch | Mem | Csr | System."),
          List("op", "UopOp", "Unit-local operation (ALU/MUL/DIV/branch/load/store/CSR control)."),
          List("rd, rs1, rs2", "UInt(5)", "Architectural registers; rd==x0 means no destination."),
          List("imm", "UInt(XLen)", "Sign-extended immediate."),
          List("isCfi", "Bool", "Control-flow uop: allocates a branch checkpoint at rename."),
          List("isLoad, isStore", "Bool", "Allocates an LQ or SQ entry at rename."),
          List("serialize", "Bool", "CSR/system uop: renamed only into an empty ROB, blocks younger rename until it retires (ADR-004 D-4.2 re-based)."),
          List("prediction", "PredictionView", "Frontend prediction for this slot: predictedTaken, predictedTarget, ftqIdx, slot, blockEnd."),
          List("predictionFault", "Bool", "The frontend predicted a taken CFI at this slot but it decodes as a non-CFI."),
          List("exception", "ExceptionInfo", "Fetch-time fault (instruction page/access fault) or illegal instruction, raised precisely at commit.")
        )
      )
      .build()
  }

  val bndRenameAllocation = spec {
    BUNDLE("RenameAllocation")
      .desc("The in-order allocation token RenameUnit forks to ROB, RS, and (for memory uops) LSQ in one atomic transfer.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("robTag", "RobTag", "Allocated by RenameUnit's program-order allocation pointer."),
          List("uop", "DecodedUop", "Decoded payload."),
          List("prs1, prs2", "UInt(PhysRegIdWidth)", "Source mappings read from the sRAT."),
          List("prs1Ready, prs2Ready", "Bool", "Busy-table state at rename (with same-cycle wakeup bypass)."),
          List("hasDest", "Bool", "rd != x0."),
          List("newPrd", "UInt(PhysRegIdWidth)", "Destination allocated from the free list."),
          List("oldPrd", "UInt(PhysRegIdWidth)", "Previous sRAT mapping of rd, freed when this uop commits."),
          List("checkpointId", "BranchCheckpointId", "Valid for control-flow uops.")
        )
      )
      .uses(bndRobTag, bndBranchCheckpointId, bndDecodedUop)
      .build()
  }

  val bndRobEntry = spec {
    BUNDLE("RobEntry")
      .desc("Data-less ROB entry (ADR-019 D-19.7): ordering and precise-state metadata only; values live in the PRF.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("valid", "Bool", "Allocated and not killed."),
          List("done", "Bool", "Completion observed (result published, or memory uop resolved)."),
          List("pc, insn", "UInt", "Identity for traps, tval, and the retire stream."),
          List("archRd, hasDest", "UInt(5), Bool", "Architectural destination."),
          List("newPrd, oldPrd", "UInt(PhysRegIdWidth)", "Physical destination and the mapping it replaces."),
          List("exception", "ExceptionInfo", "valid/cause/tval recorded at completion or allocation; raised only at the head."),
          List("isCfi, checkpointId", "Bool, BranchCheckpointId", "Branch recovery metadata (the checkpoint itself is freed at resolution, not at commit)."),
          List("cfiOutcome", "CfiOutcome", "Resolved outcome recorded at branch completion (predictor training at commit)."),
          List("ftqIdx, blockEnd", "FtqIdx, Bool", "FTQ reference; blockEnd marks the last committed instruction of its fetch block."),
          List("isLoad, isStore", "Bool", "Memory ordering metadata (SQ commit handoff for stores)."),
          List("headExecute", "Bool", "Uncacheable load/store reported by the LSQ: not done until CommitUnit grants its execution at the head (HeadMemGrant) and the LSQ completes it."),
          List("serialize, sysOp", "Bool, SysOp", "Commit-head system behavior: none | xRET | fence | fence.i | sfence.vma | wfi | csr-with-side-effect."),
          List("predictionFault", "Bool", "Commit triggers an ArchRedirect(Refetch) to pc+4.")
        )
      )
      .uses(bndRobTag, bndBranchCheckpointId, bndCfiOutcome)
      .build()
  }

  val bndRobCompletion = spec {
    BUNDLE("RobCompletion")
      .desc("Out-of-order completion notice from PublishMux to the ROB, keyed by robTag.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("robTag", "RobTag", "Completing uop."),
          List("exception", "ExceptionInfo", "Execution-detected exception (misaligned, page/access fault, illegal CSR access)."),
          List("cfiOutcome", "CfiOutcome", "Present for control-flow uops."),
          List("headExecute", "Bool", "Memory uops only: the uop is NOT done - it must be granted execution at the ROB head (uncacheable access). The completion that follows the grant has headExecute = 0.")
        )
      )
      .uses(bndRobTag, bndCfiOutcome)
      .build()
  }

  val bndRobHead = spec {
    BUNDLE("RobHead")
      .desc("The ROB head entry offered to CommitUnit; the transfer fires exactly when the entry retires or is taken as a trap.")
      .has(bndRobEntry)
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("robTag", "RobTag", "Head tag."),
          List("entry", "RobEntry", "Head entry; presented when done or headExecute (valid = head.valid && (done || headExecute)); a headExecute && !done head is observed, never transferred.")
        )
      )
      .build()
  }

  val bndRobStatus = spec {
    BUNDLE("RobStatus")
      .desc("Committed-state view of the ROB published every cycle (rawNoDecoupled class 4).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("empty", "Bool", "No live entry (serialization gate in RenameUnit)."),
          List("headTag", "RobTag", "Tag of the oldest live entry (LQ release in the LSQ).")
        )
      )
      .uses(bndRobTag)
      .build()
  }

  // ---- Execution -----------------------------------------------------------

  val bndIssuedUop = spec {
    BUNDLE("IssuedUop")
      .desc("A uop selected from the RS with operands read from the PRF, routed by DispatchUnit to one FU.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("robTag", "RobTag", "Order/recovery identity (every FU pipeline stage holds it)."),
          List("fuType, op", "FuType, UopOp", "Routing and operation."),
          List("src1, src2", "UInt(XLen)", "Operand values."),
          List("imm, pc", "UInt", "Immediate and PC (branches, AUIPC, JAL/JALR link value)."),
          List("prd, hasDest", "UInt(PhysRegIdWidth), Bool", "Destination."),
          List("checkpointId, prediction", "BranchCheckpointId, PredictionView", "Branch resolution inputs.")
        )
      )
      .uses(bndRobTag)
      .build()
  }

  val bndFuResult = spec {
    BUNDLE("FuResult")
      .desc("Result of any execution unit toward PublishMux: register value (optional) plus the ROB completion payload.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("robTag", "RobTag", "Completing uop."),
          List("prd, wen", "UInt(PhysRegIdWidth), Bool", "Destination write."),
          List("data", "UInt(XLen)", "Result value."),
          List("exception", "ExceptionInfo", "Execution-detected exception."),
          List("cfiOutcome", "CfiOutcome", "Branch unit only.")
        )
      )
      .uses(bndRobTag, bndCfiOutcome)
      .build()
  }

  val bndBranchResolution = spec {
    BUNDLE("BranchResolution")
      .desc("A mispredicted control-flow resolution from BranchUnit to RecoveryController (correct predictions complete through PublishMux only).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("robTag", "RobTag", "Resolving branch."),
          List("checkpointId", "BranchCheckpointId", "Its rename checkpoint."),
          List("ftqIdx", "FtqIdx", "Its FTQ entry."),
          List("pc", "UInt(vAddrWidth)", "Branch PC."),
          List("outcome", "CfiOutcome", "Resolved type/direction/target."),
          List("redirectTarget", "UInt(vAddrWidth)", "taken ? target : pc + 4."),
          List("cause", "RecoveryCause", "DirectionMispredict | TargetMispredict | UnpredictedCfi.")
        )
      )
      .uses(bndRobTag, bndBranchCheckpointId, bndCfiOutcome)
      .build()
  }

  val bndArchRedirect = spec {
    BUNDLE("ArchRedirect")
      .desc("A commit-head architectural redirect request from TrapController to RecoveryController.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("robTag", "RobTag", "The commit-head uop that caused it."),
          List("ftqIdx", "FtqIdx", "Its FTQ entry (history restore choice for the frontend)."),
          List("target", "UInt(vAddrWidth)", "Trap vector, xEPC, or pc+4 (Refetch)."),
          List("cause", "RecoveryCause", "Trap | Interrupt | XRet | Refetch.")
        )
      )
      .uses(bndRobTag)
      .build()
  }

  val bndPhysicalRegWrite = spec {
    BUNDLE("PhysicalRegWrite")
      .desc("PRF write from PublishMux.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("prd", "UInt(PhysRegIdWidth)", "Destination."),
          List("data", "UInt(XLen)", "Value.")
        )
      )
      .uses(paramPhysRegIdWidth)
      .build()
  }

  val bndWakeupBroadcast = spec {
    BUNDLE("WakeupBroadcast")
      .desc("Result-publication fact: physical register prd holds its value from the next cycle on.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("valid", "Bool", "A wakeup this cycle."),
          List("prd", "UInt(PhysRegIdWidth)", "Woken physical register.")
        )
      )
      .note("Carries no epoch: a wakeup for a killed producer's prd is harmless because that prd is either still busy-unallocated or reallocated only after its old consumers are killed by the same RecoveryEvent.")
      .uses(paramPhysRegIdWidth)
      .build()
  }

  val bndRegisterFileReadReq = spec {
    BUNDLE("RegisterFileReadReq")
      .desc("Operand read at RS select.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("prs1, prs2", "UInt(PhysRegIdWidth)", "Source physical registers."))
      )
      .build()
  }

  val bndRegisterFileReadResp = spec {
    BUNDLE("RegisterFileReadResp")
      .desc("Operand values for the selected uop.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("src1, src2", "UInt(XLen)", "Values (p0 reads zero)."))
      )
      .build()
  }

  // ---- Memory (LSQ) ---------------------------------------------------------

  val bndLsqAllocation = spec {
    BUNDLE("LsqAllocation")
      .desc("Program-order LQ/SQ allocation, one projection of bndRenameAllocation.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("robTag", "RobTag", "Owner uop."),
          List("isLoad, isStore", "Bool", "Queue selection."),
          List("size, signed", "UInt(2), Bool", "Access size (byte/half/word) and load sign extension."),
          List("prd", "UInt(PhysRegIdWidth)", "Load destination.")
        )
      )
      .uses(bndRobTag)
      .build()
  }

  val bndMemAddress = spec {
    BUNDLE("MemAddress")
      .desc("AGU result: effective virtual address (and store data) for the LSQ entry owned by robTag.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("robTag", "RobTag", "Owner uop; the LSQ matches its entry by robTag."),
          List("vaddr", "UInt(vAddrWidth)", "rs1 + imm."),
          List("storeData", "UInt(XLen)", "rs2 value for stores."),
          List("misaligned", "Bool", "Natural-alignment violation (raised as an address-misaligned exception).")
        )
      )
      .uses(bndRobTag)
      .build()
  }

  val bndLoadQueueEntry = spec {
    BUNDLE("LoadQueueEntry")
      .desc("Load queue entry.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("valid, gen", "Bool, UInt", "Live; per-entry allocation generation (transaction tag for D-cache responses, propGenerationTagScope)."),
          List("robTag", "RobTag", "Order and recovery identity."),
          List("state", "LoadState", "AddrPending | TranslationPending | Ready | Issued | WaitStoreData | WaitStoreDrain | Done | Faulted."),
          List("vaddr, paddr", "UInt", "Virtual address from the AGU; physical address from translation (authoritative for ordering)."),
          List("size, signed, prd", "", "Access shape and destination."),
          List("uncacheable", "Bool", "PMA attribute; executes only at the ROB head."),
          List("exception", "ExceptionInfo", "Misaligned / load page fault / load access fault.")
        )
      )
      .uses(bndRobTag)
      .build()
  }

  val bndStoreQueueEntry = spec {
    BUNDLE("StoreQueueEntry")
      .desc("Store queue entry (speculative until commit hands it to the StoreBuffer).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("valid", "Bool", "Live."),
          List("robTag", "RobTag", "Order and recovery identity."),
          List("state", "StoreState", "AddrPending | TranslationPending | Ready | Faulted."),
          List("vaddr, paddr", "UInt", "Addresses; paddr is authoritative for forwarding and ordering."),
          List("data, mask", "UInt(XLen), UInt(XLen/8)", "Store data and byte mask."),
          List("addrKnown, dataKnown", "Bool", "Resolution flags consulted by younger loads."),
          List("exception", "ExceptionInfo", "Misaligned / store page fault / store access fault.")
        )
      )
      .uses(bndRobTag)
      .build()
  }

  val bndCommittedStore = spec {
    BUNDLE("CommittedStore")
      .desc("A committed cacheable store handed from the SQ head to the StoreBuffer; irrevocable from this transfer on. Uncacheable stores never take this path (they are performed at the head through the LSQ uncached port).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("paddr", "UInt(pAddrWidth)", "Physical address."),
          List("data, mask", "UInt(XLen), UInt(XLen/8)", "Bytes to write."),
        )
      )
      .build()
  }

  val bndHeadMemGrant = spec {
    BUNDLE("HeadMemGrant")
      .desc(
        "CommitUnit to LSQ: the ROB head is an uncacheable memory uop and may now perform its " +
        "single bus access. Issued at most once per robTag; from the grant until that head " +
        "retires or traps, no interrupt or debug request is taken in front of it."
      )
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("robTag", "RobTag", "The head uop."))
      )
      .uses(bndRobTag)
      .build()
  }

  val bndStoreForwardQuery = spec {
    BUNDLE("StoreForwardQuery")
      .desc("Physical-address forwarding probe from the LSQ into the StoreBuffer for one load.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("paddr", "UInt(pAddrWidth)", "Load physical address."),
          List("mask", "UInt(XLen/8)", "Bytes read by the load.")
        )
      )
      .build()
  }

  val bndStoreForwardData = spec {
    BUNDLE("StoreForwardData")
      .desc("StoreBuffer forwarding answer: youngest committed bytes per load byte.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("data", "UInt(XLen)", "Forwarded bytes."),
          List("hitMask", "UInt(XLen/8)", "Bytes supplied by committed stores."),
          List("partial", "Bool", "Some load bytes overlap a committed store that cannot be forwarded exactly; the load waits for drain.")
        )
      )
      .build()
  }

  val bndMemResult = spec {
    BUNDLE("MemResult")
      .desc("LSQ completion toward PublishMux: load value (with destination) or store/load resolution with exception.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("robTag", "RobTag", "Completing memory uop."),
          List("prd, wen, data", "", "Load writeback (wen false for stores and faulting loads)."),
          List("headExecute", "Bool", "Not a completion: the uncacheable uop waits for HeadMemGrant (the ROB records it, done stays 0)."),
          List("exception", "ExceptionInfo", "Misaligned / page fault / access fault of the right access type.")
        )
      )
      .uses(bndRobTag)
      .build()
  }

  // ---- Commit ----------------------------------------------------------------

  val bndCommitBroadcast = spec {
    BUNDLE("CommitBroadcast")
      .desc(
        "The single in-order commit event, owned and driven by CommitUnit and keyed by robTag " +
        "(ADR-012 D-12.4 concept, re-based on the ROB). Consumers take field-projected views."
      )
      .markdownTable(
        List("Projected view", "Fields", "Consumer", "Protocol"),
        List(
          List("RenameCommit", "archRd, newPrd, oldPrd, hasDest", "RenameUnit (rRAT update, oldPrd free)", "ready/valid"),
          List("StoreCommit", "robTag", "LoadStoreQueue (SQ head -> StoreBuffer)", "ready/valid"),
          List("FtqCommit", "ftqIdx, cfiOutcome of the block exit", "FetchTargetQueue (entry release, predictor training)", "ready/valid"),
          List("CommitGrant", "robTag, valid", "CsrController (commit-gated CSR write)", "rawNoDecoupled class 4"),
          List("RetireToken", "see bndRetireToken", "verification retire stream", "ready/valid, observation-only")
        )
      )
      .uses(bndRobTag)
      .note(
        "A uop commits only when every ready/valid view it needs is accepted in the same cycle, " +
        "so the store, rename, and FTQ consumers can never disagree about which uop retired."
      )
      .build()
  }

  val bndFtqCommit = spec {
    BUNDLE("FtqCommit")
      .desc("Commit notice for a fetch block whose last instruction (blockEnd) retired.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("ftqIdx", "FtqIdx", "Retiring FTQ entry."),
          List("exit", "CfiOutcome", "Committed block exit: the taken control-flow instruction, or cfiType None for fall-through.")
        )
      )
      .uses(bndCfiOutcome)
      .build()
  }

  val bndRetireToken = spec {
    BUNDLE("RetireToken")
      .desc("Per-retirement verification token emitted by CommitUnit (ADR-010 D-10.1).")
      .markdownTable(
        List("field", "meaning"),
        List(
          List("order", "64b monotone retire sequence (verif-only, gated by usingRvvi)"),
          List("pc", "architectural PC of the retiring instruction"),
          List("insn", "32-bit instruction word"),
          List("rd", "architectural destination register"),
          List("wdata", "committed value from a commit-time PRF read"),
          List("wen", "register-write-enable"),
          List("trap", "1 for a trap-entry retire token (ADR-010 D-10.4)"),
          List("cause, tval", "trap cause and value"),
          List("priv", "privilege mode after the retirement")
        )
      )
      .note(
        "Emitted only for tokens that actually retire; observation-only, ready tied high (ADR-010 D-10.2). " +
        "The whole path including the order counter folds away at usingRvvi=false (ADR-010 D-10.3)."
      )
      .build()
  }

  // ---- Trap / CSR -----------------------------------------------------------
  // bndInterrupt, bndCsrReq, bndCsrResult, bndCsrTrapRead, bndCsrTrapWrite keep
  // their names: the owner-protected csr/CSR.scala binds the design bundles.

  val bndInterrupt = spec {
    BUNDLE("Interrupt")
      .desc("Raw interrupt source lines (machine and supervisor external/timer/software) as seen by the CSR mip view.")
      .note("Fields: meip, mtip, msip, seip, stip, ssip (sampled only at a retire boundary by CommitUnit).")
      .build()
  }

  val bndInterruptCtrl = spec {
    BUNDLE("InterruptCtrl")
      .desc("Pending-and-enabled interrupt view (mip & mie, mideleg, mstatus.MIE/SIE, priv) consumed by CommitUnit at a retire boundary.")
      .build()
  }

  val bndException = spec {
    BUNDLE("Exception")
      .desc("Commit-head trap notice from CommitUnit to TrapController.")
      .markdownTable(
        List("field", "meaning"),
        List(
          List("source", "Sync | Interrupt | Debug | SysOp"),
          List("cause, tval", "RISC-V cause code and trap value (faulting VA for page faults)"),
          List("pc, robTag, ftqIdx", "identity of the head uop"),
          List("sysOp", "xRET / fence.i / sfence.vma / Refetch request for a non-trapping serialization")
        )
      )
      .build()
  }

  val bndCsrReq = spec {
    BUNDLE("CSRReq")
      .desc("CSR operation request (the CSR uop always executes at the ROB head).")
      .note("Fields: csr, op, data, meta{rd}; the legacy CSR.scala (to be rewritten, OQ-E waived) still carries a legacy epoch meta field, which has no ADR-019 meaning.")
      .build()
  }

  val bndCsrResult = spec {
    BUNDLE("CSRResult")
      .desc("CSR execution result: the OLD CSR value as the rd writeback, plus illegal-access exception.")
      .build()
  }

  val bndCsrTrapRead = spec {
    BUNDLE("CSRTrapRead")
      .desc("Live trap-CSR snapshot read by TrapController at the commit head.")
      .note("Fields: mstatus, mepc, mcause, mtval, mtvec, medeleg, mideleg, mie, mip, sepc, scause, stval, stvec, priv, dpc, dcsr.")
      .build()
  }

  val bndCsrTrapWrite = spec {
    BUNDLE("CSRTrapWrite")
      .desc("Single trap/return application packet driven only by TrapController (ADR-004 D-4.3).")
      .markdownTable(
        List("field", "meaning"),
        List(
          List("kind", "TrapEntryM | TrapEntryS | MRet | SRet | DRet | DebugEntry"),
          List("xepc, xcause, xtval", "written to the M or S trap registers selected by delegation"),
          List("mstatusNext", "mstatus/sstatus after the MIE/SIE/MPIE/SPIE/MPP/SPP push or pop"),
          List("privNext", "privilege after the transition"),
          List("dpc, dcsrNext", "debug entry/return state")
        )
      )
      .build()
  }

  val bndTranslationContext = spec {
    BUNDLE("TranslationContext")
      .desc("Committed CSR state that governs translation and permission checks, published by CsrController to the ITLB, DTLB, and PTW.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("satpMode", "Bool", "0 = Bare, 1 = Sv32."),
          List("asid", "UInt(9)", "satp.ASID."),
          List("rootPpn", "UInt(22)", "satp.PPN."),
          List("priv", "Priv", "Current privilege (instruction side)."),
          List("dataPriv", "Priv", "Effective data privilege (mstatus.MPRV ? MPP : priv)."),
          List("sum, mxr", "Bool", "mstatus.SUM and mstatus.MXR.")
        )
      )
      .note(
        "Changes only when a serializing CSR write or trap/xRET commits, and every such " +
        "change is followed by an ArchRedirect, so no younger uop ever observed the old context."
      )
      .build()
  }
}
