package udacore.frontend.design.shared

import framework.macros.LocalSpec
import udacore.backend.design.shared.{BackendFrontendView, BackendParams}
import udacore.frontend.spec.shared.FrontendParamsSpecs._

/** Frontend domain parameters (ADR-019 v0 reference values as defaults).
  *
  * Implements FrontendParamsSpecs. Instructions are fixed 32-bit words; there
  * is no compressed-instruction or half-word slot parameter.
  */

/** Contract tier: fetch-block geometry and the decode delivery width. */
@LocalSpec(paramFetchBytes)
case class FrontendContractParams(
    fetchBytes: Int = 16, // fetch block bytes (ADR-019 D-19.1)
    decodeWidth: Int = 2, // instructions per cycle into DecodeUnit
    vAddrWidth: Int = 32  // Sv32 virtual address width
) {
  require(fetchBytes >= 4 && (fetchBytes & (fetchBytes - 1)) == 0, "fetchBytes must be a power of two >= 4")
  require(decodeWidth >= 1 && decodeWidth <= fetchBytes / 4, "decodeWidth must be in 1..fetchWidth")
  require(vAddrWidth == 32, "v0 is Sv32: vAddrWidth must be 32")
}

/** Tuning tier: predictor, FTQ, and fetch-buffer sizing. */
@LocalSpec(paramTageGeometry)
case class FrontendTuningParams(
    btbSets: Int = 64,
    btbWays: Int = 2,
    bimodalEntries: Int = 1024,
    tageTableEntries: Int = 256,
    tageTagBits: Int = 8,
    tageHistoryLengths: Seq[Int] = Seq(8, 16, 32, 64),
    rasDepth: Int = 8,
    ftqDepth: Int = 16,
    fetchBufferEntries: Int = 8
) {
  private def pow2(x: Int) = x > 0 && (x & (x - 1)) == 0
  require(pow2(btbSets) && btbWays >= 1, "BTB sets must be a power of two, ways >= 1")
  require(pow2(bimodalEntries) && pow2(tageTableEntries), "TAGE table sizes must be powers of two")
  require(tageHistoryLengths.nonEmpty && tageHistoryLengths == tageHistoryLengths.sorted.distinct,
    "TAGE history lengths must be strictly increasing (geometric in the reference)")
  require(rasDepth >= 1, "RAS depth must be positive")
  require(pow2(ftqDepth), "FTQ depth must be a power of two (wrap-aware ftqIdx)")
  require(fetchBufferEntries >= 1, "fetch buffer must hold at least one instruction")
}

/** Complete frontend parameter set. `backend` is the backend parameter set whose RecoveryEvent
  * and FtqCommit payloads the frontend consumes (passed down by CoreTop); its frontend mirror
  * must equal this set. */
case class FrontendParams(
    contract: FrontendContractParams = FrontendContractParams(),
    tuning: FrontendTuningParams = FrontendTuningParams(),
    backend: BackendParams = BackendParams()
) {
  require(backend.frontend == BackendFrontendView(contract.decodeWidth, contract.fetchBytes / 4, tuning.ftqDepth,
    contract.vAddrWidth), "the backend's frontend mirror must equal the frontend parameters")
  def fetchBytes: Int  = contract.fetchBytes
  def fetchWidth: Int  = contract.fetchBytes / 4
  def decodeWidth: Int = contract.decodeWidth
  def vAddrWidth: Int  = contract.vAddrWidth
  def ghrLength: Int   = tuning.tageHistoryLengths.max
  def ftqDepth: Int    = tuning.ftqDepth

  def slotWidth: Int     = log2Ceil(fetchWidth)
  def offsetBits: Int    = log2Ceil(fetchBytes) // blockBase = fetchPc with these low bits cleared
  def ftqIdxWidth: Int   = log2Ceil(ftqDepth) + 1
  def tageTables: Int    = tuning.tageHistoryLengths.size
  def providerWidth: Int = log2Ceil(tageTables + 1)
  def btbWayWidth: Int   = log2Ceil(tuning.btbWays)
  def rasTopWidth: Int   = log2Ceil(tuning.rasDepth)
  /** FetchUnit fetch generation (transaction tag, propGenerationTagScope class 1). */
  def fetchGenWidth: Int = 4

  private def log2Ceil(x: Int): Int = if (x <= 1) 1 else 32 - Integer.numberOfLeadingZeros(x - 1)
}
