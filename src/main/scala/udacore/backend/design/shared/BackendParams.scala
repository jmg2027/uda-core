package udacore.backend.design.shared

import framework.macros.LocalSpec
import udacore.backend.spec.shared.BackendParamsSpecs._

/** Backend domain parameters (ADR-019 v0 reference values as defaults).
  *
  * Implements BackendParamsSpecs: three tiers, with the window sizes of the
  * explicit-ROB machine in the tuning tier.
  */

/** Contract tier. */
@LocalSpec(paramXLen)
case class BackendContractParams(
    xLen: Int = 32,              // RV32 (ADR-019 D-19.1)
    regNum: Int = 32,            // architectural integer registers
    hartId: Int = 0,
    enableMulDiv: Boolean = true, // M extension (v0: on)
    enableBitAlu: Boolean = false // bit-manipulation contribution (v0: off, ADR-017)
) {
  require(xLen == 32, "v0 is RV32IM: xLen must be 32 (RV64 needs a later ADR)")
  require(regNum == 32, "ArchRegNum must be 32")
  require(hartId >= 0, "Hart ID must be non-negative")
}

/** Tuning tier: the ADR-019 D-19.1 reference window. */
@LocalSpec(paramRobDepth)
case class BackendTuningParams(
    renameWidth: Int = 1,
    issueWidth: Int = 1,
    publishWidth: Int = 1,
    commitWidth: Int = 1,
    robDepth: Int = 16,
    integerPrfEntries: Int = 48,
    integerRsEntries: Int = 8,
    loadQueueDepth: Int = 8,
    storeQueueDepth: Int = 8,
    storeBufferDepth: Int = 4,
    branchCheckpointCount: Int = 4
) {
  require(renameWidth == 1 && commitWidth == 1 && publishWidth == 1,
    "v0 supports rename/commit/publish width 1 only (ADR-014 single lane)")
  require(issueWidth >= 1, "issue width must be positive")
  require(integerRsEntries >= 1 && loadQueueDepth >= 1 && storeQueueDepth >= 1 && storeBufferDepth >= 1,
    "queue depths must be positive")
  require(branchCheckpointCount >= 1, "at least one branch checkpoint is required")

  @LocalSpec(propPrfSizingCoversRob)
  val prfSizingCoversRob: Unit = require(
    robDepth >= 2 && (robDepth & (robDepth - 1)) == 0 && integerPrfEntries >= 32 + robDepth,
    "PrfSizingCoversRob: robDepth must be a power of two and integerPrfEntries >= ArchRegNum + robDepth"
  )
}

/** The frontend contract values the backend payloads carry (DecodedUop.prediction,
  * RecoveryEvent.ftqIdx/target, CfiOutcome.slot). They mirror FrontendContractParams /
  * FrontendTuningParams (paramDecodeWidth, paramFetchWidth, paramFtqIdxWidth) and must equal
  * them in any composed core; the backend never derives behavior from them beyond widths
  * and the decode-packet lane count.
  */
case class BackendFrontendView(
    decodeWidth: Int = 2, // DecodedPacket lanes (FrontendContractParams.decodeWidth)
    fetchWidth: Int = 4,  // instructions per fetch block (fetchBytes / 4)
    ftqDepth: Int = 16,   // FrontendTuningParams.ftqDepth
    vAddrWidth: Int = 32  // Sv32 virtual address width
) {
  require(decodeWidth >= 1 && decodeWidth <= fetchWidth, "decodeWidth must be in 1..fetchWidth")
  require(fetchWidth >= 1 && (fetchWidth & (fetchWidth - 1)) == 0, "fetchWidth must be a power of two")
  require(ftqDepth >= 2 && (ftqDepth & (ftqDepth - 1)) == 0, "ftqDepth must be a power of two")
}

/** Complete backend parameter set. */
case class BackendParams(
    contract: BackendContractParams = BackendContractParams(),
    tuning: BackendTuningParams = BackendTuningParams(),
    frontend: BackendFrontendView = BackendFrontendView(),
    usingRvvi: Boolean = false // CoreParams.tuning.usingRvvi, passed down by CoreTop (ADR-010 D-10.3)
) {
  def xLen: Int             = contract.xLen
  def iLen: Int             = 32 // fixed-width instructions, no RVC (ADR-019 D-19.3)
  def regNum: Int           = contract.regNum
  def hartId: Int           = contract.hartId
  def enableMulDiv: Boolean = contract.enableMulDiv
  def enableBitAlu: Boolean = contract.enableBitAlu

  def robDepth: Int          = tuning.robDepth
  def robTagWidth: Int       = log2(robDepth) + 1
  def physRegIdWidth: Int    = log2Ceil(tuning.integerPrfEntries)
  def checkpointIdWidth: Int = log2Ceil(tuning.branchCheckpointCount)
  def regIdWidth: Int        = 5
  def prfEntries: Int        = tuning.integerPrfEntries
  def checkpointCount: Int   = tuning.branchCheckpointCount

  def decodeWidth: Int    = frontend.decodeWidth
  def fetchSlotWidth: Int = log2Ceil(frontend.fetchWidth)
  def ftqIdxWidth: Int    = log2(frontend.ftqDepth) + 1
  def vAddrWidth: Int     = frontend.vAddrWidth

  /** Width of the legacy epoch meta field of the owner-protected CSR.scala
    * interface (OQ-E waived; removed when CSR.scala is rewritten). It has no ADR-019 recovery meaning. */
  def legacyCsrEpochWidth: Int = 1

  private def log2(x: Int): Int     = Integer.numberOfTrailingZeros(x)
  private def log2Ceil(x: Int): Int = if (x <= 1) 1 else 32 - Integer.numberOfLeadingZeros(x - 1)
}
