package udacore.common.spec

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

/** Product-level narrative specifications for UDACore (ADR-019 root).
  *
  * These RAW specs describe the product the implementation specs serve. They
  * are referenced by CoreTop and used for documentation generation.
  */
object ProductSpecs {

  val rawUdacoreProduct = spec {
    RAW("UDACoreProduct", "product.identity")
      .desc("""
        |UDACore is a personal, conventional out-of-order RISC-V core built with the Unified
        |Dataflow Architecture (UDA) engineering discipline: independent vertices connected by
        |ready/valid edges, specified in the Scala spec DSL before any RTL is written. The v0
        |machine has a PC-indexed BTB+TAGE+RAS frontend with an FTQ, an explicit data-less ROB,
        |sRAT/rRAT renaming over a unified PRF, a reservation station, a load/store queue,
        |execute-time selective branch recovery, VIPT L1 caches, and an Sv32 MMU. Its system
        |boundary is two TileLink master links (ADR-016 as amended by ADR-019).
        """.stripMargin)
      .note("Core product identity for UDACore (ADR-019 D-19.1).")
      .build()
  }

  val rawUDAMethodology = spec {
    RAW("UDAMethodology", "product.methodology")
      .desc("""
        |UDA treats the pipeline as dataflow paths: every token-moving connection is a ready/valid
        |edge, stalls are backpressure, and registers may be added or removed on an edge without
        |changing function. Speculation is recovered by one broadcast fact, the RecoveryEvent,
        |from which every speculative holder derives its own younger-than invalidation using a
        |single wrap-aware ROB-order rule. There are no ad-hoc flush wires and no global-epoch
        |squash: older correct-path work always survives a younger branch misprediction.
        """.stripMargin)
      .note("ADR-019 D-19.9/D-19.14 re-base of the UDA method onto selective recovery.")
      .build()
  }

  val rawParametricISA = spec {
    RAW("ParametricISA", "product.isa")
      .desc("""
        |The v0 architectural point is RV32IM with M, S, and U privilege modes and Sv32 virtual
        |memory. Every instruction is a fixed 32-bit word at a 4-byte aligned PC: the C extension
        |is not fetched, decoded, expanded, or represented anywhere in the frontend. Further
        |extensions (bit-manipulation, A, F, RV64) are ADR-017 contributions that later ADRs may
        |enable; none is part of v0.
        """.stripMargin)
      .note("ADR-019 D-19.1/D-19.3. XLEN remains a parameter, but only XLEN=32 is a v0 configuration.")
      .build()
  }

  val rawReferencePipeline = spec {
    RAW("ReferencePipeline", "product.pipeline")
      .desc("""
        |v0 reference configuration: 16-byte / 4-instruction fetch block, decode width 2, rename
        |width 1, one issue per cycle out of order from an 8-entry integer RS, commit width 1,
        |16-entry ROB, 48-entry integer PRF, LQ8 + SQ8, 16 KiB 4-way 64-byte-line VIPT I-cache and
        |D-cache, 16-entry ITLB and DTLB, a shared Sv32 PTW, two D-cache MSHRs with hit-under-miss.
        """.stripMargin)
      .note(
        "Widths and depths are tuning parameters; these values define the v0 reference point, " +
        "not permanent maxima (ADR-019 D-19.1)."
      )
      .build()
  }

  val rawSpecFirstDevelopment = spec {
    RAW("SpecFirstDevelopment", "product.workflow")
      .desc("""
        |All design components must have their contracts defined in Spec DSL before implementation.
        |@LocalSpec annotations ensure traceability between specifications and implementations,
        |while mermaid diagrams and interface definitions serve as the single source of truth (SSOT).
        """.stripMargin)
      .note(
        "Development workflow that ensures specification-implementation consistency"
      )
      .build()
  }

  val rawParameterArchitecture = spec {
    RAW("ParameterArchitecture", "product.parameters")
      .desc("""
        |A three-tier parameter system systematically manages global configuration, domain parameters,
        |and per-vertex arguments. Each domain resolves parameters in design/shared/ and exposes only
        |contract-safe views through design/api/ to maintain clear dependency boundaries.
        """.stripMargin)
      .note("Systematic parameter management across domain boundaries")
      .build()
  }

  val contConfigRegistry = spec {
    CONTRACT("ConfigRegistry")
      .desc(
        "Single source of named elaboration configurations for the synth/STA/verif instruments; " +
        "sta.sh/EmitCore/the verif harness consume it by name. Each name differs from the v0 " +
        "reference in exactly one axis so a measured delta is attributable."
      )
      .markdownTable(
        List("Config name", "Differs from v0 reference in", "Purpose"),
        List(
          List("default", "nothing (usingRvvi=false)", "v0 reference product point"),
          List("verif", "usingRvvi=true", "harness build: retire stream elaborated for ISA-model comparison"),
          List("minimal", "usingRvvi=true, faster boot", "fast directed-test build")
        )
      )
      .note(
        "ADR-015 D-15.5 one-axis-per-name discipline, re-based by ADR-019: the old " +
        "SpeculativeRegNum axis (n1/n8/n32) no longer exists. Future axes (ROB depth, cache " +
        "geometry, coherence enable) are added as separate names, never folded together."
      )
      .build()
  }
}
