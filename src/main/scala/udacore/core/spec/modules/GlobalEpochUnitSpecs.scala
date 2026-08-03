package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.core.spec.shared.CoreBundlesSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._

object GlobalEpochUnitSpecs {
  val contGlobalEpochUnit = spec {
    CONTRACT("GlobalEpochUnit")
      .desc("Manages global epoch counter and broadcasts to all pipeline stages")
      .has(
        intfRedirectFireIn,
        intfEpochOut,
        funcEpochIncrement,
        funcSameCycleEpochExposure,
        propEpochToggle,
        propEpochWrapBound,
        propEpochDistribution,
        propSingleIncrementPerRedirect
      )
      .uses(paramEpochWidth)
      .note("Epoch increments on redirect acceptance")
      .note("No explicit flush signals - epoch comparison filters stale tokens")
      .note(
        "ADR-005 D-5.3: consumers use exact-match compare (epoch === globalEpoch), NOT distance; eager filtering kills at the first mismatch so no subtractor is needed on the fan-out-heavy compare."
      )
      .build()
  }

  val intfRedirectFireIn = spec {
    INTERFACE("RedirectFireIn")
      .desc("Redirect acceptance signal from backend")
      .is(rawNoDecoupled)
      .markdownTable(
        List("Signal", "Direction", "Type", "Description"),
        List(
          List("redirectFireIn", "input", "Bool", "Redirect accepted (Decoupled.fire) signal")
        )
      )
      .note("Driven by redirect.valid && redirect.ready at CoreTop")
      .build()
  }

  val intfEpochOut = spec {
    INTERFACE("EpochOut")
      .desc("Global epoch broadcast to all pipeline stages")
      .is(rawNoDecoupled)
      .markdownTable(
        List("Signal", "Direction", "Type", "Description"),
        List(
          List("epochOut", "output", "UInt(epochWidth)", "Current global epoch value")
        )
      )
      .note("Broadcast wire to Frontend, Backend, MemorySubsystem")
      .build()
  }

  val funcEpochIncrement = spec {
    FUNCTION("EpochIncrement")
      .desc("Increments epoch register when redirect fires")
      .code("scala", """
        val nextEpoch = if (epochWidth == 1) {
          epoch ^ 1.U(1.W)
        } else {
          (epoch + 1.U)(epochWidth - 1, 0)
        }
        when(redirectFire) { epoch := nextEpoch }
      """)
      .note("Single-bit epoch uses XOR toggle for efficiency")
      .note("Multi-bit epoch wraps at 2^epochWidth")
      .build()
  }

  val funcSameCycleEpochExposure = spec {
    FUNCTION("SameCycleEpochExposure")
      .desc("Exposes new epoch value in same cycle as redirect")
      .code("scala", """
        io.epochOut := Mux(redirectFire, nextEpoch, epoch)
      """)
      .note("Combinational path allows pipeline to see new epoch immediately")
      .note("Registered value available for consumers on following cycle")
      .build()
  }

  val propEpochToggle = spec {
    PROPERTY("EpochToggle")
      .desc(
        "The epoch register value changes exactly when a redirect fires and holds otherwise: the next epoch equals the incremented value (XOR toggle at width 1, wrapping increment above) on a redirect-fire cycle and equals the current epoch on every other cycle."
      )
      .note(
        "This replaces the earlier vacuous nextEpoch =/= epoch statement (always true) with the load-bearing invariant. Paired with an @LocalSpec design assert in GlobalEpochUnit (ADR-015 D-15.2/D-15.3); assertion text is not placed in .code/.note."
      )
      .build()
  }

  val propEpochWrapBound = spec {
    PROPERTY("EpochWrapBound")
      .desc(
        "Epoch width must exceed the number of redirect generations any token can survive with a deferred compare."
      )
      .uses(paramEpochWidth)
      .entry(
        "invariant",
        "every epoch-holding vertex compares epoch === globalEpoch each cycle it holds a token (ADR-005 D-5.1)"
      )
      .entry(
        "maxSurvivableGenerations",
        "1 under eager filtering; raised by any vertex that defers its compare"
      )
      .entry("require", "(1 << epochWidth) > maxSurvivableGenerations + 1")
      .note(
        "ADR-005 D-5.3: with maxSurvivableGenerations=1 this gives epochWidth >= 2 and the default satisfies it with one generation of margin. Elaboration-require type per ADR-015 D-15.3, paired with an @LocalSpec design require. The vertex enumeration lives in DesignRuleSpecs propEpochVertexEnumeration."
      )
      .build()
  }

  val propEpochDistribution = spec {
    PROPERTY("EpochDistributionTiming")
      .desc(
        "The epoch consumed by the commit gate and the redirect generator is the combinational same-cycle value with ZERO registered stages forever; the copy fed to purely-speculative drop-only consumers MAY be registered within the edgeRedirectToFetch budget."
      )
      .uses(paramEpochWidth)
      .entry("commitPath.stages", "0 (normative, all N)")
      .entry("specPath.stages.N1", "0")
      .entry("specPath.stages.N8plus", "0..1, counts against edgeRedirectToFetch budget")
      .entry(
        "invariant",
        "a registered epoch copy is legal only where the consumer can never commit architectural state"
      )
      .note(
        "ADR-006 D-6.1/D-6.2: registering the speculative copy adds one cycle to the mispredict penalty, refused at N=1. The edgeRedirectToFetch budget is owned by WP-A EdgeBudgetSpecs (redirectToFetchBudget). Elaboration-require type per ADR-015 D-15.3."
      )
      .build()
  }

  val propSingleIncrementPerRedirect = spec {
    PROPERTY("SingleIncrementPerRedirect")
      .desc(
        "GlobalEpochUnit increments the epoch by exactly one per accepted redirect; at most one redirect target is latched per epoch increment, with no double-increment and no target-mux race."
      )
      .uses(paramEpochWidth)
      .note(
        "ADR-013 D-13.4: the target latched is the winner of the RedirectUnit priority ladder (trap/interrupt > branch > memory-order). Under ADR-011 commit-head redirect at most one producer is the commit head per cycle, so the ladder never actually ties in the base machine. Simulation-assert per ADR-015 D-15.3, paired with an @LocalSpec design assert."
      )
      .build()
  }
}
