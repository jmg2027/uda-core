package udacore.core.spec.top

import framework.macros.SpecEmit.spec
import framework.specs.Spec._

import udacore.common.spec.DesignRuleSpecs._
import udacore.common.spec.ProductSpecs._
import udacore.common.tilelink.TileLinkSpecs._
import udacore.core.spec.shared.CoreBundlesSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._

/** CoreTop: the ADR-019 conventional OoO core graph.
  *
  * The frontend and backend rawTops, the MMU (ITLB, DTLB, shared PTW), the VIPT
  * L1 caches, the boot sequencer, and the two TileLink bus adapters. CoreTop is
  * wiring only; its boundary is unchanged from ADR-016: boot/enable statics,
  * interrupt/debug lines, and two TileLink master links.
  */
object CoreTopSpecs {
  val contCoreTop = spec {
    CONTRACT("CoreTop")
      .desc("""Core top level contract (rawTop: vertex instantiation and :<>= wiring only).
              | BootSequencer seeds the frontend fetch PC. FrontendTop predicts and fetches through
              | the InstructionTlb and the VIPT InstructionCache; BackendTop decodes, renames,
              | executes out of order, and commits in order, reaching memory through the DataTlb,
              | the VIPT DataCache, and its committed StoreBuffer. The shared PageTableWalker
              | serves both TLBs with physical PTE reads through the DataCache. InstBusAdapter and
              | DataBusAdapter bridge the caches to the two TileLink links. The backend
              | RecoveryEvent is the single selective-recovery broadcast back into the frontend.
              """)
      .is(rawTop)
      .uses(rawUdacoreProduct, rawUDAMethodology, rawParametricISA, rawReferencePipeline, propGraphConsistency)
      .has(
        intfBootAddrIn,
        intfHartEnIn,
        intfInterruptIn,
        intfDebugReqIn,
        intfInstBus,
        intfDataBus,
        intfRetireStreamOut,
        capPrivilegeModes,
        capAddressTranslation,
        capCacheHierarchy,
        capDataCoherence,
        rawSelectiveRecoveryDoctrine,
        rawUnifiedDataflowDoctrine
      )
      .draw(
        "mermaid",
        """
      | graph LR
      |     %% Legend
      |     %% -.-> : rawNoDecoupled (boundary statics/async lines, RecoveryEvent, committed views)
      |     %% --> : Decoupled edge (ready/valid)
      |     %% [] : vertex   [[]] : rawTop
      |
      |     subgraph inputs_group[Inputs]
      |         in_anchor:::hidden
      |         bootaddr@{shape: text, label: BootAddr}
      |         en@{shape: text, label: hartEn}
      |         irq@{shape: text, label: Interrupt}
      |         debugreq@{shape: text, label: DebugReq}
      |     end
      |
      |     boot[BootSequencer]
      |     fe[[FrontendTop]]
      |     be[[BackendTop]]
      |     itlb[InstructionTlb]
      |     ic[InstructionCache]
      |     dtlb[DataTlb]
      |     dc[DataCache]
      |     ptw[PageTableWalker]
      |     iba[InstBusAdapter]
      |     dba[DataBusAdapter]
      |
      |     bootaddr -.-> boot
      |     en -.-> boot
      |     irq -.-> be
      |     debugreq -.-> be
      |
      |     subgraph CoreTop
      |         direction LR
      |         boot -- BootAddr --> fe
      |         fe -- FetchPacket --> be
      |         be -- FtqCommit --> fe
      |         be -. RecoveryEvent .-> fe
      |
      |         fe -- ITlbReq --> itlb
      |         fe -- ICacheReq --> ic
      |         itlb -- ICacheTranslation --> ic
      |         ic -- ICacheResp --> fe
      |         be -- ICacheInvalidate --> ic
      |
      |         be -- DtlbReq --> dtlb
      |         be -- DCacheLoadReq --> dc
      |         dtlb -- DCacheTranslation --> dc
      |         dc -- DCacheLoadResp --> be
      |         be -- UncachedStoreReq --> dc
      |         dc -- UncachedStoreResp --> be
      |         be -- UncachedLoadReq --> dc
      |         dc -- UncachedLoadResp --> be
      |         dtlb -- DtlbStoreResp --> be
      |         dtlb -- DtlbRefill --> be
      |         be -- StoreDrainReq --> dc
      |         dc -- StoreDrainResp --> be
      |         be -- DCacheCleanReq --> dc
      |         dc -- DCacheCleanResp --> be
      |
      |         itlb -- ItlbWalkReq --> ptw
      |         ptw -- ItlbWalkResp --> itlb
      |         dtlb -- DtlbWalkReq --> ptw
      |         ptw -- DtlbWalkResp --> dtlb
      |         ptw -- PtwMemReq --> dc
      |         dc -- PtwMemResp --> ptw
      |         be -- SfenceVma --> ptw
      |         ptw -- ItlbFlush --> itlb
      |         ptw -- DtlbFlush --> dtlb
      |         be -. TranslationContext .-> itlb & dtlb
      |
      |         ic -- InstMemReq --> iba
      |         iba -- InstMemResp --> ic
      |         dc -- DataMemReq --> dba
      |         dba -- DataMemResp --> dc
      |     end
      |
      |     iba --> instbus
      |     dba --> databus
      |     be --> retire
      |
      |     subgraph outputs_group[Outputs]
      |         instbus@{shape: text, label: InstBus}
      |         databus@{shape: text, label: DataBus}
      |         retire@{shape: text, label: RetireStream}
      |     end
      |
      |     style inputs_group fill:transparent,stroke:transparent
      |     style outputs_group fill:transparent,stroke:transparent
      """
      )
      .note(
        "Edge reconciliation (propGraphConsistency) by child INTERFACE: BootSequencer.BootAddrOut " +
        "-> FrontendTop.BootAddrIn; FrontendTop.FetchPacketOut -> BackendTop.FetchPacketIn; " +
        "BackendTop.{FtqCommitOut, RecoveryEventOut} -> FrontendTop.{FtqCommitIn, " +
        "RecoveryEventIn}; FrontendTop.{ITlbReqOut, ICacheReqOut} -> InstructionTlb.ITlbReqIn, " +
        "InstructionCache.ICacheReqIn; InstructionTlb.ICacheTranslationOut -> " +
        "InstructionCache.ICacheTranslationIn; InstructionCache.ICacheRespOut -> " +
        "FrontendTop.ICacheRespIn; BackendTop.{DtlbReqOut, DCacheLoadReqOut, StoreDrainReqOut, " +
        "DCacheCleanReqOut, ICacheInvalidateOut, SfenceVmaOut, TranslationContextOut} -> " +
        "DataTlb/DataCache/InstructionCache/PageTableWalker/both TLBs; DataTlb." +
        "{DCacheTranslationOut, DtlbStoreRespOut, DtlbRefillOut}; DataCache.{DCacheLoadRespOut, " +
        "StoreDrainRespOut, DCacheCleanRespOut} -> BackendTop; TLB walk and flush edges <-> " +
        "PageTableWalker; PageTableWalker.PtwMemReqOut <-> DataCache.PtwMemReqIn/RespOut; cache " +
        "memory edges <-> bus adapters; adapters -> InstBus/DataBus."
      )
      .note("Roadmap (not in v0): power management, CLIC, TL-C coherence, RV64, compressed instructions.")
      .build()
  }

  val intfBootAddrIn = spec {
    INTERFACE("BootAddrIn")
      .desc("Boot address input.")
      .uses(bndBootAddr)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 3.")
      .build()
  }

  val intfHartEnIn = spec {
    INTERFACE("HartEnIn")
      .desc("Hart enable signal input.")
      .uses(bndHartEnable)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 3.")
      .build()
  }

  val intfInterruptIn = spec {
    INTERFACE("InterruptIn")
      .desc("Interrupt source lines, wired to BackendTop.")
      .uses(bndInterrupt)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 2.")
      .build()
  }

  val intfDebugReqIn = spec {
    INTERFACE("DebugReqIn")
      .desc("Debug request line, wired to BackendTop.")
      .uses(bndDebugReq)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 2.")
      .build()
  }

  val intfInstBus = spec {
    INTERFACE("InstBus")
      .desc("Instruction TileLink master link, driven by the InstBusAdapter (Get only; TL-UH line fills).")
      .uses(contTileLink, paramTLLinkDerivation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDataBus = spec {
    INTERFACE("DataBus")
      .desc("Data TileLink master link, driven by the DataBusAdapter (TL-UH in v0; TL-C only when DataCoherence).")
      .uses(contTileLink, paramTLLinkDerivation, paramDataCoherence)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRetireStreamOut = spec {
    INTERFACE("RetireStreamOut")
      .desc(
        "Verification-only retire stream (ADR-010), wired from BackendTop and elaborated only " +
        "when usingRvvi; it is the harness observation point for propIsaRetireEquivalence and " +
        "is absent from the product boundary."
      )
      .note(
        "ADR-019B E-3/E-5: each token is a retirement or precise trap-entry observation event; " +
        "wdata comes from the backend-internal CommitUnit -> PhysicalRegisterFile commit read " +
        "(CommitPrfReadReq/Resp), which elaborates only with this port."
      )
      .uses(paramUsingRvvi)
      .is(rawReadyValidIntf)
      .build()
  }

  val capPrivilegeModes = spec {
    CAPABILITY("PrivilegeModes")
      .desc(
        "M, S, and U modes (v0). Privilege-dependent behavior enters at exactly three seams: " +
        "DecodeUnit (privileged-instruction legality), TrapController/CsrController (trap " +
        "routing, delegation, xRET, CSR access), and the TLBs (translation mode and permission)."
      )
      .uses(paramPrivilegeModes)
      .build()
  }

  val capAddressTranslation = spec {
    CAPABILITY("AddressTranslation")
      .desc(
        "Sv32 through the InstructionTlb, the DataTlb, and the shared PageTableWalker, all " +
        "present in v0. Translation faults are precise and never appear on the TileLink boundary."
      )
      .uses(
        udacore.core.spec.modules.InstructionTlbSpecs.contInstructionTlb,
        udacore.core.spec.modules.DataTlbSpecs.contDataTlb,
        udacore.core.spec.modules.PageTableWalkerSpecs.contPageTableWalker
      )
      .build()
  }

  val capCacheHierarchy = spec {
    CAPABILITY("CacheHierarchy")
      .desc("VIPT L1 InstructionCache and DataCache, both present in v0 (16 KiB, 4-way, 64-byte lines).")
      .uses(
        udacore.core.spec.modules.InstructionCacheSpecs.contInstructionCache,
        udacore.core.spec.modules.DataCacheSpecs.contDataCache
      )
      .build()
  }

  val capDataCoherence = spec {
    CAPABILITY("DataCoherence")
      .desc(
        "Coherence is a separate capability from cache presence (ADR-019 D-19.13). v0 is " +
        "non-coherent TL-UH; enabling TL-C later changes only the DataCache/DataBusAdapter " +
        "internals and the link parameters, never the backend, LSQ, or MMU interfaces."
      )
      .uses(paramDataCoherence)
      .build()
  }

  val rawSelectiveRecoveryDoctrine = spec {
    RAW("SelectiveRecovery", "DOCTRINE")
      .desc(
        "Branch mispredictions are recovered at execute: the RecoveryEvent names the recovering " +
        "branch by robTag and every speculative holder discards only younger work, so older " +
        "correct-path uops survive. Commit-head architectural redirects discard the whole " +
        "window. There is no global epoch and no flush wire."
      )
      .build()
  }

  val rawUnifiedDataflowDoctrine = spec {
    RAW("UnifiedDataflow", "DOCTRINE")
      .desc(
        "All components are vertices connected by ready/valid edges; rawTop modules contain " +
        "only :<>= wiring; broadcast facts use only the sanctioned rawNoDecoupled classes."
      )
      .build()
  }
}
