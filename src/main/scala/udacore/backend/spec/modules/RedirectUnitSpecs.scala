package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object RedirectUnitSpecs {
  val contRedirectUnit = spec {
    CONTRACT("RedirectUnit")
      .desc(
        "Redirect unit consolidates branch and trap feedback before broadcasting epochs."
      )
      .has(
        intfRedirectIn,
        intfMispredictIn,
        intfRedirectOutOut,
        funcRedirectPriority,
        propSingleRedirectPerEpoch,
        propRedirectAtCommit
      )
      .note("Base build has two producers: trap (incl. interrupt/mret/dret/debug) and branch mispredict; the memory-order producer is added only when MemDisambig=true (ADR-013 D-13.3).")
      .build()
  }

  // ADR-013 D-13.1: strict redirect priority ladder.
  val funcRedirectPriority = spec {
    FUNCTION("RedirectPriority")
      .desc("Merge redirect producers in strict priority: trap/interrupt (incl. mret/dret/debug entry) > branch mispredict > memory-order violation. Exactly one target is selected per epoch increment.")
      .note("Under ADR-011 commit-head redirect the ladder never actually ties in the base machine; it is defense-in-depth for the proposed superscalar-retire and multi-context extensions (ADR-013 D-13.2).")
      .uses(intfRedirectIn, intfMispredictIn, intfRedirectOutOut)
      .build()
  }

  // ADR-013 D-13.4: one increment per redirect, no target-mux race.
  val propSingleRedirectPerEpoch = spec {
    PROPERTY("SingleRedirectPerEpoch")
      .desc("At most one redirect target is latched per epoch increment; GlobalEpochUnit increments by exactly one per accepted redirect, with no double-increment and no target-mux race.")
      .note("Pair with a design assert (ADR-013 D-13.4, ADR-015 D-15.3).")
      .uses(intfRedirectOutOut)
      .build()
  }

  // ADR-011 D-11.1: all redirects originate at the commit head.
  val propRedirectAtCommit = spec {
    PROPERTY("RedirectAtCommit")
      .desc("Every redirect target entering RedirectUnit originates from the in-order commit head; no functional unit emits a redirect token directly.")
      .note("A branch resolves early in an FU but carries its direction/target as commit-pending payload; the redirect is emitted only at the commit head (ADR-011 D-11.1). Pair with a design assert.")
      .uses(intfRedirectIn, intfMispredictIn)
      .build()
  }

  val intfRedirectIn = spec {
    INTERFACE("RedirectIn")
      .desc("Trap-driven redirect stream entering the redirect unit.")
      .uses(bndRedirect)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMispredictIn = spec {
    INTERFACE("MispredictIn")
      .desc("Branch mispredict stream entering the redirect unit.")
      .uses(bndMispredict)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRedirectOutOut = spec {
    INTERFACE("RedirectOutOut")
      .desc("Final redirect broadcast stream leaving the redirect unit.")
      .uses(bndRedirectOut)
      .is(rawReadyValidIntf)
      .build()
  }
}
