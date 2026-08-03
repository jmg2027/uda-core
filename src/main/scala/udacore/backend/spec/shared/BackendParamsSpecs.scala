package udacore.backend.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.spec.ParamsSpecs._
import udacore.memorysubsystem.spec.shared.MemorySubsystemParamsSpecs.paramStoreBufferDepth
import udacore.core.spec.shared.CoreParamsSpecs.paramSerializingStageDepth

/** Backend domain parameter specifications.
  *
  * Defines parameters for instruction execution, commit, and memory operations.
  * Parameters are organized in three tiers: Contract (must be specified by
  * integrators), Tuning (performance optimization), and Private (internal
  * implementation details).
  */
object BackendParamsSpecs {

  // Contract Tier - Parent integrators must specify these
  val paramXLen = spec {
    PARAMETER("XLen")
      .desc("Register and ALU width in bits")
      .note("Contract tier - affects all execution units")
      .build()
  }

  val paramRegNum = spec {
    PARAMETER("RegNum")
      .desc("Number of architectural registers (32 or 16 for RVE)")
      .note("Contract tier - affects register file size")
      .build()
  }

  val paramMemOpWidth = spec {
    PARAMETER("MemOpWidth")
      .desc("Memory operation data width")
      .note("Contract tier - affects LSU interface")
      .build()
  }

  // Tuning Tier - Performance optimization parameters
  val paramExecutionUnits = spec {
    PARAMETER("ExecutionUnits")
      .desc("Configuration of execution units (ALU, Multiplier, etc.)")
      .note("Tuning tier - affects instruction throughput")
      .build()
  }

  val paramReservationStations = spec {
    PARAMETER("ReservationStations")
      .desc("Number and size of reservation station entries")
      .note("Tuning tier - affects out-of-order window")
      .build()
  }

  val paramLoadStoreQueue = spec {
    PARAMETER("LoadStoreQueue")
      .desc("Load-store queue sizing and organization")
      .note("Tuning tier - affects memory-level parallelism")
      .build()
  }

  // Private Tier - Internal implementation details
  val paramBypassPaths = spec {
    PARAMETER("BypassPaths")
      .desc("Bypass network configuration")
      .note("Private tier - implementation optimization")
      .build()
  }

  val paramScoreboardEntries = spec {
    PARAMETER("ScoreboardEntries")
      .desc("Scoreboard size for dependency tracking")
      .note("Private tier - implementation detail")
      .build()
  }

  // Unified Physical Register File parameters
  val paramPhysicalRegNum = spec {
    PARAMETER("PhysicalRegNum")
      .desc("Total number of physical registers (architectural + speculative)")
      .note("Tuning tier - typically 33 + N where N is speculative entries")
      .build()
  }

  val paramSpeculativeRegNum = spec {
    PARAMETER("SpeculativeRegNum")
      .desc("Number of speculative physical registers for out-of-order execution")
      .note("Tuning tier - determines OoO window size, scales from 1 (in-order) to 32+ (wide OoO)")
      .build()
  }

  // ------------------------------------------------------------------------
  // ADR-012 canonical tag width laws. WP-A OWNS these; WP-B/WP-C/WP-D import.
  // ------------------------------------------------------------------------

  // ADR-012 D-12.1: physical register id width; never a fixed 32.
  val physRegIdWidth = spec {
    PARAMETER("PhysRegIdWidth")
      .desc("Width of a physical register id: physRegIdWidth = log2Ceil(33 + SpeculativeRegNum), addressing the full PRF depth.")
      .note(
        "The PRF has 33 + N slots (32 architectural registers + the x0-hardwired slot + N speculative), consistent with PhysicalRegisterFile depth 33+N. log2Ceil(33+N): N=1 -> 6, N=32 -> log2Ceil(65) = 7. Never a fixed 32 (ADR-012 D-12.1)."
      )
      .uses(paramSpeculativeRegNum, paramPhysicalRegNum)
      .build()
  }

  // ADR-002: the backend in-flight allocation FIFO window. WP-A OWNS this.
  val paramAllocFifoDepth = spec {
    PARAMETER("AllocFifoDepth")
      .desc("Backend in-flight allocation FIFO window: the number of simultaneously in-flight uops the backend tracks (ADR-002).")
      .note("Tuning tier - one term of the maxInFlight seqTag horizon (ADR-012 D-12.2).")
      .build()
  }

  // ADR-012 D-12.2: the maxInFlight closed form over EVERY seqTag horizon.
  val maxInFlight = spec {
    PARAMETER("MaxInFlight")
      .desc(
        "Maximum number of simultaneously LIVE seqTags, summed over every horizon that keys on it."
      )
      .code(
        "maxInFlight = allocFifoDepth + storeBufferDepth + serializingStageDepth"
      )
      .uses(paramAllocFifoDepth, paramStoreBufferDepth, paramSerializingStageDepth)
      .note("allocFifoDepth: backend in-flight window (ADR-002).")
      .note(
        "storeBufferDepth: committed-but-undrained stores (ADR-003 D-3.5); published by WP-B via api (read-only)."
      )
      .note(
        "serializingStageDepth: staged CSR/system writes (ADR-004); published by WP-D via api (read-only)."
      )
      .note(
        "A store's seqTag is not recycled until the store buffer drains that entry; the width covers every live token including committed store-buffer entries and staged CSR writes (ADR-012 D-12.2)."
      )
      .build()
  }

  // ADR-012 D-12.1: canonical in-flight identifier width.
  val seqWidth = spec {
    PARAMETER("SeqWidth")
      .desc("Canonical in-flight tag width: seqWidth = log2Ceil(maxInFlight).")
      .note(
        "seqTag unifies the former 32-bit uopId and seq into one key; the verif-only order counter (ADR-010) stays a separate 64b field and is NOT a hardware key (ADR-012 D-12.1)."
      )
      .uses(maxInFlight)
      .build()
  }

  // ADR-012 D-12.3: wrap-aware modular age compare.
  val funcSeqOlder = spec {
    FUNCTION("SeqOlder")
      .desc(
        "Wrap-aware modular age compare seqOlder(a, b, oldestLive): true iff seqTag a is older than b within the bounded live range [oldestLive, newestLive]."
      )
      .note(
        "All age comparisons (e.g. store-buffer forwarding entry.seqTag < load.seqTag, ADR-003 D-3.9) use this modular order, never a naive unsigned '<' (ADR-012 D-12.3)."
      )
      .uses(seqWidth, maxInFlight)
      .build()
  }

  // ADR-012 D-12.2 verification obligation: tag uniqueness over all horizons.
  val propTagUniqueness = spec {
    PROPERTY("TagUniqueness")
      .desc(
        "No two live tokens share a seqTag across the backend in-flight window, the store-buffer horizon, and the CSR staging horizon."
      )
      .note(
        "seqTag MUST NOT be reused until every horizon has released it (ADR-012 D-12.2). Runtime simulation monitor; pair with a design assert (ADR-015 D-15.3)."
      )
      .uses(seqWidth, maxInFlight)
      .build()
  }
}
