package udacore.core.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.spec.ParamsSpecs._

/** Core domain parameter specifications.
  *
  * Defines the parameter specifications for the core integration layer.
  * Parameters are organized in three tiers: Contract (must be specified by
  * integrators), Tuning (performance optimization), and Private (internal
  * implementation details).
  */
object CoreParamsSpecs {

  // Contract Tier - Parent integrators must specify these
  val paramXLen = spec {
    PARAMETER("XLen")
      .desc(
        "Base integer register width (CoreParams.dataWidth): 32 or 64. Every datapath, " +
        "bundle, and TileLink beat width derives from it; nothing may hard-code 32."
      )
      .is(rawContractParams)
      .entry("default", "32")
      .note(
        "ADR-016: the shipped decode tables/assembler cover RV32 today; RV64 decode is " +
        "tracked accommodation debt (rawParametricISA), the parameter plumbing must not block it."
      )
      .build()
  }

  val paramPrivilegeModes = spec {
    PARAMETER("PrivilegeModes")
      .desc(
        "Privilege-mode ladder options (PrivilegeParams): usingUser / usingSupervisor / " +
        "usingHypervisor, each defaulting off (M-only base point)."
      )
      .is(rawContractParams)
      .entry("default", "M-only (all options false)")
      .note("Legality requires: S requires U, H requires S (capPrivilegeModes).")
      .note(
        "ADR-016: mode-dependent behavior is confined to TrapController and the TLB " +
        "vertices; enabling a mode adds CSR state and checks there, never a new boundary."
      )
      .build()
  }

  val paramCacheGeometry = spec {
    PARAMETER("CacheGeometry")
      .desc(
        "Optional per-side cache geometry (CacheParams: sets, ways, blockBytes). None (the " +
        "default) is the TCM point - the bus adapter attaches directly; Some elaborates the " +
        "cache vertex on that side (capCacheHierarchy)."
      )
      .is(rawContractParams)
      .entry("default", "None / None (icache, dcache)")
      .note(
        "A configured dcache flips the data TileLink link to TL-C (hasBCE) and raises its " +
        "maxTransferBytes to blockBytes; the icache raises the instruction link to burst " +
        "fills but never to coherence."
      )
      .build()
  }

  val paramTlbGeometry = spec {
    PARAMETER("TlbGeometry")
      .desc(
        "Optional per-side TLB geometry (TlbParams: entries). None (the default) means no " +
        "translation; Some elaborates the TLB vertex on that side (capAddressTranslation)."
      )
      .is(rawContractParams)
      .entry("default", "None / None (itlb, dtlb)")
      .note("Requires S-mode: satp owns translation enablement; Sv scheme follows XLen.")
      .build()
  }

  val paramTLLinkDerivation = spec {
    PARAMETER("TLLinkDerivation")
      .desc(
        "Derived TileLink link geometries (CoreParams.instBusParams / dataBusParams): " +
        "addressBits = pAddrWidth, dataBits = XLen, maxTransferBytes = cache blockBytes when " +
        "cached else XLen/8, source ids 1 (inst) / dataBusSourceIds (data), hasBCE iff dcache."
      )
      .is(rawContractParams)
      .note("ADR-016: the single derivation point for both CoreTop boundary links.")
      .build()
  }

  val paramProgramMemoryAddrWidth = spec {
    PARAMETER("ProgramMemoryAddrWidth")
      .desc("Program memory address width in bits")
      .is(rawContractParams)
      .entry("default", "32")
      .build()
  }

  val paramProgramMemoryDataWidth = spec {
    PARAMETER("ProgramMemoryDataWidth")
      .desc("Data width for program memory accesses")
      .is(rawContractParams)
      .entry("default", "32")
      .build()
  }
  
  val paramDataMemoryAddrWidth = spec {
    PARAMETER("DataMemoryAddrWidth")
      .desc("Data memory address width in bits")
      .is(rawContractParams)
      .entry("default", "32")
      .build()
  }

  val paramDataMemoryCommandWidth = spec {
    PARAMETER("DataMemoryCommandWidth")
      .desc("Data memory command width in bits")
      .is(rawContractParams)
      .entry("default", "5")
      .build()
  }

  val paramDataMemoryTxnIdWidth = spec {
    PARAMETER("DataMemoryTxnIdWidth")
      .desc("Data memory transaction ID width in bits")
      .is(rawContractParams)
      .entry("default", "0")
      .note(
        "ADR-003 D-3.13: derived = ceil(log2(LoadOutstanding)); 0 when single-outstanding. LoadOutstanding (paramLoadOutstanding) is defined by WP-B and consumed read-only."
      )
      .note("The base (single-outstanding) build carries txnId width 0, which folds the load-outstanding table away.")
      .build()
  }

  val paramDataMemoryMaskWidth = spec {
    PARAMETER("DataMemoryMaskWidth")
      .desc("Data memory mask width in bits")
      .is(rawContractParams)
      .note("dataMemoryDataWidth/8")
      .build()
  }

  val paramDataMemorySizeWidth = spec {
    PARAMETER("DataMemorySizeWidth")
      .desc("Data memory size width in bits")
      .is(rawContractParams)
      .note("log2(dataMemoryDataWidth/8)")
      .build()
  }

  val paramDataMemoryDataWidth = spec {
    PARAMETER("DataMemoryDataWidth")
      .desc("Data width for data memory accesses")
      .is(rawContractParams)
      .entry("default", "32")
      .build()
  }
  
  // Tuning Tier - Performance optimization parameters
  val paramEpochWidth = spec {
    PARAMETER("EpochWidth")
      .desc("Width of epoch counter for speculation management")
      .is(rawTuningParams)
      .entry("default", "2")
      .note("ADR-005 D-5.3: default 2 satisfies require((1<<epochWidth) > maxSurvivableGenerations+1) with one generation of margin.")
      .build()
  }

  // WP-D OWNS: the window/speculation axis and the verification retire knob.
  val paramSpeculativeRegNum = spec {
    PARAMETER("SpeculativeRegNum")
      .desc(
        "The window/speculation parameter N that scales one RTL codebase from an in-order point (N=1) to wide OoO (N=32+)."
      )
      .is(rawTuningParams)
      .entry("default", "1")
      .note(
        "ADR-008/ADR-015: at N=1 the OoO fabric (wakeup CAM, publish arbiter, free list, multi-entry map) MUST elaborate away (propN1FoldsOoO / propN1ForbiddenStructures). Physical-register and seq tag widths derive from N (WP-A physRegIdWidth/seqWidth)."
      )
      .build()
  }

  val paramUsingRvvi = spec {
    PARAMETER("UsingRvvi")
      .desc(
        "Verification-only knob that elaborates the CommitUnit retire stream, its commit-time wdata PRF read, and the 64-bit order counter. Default false."
      )
      .is(rawTuningParams)
      .entry("default", "false")
      .note(
        "ADR-010 D-10.3: when false the entire retire path folds away, INCLUDING the 64-bit order counter (on the ADR-008 N=1 forbidden-structure list). Gates the port AND the counter."
      )
      .build()
  }

  val paramSerializingStageDepth = spec {
    PARAMETER("SerializingStageDepth")
      .desc(
        "Number of in-flight serializing (CSR/system) uops permitted; default 1 per ADR-004 single-in-flight serialize."
      )
      .is(rawPrivateParams)
      .entry("default", "1")
      .note(
        "ADR-004 D-4.2 / ADR-012: published for WP-A maxInFlight = allocFifoDepth + storeBufferDepth + serializingStageDepth (consumed read-only). At most one un-committed CSR/system uop exists."
      )
      .build()
  }

  // Private Tier - Internal implementation details
  val paramBootCycles = spec {
    PARAMETER("BootCycles")
      .desc("Number of boot cycles for reset sequence")
      .is(rawPrivateParams)
      .entry("default", "2")
      .build()
  }

  val paramDebugFeatures = spec {
    PARAMETER("DebugFeatures")
      .desc("Debug feature enable flags")
      .is(rawPrivateParams)
      .entry("default", "true")
      .build()
  }
}
