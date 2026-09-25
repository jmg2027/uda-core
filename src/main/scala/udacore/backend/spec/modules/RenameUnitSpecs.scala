package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._

/** RenameUnit: sRAT + rRAT + free list + branch checkpoints + busy table, and
  * the program-order allocation point of the backend (ADR-019 D-19.8).
  */
object RenameUnitSpecs {
  val contRenameUnit = spec {
    CONTRACT("RenameUnit")
      .desc(
        "RenameUnit is the in-order allocation point. For each uop (RenameWidth per cycle) it " +
        "assigns the next robTag, maps sources through the speculative RAT (sRAT), allocates a " +
        "destination from the free list, snapshots a branch checkpoint for control-flow uops, " +
        "and forks one RenameAllocation to the ROB, the RS, and (memory uops) the LSQ in one " +
        "atomic transfer. It also owns the retirement RAT (rRAT), updated in program order by " +
        "CommitUnit, and the physical-register busy table."
      )
      .has(
        intfDecodedPacketIn,
        intfRobAllocOut,
        intfRsAllocOut,
        intfLsqAllocOut,
        intfRenameCommitIn,
        intfWakeupBroadcastIn,
        intfRobStatusIn,
        intfRecoveryEventIn,
        funcRobTagAllocate,
        funcRenameMap,
        funcFreeListAllocate,
        funcBranchCheckpoint,
        funcBusyTable,
        funcAllocateAtomic,
        funcSerializeGate,
        funcRetirementMapUpdate,
        funcBranchRecoveryRestore,
        funcArchRecoveryRestore,
        propPhysRegConservation,
        propCheckpointRestoreExact,
        propRobTagConsecutive
      )
      .uses(paramRenameWidth, paramIntegerPrfEntries, paramBranchCheckpointCount, funcRobOlder)
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance. Ordering: the allocation pointer (robTag), the free list " +
        "(a circular FIFO of free prds with a speculative head, an architectural head, and a " +
        "tail), and the checkpoints (allocated in program order, circular). Live: sRAT " +
        "mappings and checkpoints of uncommitted uops. Recovery: BranchMispredict restores the " +
        "sRAT and speculative free-list head from the recovering branch's checkpoint and frees " +
        "every younger checkpoint; ArchRedirect restores sRAT := rRAT and speculative head := " +
        "architectural head and frees every checkpoint. Survives: rRAT, the architectural " +
        "free-list head, the tail. Reclaim: a free-list head restore returns every prd " +
        "allocated after the restore point; commit pushes each oldPrd at the tail."
      )
      .build()
  }

  val intfDecodedPacketIn = spec {
    INTERFACE("DecodedPacketIn")
      .desc("Decoded packets from DecodeUnit, consumed RenameWidth lanes per cycle in order.")
      .uses(bndDecodedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRobAllocOut = spec {
    INTERFACE("RobAllocOut")
      .desc("Allocation token to the ReorderBuffer; not ready when the ROB is full.")
      .uses(bndRenameAllocation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRsAllocOut = spec {
    INTERFACE("RsAllocOut")
      .desc("Allocation token to the ReservationStation; not ready when the RS is full.")
      .uses(bndRenameAllocation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfLsqAllocOut = spec {
    INTERFACE("LsqAllocOut")
      .desc("LQ/SQ allocation to the LoadStoreQueue for loads and stores; not ready when the target queue is full.")
      .uses(bndLsqAllocation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRenameCommitIn = spec {
    INTERFACE("RenameCommitIn")
      .desc("RenameCommit view of the commit broadcast: {archRd, newPrd, oldPrd, hasDest, checkpointId} in program order.")
      .uses(bndCommitBroadcast)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfWakeupBroadcastIn = spec {
    INTERFACE("WakeupBroadcastIn")
      .desc("Result-publication fact clearing busy bits.")
      .uses(bndWakeupBroadcast)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 5.")
      .build()
  }

  val intfRobStatusIn = spec {
    INTERFACE("RobStatusIn")
      .desc("ROB empty/head view for the serialization gate.")
      .uses(bndRobStatus)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4 (committed-state view).")
      .build()
  }

  val intfRecoveryEventIn = spec {
    INTERFACE("RecoveryEventIn")
      .desc("The common RecoveryEvent broadcast.")
      .uses(bndRecoveryEvent)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 6.")
      .build()
  }

  val funcRobTagAllocate = spec {
    FUNCTION("RobTagAllocate")
      .desc(
        "RenameUnit owns the program-order allocation pointer: each allocated uop receives the " +
        "current pointer as its robTag and the pointer advances by one (idx wraps and toggles " +
        "wrap). After any RecoveryEvent the pointer becomes e.robTag + 1."
      )
      .uses(paramRobTagWidth, intfRecoveryEventIn)
      .build()
  }

  val funcRenameMap = spec {
    FUNCTION("RenameMap")
      .desc(
        "prs1/prs2 = sRAT[rs1]/sRAT[rs2] (x0 -> p0). When hasDest, newPrd comes from the free " +
        "list, oldPrd = sRAT[rd], and sRAT[rd] := newPrd. Within a rename group a later lane " +
        "sees an earlier lane's destination."
      )
      .uses(intfDecodedPacketIn)
      .build()
  }

  val funcFreeListAllocate = spec {
    FUNCTION("FreeListAllocate")
      .desc(
        "The free list is a circular FIFO of prds. Allocation pops at the speculative head; " +
        "commit of a uop with a destination advances the architectural head by one and pushes " +
        "its oldPrd at the tail. p0 is never in the free list."
      )
      .uses(paramIntegerPrfEntries)
      .build()
  }

  val funcBranchCheckpoint = spec {
    FUNCTION("BranchCheckpoint")
      .desc(
        "A control-flow uop allocates the next checkpoint (circular, program order) and " +
        "snapshots {sRAT, speculative free-list head} as they stand immediately after renaming " +
        "that uop, including its own link destination. With no free checkpoint the uop waits " +
        "(backpressure). The checkpoint is released when the uop commits (RenameCommit carries " +
        "its id) or when a RecoveryEvent kills it."
      )
      .uses(paramBranchCheckpointCount, bndBranchCheckpointId)
      .build()
  }

  val funcBusyTable = spec {
    FUNCTION("BusyTable")
      .desc(
        "One ready bit per prd: cleared when allocated as newPrd, set by a WakeupBroadcast for " +
        "that prd (including a wakeup in the rename cycle). RenameAllocation carries " +
        "prs1Ready/prs2Ready from it. After a RecoveryEvent every prd outside the restored map " +
        "is don't-care; every prd in the restored map that belongs to a surviving producer " +
        "keeps its bit."
      )
      .uses(intfWakeupBroadcastIn)
      .build()
  }

  val funcAllocateAtomic = spec {
    FUNCTION("AllocateAtomic")
      .desc(
        "A uop is renamed only in a cycle where RobAllocOut, RsAllocOut, and (for memory uops) " +
        "LsqAllocOut are all ready and a destination and (for CFIs) a checkpoint are " +
        "available; then all its allocations happen in that cycle and all offered tokens fire " +
        "together. Otherwise nothing is allocated."
      )
      .uses(intfRobAllocOut, intfRsAllocOut, intfLsqAllocOut)
      .build()
  }

  val funcSerializeGate = spec {
    FUNCTION("SerializeGate")
      .desc(
        "A uop with serialize set is renamed only when RobStatus.empty; after it is renamed no " +
        "further uop is renamed until RobStatus.empty holds again (the serializing uop has " +
        "retired or been discarded)."
      )
      .uses(intfRobStatusIn)
      .build()
  }

  val funcRetirementMapUpdate = spec {
    FUNCTION("RetirementMapUpdate")
      .desc(
        "On each RenameCommit: if hasDest, rRAT[archRd] := newPrd, push oldPrd to the free-list " +
        "tail, and advance the architectural head; if the uop owns a checkpoint, release it."
      )
      .uses(intfRenameCommitIn)
      .build()
  }

  val funcBranchRecoveryRestore = spec {
    FUNCTION("BranchRecoveryRestore")
      .desc(
        "On RecoveryEvent{BranchMispredict}: sRAT := checkpoint[e.checkpointId].sRAT, " +
        "speculative head := checkpoint[e.checkpointId].head, release every checkpoint " +
        "allocated after e.checkpointId, keep e.checkpointId, and drop any unrenamed uop in the " +
        "event cycle. Recovery is complete in the event cycle; no ROB walk is performed."
      )
      .uses(intfRecoveryEventIn, funcRecoveryKills)
      .build()
  }

  val funcArchRecoveryRestore = spec {
    FUNCTION("ArchRecoveryRestore")
      .desc(
        "On RecoveryEvent{ArchRedirect}: sRAT := rRAT, speculative head := architectural head, " +
        "release all checkpoints, and mark every prd named by the rRAT ready."
      )
      .uses(intfRecoveryEventIn)
      .note("ADR-019 D-19.8: the architectural full-recovery source is the rRAT (ADR-001 retained for this case only).")
      .build()
  }

  val propPhysRegConservation = spec {
    PROPERTY("PhysRegConservation")
      .desc(
        "At every cycle boundary the sets {p0}, rRAT image, destinations of live uncommitted " +
        "uops, and free-list contents between the speculative head and the tail partition the " +
        "PRF exactly: no prd is in two sets (no double allocation or double free) and none is " +
        "in no set (no leak), including across branch and architectural recovery."
      )
      .uses(paramIntegerPrfEntries)
      .note("Simulation assert. ADR-019 verification obligation: rename checkpoint case with repeated writes to one register around a mispredicted branch.")
      .build()
  }

  val propCheckpointRestoreExact = spec {
    PROPERTY("CheckpointRestoreExact")
      .desc(
        "After a BranchMispredict recovery, the sRAT equals the sRAT that existed immediately " +
        "after the recovering branch was renamed, and the next allocated prd is the one that " +
        "would have followed that branch."
      )
      .uses(funcBranchRecoveryRestore)
      .build()
  }

  val propRobTagConsecutive = spec {
    PROPERTY("RobTagConsecutive")
      .desc("Between RecoveryEvents, consecutive allocations carry consecutive robTags; after an event the next allocation carries e.robTag + 1.")
      .uses(funcRobTagAllocate)
      .build()
  }
}
