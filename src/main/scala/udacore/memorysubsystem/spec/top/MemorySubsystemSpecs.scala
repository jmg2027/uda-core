package udacore.memorysubsystem.spec.top

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.core.spec.shared.CoreBundlesSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs._

object MemorySubsystemSpecs {
  val contMemorySubsystem = spec {
    CONTRACT("MemorySubsystemTop")
      .desc("""Memory subsystem top level contract.
              | Wires the committed-memory dataflow: dispatcher splits load/
              | store streams, the StoreBuffer is the sole ordering authority
              | (ADR-003), the controller merges external traffic, and the
              | response arbiter reunites results for the backend. Boundary
              | edges: memory op req in / resp out, store commit in, external
              | data memory req/resp (terminating at CoreTop's DataBusAdapter),
              | plus the global epoch broadcast.
              | rawTop: vertex instantiation and :<>= edge wiring only.
              """)
      .is(rawTop)
      .has(
        intfMemorySubsystemReqIn,
        intfStoreCommitIn,
        intfGlobalEpochIn,
        intfExternalDataMemoryRespIn,
        intfMemorySubsystemRespOut,
        intfExternalDataMemoryReqOut
      )
      .draw(
        "mermaid",
        """
  graph LR
    %% Legend
    %% -.-> : Normal Wire
    %% Normal Wires are forbidden except for from or to outside core top
    %% --> : Decoupled Edge
    %% [] : Normal Module
    %% [[]] : Top Module (rawTop)

    %% Inputs
    subgraph inputs_group[Inputs]
        in_anchor:::hidden
        memreq@{shape: text, label: MemorySubsystemReq}
        scommit@{shape: text, label: StoreCommit}
        ep@{shape: text, label: GlobalEpoch}
        edmresp@{shape: text, label: ExternalDataMemoryResp}
    end

    dis[MemoryDispatcher]
    su[StoreUnit]
    sb[StoreBuffer]
    lu[LoadUnit]
    con[MemoryController]
    arb[ResponseArbiter]

    memreq ---> dis
    scommit ---> sb
    edmresp ---> con
    %% Epoch signals are globally injected to nodes where epoch filtering is needed
    ep -.-> MemorySubsystemTop

    subgraph MemorySubsystemTop
        direction LR
        dis -- LoadReq --> lu
        dis -- StoreReq --> su
        su -- StoreEnqueue --> sb
        lu -- FwdQuery --> sb
        sb -- FwdData --> lu
        sb -- WriteReq --> con
        lu -- ReadReq --> con
        con -- ReadResp --> lu
        con -- WriteAck --> sb
        sb -- StoreComplete --> arb
        lu -- LoadResp --> arb
    end

    con ---> edmreq
    arb ---> memresp

    subgraph outputs_group[Outputs]
        memresp@{shape: text, label: MemorySubsystemResp}
        edmreq@{shape: text, label: ExternalDataMemoryReq}
    end

    style inputs_group fill:transparent,stroke:transparent
    style outputs_group fill:transparent,stroke:transparent
  """.stripMargin
      )
      .note(
        "ADR-003 D-3.12: the StoreBuffer is the sole ordering authority; the dispatcher is a splitter and the controller a stateless merge. Ordering is enforced in exactly one vertex."
      )
      .note(
        "ADR-015 check 1 (propGraphConsistency): the edges drawn here reconcile edge-for-edge with the union of child module INTERFACE sets. StoreBuffer has StoreDispatchIn/StoreCommitIn/LoadFwdQuery/LoadFwdData/ControllerWriteOut/ControllerWriteAckIn/StoreCompleteOut/GlobalEpochIn; the con -- WriteAck --> sb edge is MemoryController.WriteAckOut -> StoreBuffer.ControllerWriteAckIn, and con -- WriteReq --> sb-side is StoreBuffer.ControllerWriteOut -> MemoryController.WriteReqIn."
      )
      .build()
  }

  // ADR-003 D-3.3 / cross-domain: commit broadcast StoreCommit view routed to the StoreBuffer.
  val intfStoreCommitIn = spec {
    INTERFACE("StoreCommitIn")
      .desc("Commit broadcast StoreCommit view from BackendTop.CommitUnit that marks a buffered store irrevocable.")
      .uses(bndCommitBroadcast)
      .is(rawReadyValidIntf)
      .note(
        "ADR-003 cross-domain edge (M1): CoreTop wires BackendTop.storeCommitOut -> MemorySubsystem.storeCommitIn (:<>=); without it stores cannot be gated at commit."
      )
      .build()
  }

  // Memory Subsystem Top Interfaces (from mermaid diagram)
  val intfMemorySubsystemReqIn = spec {
    INTERFACE("MemorySubsystemReqIn")
      .desc("Memory subsystem request input.")
      .uses(bndMemoryOpReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfGlobalEpochIn = spec {
    INTERFACE("GlobalEpochIn")
      .desc("Global epoch broadcast input.")
      .uses(bndGlobalEpoch)
      .is(rawNoDecoupled)
      .build()
  }

  // ADR-016: the external data memory edges now terminate at CoreTop's DataBusAdapter
  // (TileLink bridge); the subsystem keeps its own request/response bundles and the
  // adapter owns the TileLink translation. A coherent DCache, when configured, splices
  // between MemoryController and the adapter (capCacheHierarchy).
  val intfExternalDataMemoryRespIn = spec {
    INTERFACE("ExternalDataMemoryRespIn")
      .desc("External data memory response input.")
      .uses(bndExternalDataMemoryResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMemorySubsystemRespOut = spec {
    INTERFACE("MemorySubsystemRespOut")
      .desc("Memory subsystem response output.")
      .uses(bndMemoryOpResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfExternalDataMemoryReqOut = spec {
    INTERFACE("ExternalDataMemoryReqOut")
      .desc("External data memory request output.")
      .uses(bndExternalDataMemoryReq)
      .is(rawReadyValidIntf)
      .build()
  }
}
