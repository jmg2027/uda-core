package udacore.frontend.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.spec.ParamsSpecs._
// WP-D publishes the canonical epoch width (ADR-005 D-5.3); consumed read-only.
import udacore.core.spec.shared.CoreParamsSpecs.paramEpochWidth

/** Frontend domain parameter specifications.
  *
  * Defines the parameter specifications for the frontend instruction pipeline.
  * Parameters are organized in three tiers: Contract (must be specified by
  * integrators), Tuning (performance optimization), and Private (internal
  * implementation details).
  */
object FrontendParamsSpecs {

  // Contract Tier - Parent integrators must specify these

  // ADR-009 D-9.8: memDataWidth is the physical program-memory data-port width;
  // it is the bus contract parents wire and it sets slotsPerBeat.
  val paramMemDataWidth = spec {
    PARAMETER("MemDataWidth")
      .desc(
        "Program-memory data-port width in bits (32/64/128). Sets slotsPerBeat = MemDataWidth/16 and fetchStride = MemDataWidth/8 bytes."
      )
      .note(
        "Contract tier - the physical program-memory bus width parents must wire. ADR-009 D-9.8 recommends this live in a shared api/ param because the memory subsystem also needs the bus width."
      )
      .note("Elaboration require: memDataWidth % 16 == 0 (a whole number of half-words).")
      .build()
  }

  // ADR-009 D-9.8: slotsPerBeat is derived, the upper bound on fetchWidth.
  val paramSlotsPerBeat = spec {
    PARAMETER("SlotsPerBeat")
      .desc("Derived: MemDataWidth/16. The number of 16-bit slots a fetch beat can hold; the upper bound on fetchWidth.")
      .note("Derived tier - not independently set; a function of MemDataWidth.")
      .uses(paramMemDataWidth)
      .build()
  }

  // ADR-009 D-9.8: fetchWidth is the instruction bandwidth, bounded by slotsPerBeat.
  val paramFetchWidth = spec {
    PARAMETER("FetchWidth")
      .desc(
        "Maximum number of instruction slots the frontend produces and the issue queue enqueues per cycle. Bounded: fetchWidth <= slotsPerBeat = MemDataWidth/16."
      )
      .note(
        "Contract tier - the instruction bandwidth. ADR-009 D-9.8 splits the former conflated fetchWidth into MemDataWidth (bus) and this (instructions). Base config fetchWidth=1; NextPcGen never widens (one PC/cycle regardless)."
      )
      .uses(paramSlotsPerBeat)
      .build()
  }

  val paramInstructionCacheSize = spec {
    PARAMETER("InstructionCacheSize")
      .desc("Size of instruction cache in bytes")
      .note("Contract tier - affects memory hierarchy")
      .build()
  }

  // Tuning Tier - Performance optimization parameters
  val paramBranchPredictorEntries = spec {
    PARAMETER("BranchPredictorEntries")
      .desc("Number of entries in branch predictor")
      .note("Tuning tier - affects prediction accuracy")
      .build()
  }

  val paramPrefetchDepth = spec {
    PARAMETER("PrefetchDepth")
      .desc("Instruction prefetch queue depth")
      .note("Tuning tier - affects fetch bandwidth utilization")
      .build()
  }

  // Private Tier - Internal implementation details
  val paramAlignmentStages = spec {
    PARAMETER("AlignmentStages")
      .desc("Number of pipeline stages for instruction alignment")
      .note("Private tier - implementation detail")
      .build()
  }

  val paramDecodeLatency = spec {
    PARAMETER("DecodeLatency")
      .desc("Instruction decode latency in cycles")
      .note("Private tier - affects pipeline depth")
      .build()
  }

  // Program-memory address width (instruction address bus). Contract tier.
  val paramProgramMemoryAddrWidth = spec {
    PARAMETER("ProgramMemoryAddrWidth")
      .desc("Program-memory (instruction) address width in bits. Sizes every frontend PC/target/addr field.")
      .note("Contract tier - the instruction address bus width. RISC-V instruction addresses are 2-byte aligned minimum (ADR-009 section 5.5).")
      .build()
  }

  // Program-memory data width == the fetch beat width. Aliases MemDataWidth.
  val paramProgramMemoryDataWidth = spec {
    PARAMETER("ProgramMemoryDataWidth")
      .desc("Program-memory response data width in bits: one fetch beat. Equal to MemDataWidth (ADR-009 D-9.8).")
      .note("Contract tier - the fetch beat width; sizes ExternalProgramMemoryResp.data and FetchResponse.data.")
      .uses(paramMemDataWidth)
      .build()
  }

  // Redirect cause code width (branch / trap / memory-order encodings).
  val paramRedirectCauseWidth = spec {
    PARAMETER("RedirectCauseWidth")
      .desc("Width of the redirect cause code carried on the Redirect bundle (branch / trap / memory-order class).")
      .note("Contract tier - fixed by the redirect merge priority ladder (ADR-013).")
      .build()
  }

  // Frontend view of the global epoch width. Derived from WP-D paramEpochWidth.
  val paramGlobalEpochWidth = spec {
    PARAMETER("GlobalEpochWidth")
      .desc("Width of the epoch tag stamped on every frontend token. Equal to the core-wide EpochWidth (ADR-005 D-5.3, default 2).")
      .note("Contract tier - mirrors WP-D CoreParamsSpecs.paramEpochWidth; the frontend consumes the same core-wide epoch width and stamps it via NextPcGen (funcEpochStamp).")
      .uses(paramEpochWidth)
      .build()
  }
}
