package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.RenameUnitSpecs._

/** RenameUnit vertex shell (spec: RenameUnitSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contRenameUnit)
class RenameUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfDecodedPacketIn)
    val decodedPacketIn = ???

    @LocalSpec(intfRobAllocOut)
    val robAllocOut = ???

    @LocalSpec(intfRsAllocOut)
    val rsAllocOut = ???

    @LocalSpec(intfLsqAllocOut)
    val lsqAllocOut = ???

    @LocalSpec(intfRenameCommitIn)
    val renameCommitIn = ???

    @LocalSpec(intfCheckpointReleaseIn)
    val checkpointReleaseIn = ???

    @LocalSpec(intfWakeupBroadcastIn)
    val wakeupBroadcastIn = ???

    @LocalSpec(intfRobStatusIn)
    val robStatusIn = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcRobTagAllocate)
  val robTagAllocate = ???

  @LocalSpec(funcRenameMap)
  val renameMap = ???

  @LocalSpec(funcFreeListAllocate)
  val freeListAllocate = ???

  @LocalSpec(funcBranchCheckpoint)
  val branchCheckpoint = ???

  @LocalSpec(funcBusyTable)
  val busyTable = ???

  @LocalSpec(funcAllocateAtomic)
  val allocateAtomic = ???

  @LocalSpec(funcSerializeGate)
  val serializeGate = ???

  @LocalSpec(funcRetirementMapUpdate)
  val retirementMapUpdate = ???

  @LocalSpec(funcBranchRecoveryRestore)
  val branchRecoveryRestore = ???

  @LocalSpec(funcArchRecoveryRestore)
  val archRecoveryRestore = ???
}
