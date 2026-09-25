package udacore.core.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.spec.ParamsSpecs._

/** Core domain parameter specifications (ADR-019 v0 reference point).
  *
  * The core domain owns the ISA/privilege point, the address widths, the L1
  * cache and TLB geometry, the PTW, the coherence capability, and the TileLink
  * link derivation. Backend window sizes live in BackendParamsSpecs and
  * frontend fetch/predictor sizes in FrontendParamsSpecs.
  */
object CoreParamsSpecs {

  // ---- Contract tier ------------------------------------------------------

  val paramXLen = spec {
    PARAMETER("XLen")
      .desc(
        "Base integer register width (CoreParams.dataWidth). The v0 architecture point is " +
        "RV32IM_Zicsr_Zifencei, so 32 is the only v0 value; RV64 is a later ADR."
      )
      .is(rawContractParams)
      .entry("v0", "32")
      .build()
  }

  val paramVAddrWidth = spec {
    PARAMETER("VAddrWidth")
      .desc("Virtual address width: 32 for Sv32.")
      .is(rawContractParams)
      .entry("v0", "32")
      .build()
  }

  val paramPAddrWidth = spec {
    PARAMETER("PAddrWidth")
      .desc(
        "Physical address width: 34 for Sv32 (22-bit PPN + 12-bit offset). Every physical " +
        "address field (LSQ paddr, cache tags, PTW requests, TileLink addressBits) derives from it."
      )
      .is(rawContractParams)
      .entry("v0", "34")
      .build()
  }

  val paramPrivilegeModes = spec {
    PARAMETER("PrivilegeModes")
      .desc(
        "Privilege-mode set (PrivilegeParams). v0 requires M, S, and U (usingUser = " +
        "usingSupervisor = true); usingHypervisor stays false."
      )
      .is(rawContractParams)
      .entry("v0", "M + S + U")
      .note("Legality requires: S requires U, H requires S, and Sv32 translation requires S.")
      .build()
  }

  val paramPmaMap = spec {
    PARAMETER("PmaMap")
      .desc(
        "Static physical-memory-attribute map: a list of physical regions with {cacheable, " +
        "executable, readable, writable}. Consulted by the ITLB/DTLB after translation (or in " +
        "Bare mode) and by the PTW for page-table reads. Unmapped addresses raise access faults."
      )
      .note(
        "Platform contract: a cacheable region never denies a line fill or writeback of an " +
        "address the PMA check accepted, so cached stores and dirty writebacks cannot fault " +
        "after retirement. Only uncacheable accesses may be denied, and those are performed at " +
        "the ROB head, so every store access fault is precise."
      )
      .is(rawContractParams)
      .entry("v0", "one cacheable RAM region plus one uncacheable device region; set by the integrator")
      .build()
  }

  val paramICacheGeometry = spec {
    PARAMETER("ICacheGeometry")
      .desc(
        "L1 instruction cache geometry (CacheParams: sets, ways, lineBytes) and miss contexts. " +
        "VIPT legality: sets * lineBytes <= 4096 (the Sv32 base page size)."
      )
      .is(rawContractParams)
      .entry("v0", "16 KiB: 64 sets x 4 ways x 64-byte lines, 1 miss context")
      .build()
  }

  val paramDCacheGeometry = spec {
    PARAMETER("DCacheGeometry")
      .desc(
        "L1 data cache geometry (CacheParams: sets, ways, lineBytes), MSHR count, and load " +
        "targets per MSHR. VIPT legality: sets * lineBytes <= 4096."
      )
      .is(rawContractParams)
      .entry("v0", "16 KiB: 64 sets x 4 ways x 64-byte lines, 2 MSHRs, 2 load targets per MSHR")
      .build()
  }

  val paramTlbGeometry = spec {
    PARAMETER("TlbGeometry")
      .desc(
        "ITLB and DTLB geometry (TlbParams: entries, fully associative). Entries hold either " +
        "a 4 KiB page or a 4 MiB Sv32 superpage."
      )
      .is(rawContractParams)
      .entry("v0", "ITLB 16 entries, DTLB 16 entries")
      .build()
  }

  val paramDataCoherence = spec {
    PARAMETER("DataCoherence")
      .desc(
        "Coherence capability of the data TileLink link, independent of cache presence " +
        "(ADR-019 D-19.13). false: the D-cache is a non-coherent write-back cache on a TL-UH " +
        "link (Get/PutFullData bursts). true (later): TL-C with Acquire/Probe/Release."
      )
      .is(rawContractParams)
      .entry("v0", "false")
      .note(
        "Amends ADR-016 D-16.4: a configured D-cache no longer implies hasBCE. Enabling TL-C " +
        "later MUST NOT change the backend/LSQ/MMU architectural interfaces."
      )
      .build()
  }

  val paramTLLinkDerivation = spec {
    PARAMETER("TLLinkDerivation")
      .desc(
        "Derived TileLink link geometries (CoreParams.instBusParams / dataBusParams): " +
        "addressBits = PAddrWidth, dataBits = XLen, maxTransferBytes = lineBytes of the " +
        "side's cache, source ids 1 (inst) / DCache MSHRs + 1 (data: fills, writeback, " +
        "uncached), hasBCE = DataCoherence (never cache presence)."
      )
      .is(rawContractParams)
      .uses(paramDataCoherence)
      .note("ADR-016 single derivation point for both CoreTop boundary links, amended by ADR-019 D-19.13.")
      .build()
  }

  // ---- Tuning tier --------------------------------------------------------

  val paramPtwOutstanding = spec {
    PARAMETER("PtwOutstanding")
      .desc("Concurrent page-table walks in the shared PTW.")
      .is(rawTuningParams)
      .entry("v0", "1")
      .build()
  }

  val paramUsingRvvi = spec {
    PARAMETER("UsingRvvi")
      .desc(
        "Verification-only knob that elaborates the CommitUnit retire stream, its commit-time wdata PRF read, and the 64-bit order counter. Default false."
      )
      .is(rawTuningParams)
      .entry("default", "false")
      .note("ADR-010 D-10.3: when false the entire retire path folds away, including the order counter.")
      .build()
  }

  // ---- Private tier -------------------------------------------------------

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

  // ---- Legality -----------------------------------------------------------

  val propViptGeometryLegal = spec {
    PROPERTY("ViptGeometryLegal")
      .desc(
        "Elaboration legality for both L1 caches: sets * lineBytes <= 4096, so every set-index " +
        "bit is an untranslated page-offset bit and a virtual index names the same set as the " +
        "physical address (no synonyms in the v0 reference)."
      )
      .uses(paramICacheGeometry, paramDCacheGeometry)
      .note("Elaboration require in CacheParams (ADR-015 D-15.3).")
      .build()
  }
}
