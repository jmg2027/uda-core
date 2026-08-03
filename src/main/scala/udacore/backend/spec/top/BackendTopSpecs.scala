package udacore.backend.spec.top

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.core.spec.shared.CoreBundlesSpecs.{bndGlobalEpoch, bndDebugReq}

object BackendTopSpecs {
  val contBackendTop = spec {
    CONTRACT("BackendTop")
      .desc("""Backend top level contract.
              | Wires the execution graph: decode -> rename -> reservation
              | station -> dispatch -> functional units -> publish -> commit,
              | with the trap/CSR pair and the redirect merge. Owns the
              | backend boundary edges (instruction issue in, memory op
              | req/resp, store commit out, redirect out) and receives the
              | global epoch broadcast plus the raw interrupt/debug lines.
              | rawTop: vertex instantiation and :<>= edge wiring only.
              """)
      .is(rawTop)
      .has(
        intfInstructionIssueIn,
        intfGlobalEpochIn,
        intfInterruptIn,
        intfDebugReqIn,
        intfMemoryOpRespIn,
        intfRedirectOut,
        intfMemoryOpReqOut,
        intfStoreCommitOut
      )
      .draw(
        "mermaid",
        """
        graph LR
    %% Legend
    %% -.-> : Normal Wire (scalar signals, epoch)
    %% Normal Wires are forbidden except for from or to outside core top
    %% --> : Decoupled Edge (ready/valid)
    %% [] : Normal Module
    %% [[]] : Top Module (rawTop)

    %% Inputs
    subgraph inputs_group[Inputs]
        in_anchor:::hidden
        issue@{shape: text, label: InstructionIssue}
        ep@{shape: text, label: GlobalEpoch}
        irq@{shape: text, label: Interrupt}
        debugreq@{shape: text, label: DebugReq}
        memres@{shape: text, label: MemoryOpResp}
    end

    dec[DecodeUnit]
    rn[RenameUnit]
    rs[ReservationStation]
    dis[DispatchUnit]
    %% Functional Units
    alu[ALU]
    balu[BitALU]
    mul[MultiplierUnit]
    div[DividerUnit]
    agu[AddressGenerationUnit]
    bru[BranchUnit]
    csr[CSRController]

    pub[PublishMux]
    prf[PhysicalRegisterFile]

    trap[TrapController]
    com[CommitUnit]
    ru[RedirectUnit]

    issue -- InstructionIssue --> dec
    irq -. Interrupt .-> trap
    debugreq -. DebugReq .-> trap

    %% Epoch signals are globally injected to nodes where epoch filtering is needed
    ep -.-> BackendTop

    %% Unified PRF Architecture: Map Table based, no data writeback on commit
    %% PublishMux is Result Bus for all FU outputs
    subgraph BackendTop
        direction LR
        dec -- DecodedUop --> rn
        rn -- RenamedUop --> rs

        %% RS <-> PRF (operand read)
        rs -- RegisterFileReadReq --> prf
        prf -- RegisterFileReadResp --> rs

        rs -- DispatchedUop --> dis

        rn -- DecodedUopAlloc --> com

        %% Dispatch to Functional Units
        dis -- ALUReq --> alu
        dis -- BitALUReq --> balu
        dis -- MultiplierReq --> mul
        dis -- DividerReq --> div
        dis -- BranchUnitReq --> bru
        dis -- CSRReq --> csr
        dis -- AddressGenerationReq --> agu

        %% All FU results to PublishMux (Result Bus)
        alu -- ALUResult --> pub
        balu -- BitALUResult --> pub
        mul -- MultiplierResult --> pub
        div -- DividerResult --> pub
        bru -- BranchUnitResult --> pub
        csr -- CSRResult --> pub
        memres -- MemoryOpResp --> pub

        %% PublishMux outputs
        pub -- PhysicalRegWrite --> prf
        pub -- WakeupBroadcast --> rs
        pub -- PublishResult --> com

        %% Commit feedback to Rename (no data writeback)
        com -- MapTableUpdate --> rn
        com -- PhysicalRegFree --> rn

        %% ADR-001: architectural-map snapshot restores the speculative map on redirect
        com -. ArchMapRestore .-> rn

        csr -- CSRTrapRead --> trap
        trap -- CSRTrapWrite --> csr
        com -- Exception --> trap
        trap -- Redirect --> ru
        %% ADR-013 D-13.3: branch-mispredict producer wired into the redirect merge
        bru -- Mispredict --> ru
    end

    agu -- MemoryOpReq --> memreq
    ru -- RedirectOut --> redirect
    %% ADR-012/ADR-003: StoreCommit view of the unified commit broadcast to the memory subsystem
    com -- StoreCommit --> storecommit

    subgraph outputs_group[Outputs]
        redirect@{shape: text, label: RedirectOut}
        memreq@{shape: text, label: MemoryOpReq}
        storecommit@{shape: text, label: StoreCommit}
    end

    style inputs_group fill:transparent,stroke:transparent
    style outputs_group fill:transparent,stroke:transparent
        """.stripMargin
      )
      .note(
        "Unified PRF architecture: single physical register file eliminates writeback on commit"
      )
      .note(
        "Map table approach: commit updates mapping only, no data copy required"
      )
      .note(
        "PublishMux is Result Bus: unified collection point for all FU results"
      )
      .note(
        "Wakeup mechanism: PublishMux broadcasts prd to RS for dependency resolution"
      )
      .note(
        "Scalability: N speculative registers scales from 1 (in-order) to 32+ (wide OoO)"
      )
      .note(
        "After timing synthesis each edge can be analyzed for critical path and pipelined easily"
      )
      .note("It should be highly parameterized")
      .build()
  }

  // Backend Top Interfaces (from mermaid diagram)
  val intfInstructionIssueIn = spec {
    INTERFACE("InstructionIssueIn")
      .desc("Instruction issue input from frontend.")
      .uses(bndInstructionIssue)
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
      .note("TrapController processes debug request and generates redirect to debug handler address")
      .build()
  }

  val intfMemoryOpRespIn = spec {
    INTERFACE("MemoryOpRespIn")
      .desc("Memory operation response input.")
      .uses(bndMemoryOpResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRedirectOut = spec {
    INTERFACE("RedirectOut")
      .desc("Redirect command output.")
      .uses(bndRedirectOut)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMemoryOpReqOut = spec {
    INTERFACE("MemoryOpReqOut")
      .desc("Memory operation request output.")
      .uses(bndMemoryOpReq)
      .is(rawReadyValidIntf)
      .build()
  }

  // ADR-012 D-12.4 / ADR-003 D-3.3: StoreCommit view of the unified commit broadcast.
  val intfStoreCommitOut = spec {
    INTERFACE("StoreCommitOut")
      .desc("StoreCommit {seqTag, epoch} projected view of the unified commit broadcast, driven to the memory subsystem store buffer.")
      .uses(bndCommitBroadcast)
      .is(rawReadyValidIntf)
      .note("CoreTop wires this to MemorySubsystem.storeCommitIn (WP-D CoreTop); only committed head stores drain (ADR-003 D-3.3).")
      .build()
  }
}
