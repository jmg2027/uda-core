package udacore.backend.design.shared

import udacore.backend.spec.shared.BackendParamsSpecs

/** Backend domain parameter implementation.
  *
  * Implements the three-tier parameter architecture defined in
  * BackendParamsSpecs. Organizes parameters for instruction execution, commit,
  * and memory operations.
  */

/** Contract-tier parameters that parent integrators must specify. These form
  * the interface contract between backend and system integration.
  */
case class BackendContractParams(
    xLen: Int,       // Width of an integer register in bits
    iLen: Int,       // Maximum Instruction length supported by an implementation
    regNum: Int,     // Number of architectural registers (32 or 16 for RVE)
    memOpWidth: Int, // Memory operation data width
    hartId: Int, // Hart identifier matches zero by default
    enableMulDiv: Boolean, // Enable multiplication and division
    enableBitAlu: Boolean // Enable bitwise ALU operations
) {
  require(xLen > 0, "XLen must be positive")
  require(
    xLen == 32 || xLen == 64,
    "XLen must be 32 or 64"
  )
  require(
    regNum == 16 || regNum == 32,
    "Register number must be 16 (RVE) or 32"
  )
  require(
    iLen ==  32,
    "Instruction length must be 32"
  )
  require(memOpWidth > 0, "Memory operation width must be positive")
  require(hartId >= 0, "Hart ID must be non-negative")
  require(
    Set(8, 16, 32, 64).contains(memOpWidth),
    "Memory operation width must be 8, 16, 32, or 64 bits"
  )
}

/** Tuning-tier parameters for performance optimization. Parent integrators and
  * subsystem leads can adjust these.
  */
case class BackendTuningParams(
    executionUnits: Int = 2,      // Number of execution units
    reservationStations: Int = 8, // Number of reservation station entries
    loadStoreQueueSize: Int = 16, // Load-store queue sizing
    epochWidth: Int = 2           // Epoch counter width
) {
  require(executionUnits > 0, "Execution units must be positive")
  require(reservationStations > 0, "Reservation stations must be positive")
  require(loadStoreQueueSize > 0, "Load-store queue size must be positive")
  require(epochWidth > 0, "Epoch width must be positive")
}

/** Private-tier parameters for internal implementation details. Only backend
  * subsystem implementers should modify these.
  */
case class BackendPrivateParams(
    bypassPaths: Int = 4,       // Number of bypass paths
    scoreboardEntries: Int = 32 // Scoreboard size for dependency tracking
) {
  require(bypassPaths > 0, "Bypass paths must be positive")
  require(scoreboardEntries > 0, "Scoreboard entries must be positive")
}

/** Complete backend parameter bundle combining all tiers. This is the resolved
  * parameter set used by backend implementation.
  */
case class BackendParams(
    contract: BackendContractParams,
    tuning: BackendTuningParams = BackendTuningParams(),
    privateParams: BackendPrivateParams = BackendPrivateParams()
) {
  // Convenience accessors for frequently used parameters
  def xLen: Int           = contract.xLen
  def iLen: Int           = contract.iLen
  def regNum: Int         = contract.regNum
  def memOpWidth: Int     = contract.memOpWidth
  def hartId: Int    = contract.hartId
  def enableMulDiv: Boolean = contract.enableMulDiv
  def enableBitAlu: Boolean = contract.enableBitAlu
  def executionUnits: Int = tuning.executionUnits
  def epochWidth: Int     = tuning.epochWidth

  // Parameter derivations
  def regIdWidth: Int = if (regNum == 16) 4 else 5
  def isRVE: Boolean  = regNum == 16
}

object BackendParams {

  /** Create BackendParams with only contract parameters specified. Uses default
    * values for tuning and private parameters.
    */
  def minimal(
      xLen: Int,
      iLen: Int,
      regNum: Int,
      memOpWidth: Int,
      hartId: Int
  ): BackendParams = {
    BackendParams(
      contract = BackendContractParams(
        xLen,
        iLen,
        regNum,
        memOpWidth,
        hartId,
        enableMulDiv = false,
        enableBitAlu = false
      )
    )
  }

  /** Create BackendParams with contract and tuning parameters. Uses default
    * values for private parameters.
    */
  def tuned(
      xLen: Int,
      iLen: Int,
      regNum: Int,
      memOpWidth: Int,
      hartId: Int,
      enableMulDiv: Boolean,
      enableBitAlu: Boolean,
      executionUnits: Int,
      epochWidth: Int = 2
  ): BackendParams = {
    BackendParams(
      contract = BackendContractParams(xLen, iLen, regNum, memOpWidth, hartId, enableMulDiv, enableBitAlu),
      tuning = BackendTuningParams(
        executionUnits = executionUnits,
        epochWidth = epochWidth
      )
    )
  }
}
