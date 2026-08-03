package udacore.memorysubsystem.design.shared

import udacore.memorysubsystem.spec.shared.MemorySubsystemParamsSpecs

/** Memory subsystem domain parameter implementation.
  *
  * Implements the three-tier parameter architecture defined in
  * MemorySubsystemParamsSpecs, re-based for an RV32-TCM core per ADR-003 M5:
  *
  *   - XLEN-parametric datapath (memOpWidth = xLen, ADR-016 amending D-3.14),
  *     single memory port.
  *   - Exactly one LoadUnit, one StoreUnit, one StoreBuffer (no loadUnitCount /
  *     storeUnitCount knobs).
  *   - The single window axis N drives two derived depth knobs, storeBufferDepth
  *     = max(1, min(N, storeBufferDepthMax)) and loadOutstanding (default 1).
  *   - The >= 4 lower-bound requires that forbade the N=1 single-entry point are
  *     removed; the 64-bit standard / highPerformance presets are deleted; the
  *     former minimal preset becomes the tcm default.
  */

/** Contract-tier parameters that parent integrators must specify. These form
  * the interface contract between memory subsystem and system integration.
  */
case class MemorySubsystemContractParams(
    addressWidth: Int = 32, // Data memory address width in bits (integrator's pAddrWidth)
    memOpWidth: Int = 32,   // Memory operation datapath width in bits = XLEN (ADR-016)
    memoryPorts: Int = 1    // Number of external memory ports (ADR-003 D-3.14: single port)
) {
  require(addressWidth > 0, "Address width must be positive")
  // ADR-016 amends ADR-003 D-3.14: the datapath follows XLEN instead of a fixed 32.
  require(
    memOpWidth == 32 || memOpWidth == 64,
    "Memory operation width must equal XLEN (32 or 64, ADR-016)"
  )
  require(memoryPorts > 0, "Number of memory ports must be positive")
}

/** Tuning-tier parameters for performance optimization. ADR-003 M5: only the two
  * derived depth knobs plus their bound and the disambiguation policy remain; no
  * lower-bound require blocks the N=1 single-entry point.
  */
case class MemorySubsystemTuningParams(
    storeBufferDepth: Int = 1,     // Speculative store capacity = max(1, min(N, storeBufferDepthMax))
    loadOutstanding: Int = 1,      // Max concurrent outstanding external reads; derives txnId width
    storeBufferDepthMax: Int = 8,  // Upper bound storeBufferDepth saturates at
    memDisambig: Boolean = false   // Speculative load disambiguation; default follows N>1 at the integrator
) {
  require(
    storeBufferDepth >= 1 && storeBufferDepth <= storeBufferDepthMax,
    "Store buffer depth must be between 1 and storeBufferDepthMax (ADR-003 D-3.7)"
  )
  require(
    loadOutstanding >= 1,
    "Load outstanding count must be at least 1 (ADR-003 D-3.13)"
  )
  require(
    storeBufferDepthMax >= 1,
    "Store buffer depth max must be at least 1"
  )
}

/** Private-tier parameters for internal implementation details. Only subsystem
  * implementers should modify these.
  */
case class MemorySubsystemPrivateParams(
    writePriority: Boolean = true // Reads may not starve the single committed write on the shared port
)

/** Complete memory subsystem parameter bundle. Aggregates all tiers into a
  * single parameter interface.
  */
case class MemorySubsystemParams(
    contract: MemorySubsystemContractParams,
    tuning: MemorySubsystemTuningParams = MemorySubsystemTuningParams(),
    privateParams: MemorySubsystemPrivateParams = MemorySubsystemPrivateParams()
) {
  // Derived convenience accessors
  def addressWidth: Int        = contract.addressWidth
  def memOpWidth: Int          = contract.memOpWidth
  def memoryPorts: Int         = contract.memoryPorts
  def storeBufferDepth: Int    = tuning.storeBufferDepth
  def loadOutstanding: Int     = tuning.loadOutstanding
  def storeBufferDepthMax: Int = tuning.storeBufferDepthMax
  def memDisambig: Boolean     = tuning.memDisambig
  def writePriority: Boolean   = privateParams.writePriority

  // ADR-003 D-3.13: derived transaction-id width; 0 at single-outstanding.
  def dataMemoryTxnIdWidth: Int =
    if (loadOutstanding <= 1) 0 else BigInt(loadOutstanding - 1).bitLength

  // Fixed-topology accessors retained for the domain module trait (single
  // LoadUnit / StoreUnit / StoreBuffer, ADR-003 M5). These are no longer tuning
  // knobs; the store window is expressed entirely by storeBufferDepth.
  def loadUnitCount: Int  = 1
  def storeUnitCount: Int = 1
  def loadQueueSize: Int  = loadOutstanding
  def storeQueueSize: Int = storeBufferDepth
}

/** Default memory subsystem parameter configurations. ADR-003 M5: the RV32-TCM
  * configuration is the only sane default; the 64-bit presets are deleted.
  */
object MemorySubsystemParams {

  /** TCM default point: XLEN-wide datapath (32 here), single port, N=1
    * single-entry store buffer, single outstanding read (txnId width 0),
    * conservative disambiguation. This is the in-order degenerate point.
    */
  def tcm(xLen: Int = 32, addressWidth: Int = 32): MemorySubsystemParams = MemorySubsystemParams(
    contract = MemorySubsystemContractParams(
      addressWidth = addressWidth,
      memOpWidth = xLen,
      memoryPorts = 1
    ),
    tuning = MemorySubsystemTuningParams(
      storeBufferDepth = 1,
      loadOutstanding = 1,
      storeBufferDepthMax = 8,
      memDisambig = false
    )
  )

  /** Alias retained for existing callers; the former minimal preset is the
    * TCM default at XLEN=32.
    */
  def minimal: MemorySubsystemParams = tcm()
}
