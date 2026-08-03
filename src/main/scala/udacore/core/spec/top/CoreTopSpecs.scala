package udacore.core.spec.top

import framework.macros.SpecEmit.spec
import framework.specs.Spec._

import udacore.common.spec.DesignRuleSpecs._
import udacore.common.spec.ProductSpecs._
import udacore.common.tilelink.TileLinkSpecs._
import udacore.core.spec.shared.CoreBundlesSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._

/** Core raw top specifications.
  *
  * The CoreTop vertex encapsulates frontend, backend, epoch control, and boot
  * sequencing and exposes only the interfaces required for system integration.
  */

object CoreTopSpecs {
  val contCoreTop = spec {
    CONTRACT("CoreTop")
      .desc("""Core top level contract
              | Core top level graph description
              | Interface with External System
              | Program memory, Data memory, Interrupt signals
              | Provide debug and power management surfaces for system integration
              | Instantiate and Connect Top level components
              | Manage uop flow by Global Epoch value
              | BootSequencer owns boot sequence and hart enable wiring
              """)
      .is(rawTop)
      .uses(rawUdacoreProduct, rawUDAMethodology, rawParametricISA)
      .has(
        intfBootAddrIn,
        intfHartEnIn,
        intfInterruptIn,
        intfDebugReqIn,
        intfInstBus,
        intfDataBus,
        capPrivilegeModes,
        capAddressTranslation,
        capCacheHierarchy,
        funcBootSequencing,
        funcGlobalEpochManagement,
        funcFrontendBackendDataflow,
        funcMemorySubsystemIntegration,
        funcStoreCommitCrossEdge,
        funcExternalMemoryBridge,
        funcInterruptDebugIntegration,
        propEpochBasedSpeculation,
        propUnifiedDataflowArchitecture,
        propFrontendBackendSeparation,
        propParametricConfiguration
      )
      .draw(
        "mermaid",
        """
      | graph LR
      |     %% Legend
      |     %% -.-> : Normal Wire
      |     %% Normal Wires are forbidden except for from or to outside core top
      |     %% --> : Decoupled Edge
      |     %% [] : Normal Module
      |     %% [[]] : Top Module (rawTop)
      |
      |     %% Inputs
      |     subgraph inputs_group[Inputs]
      |         in_anchor:::hidden
      |         bootaddr@{shape: text, label: BootAddr}
      |         en@{shape: text, label: hartEn}
      |         irq@{shape: text, label: Interrupt}
      |         debugreq@{shape: text, label: DebugReq}
      |     end
      |     bootaddr -.-> BootSequencer
      |     en -.-> BootSequencer
      |     irq -.-> be[[BackendTop]]
      |     debugreq -.-> be
      |
  |     subgraph CoreTop
      |         direction LR
      |         BootSequencer --BootAddr--> fe[[FrontendTop]]
      |         fe -- InstructionIssue --> be
      |         be -- Redirect --> fe & ep
      |         be -- MemoryOpReq --> me[[MemorySubsystemTop]]
      |         be -- StoreCommit --> me
      |         me -- MemoryOpResp --> be
      |         ep[GlobalEpochUnit] -. GlobalEpoch .-> fe & be & me
      |         fe -- ProgMemReq --> iba[InstBusAdapter]
      |         iba -- ProgMemResp --> fe
      |         me -- DataMemReq --> dba[DataBusAdapter]
      |         dba -- DataMemResp --> me
      |     end
      |
      |
      |     subgraph outputs_group[Outputs]
      |         instbus@{shape: text, label: InstBus}
      |         databus@{shape: text, label: DataBus}
      |     end
      |
      |     iba --> instbus
      |     dba --> databus
      |
      |
      |     style inputs_group fill:transparent,stroke:transparent
      |     style outputs_group fill:transparent,stroke:transparent
      """
      )
      .note("Roadmap (not yet in the base machine): power state management.")
      .note("Roadmap (not yet in the base machine): configurable CLIC support.")
      .build()
  }

  // External System Interfaces (from mermaid diagram)
  val intfBootAddrIn = spec {
    INTERFACE("BootAddrIn")
      .desc("Boot address input.")
      .uses(bndBootAddr)
      .is(rawNoDecoupled)
      .build()
  }

  val intfHartEnIn = spec {
    INTERFACE("HartEnIn")
      .desc("Hart enable signal input.")
      .uses(bndHartEnable)
      .is(rawNoDecoupled)
      .build()
  }

  val intfInterruptIn = spec {
    INTERFACE("InterruptIn")
      .desc("Interrupt signal input.")
      .uses(bndInterrupt)
      .is(rawNoDecoupled)
      .build()
  }

  val intfDebugReqIn = spec {
    INTERFACE("DebugReqIn")
      .desc("Debug request signal input.")
      .uses(bndDebugReq)
      .is(rawNoDecoupled)
      .note("Debug module triggers debug entry through CSR-based redirect mechanism")
      .build()
  }

  val intfInstBus = spec {
    INTERFACE("InstBus")
      .desc(
        "Instruction TileLink link (master view). InstBusAdapter translates the frontend's " +
        "ProgMemReq/ProgMemResp edges into Get requests on channel A and consumes " +
        "AccessAckData on channel D."
      )
      .uses(contTileLink)
      .is(rawReadyValidIntf)
      .note(
        "TL-UL at the uncached base point; the same link carries TL-UH bursts once an " +
        "instruction cache vertex fills whole blocks (hasBCE stays false - the I-side never " +
        "needs coherence ownership)."
      )
      .build()
  }

  val intfDataBus = spec {
    INTERFACE("DataBus")
      .desc(
        "Data TileLink link (master view). DataBusAdapter translates the memory subsystem's " +
        "DataMemReq/DataMemResp edges into Get/Put on A/D; with a coherent data cache " +
        "configured the link elaborates B/C/E and speaks Acquire/Probe/Release (TL-C)."
      )
      .uses(contTileLink)
      .is(rawReadyValidIntf)
      .note(
        "ADR-016: D-channel denied/corrupt is the only bus-side fault source; the adapter " +
        "converts it to the core's access-fault exception at the originating txnId/seqTag."
      )
      .build()
  }

  // Capability accommodations (ADR-016): parameter/spec seams that later vertices
  // plug into without a boundary change. None of these elaborate hardware today.
  val capPrivilegeModes = spec {
    CAPABILITY("PrivilegeModes")
      .desc(
        "Privilege-mode accommodation: M-mode always; U, S, and H modes are elaboration-time " +
        "options (PrivilegeParams). CSR ownership stays with TrapController (ADR-004), which " +
        "gains the per-mode trap/return files as the modes are enabled."
      )
      .note("Legality: S requires U; H requires S (enforced by a PrivilegeParams require).")
      .note(
        "Mode-dependent behavior enters at exactly two seams: TrapController (trap routing, " +
        "xRET, CSR views) and the TLB vertices (permission checks) - no other vertex may " +
        "branch on privilege."
      )
      .build()
  }

  val capAddressTranslation = spec {
    CAPABILITY("AddressTranslation")
      .desc(
        "Translation accommodation: optional ITLB/DTLB vertices sit on the ProgMemReq and " +
        "DataMemReq edges in front of the bus adapters, backed by a shared PTW that issues " +
        "its walks through the DataBusAdapter. Sv scheme follows xLen (Sv32 at 32, Sv39+ at 64)."
      )
      .note(
        "Translation faults are raised in-core by the TLB vertices and never appear on the " +
        "TileLink boundary; requires S-mode (satp) to elaborate."
      )
      .build()
  }

  val capCacheHierarchy = spec {
    CAPABILITY("CacheHierarchy")
      .desc(
        "Cache accommodation: optional ICache/DCache vertices replace the direct " +
        "adapter attachment on their respective edges. The ICache fills over TL-UH bursts; " +
        "the DCache owns lines coherently over TL-C (hasBCE), including Probe service on " +
        "channel B independent of core progress (TLChannelPriority)."
      )
      .uses(
        udacore.core.spec.modules.InstructionCacheSpecs.contInstructionCache,
        udacore.core.spec.modules.DataCacheSpecs.contDataCache
      )
      .note(
        "CacheParams (sets/ways/blockBytes) are contract-tier options defaulting to None " +
        "(the TCM point); the StoreBuffer drain contract (ADR-003) is unchanged - the cache " +
        "sits below it. Full vertex contracts: core/spec/modules/InstructionCacheSpecs.scala " +
        "and DataCacheSpecs.scala."
      )
      .build()
  }

  // Core Top Functions
  val funcBootSequencing = spec {
    FUNCTION("BootSequencing")
      .desc(
        "BootSequencer coordinates hart enable and boot address delivery to frontend pipeline."
      )
      .note(
        "Boot sequence ensures frontend receives valid start address synchronized with hart enable pulse."
      )
      .build()
  }

  val funcGlobalEpochManagement = spec {
    FUNCTION("GlobalEpochManagement")
      .desc(
        "GlobalEpochUnit maintains global epoch counter and broadcasts epoch value to all pipeline stages."
      )
      .note(
        "Epoch increments on redirect events, enabling automatic invalidation of stale pipeline tokens."
      )
      .note(
        "No explicit flush signals required - epoch mismatch filters tokens automatically."
      )
      .build()
  }

  val funcFrontendBackendDataflow = spec {
    FUNCTION("FrontendBackendDataflow")
      .desc(
        "CoreTop wires instruction issue from frontend to backend and redirect feedback from backend to frontend."
      )
      .note(
        "All inter-domain edges use Decoupled protocol with ready/valid handshake."
      )
      .note(
        "Exception: epoch broadcast uses rawNoDecoupled as it is globally injected wire."
      )
      .build()
  }

  val funcMemorySubsystemIntegration = spec {
    FUNCTION("MemorySubsystemIntegration")
      .desc(
        "CoreTop connects backend memory operation requests to memory subsystem and routes responses back."
      )
      .note(
        "Memory subsystem handles load/store unit operations and external data memory interface."
      )
      .build()
  }

  val funcStoreCommitCrossEdge = spec {
    FUNCTION("StoreCommitCrossEdge")
      .desc(
        "CoreTop wires BackendTop.storeCommitOut to MemorySubsystem.storeCommitIn as a Decoupled edge so the store buffer drains only committed stores in seqTag order."
      )
      .note(
        "ADR-003/ADR-013: the StoreCommit edge carries the bndCommitBroadcast StoreCommit view {seqTag, epoch}. Connected with the :<>= operator in the CoreTop rawTop body; committed store-buffer entries are epoch-exempt (ADR-005 D-5.2)."
      )
      .build()
  }

  val funcExternalMemoryBridge = spec {
    FUNCTION("ExternalMemoryBridge")
      .desc(
        "CoreTop instantiates the InstBusAdapter and DataBusAdapter vertices, which bridge " +
        "the internal ProgMemReq/Resp and DataMemReq/Resp edges onto the two TileLink links " +
        "(intfInstBus / intfDataBus)."
      )
      .note(
        "The adapters own source-id allocation (txnId <-> a.source), size/mask formation, and " +
        "D-channel denied/corrupt -> access-fault conversion. They are ordinary vertices: " +
        "pure dataflow, epoch-blind (requests already committed to the bus must complete)."
      )
      .note(
        "Cache and TLB vertices, when configured, splice into these internal edges " +
        "(capAddressTranslation / capCacheHierarchy); the TileLink boundary itself never changes."
      )
      .build()
  }

  val funcInterruptDebugIntegration = spec {
    FUNCTION("InterruptDebugIntegration")
      .desc(
        "CoreTop delivers interrupt and debug request signals directly to backend trap controller."
      )
      .note(
        "Interrupt signals (external, timer, software) bypass Decoupled protocol as they are async events."
      )
      .note(
        "Debug request triggers trap controller to generate redirect to debug handler."
      )
      .build()
  }

  // Core Top Doctrine (design philosophy, not machine-checkable properties;
  // RAW/DOCTRINE per the arbitration ruling - see propGraphConsistency owners).
  val propEpochBasedSpeculation = spec {
    RAW("EpochBasedSpeculation", "DOCTRINE")
      .desc(
        "Core implements epoch-based speculation instead of traditional flush signal approach."
      )
      .note(
        "When speculation fails, epoch counter increments and stale data is automatically filtered."
      )
      .note(
        "Every pipeline token carries epoch value for comparison with global epoch."
      )
      .build()
  }

  val propUnifiedDataflowArchitecture = spec {
    RAW("UnifiedDataflowArchitecture", "DOCTRINE")
      .desc(
        "All components are vertices in dataflow graph connected by ready/valid edges."
      )
      .note(
        "Function and performance characteristics are completely separated through graph-native design."
      )
      .note(
        "Raw top modules contain only wiring using :<>= operator, no behavioral logic."
      )
      .build()
  }

  val propFrontendBackendSeparation = spec {
    RAW("FrontendBackendSeparation", "DOCTRINE")
      .desc(
        "Frontend handles PC generation and instruction fetch, backend handles execution and redirect generation."
      )
      .note(
        "Clean interface boundary with explicit redirect and epoch signals enables independent optimization."
      )
      .build()
  }

  val propParametricConfiguration = spec {
    RAW("ParametricConfiguration", "DOCTRINE")
      .desc(
        "Core top exposes parametric interfaces allowing system-level configuration."
      )
      .note(
        "Memory bus widths, cache sizes, queue depths are configurable through CoreParams."
      )
      .note(
        "ISA extensions (M, C) can be enabled/disabled via parameters."
      )
      .build()
  }
}
