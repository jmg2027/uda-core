package udacore.backend.spec.top

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.frontend.spec.shared.FrontendBundlesSpecs.bndFetchPacket
import udacore.core.spec.shared.CoreBundlesSpecs.{bndDebugReq, bndInterrupt}
import udacore.core.spec.shared.MemoryBundlesSpecs._

/** BackendTop: explicit-ROB out-of-order backend (ADR-019 D-19.7..D-19.9).
  *
  * decode(2) -> rename(1) -> {ROB, RS, LSQ} -> select -> execute (OoO) ->
  * publish -> ROB completion -> in-order commit. Execute-time branch recovery
  * and commit-head architectural redirects meet in the RecoveryController,
  * whose RecoveryEvent is the only squash fact in the core.
  */
object BackendTopSpecs {
  val contBackendTop = spec {
    CONTRACT("BackendTop")
      .desc("""Backend top level contract (rawTop: vertex instantiation and :<>= wiring only).
              | DecodeUnit decodes two instructions per cycle; RenameUnit allocates one uop per
              | cycle into the ReorderBuffer, the ReservationStation, and the LoadStoreQueue;
              | the RS selects ready uops out of order; execution units and the LSQ publish
              | through the single PublishMux lane into the PRF, the wakeup broadcast, and ROB
              | completion; CommitUnit retires from the ROB head in order. The BranchUnit and
              | the TrapController request recovery from the RecoveryController, which
              | broadcasts the RecoveryEvent to every speculative holder here and in the frontend.
              | Memory translation and caches are CoreTop-level vertices reached over the
              | DTLB/D-cache/StoreBuffer boundary edges.
              """)
      .is(rawTop)
      .has(
        intfFetchPacketIn,
        intfInterruptIn,
        intfDebugReqIn,
        intfDtlbStoreRespIn,
        intfDtlbRefillIn,
        intfDCacheLoadRespIn,
        intfUncachedStoreRespIn,
        intfUncachedLoadRespIn,
        intfStoreDrainRespIn,
        intfDCacheCleanRespIn,
        intfRecoveryEventOut,
        intfFtqCommitOut,
        intfDtlbReqOut,
        intfDCacheLoadReqOut,
        intfUncachedStoreReqOut,
        intfUncachedLoadReqOut,
        intfStoreDrainReqOut,
        intfTranslationContextOut,
        intfSfenceVmaOut,
        intfICacheInvalidateOut,
        intfDCacheCleanReqOut,
        intfRetireStreamOut
      )
      .uses(propGraphConsistency)
      .draw(
        "mermaid",
        """
    graph LR
    %% Legend
    %% -.-> : rawNoDecoupled (RecoveryEvent class 6, wakeup class 5, committed views class 4, async class 2)
    %% --> : Decoupled edge (ready/valid)
    %% [] : vertex   [[]] : rawTop

    subgraph inputs_group[Inputs]
        in_anchor:::hidden
        packet@{shape: text, label: FetchPacket}
        irq@{shape: text, label: Interrupt}
        debugreq@{shape: text, label: DebugReq}
        dtlbsresp@{shape: text, label: DtlbStoreResp}
        dtlbrefill@{shape: text, label: DtlbRefill}
        dcresp@{shape: text, label: DCacheLoadResp}
        ucresp@{shape: text, label: UncachedStoreResp}
        ulresp@{shape: text, label: UncachedLoadResp}
        sdresp@{shape: text, label: StoreDrainResp}
        dccresp@{shape: text, label: DCacheCleanResp}
    end

    dec[DecodeUnit]
    rn[RenameUnit]
    rob[ReorderBuffer]
    rs[ReservationStation]
    prf[PhysicalRegisterFile]
    dis[DispatchUnit]
    alu[AluUnit]
    balu[BitAluUnit]
    mul[MultiplierUnit]
    div[DividerUnit]
    bru[BranchUnit]
    agu[AddressGenerationUnit]
    csr[CsrController]
    pub[PublishMux]
    lsq[LoadStoreQueue]
    sb[StoreBuffer]
    com[CommitUnit]
    trap[TrapController]
    rc[RecoveryController]

    packet --> dec
    irq -.-> csr
    debugreq -.-> com
    dtlbsresp --> lsq
    dtlbrefill --> lsq
    dcresp --> lsq
    ucresp --> lsq
    ulresp --> lsq
    sdresp --> sb
    dccresp --> com

    subgraph BackendTop
        direction LR
        dec -- DecodedPacket --> rn
        rn -- RobAlloc --> rob
        rn -- RsAlloc --> rs
        rn -- LsqAlloc --> lsq

        rs -- RegisterFileReadReq --> prf
        prf -- RegisterFileReadResp --> rs
        rs -- IssuedUop --> dis

        dis -- AluReq --> alu
        dis -- BitAluReq --> balu
        dis -- MultiplierReq --> mul
        dis -- DividerReq --> div
        dis -- BranchUnitReq --> bru
        dis -- AddressGenerationReq --> agu
        dis -- CsrReq --> csr

        agu -- MemAddress --> lsq

        alu -- AluResult --> pub
        balu -- BitAluResult --> pub
        mul -- MultiplierResult --> pub
        div -- DividerResult --> pub
        bru -- BranchResult --> pub
        csr -- CsrResult --> pub
        lsq -- MemResult --> pub

        pub -- PhysicalRegWrite --> prf
        pub -. WakeupBroadcast .-> rs & rn
        pub -- RobCompletion --> rob

        rob -- RobHead --> com
        rob -. RobStatus .-> rn & lsq

        com -- RenameCommit --> rn
        com -- StoreCommit --> lsq
        lsq -- CommittedStore --> sb
        lsq -- StoreForwardQuery --> sb
        sb -- StoreForwardData --> lsq
        com -- StoreBufferDrainReq --> sb
        sb -- StoreBufferDrainResp --> com
        com -- HeadMemGrant --> lsq
        sb -. StoreBufferEmpty .-> lsq
        com -. CommitGrant .-> csr
        csr -. InterruptCtrl .-> com

        com -- Exception --> trap
        csr -. CSRTrapRead .-> trap
        trap -- CSRTrapWrite --> csr

        trap -- ArchRedirect --> rc
        bru -- BranchResolution --> rc
        bru -- CheckpointRelease --> rn
        rc -. RecoveryEvent .-> dec & rn & rob & rs & dis & com
        rc -. RecoveryEvent .-> alu & balu & mul & div & bru & agu & lsq
    end

    rc -.-> recovery
    com --> ftqcommit
    lsq --> dtlbreq
    lsq --> dcreq
    lsq --> ucreq
    lsq --> ulreq
    sb --> sdreq
    csr -.-> tctx
    com --> sfence
    com --> icinv
    com --> dccreq
    com --> retire

    subgraph outputs_group[Outputs]
        recovery@{shape: text, label: RecoveryEvent}
        ftqcommit@{shape: text, label: FtqCommit}
        dtlbreq@{shape: text, label: DtlbReq}
        dcreq@{shape: text, label: DCacheLoadReq}
        ucreq@{shape: text, label: UncachedStoreReq}
        ulreq@{shape: text, label: UncachedLoadReq}
        sdreq@{shape: text, label: StoreDrainReq}
        tctx@{shape: text, label: TranslationContext}
        sfence@{shape: text, label: SfenceVma}
        icinv@{shape: text, label: ICacheInvalidate}
        dccreq@{shape: text, label: DCacheCleanReq}
        retire@{shape: text, label: RetireStream}
    end

    style inputs_group fill:transparent,stroke:transparent
    style outputs_group fill:transparent,stroke:transparent
        """.stripMargin
      )
      .note(
        "Edge reconciliation (propGraphConsistency) by child INTERFACE: DecodeUnit." +
        "DecodedPacketOut -> RenameUnit.DecodedPacketIn; RenameUnit.{RobAllocOut, RsAllocOut, " +
        "LsqAllocOut} -> {ReorderBuffer.RobAllocIn, ReservationStation.RsAllocIn, " +
        "LoadStoreQueue.LsqAllocIn}; RS.RegisterFileReadReqOut <-> PRF.RegisterFileRead*; " +
        "RS.IssuedUopOut -> DispatchUnit.IssuedUopIn; DispatchUnit.*ReqOut -> each unit's " +
        "*ReqIn; AGU.MemAddressOut -> LSQ.MemAddressIn; unit *ResultOut and LSQ.MemResultOut -> " +
        "PublishMux.*ResultIn/MemResultIn; PublishMux.{PhysicalRegWriteOut, " +
        "WakeupBroadcastOut, RobCompletionOut} -> PRF/RS+Rename/ROB; ROB.RobHeadOut -> " +
        "CommitUnit.RobHeadIn; ROB.RobStatusOut -> Rename/LSQ.RobStatusIn; CommitUnit." +
        "{RenameCommitOut, StoreCommitOut, StoreBufferDrainReqOut, CommitGrantOut} -> " +
        "Rename.RenameCommitIn, LSQ.StoreCommitIn, StoreBuffer.StoreBufferDrainReqIn, Csr.CommitGrantIn; " +
        "StoreBuffer.StoreBufferDrainRespOut -> CommitUnit.StoreBufferDrainRespIn; LSQ.{CommittedStoreOut, " +
        "StoreForwardQueryOut} -> StoreBuffer; StoreBuffer.StoreForwardDataOut -> LSQ; " +
        "CommitUnit.ExceptionOut -> Trap.ExceptionIn; Csr.CSRTrapReadOut -> Trap; " +
        "Trap.CSRTrapWriteOut -> Csr; Trap.ArchRedirectOut and BranchUnit." +
        "BranchResolutionOut -> RecoveryController; RecoveryController.RecoveryEventOut -> " +
        "every *RecoveryEventIn and the boundary."
      )
      .note(
        "BitAluUnit and its edges elaborate only when the bit-manipulation extension is " +
        "enabled (ADR-017); the v0 reference configuration omits them."
      )
      .build()
  }

  val intfFetchPacketIn = spec {
    INTERFACE("FetchPacketIn")
      .desc("Instruction packets from the frontend FetchBuffer, wired to DecodeUnit.")
      .uses(bndFetchPacket)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfInterruptIn = spec {
    INTERFACE("InterruptIn")
      .desc("Raw interrupt lines, wired to the CsrController mip.")
      .uses(bndInterrupt)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 2.")
      .build()
  }

  val intfDebugReqIn = spec {
    INTERFACE("DebugReqIn")
      .desc("Debug request line, wired to the CommitUnit (sampled at a precise retire boundary).")
      .uses(bndDebugReq)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 2.")
      .build()
  }

  val intfDtlbStoreRespIn = spec {
    INTERFACE("DtlbStoreRespIn")
      .desc("DataTlb answers for store-address translations, wired to the LSQ.")
      .uses(bndTranslation)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbRefillIn = spec {
    INTERFACE("DtlbRefillIn")
      .desc("DataTlb refill notices, wired to the LSQ.")
      .uses(bndWalkResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheLoadRespIn = spec {
    INTERFACE("DCacheLoadRespIn")
      .desc("DataCache load answers, wired to the LSQ.")
      .uses(bndDCacheLoadResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfUncachedLoadRespIn = spec {
    INTERFACE("UncachedLoadRespIn")
      .desc("DataCache answers of uncached loads, wired to the LSQ.")
      .uses(bndUncachedLoadResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfUncachedLoadReqOut = spec {
    INTERFACE("UncachedLoadReqOut")
      .desc("Granted uncacheable loads from the LSQ to the DataCache uncached port.")
      .uses(bndUncachedLoadReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfUncachedStoreRespIn = spec {
    INTERFACE("UncachedStoreRespIn")
      .desc("DataCache acknowledgements of uncached stores, wired to the LSQ.")
      .uses(bndUncachedStoreResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfUncachedStoreReqOut = spec {
    INTERFACE("UncachedStoreReqOut")
      .desc("Granted uncacheable stores from the LSQ to the DataCache uncached port.")
      .uses(bndUncachedStoreReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreDrainRespIn = spec {
    INTERFACE("StoreDrainRespIn")
      .desc("DataCache store-drain completions, wired to the StoreBuffer.")
      .uses(bndStoreDrainResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheCleanRespIn = spec {
    INTERFACE("DCacheCleanRespIn")
      .desc("DataCache clean-all completion, wired to the CommitUnit.")
      .uses(bndCacheMaintenance)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRecoveryEventOut = spec {
    INTERFACE("RecoveryEventOut")
      .desc("The RecoveryEvent broadcast toward the frontend.")
      .uses(bndRecoveryEvent)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 6.")
      .build()
  }

  val intfFtqCommitOut = spec {
    INTERFACE("FtqCommitOut")
      .desc("Block-commit notices to the frontend FetchTargetQueue.")
      .uses(bndFtqCommit)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbReqOut = spec {
    INTERFACE("DtlbReqOut")
      .desc("LSQ translation requests to the CoreTop-level DataTlb.")
      .uses(bndTranslateReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheLoadReqOut = spec {
    INTERFACE("DCacheLoadReqOut")
      .desc("LSQ load lookups to the CoreTop-level DataCache.")
      .uses(bndDCacheLoadReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreDrainReqOut = spec {
    INTERFACE("StoreDrainReqOut")
      .desc("Committed store drains from the StoreBuffer to the DataCache.")
      .uses(bndStoreDrainReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfTranslationContextOut = spec {
    INTERFACE("TranslationContextOut")
      .desc("Committed translation context from the CsrController to the ITLB, DTLB, and PTW.")
      .uses(bndTranslationContext)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 4.")
      .build()
  }

  val intfSfenceVmaOut = spec {
    INTERFACE("SfenceVmaOut")
      .desc("SFENCE.VMA tokens from the CommitUnit to the PageTableWalker.")
      .uses(bndTlbFlush)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfICacheInvalidateOut = spec {
    INTERFACE("ICacheInvalidateOut")
      .desc("FENCE.I invalidate tokens from the CommitUnit to the InstructionCache.")
      .uses(bndCacheMaintenance)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRetireStreamOut = spec {
    INTERFACE("RetireStreamOut")
      .desc("Verification retire stream from the CommitUnit (ADR-010), elaborated only when usingRvvi.")
      .uses(bndRetireToken)
      .is(rawReadyValidIntf)
      .note("Observation-only: the harness ties ready high (propRetireNonBlocking).")
      .build()
  }

  val intfDCacheCleanReqOut = spec {
    INTERFACE("DCacheCleanReqOut")
      .desc("FENCE.I clean-all requests from the CommitUnit to the DataCache.")
      .uses(bndCacheMaintenance)
      .is(rawReadyValidIntf)
      .build()
  }
}
