package udacore.frontend.spec.top

import framework.macros.SpecEmit.spec
import framework.specs.Spec._

import udacore.common.spec.DesignRuleSpecs._
import udacore.frontend.spec.shared.FrontendBundlesSpecs._

object FrontendTopSpecs {
  val contFrontendTop = spec {
    CONTRACT("FrontendTop")
      .desc("""
      | Frontend Top level contract
      | Frontend level graph description
      | Interface with Backend and Core top system
      | Boot interface delivers fetch start event and boot address
      | Send fetch request to external program memory
      | Receive fetch response from external program memory
      | Process fetch response data into instruction slots
      | Instruction slot indicates single operation
      | Calculate next fetch address using redirect, prediction, and other mechanisms
      | BranchPredecoder will predecode jal, br and calculate target of jal, br
      | Based on first taken rule, BranchPredecoder marks valid and invalid entry
      | Issue queue must contain valid slots for program flow (even it was speculatively)
      | So slots validity should be checked during previous nodes
      | Such as starting pc, result of rvc/rvi, misaligned rvc/rvi, only to first taken branch, .. etc
      | Issue queue issue instruction slots and meta informations to the backend
      """)
      .is(rawTop)
      .draw(
        "mermaid",
        """
      graph LR
    %% Legend
    %% -.-> : Normal Wire (rawNoDecoupled), only epoch broadcast and top-boundary crossings
    %% --> : Decoupled Edge (ready/valid)
    %% [] : Normal Module      [[]] : Top Module (rawTop)
    %% ADR-009 D-9.3: each edge below reconciles with exactly one child INTERFACE
    %% (producer .out / consumer .in). See propGraphConsistency (ADR-015 check 1).

    %% Inputs
    subgraph inputs_group[Inputs]
        in_anchor:::hidden
        boot@{shape: text, label: BootAddr}
        rd@{shape: text, label: Redirect}
        ep@{shape: text, label: GlobalEpoch}
        epmresp@{shape: text, label: ExternalProgramMemoryResp}
    end

    npc[NextPcGen]
    fu[FetchUnit]
    align[SlotSlicer]
    predecbp[BranchPredecoder]
    iq[IssueQueue]

    %% Top-boundary inputs into child .in interfaces
    boot ---> npc
    rd ---> npc
    epmresp ---> fu

    %% Epoch is broadcast (rawNoDecoupled) to every epoch-filtering vertex
    ep -.-> npc
    ep -.-> fu
    ep -.-> align
    ep -.-> predecbp
    ep -.-> iq

    subgraph FrontendTop
        direction LR
        %% npc.NextPcOut -> fu.NextPcIn
        npc -- NextPcIssue --> fu
        %% fu.FetchResponseOut -> align.InputIn
        fu -- FetchResponse --> align
        %% align.OutputOut -> predecbp.PredictorIn (expanded 32-bit slot stream;
        %% RVC expansion is applied per-slot by the RvcExpander helper INSIDE SlotSlicer)
        align -- SlotGroup --> predecbp
        %% predecbp.PredecodedOut -> iq.FrontendIn
        predecbp -- PredecodedSlots --> iq
        %% predecbp.PredictorOut -> npc.PredictionIn (first-taken feedback)
        predecbp -- BranchPrediction --> npc
    end

    %% Child .out interfaces to top-boundary outputs
    fu ---> epmreq
    iq ---> issue

    subgraph outputs_group[Outputs]
        issue@{shape: text, label: InstructionIssue}
        epmreq@{shape: text, label: ExternalProgramMemoryReq}
    end

    style inputs_group fill:transparent,stroke:transparent
    style outputs_group fill:transparent,stroke:transparent
      """.stripMargin
      )
      .note(
        "Consider start from minimal cycle - only memory latency and issue queue are cyclic"
      )
      .note(
        "After timing synthesis each edge can be analyzed for critical path and pipelined easily"
      )
      .note(
        "RVC expansion is NOT a FrontendTop graph vertex: it is applied per-slot by the RvcExpander combinational helper INSIDE SlotSlicer, so IssueQueue receives only expanded 32-bit slots. Its scalar in/out are a documented exception (per-slot helper interfaces, not ready/valid graph edges), per ONBOARDING's rule that modules inside a vertex's box are not vertices."
      )
      .note(
        "Naming rule: functional spec objects omit the FrontendTop prefix because the module context is implicit."
      )
      .note(
        "ADR-009 D-9.3 / ADR-015 machine check 1 (propGraphConsistency): the mermaid edge set reconciles edge-for-edge with the union of child INTERFACE sets - boot->npc.BootIn, redirect->npc.RedirectIn, npc.NextPcOut->fu.NextPcIn, fu.ProgMemReqOut->ExternalProgramMemoryReqOut, ExternalProgramMemoryRespIn->fu.ProgMemRespIn, fu.FetchResponseOut->align.InputIn, align.OutputOut->predecbp.PredictorIn, predecbp.PredecodedOut->iq.FrontendIn, predecbp.PredictorOut->npc.PredictionIn, iq.BackendOut->InstructionIssueOut, epoch broadcast to {npc,fu,align,predecbp,iq}. RvcExpander is NOT a vertex here: it is a per-slot helper inside SlotSlicer, so align emits already-expanded 32-bit slots."
      )
      .uses(propGraphConsistency)
      .has(
        intfBootAddrIn,
        intfRedirectIn,
        intfGlobalEpochIn,
        intfExternalProgramMemoryRespIn,
        intfInstructionIssueOut,
        intfExternalProgramMemoryReqOut,
        funcBootSequencing,
        funcPcResolution,
        funcMemoryBridge,
        funcSlotConstruction,
        funcPredictionDiscipline,
        funcSlotValidation,
        funcIssueDispatch
      )
      .build()
  }

  // Frontend Top Interfaces (from mermaid diagram)
  val intfBootAddrIn = spec {
    INTERFACE("BootAddrIn")
      .desc("Boot address input.")
      .uses(bndBootAddr)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRedirectIn = spec {
    INTERFACE("RedirectIn")
      .desc("Redirect command input.")
      .uses(bndRedirect)
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

  // ADR-016: this edge now terminates at CoreTop's InstBusAdapter (TileLink bridge),
  // not directly at an external pin; the bundle and handshake are unchanged.
  val intfExternalProgramMemoryRespIn = spec {
    INTERFACE("ExternalProgramMemoryRespIn")
      .desc("External program memory response input.")
      .uses(bndExternalProgramMemoryResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfInstructionIssueOut = spec {
    INTERFACE("InstructionIssueOut")
      .desc("Instruction issue output.")
      .uses(bndIssueBackend)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfExternalProgramMemoryReqOut = spec {
    INTERFACE("ExternalProgramMemoryReqOut")
      .desc("External program memory request output.")
      .uses(bndExternalProgramMemoryReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcBootSequencing = spec {
    FUNCTION("BootSequencing")
      .desc(
        "Boot channel delivers the hart enable pulse and start address that awakens the frontend pipeline."
      )
      .note(
        "NextPcGen captures the boot address and seeds the initial fetch window once hartEn is observed."
      )
      .build()
  }

  val funcPcResolution = spec {
    FUNCTION("PcResolution")
      .desc(
        "NextPcGen synthesizes the next fetch address from boot, redirect, prediction, and epoch feedback paths."
      )
      .note(
        "The generator honours the most recent redirect with epoch guardrails before falling back to sequential prediction."
      )
      .table(
        "guardrail",
        "G1 redirect wins same-cycle (combinational post-increment epoch stamped on NextPc, ADR-006 D-6.1/D-6.3); " +
          "G2 in-flight fetch dropped on epoch mismatch at the FetchUnit response boundary; " +
          "G3 prediction consumed only if srcEpoch === GlobalEpoch; " +
          "G4 indirect jumps stall fetch until the backend redirects; " +
          "G5 the outstanding fetch latch eager-filters, so a fetch dies at the first redirect and never wraps (ADR-005 D-5.2; P03 prefetch bound withdrawn)."
      )
      .build()
  }

  val funcMemoryBridge = spec {
    FUNCTION("MemoryBridge")
      .desc(
        "FetchUnit arbitrates outstanding memory queries and translates instruction fetch requests for the external program memory."
      )
      .note(
        "Responses are accepted when epoch alignment matches, ensuring stale lines are discarded."
      )
      .build()
  }

  val funcSlotConstruction = spec {
    FUNCTION("SlotConstruction")
      .desc(
        "SlotSlicer and RvcExpander cooperate to transform returned memory beats into aligned instruction slots."
      )
      .note(
        "Each slot maps to exactly one architectural operation ready for predecode analysis."
      )
      .build()
  }

  val funcPredictionDiscipline = spec {
    FUNCTION("PredictionDiscipline")
      .desc(
        "BranchPredecoder inspects expanded slots to classify branch and jump forms while computing their targets."
      )
      .note(
        "First-taken policy marks only the leading taken branch as valid, pruning later speculative entries."
      )
      .build()
  }

  val funcSlotValidation = spec {
    FUNCTION("SlotValidation")
      .desc(
        "Frontend ensures slot validity prior to queuing by checking start PC, RVC expansion integrity, and alignment constraints."
      )
      .note(
        "Invalidated slots are filtered before the issue queue to maintain coherent program flow."
      )
      .build()
  }

  val funcIssueDispatch = spec {
    FUNCTION("IssueDispatch")
      .desc(
        "IssueQueue releases validated slots and their metadata toward the backend over a decoupled issue interface."
      )
      .note(
        "Downstream units may rely on slot order to collapse the minimal-latency frontend pipeline."
      )
      .build()
  }
}
