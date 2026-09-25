package udacore.backend.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

/** Critical-recurrence registry (ADR-007 concept, budgets re-derived for ADR-019).
  *
  * Every Decoupled edge is ELASTIC (default: registers may be added for
  * frequency without a stated IPC cost) or belongs to a registered
  * IPC-critical recurrence with a v0 cycle budget. The old N-indexed budgets
  * (B(1)/B(8)/B(32)) are superseded; the v0 budgets below are the interim
  * targets for the reference pipeline and are re-measured when RTL exists.
  */
object EdgeBudgetSpecs {

  // ADR-007 D-7.3(a): the pre-PD structural enforcement helper contract.
  val ipcCriticalEdge = spec {
    CONTRACT("IpcCriticalEdge")
      .desc(
        "Construction helper ipcCriticalEdge(gen, stages, budget, id) for every registered " +
        "recurrence edge; stages defaults 0 and require(stages <= budget) fails elaboration " +
        "when an engineer raises the explicit stages knob over budget."
      )
      .note(
        "Post-PD, a netlist/STA registered-depth report is the guard for retiming the " +
        "elaboration require cannot see (ADR-007 D-7.3b). An edge not registered here is ELASTIC."
      )
      .build()
  }

  val edgeRegistry = spec {
    RAW("EdgeRegistry", "IpcCriticalRegistry")
      .desc("Registered recurrences of the ADR-019 v0 pipeline with their v0 structural-stage budgets.")
      .markdownTable(
        List("id", "recurrence", "v0 budget (stages)"),
        List(
          List("edgeWakeupSelect", "PublishMux wakeup(prd) -> RS match -> select", "0"),
          List("edgePrfReadToExec", "RS select -> PRF read -> FU operand", "1"),
          List("edgeLoadUse", "load issue -> D-cache hit data -> publish -> dependent select", "2"),
          List("edgeBranchRecovery", "BranchUnit resolve -> RecoveryEvent -> FetchPcGen next fetch", "1"),
          List("edgePredictNextPc", "BranchPredictor BTB/RAS lookup -> FetchPcGen next fetch PC", "1 (a BTB-hit taken block costs at most one bubble)")
        )
      )
      .uses(ipcCriticalEdge)
      .note(
        "ADR-019 supersession matrix: the recurrence-registry idea is retained, the N-based " +
        "budgets are not binding. TAGE may override the one-cycle BTB/RAS next-PC decision " +
        "later in the pipeline; that override is part of edgePredictNextPc, not a new recovery producer."
      )
      .build()
  }

  val propWakeupSelectBudget = spec {
    PROPERTY("WakeupSelectBudget")
      .desc("edgeWakeupSelect carries 0 structural stages: a single-cycle producer's dependent can be selected in the cycle after the producer is selected.")
      .uses(ipcCriticalEdge)
      .note("Elaboration require via ipcCriticalEdge; a stages=1 unit test must throw.")
      .build()
  }

  val propLoadUseBudget = spec {
    PROPERTY("LoadUseBudget")
      .desc("edgeLoadUse stages <= 2 for a D-cache hit with no forwarding conflict.")
      .uses(ipcCriticalEdge)
      .build()
  }

  val propBranchRecoveryBudget = spec {
    PROPERTY("BranchRecoveryBudget")
      .desc("edgeBranchRecovery stages <= 1: the RecoveryEvent of a resolved mispredict reaches FetchPcGen at most one register after resolution.")
      .uses(ipcCriticalEdge)
      .build()
  }

  val propPredictNextPcBudget = spec {
    PROPERTY("PredictNextPcBudget")
      .desc("edgePredictNextPc stages <= 1: a predicted-taken fetch block redirects fetch with at most one bubble.")
      .uses(ipcCriticalEdge)
      .build()
  }
}
