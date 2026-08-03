package udacore.core.design.shared

import framework.macros.LocalSpec
import udacore.common.tilelink.TLLinkParams
import udacore.core.spec.shared.CoreParamsSpecs._

/** Core domain parameter implementation.
  *
  * Implements the three-tier parameter architecture defined in CoreParamsSpecs.
  * This serves as a template for parameter organization across all domains.
  */

/** Privilege-mode ladder accommodation (ADR-016, capPrivilegeModes). M-mode is
  * always present; each optional mode requires the one below it.
  */
@LocalSpec(paramPrivilegeModes)
case class PrivilegeParams(
    usingUser: Boolean = false,       // U-mode
    usingSupervisor: Boolean = false, // S-mode (satp / Sv translation)
    usingHypervisor: Boolean = false  // H-extension (VS/VU, two-stage translation)
) {
  require(!usingSupervisor || usingUser, "S-mode requires U-mode")
  require(!usingHypervisor || usingSupervisor, "H-extension requires S-mode")
}

/** Cache geometry accommodation (ADR-016, capCacheHierarchy). None = the TCM
  * point (bus adapter attaches directly); Some = a cache vertex on that side.
  */
@LocalSpec(paramCacheGeometry)
case class CacheParams(
    sets: Int,
    ways: Int,
    blockBytes: Int
) {
  require(sets > 0 && (sets & (sets - 1)) == 0, "Cache sets must be a power of two")
  require(ways > 0, "Cache ways must be positive")
  require(
    blockBytes >= 8 && (blockBytes & (blockBytes - 1)) == 0,
    "Cache block bytes must be a power of two >= 8"
  )
  def sizeBytes: Int = sets * ways * blockBytes
}

/** TLB geometry accommodation (ADR-016, capAddressTranslation). Elaborates only
  * with S-mode (satp); the Sv scheme follows xLen.
  */
@LocalSpec(paramTlbGeometry)
case class TlbParams(
    entries: Int
) {
  require(entries > 0, "TLB entries must be positive")
}

/** Contract-tier parameters that parent integrators must specify. These form
  * the interface contract between core and system integration.
  */
case class CoreContractParams(
    dataWidth: Int,   // Core data width in bits = XLEN (32 or 64)
    vAddrWidth: Int,  // Virtual address width
    pAddrWidth: Int,  // Physical address width
    hartId: Int,      // Hart identifier field
    privilege: PrivilegeParams = PrivilegeParams(),  // ADR-016: U/S/H accommodation
    icache: Option[CacheParams] = None,              // ADR-016: I-side cache vertex
    dcache: Option[CacheParams] = None,              // ADR-016: coherent D-side cache vertex
    itlb: Option[TlbParams] = None,                  // ADR-016: fetch translation
    dtlb: Option[TlbParams] = None,                  // ADR-016: data translation
    dataBusSourceIds: Int = 2                        // TileLink source ids on the data link
) {
  require(
    dataWidth == 32 || dataWidth == 64,
    "Data width (XLEN) must be 32 or 64"
  )
  require(vAddrWidth > 0, "Virtual address width must be positive")
  require(
    pAddrWidth >= vAddrWidth,
    "Physical address must be >= virtual address"
  )
  require(hartId >= 0, "Hart ID must be non-negative")
  require(
    itlb.isEmpty && dtlb.isEmpty || privilege.usingSupervisor,
    "TLBs require S-mode (satp owns translation enablement)"
  )
  require(dataBusSourceIds >= 1, "Data bus needs at least one source id")
}

/** Tuning-tier parameters for performance optimization. Parent integrators and
  * subsystem leads can adjust these.
  */
case class CoreTuningParams(
    epochWidth: Int = 2,          // Epoch counter width
    commitWidth: Int = 1,         // Instructions committed per cycle
    robDepth: Int = 0,            // Reorder buffer depth (0 = in-order)
    speculativeRegNum: Int = 1,   // Window/speculation axis N (ADR-008/ADR-015)
    usingRvvi: Boolean = false    // Verification retire stream (ADR-010), default off
) {
  require(epochWidth > 0, "Epoch width must be positive")
  require(commitWidth > 0, "Commit width must be positive")
  require(robDepth >= 0, "ROB depth must be non-negative")
  require(speculativeRegNum >= 1, "SpeculativeRegNum (N) must be >= 1")
  // ADR-005 D-5.3: epoch must be wide enough for maxSurvivableGenerations=1 (eager filtering).
  require(
    (1 << epochWidth) > 1 + 1,
    "epochWidth too narrow for the eager-filter wrap bound (require (1<<epochWidth) > maxSurvivableGenerations+1)"
  )
}

/** Private-tier parameters for internal implementation. Only core subsystem
  * owners should modify these.
  */
case class CorePrivateParams(
    bootCycles: Int = 8,           // Boot sequence cycles
    debugFeatures: Boolean = false, // Debug infrastructure enable
    serializingStageDepth: Int = 1  // In-flight serializing (CSR/system) uops (ADR-004 D-4.2)
) {
  require(serializingStageDepth >= 1, "SerializingStageDepth must be >= 1")
}

/** Complete core parameter bundle combining all tiers. This is the resolved
  * parameter set used by core implementation.
  */
case class CoreParams(
    contract: CoreContractParams,
    tuning: CoreTuningParams = CoreTuningParams(),
    priv: CorePrivateParams = CorePrivateParams()
) {
  // Convenience accessors for frequently used parameters
  def dataWidth: Int   = contract.dataWidth
  def vAddrWidth: Int  = contract.vAddrWidth
  def pAddrWidth: Int  = contract.pAddrWidth
  def hartId: Int = contract.hartId
  def epochWidth: Int  = tuning.epochWidth
  def commitWidth: Int = tuning.commitWidth
  def speculativeRegNum: Int = tuning.speculativeRegNum
  def usingRvvi: Boolean     = tuning.usingRvvi

  // ADR-016 accommodation accessors
  def usingUser: Boolean       = contract.privilege.usingUser
  def usingSupervisor: Boolean = contract.privilege.usingSupervisor
  def usingHypervisor: Boolean = contract.privilege.usingHypervisor
  def usingVm: Boolean         = contract.itlb.nonEmpty || contract.dtlb.nonEmpty

  // Published read-only for WP-A maxInFlight derivation (ADR-004 D-4.2 / ADR-012).
  def serializingStageDepth: Int = priv.serializingStageDepth

  // Parameter derivations
  def epochMask: Long        = (1L << epochWidth) - 1
  def maxCommitPerCycle: Int = commitWidth

  // ADR-016: TileLink link geometry for the two CoreTop boundary links. The
  // instruction link is uncoherent (hasBCE=false, single fill stream); the data
  // link turns coherent exactly when a DCache is configured. Beat width follows
  // XLEN; a configured cache raises maxTransferBytes to its block (burst fills).
  def instBusParams: TLLinkParams = TLLinkParams.forMaster(
    addressBits = pAddrWidth,
    dataBits = dataWidth,
    maxTransferBytes = contract.icache.map(_.blockBytes).getOrElse(dataWidth / 8),
    sourceIds = 1
  )
  def dataBusParams: TLLinkParams = TLLinkParams.forMaster(
    addressBits = pAddrWidth,
    dataBits = dataWidth,
    maxTransferBytes = contract.dcache.map(_.blockBytes).getOrElse(dataWidth / 8),
    sourceIds = contract.dataBusSourceIds,
    hasBCE = contract.dcache.nonEmpty
  )
}

object CoreParams {

  /** Create CoreParams with only contract parameters specified. Uses default
    * values for tuning and private parameters.
    */
  def minimal(
      dataWidth: Int,
      vAddrWidth: Int,
      pAddrWidth: Int,
      hartId: Int
  ): CoreParams = {
    CoreParams(
      contract = CoreContractParams(dataWidth, vAddrWidth, pAddrWidth, hartId)
    )
  }

  /** Create CoreParams with contract and tuning parameters. Uses default values
    * for private parameters.
    */
  def tuned(
      dataWidth: Int,
      vAddrWidth: Int,
      pAddrWidth: Int,
      hartId: Int,
      epochWidth: Int = 2,
      commitWidth: Int = 1,
      robDepth: Int = 0,
      speculativeRegNum: Int = 1,
      usingRvvi: Boolean = false
  ): CoreParams = {
    CoreParams(
      contract = CoreContractParams(dataWidth, vAddrWidth, pAddrWidth, hartId),
      tuning = CoreTuningParams(epochWidth, commitWidth, robDepth, speculativeRegNum, usingRvvi)
    )
  }
}
