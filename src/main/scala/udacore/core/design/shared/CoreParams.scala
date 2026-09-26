package udacore.core.design.shared

import framework.macros.LocalSpec
import udacore.common.tilelink.TLLinkParams
import udacore.core.spec.shared.CoreParamsSpecs._

/** Core domain parameters (ADR-019 v0 reference values as defaults).
  *
  * Implements CoreParamsSpecs. The v0 machine always has both L1 caches, both
  * TLBs, and the shared PTW; geometry is a parameter, presence is not. Cache
  * presence and data-link coherence are independent (ADR-019 D-19.13).
  */

/** Privilege-mode set. v0 requires M + S + U. */
@LocalSpec(paramPrivilegeModes)
case class PrivilegeParams(
    usingUser: Boolean = true,
    usingSupervisor: Boolean = true,
    usingHypervisor: Boolean = false
) {
  require(!usingSupervisor || usingUser, "S-mode requires U-mode")
  require(!usingHypervisor || usingSupervisor, "H-extension requires S-mode")
}

/** L1 cache geometry (VIPT). */
@LocalSpec(paramICacheGeometry)
case class CacheParams(
    sets: Int = 64,
    ways: Int = 4,
    blockBytes: Int = 64,
    mshrs: Int = 1,
    targetsPerMshr: Int = 1
) {
  require(sets > 0 && (sets & (sets - 1)) == 0, "Cache sets must be a power of two")
  require(ways > 0, "Cache ways must be positive")
  require(
    blockBytes >= 16 && (blockBytes & (blockBytes - 1)) == 0,
    "Cache block bytes must be a power of two >= 16 (one fetch block)"
  )
  require(mshrs >= 1 && targetsPerMshr >= 1, "at least one miss context and target")

  @LocalSpec(propViptGeometryLegal)
  val viptGeometryLegal: Unit = require(
    sets * blockBytes <= 4096,
    "ViptGeometryLegal: sets * blockBytes must not exceed the 4 KiB Sv32 page (VIPT without synonyms)"
  )

  def sizeBytes: Int = sets * ways * blockBytes
}

/** TLB geometry (fully associative). */
@LocalSpec(paramTlbGeometry)
case class TlbParams(
    entries: Int = 16
) {
  require(entries > 0, "TLB entries must be positive")
}

/** Contract tier. */
case class CoreContractParams(
    dataWidth: Int = 32,   // XLEN (v0: 32)
    vAddrWidth: Int = 32,  // Sv32 virtual address
    pAddrWidth: Int = 34,  // Sv32 physical address
    hartId: Int = 0,
    privilege: PrivilegeParams = PrivilegeParams(),
    icache: CacheParams = CacheParams(),
    dcache: CacheParams = CacheParams(mshrs = 2, targetsPerMshr = 2),
    itlb: TlbParams = TlbParams(),
    dtlb: TlbParams = TlbParams(),
    @LocalSpec(paramDataCoherence)
    dataCoherence: Boolean = false, // TL-C only when true; never implied by the D-cache
    /** Platform / Debug Module execution address on Debug Mode entry (ADR-019E E-2);
      * implementation-specific, 0x800 is the verification platform's default. */
    @LocalSpec(paramDebugEntryAddr)
    debugEntryAddr: Long = 0x800L
) {
  require(dataWidth == 32, "v0 is RV32IM: dataWidth (XLEN) must be 32")
  require(vAddrWidth == 32 && pAddrWidth == 34, "v0 is Sv32: 32-bit VA, 34-bit PA")
  require(hartId >= 0, "Hart ID must be non-negative")
  require(privilege.usingSupervisor, "Sv32 translation requires S-mode (satp)")
  require(!dataCoherence, "TL-C data coherence is not part of v0 (ADR-019 D-19.13)")
  require(debugEntryAddr >= 0 && debugEntryAddr < (1L << vAddrWidth) && debugEntryAddr % 4 == 0,
    "debugEntryAddr must be a 4-byte aligned address within the virtual address space")
}

/** Tuning tier. */
case class CoreTuningParams(
    ptwOutstanding: Int = 1,
    usingRvvi: Boolean = false // verification retire stream (ADR-010), default off
) {
  require(ptwOutstanding == 1, "v0 PTW has one outstanding walk")
}

/** Private tier. */
case class CorePrivateParams(
    bootCycles: Int = 8,
    debugFeatures: Boolean = false
)

/** Complete core parameter set. */
case class CoreParams(
    contract: CoreContractParams = CoreContractParams(),
    tuning: CoreTuningParams = CoreTuningParams(),
    priv: CorePrivateParams = CorePrivateParams()
) {
  def dataWidth: Int     = contract.dataWidth
  def vAddrWidth: Int    = contract.vAddrWidth
  def pAddrWidth: Int    = contract.pAddrWidth
  def hartId: Int        = contract.hartId
  def debugEntryAddr: Long = contract.debugEntryAddr
  def usingRvvi: Boolean = tuning.usingRvvi

  def usingUser: Boolean       = contract.privilege.usingUser
  def usingSupervisor: Boolean = contract.privilege.usingSupervisor

  // ADR-016 single link derivation, amended by ADR-019 D-19.13: hasBCE follows
  // the coherence parameter, never cache presence.
  @LocalSpec(paramTLLinkDerivation)
  def instBusParams: TLLinkParams = TLLinkParams.forMaster(
    addressBits = pAddrWidth,
    dataBits = dataWidth,
    maxTransferBytes = contract.icache.blockBytes,
    sourceIds = 1
  )
  def dataBusParams: TLLinkParams = TLLinkParams.forMaster(
    addressBits = pAddrWidth,
    dataBits = dataWidth,
    maxTransferBytes = contract.dcache.blockBytes,
    sourceIds = contract.dcache.mshrs + 2, // fills + writeback + uncached
    hasBCE = contract.dataCoherence
  )
}
