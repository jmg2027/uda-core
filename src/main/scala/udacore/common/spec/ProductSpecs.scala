package udacore.common.spec

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.core.spec.shared.CoreParamsSpecs.paramSpeculativeRegNum

/** Product-level narrative specifications for UDACore.
  *
  * These RAW specs provide human-readable descriptions of product
  * characteristics that can be referenced by implementation specs and used for
  * documentation generation. They bridge the gap between technical
  * specifications and product documentation.
  */
object ProductSpecs {

  val rawUdacoreProduct = spec {
    RAW("UDACoreProduct", "product.identity")
      .desc("""
        |UDACore is a RISC-V implementation demonstrating Unified Dataflow Architecture (UDA) methodology.
        |Instead of traditional pipeline stages, independent vertices connected by ready/valid edges
        |form a graph-native design that achieves complete separation of function and performance.
        |It is a personal research vehicle, independent of any company line: XLEN-parametric, with a
        |standard TileLink memory boundary and accommodation seams for caches, TLBs, and the full
        |privilege-mode ladder (ADR-016).
        """.stripMargin)
      .note("Core product identity for UDACore project")
      .build()
  }

  val rawUDAMethodology = spec {
    RAW("UDAMethodology", "product.methodology")
      .desc("""
        |UDA treats pipelines as "dataflow paths" rather than "stage registers" - a paradigm shift
        |in CPU design thinking. Epoch-based control manages speculation without flush signals,
        |while each vertex operates independently, allowing registers to be freely added or removed
        |from edges without affecting functional correctness.
        """.stripMargin)
      .note(
        "Foundational design philosophy distinguishing UDACore from traditional CPU designs"
      )
      .build()
  }

  val rawParametricISA = spec {
    RAW("ParametricISA", "product.isa")
      .desc("""
        |This core is XLEN-parametric: the base integer ISA is RV32I or RV64I selected by
        |CoreParams.dataWidth, with configurable M (multiply/divide) and C (compressed) extensions
        |determined at elaboration time. Privilege modes beyond M (U, S, H) and address translation
        |are elaboration-time accommodations (ADR-016): the parameter and spec seams exist so
        |enabling them is additive, never a boundary change. The parameter system enables selective
        |feature generation for area efficiency while maintaining ISA compliance.
        """.stripMargin)
      .note("ISA support is determined by CoreParams at elaboration time")
      .note(
        "The shipped decode tables and assembler currently cover RV32; RV64 decode is part of " +
        "the accommodation debt tracked in ADR-016, not a silent claim."
      )
      .build()
  }

  val rawMinimalPipeline = spec {
    RAW("MinimalPipeline", "product.pipeline")
      .desc("""
        |UDACore provides a minimal 2-stage pipeline as the baseline configuration for area efficiency.
        |However, UDA characteristics allow pipeline depth adjustment by adding registers between vertices
        |or implementing multi-cycle operation within vertices for performance tuning.
        """.stripMargin)
      .note(
        "Pipeline depth is configurable while maintaining functional correctness"
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

  // WP-D OWNS: the single canonical N=1 forbidden-structure list (ADR-008 D-8.1).
  // backend / memory / frontend packages all .uses this rather than restating it.
  val propN1ForbiddenStructures = spec {
    PROPERTY("N1ForbiddenStructures")
      .desc(
        "The single canonical list of structures that MUST have ZERO instances in the emitted N=1 (SpeculativeRegNum=1) netlist. A structural grep hit is a hard build failure independent of the PPA percentages."
      )
      .markdownTable(
        List("Forbidden structure (emitted module/cell)", "Owning package", "Rationale"),
        List(
          List("Wakeup CAM / RS match matrix (ReservationStation match cells)", "backend", "no OoO wakeup at N=1"),
          List("Free-list allocation encoder (RenameUnit free-list priority encoder)", "backend", "N=1 uses a single free bit, no multi-entry encoder"),
          List("Map CAM / multi-entry rename map (RenameUnit map CAM)", "backend", "N=1 uses a degenerate direct-indexed map, never a CAM (ADR-008 D-8.2)"),
          List("Multi-producer publish arbiter tree (PublishMux arbiter beyond one producer)", "backend", "base publish is a single arbitrated result bus (ADR-014)"),
          List("Store-buffer byte-mask forwarding CAM (StoreBuffer forwarding CAM)", "memory", "no associative forwarding search at N=1"),
          List("Multi-entry issue queue beyond the irreducible skid depth (IssueQueue)", "frontend", "N=1 keeps only the skid buffer floor (ADR-008 D-8.3)"),
          List("Load outstanding table (MemoryController outstanding tracker)", "memory", "single-outstanding at base, txnId width 0"),
          List("32-bit seq / uopId field", "backend", "seqTag reduces to its N=1 width (ADR-012)"),
          List("64-bit retire order counter", "core/verif", "folds away when usingRvvi=false (ADR-010 D-10.3)")
        )
      )
      .uses(paramSpeculativeRegNum)
      .note(
        "ADR-008 D-8.1 / critique V-MA-4/MA-4: this is the single owned artifact; backend, memory, and frontend packages .uses it rather than restating divergent lists. The degenerate direct-indexed map (~32 x physRegIdWidth flops) plus a single free bit is NOT forbidden (ADR-008 D-8.2)."
      )
      .build()
  }

  val contConfigRegistry = spec {
    CONTRACT("ConfigRegistry")
      .desc(
        "Single source of named elaboration configs for the synth/STA/verif instrument; sta.sh/synth.sh/EmitCore consume it by name. SpeculativeRegNum is a required enumerable axis so a PPA delta is attributable to window size and nothing else."
      )
      .uses(paramSpeculativeRegNum)
      .markdownTable(
        List("Config name", "SpeculativeRegNum", "usingRvvi", "purpose"),
        List(
          List("n1", "1", "false", "in-order point; OoO structures must fold away"),
          List("n8", "8", "false", "mid OoO PPA point"),
          List("n32", "32", "false", "wide OoO PPA point"),
          List("n1_rvvi", "1", "true", "verif build at N=1 (harness on)"),
          List("n8_rvvi", "8", "true", "verif build at N=8 (harness on)"),
          List("verif", "8", "true", "default differential-equivalence pair partner")
        )
      )
      .note(
        "ADR-015 D-15.5: one-axis-per-name discipline copied from main EmitCore.configByName. N=1 configs are the elaboration-away acceptance vehicle (propN1FoldsOoO)."
      )
      .build()
  }

  val propN1FoldsOoO = spec {
    PROPERTY("N1FoldsOoO")
      .desc(
        "At SpeculativeRegNum=1 the OoO fabric must not survive into the netlist: the wakeup CAM, publish arbiter, free list, and rename map degenerate to constants/wires or a degenerate direct-indexed map."
      )
      .uses(propN1ForbiddenStructures, paramSpeculativeRegNum)
      .note(
        "ADR-008 D-8.1: acceptance is STRUCTURAL, mirroring main's feedthrough-check: synthesize n1, then assert the CAM/arbiter cell classes and the speculative-tag flop count are zero per propN1ForbiddenStructures."
      )
      .build()
  }

  val propN1PpaBar = spec {
    PROPERTY("N1PpaAcceptance")
      .desc(
        "At SpeculativeRegNum=1 the core must be competitive with a scoreboard in-order baseline: area within reference +10%, DFF within reference +10%, OOC worst-slack frequency within reference -5%, AND pass the structural grep (propN1ForbiddenStructures)."
      )
      .uses(propN1ForbiddenStructures)
      .markdownTable(
        List("Metric", "Bar"),
        List(
          List("baseline", "shipped main core, 64-entry TNP bank and RVVI stripped, re-measured in yosys 0.33 + sky130 HD OOC (~310-330k um^2, ~8000 DFF, ~46 MHz)"),
          List("area", "<= reference + 10%"),
          List("dff", "<= reference + 10%"),
          List("freq", ">= reference - 5% (OOC worst-slack)"),
          List("structural", "ZERO forbidden structures; tags at N=1 width")
        )
      )
      .note(
        "ADR-008 D-8.3: the structural clause is a hard gate independent of the percentages; the +10% envelope absorbs the degenerate direct-indexed map (ADR-008 D-8.2). Include the M-extension in the reference so the percentage is tight; the irreducible N=1 issue-queue skid floor is in the reference envelope, not counted as tax. OQ-C carries the +10% vs within-parity call."
      )
      .build()
  }
}
