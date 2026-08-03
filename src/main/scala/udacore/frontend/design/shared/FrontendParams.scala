package udacore.frontend.design.shared

import udacore.frontend.spec.shared.FrontendParamsSpecs

/** Frontend domain parameter implementation.
  *
  * Implements the three-tier parameter architecture defined in
  * FrontendParamsSpecs. Organizes parameters for instruction fetch, decode, and
  * prediction.
  */

/** Contract-tier parameters that parent integrators must specify. These form
  * the interface contract between frontend and system integration.
  */
case class FrontendContractParams(
    fetchWidth: Int,           // Instruction slots produced per cycle (ADR-009 D-9.8)
    instructionCacheSize: Int, // Size of instruction cache in bytes
    instAddrWidth: Int,        // Instruction address width in bits
    memDataWidth: Int = 32     // Program-memory data-port (beat) width, ADR-009 D-9.8
) {
  require(fetchWidth > 0, "Fetch width must be positive")
  require(instructionCacheSize > 0, "Instruction cache size must be positive")
  require(instAddrWidth > 0, "Instruction address width must be positive")
  require(
    (instructionCacheSize & (instructionCacheSize - 1)) == 0,
    "Instruction cache size must be power of 2"
  )
  // ADR-009 D-9.8: memDataWidth is a whole number of half-words; fetchWidth is
  // bounded by slotsPerBeat = memDataWidth/16.
  require(
    memDataWidth % 16 == 0,
    "memDataWidth must be a whole number of half-words (memDataWidth % 16 == 0)"
  )
  require(
    fetchWidth <= memDataWidth / 16,
    "fetchWidth cannot exceed slotsPerBeat = memDataWidth/16 (ADR-009 D-9.8)"
  )
  // ADR-009 D-9.6 G5: the P03 prefetchDepth <= 2^epochWidth-1 wrap require is
  // WITHDRAWN; the outstanding fetch latch is an enumerated eager-filter vertex
  // (ADR-005 D-5.2) so a fetch dies at the first redirect and never wraps.
}

/** Tuning-tier parameters for performance optimization. Parent integrators and
  * subsystem leads can adjust these.
  */
case class FrontendTuningParams(
    branchPredictorEntries: Int = 256, // Number of entries in branch predictor
    prefetchDepth: Int = 4,            // Instruction prefetch queue depth
    epochWidth: Int = 2                // Epoch counter width
) {
  require(
    branchPredictorEntries > 0,
    "Branch predictor entries must be positive"
  )
  require(prefetchDepth > 0, "Prefetch depth must be positive")
  require(epochWidth > 0, "Epoch width must be positive")
  require(
    (branchPredictorEntries & (branchPredictorEntries - 1)) == 0,
    "Branch predictor entries must be power of 2"
  )
}

/** Private-tier parameters for internal implementation details. Only frontend
  * subsystem implementers should modify these.
  */
case class FrontendPrivateParams(
    alignmentStages: Int =
      2,                   // Number of pipeline stages for instruction alignment
    decodeLatency: Int = 1 // Instruction decode latency in cycles
) {
  require(alignmentStages > 0, "Alignment stages must be positive")
  require(decodeLatency > 0, "Decode latency must be positive")
}

/** Complete frontend parameter bundle combining all tiers. This is the resolved
  * parameter set used by frontend implementation.
  */
case class FrontendParams(
    contract: FrontendContractParams,
    tuning: FrontendTuningParams = FrontendTuningParams(),
    privateParams: FrontendPrivateParams = FrontendPrivateParams()
) {
  // Convenience accessors for frequently used parameters
  def fetchWidth: Int             = contract.fetchWidth
  def instructionCacheSize: Int   = contract.instructionCacheSize
  def instAddrWidth: Int          = contract.instAddrWidth
  def memDataWidth: Int           = contract.memDataWidth
  def branchPredictorEntries: Int = tuning.branchPredictorEntries
  def epochWidth: Int             = tuning.epochWidth

  // Parameter derivations
  def fetchTargetWidth: Int = instAddrWidth
  def pcWidth: Int          = instAddrWidth
  def instLen: Int          = 32 // RISC-V instruction length
  // ADR-009 D-9.8 derivations.
  def slotsPerBeat: Int     = contract.memDataWidth / 16
  def fetchStrideBytes: Int = contract.memDataWidth / 8
}

object FrontendParams {

  /** Create FrontendParams with only contract parameters specified. Uses
    * default values for tuning and private parameters.
    */
  def minimal(
      fetchWidth: Int,
      instructionCacheSize: Int,
      instAddrWidth: Int
  ): FrontendParams = {
    FrontendParams(
      contract =
        FrontendContractParams(fetchWidth, instructionCacheSize, instAddrWidth)
    )
  }

  /** Create FrontendParams with contract and tuning parameters. Uses default
    * values for private parameters.
    */
  def tuned(
      fetchWidth: Int,
      instructionCacheSize: Int,
      instAddrWidth: Int,
      branchPredictorEntries: Int,
      epochWidth: Int = 2
  ): FrontendParams = {
    FrontendParams(
      contract =
        FrontendContractParams(fetchWidth, instructionCacheSize, instAddrWidth),
      tuning = FrontendTuningParams(
        branchPredictorEntries = branchPredictorEntries,
        epochWidth = epochWidth
      )
    )
  }
}
