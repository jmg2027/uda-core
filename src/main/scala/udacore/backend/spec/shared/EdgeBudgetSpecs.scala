package udacore.backend.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

/** IPC-critical edge registry (ADR-007).
  *
  * Every Decoupled edge is ELASTIC (default, frequency-only, any number of
  * post-PD registers) or IPC-CRITICAL (added latency costs measurable IPC and
  * carries a per-configuration cycle budget B(N)). Anything not listed here is
  * ELASTIC. Enforcement is two-layer: a pre-PD structural `require(stages <=
  * budget)` via the `ipcCriticalEdge` helper, plus the post-PD STA
  * registered-depth artifact co-owned with WP-D (ADR-007 D-7.3).
  */
object EdgeBudgetSpecs {

  // ADR-007 D-7.3(a): the pre-PD structural enforcement helper contract.
  val ipcCriticalEdge = spec {
    CONTRACT("IpcCriticalEdge")
      .desc(
        "Construction helper ipcCriticalEdge(gen, stages, budget, id) for every IPC-critical edge; stages defaults 0."
      )
      .note(
        "Pre-PD: require(stages <= budget) fails elaboration if an engineer raises the explicit stages knob over budget (ADR-007 D-7.3a)."
      )
      .note(
        "Post-PD: the elaboration require is scoped to structural stages ONLY; a netlist/STA registered-depth report is the post-PD guard, co-owned with WP-D (ADR-007 D-7.3b)."
      )
      .note(
        "An edge not registered here is ELASTIC: any number of post-PD registers, frequency-only (ADR-007 D-7.1)."
      )
      .build()
  }

  // ADR-007 D-7.2: the complete IPC-critical registry as budget functions.
  val edgeRegistry = spec {
    RAW("EdgeRegistry", "IpcCriticalRegistry")
      .desc("The complete IPC-critical edge list with per-configuration cycle budgets B(1)/B(8)/B(32).")
      .markdownTable(
        List("id", "edge", "B(1)", "B(8)", "B(32)"),
        List(
          List(
            "edgeWakeupSelect",
            "PublishMux wakeup(prd) -> RS match -> select/grant",
            "0",
            "0",
            "0 (proposed, ADR-007 D-7.4)"
          ),
          List("edgePublishToWakeup", "FU result -> PublishMux -> wakeup broadcast", "0", "0", "1"),
          List("edgePrfReadToExec", "RS issue -> PRF read -> FU operand", "0", "1", "1"),
          List("edgeRedirectToFetch", "resolve@commit -> GlobalEpoch -> fetch req", "1", "2", "2"),
          List("edgeLoadUse", "AGU -> mem -> load data -> bypass -> dependent", "1", "1", "2"),
          List("edgePredictToFetch", "predecoder prediction -> NextPcGen -> fetch", "0", "0", "0")
        )
      )
      .uses(ipcCriticalEdge)
      .build()
  }

  // One PROPERTY per registry row (ADR-007 verification obligation).
  val propWakeupSelectBudget = spec {
    PROPERTY("WakeupSelectBudget")
      .desc("edgeWakeupSelect carries 0 structural stages at every N in the base build.")
      .note(
        "The single hard OoO invariant: a register here forces a bubble between every producer and dependent. If N=32 cannot close, reduce the max window or bank the PRF, NEVER register this edge (ADR-007 D-7.4). Pair with an elaboration require; a stages=1 unit test must throw."
      )
      .uses(ipcCriticalEdge)
      .build()
  }

  val propPublishToWakeupBudget = spec {
    PROPERTY("PublishToWakeupBudget")
      .desc("edgePublishToWakeup stages <= B(N): 0 at N in {1,8}, 1 at N=32.")
      .note(
        "The 1 stage at N=32 means back-to-back dependent issue is bounded by the window hiding the bubble (MLP framing, ADR-014 D-14.3). Pair with an elaboration require."
      )
      .uses(ipcCriticalEdge)
      .build()
  }

  val propPrfReadToExecBudget = spec {
    PROPERTY("PrfReadToExecBudget")
      .desc("edgePrfReadToExec stages <= B(N): 0 at N=1, 1 at N in {8,32}.")
      .uses(ipcCriticalEdge)
      .build()
  }

  val propRedirectToFetchBudget = spec {
    PROPERTY("RedirectToFetchBudget")
      .desc("edgeRedirectToFetch stages <= B(N): 1 at N=1, 2 at N in {8,32}.")
      .note(
        "Re-priced as resolve-to-commit-to-fetch (ADR-011 D-11.3); the resolve-to-commit portion is a window-drain latency counted separately in the N-sweep, not a registered-edge property."
      )
      .uses(ipcCriticalEdge)
      .build()
  }

  val propLoadUseBudget = spec {
    PROPERTY("LoadUseBudget")
      .desc("edgeLoadUse stages <= B(N): 1 at N in {1,8}, 2 at N=32.")
      .uses(ipcCriticalEdge)
      .build()
  }

  val propPredictToFetchBudget = spec {
    PROPERTY("PredictToFetchBudget")
      .desc("edgePredictToFetch stages <= B(N): 0 at every N.")
      .note(
        "Base config collapses the predict->fetch loop to a same-cycle wire; pipelined only post-PD under budget (ADR-007 D-7.2, critique MI-3)."
      )
      .uses(ipcCriticalEdge)
      .build()
  }
}
