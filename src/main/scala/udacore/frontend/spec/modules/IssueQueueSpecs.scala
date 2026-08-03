package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.frontend.spec.shared.FrontendParamsSpecs._

/** IssueQueue: the rate-matching skid buffer, the one intentional frontend cycle
  * (ADR-009 D-9.7, ADR-005).
  */
object IssueQueueSpecs {
  val contIssueQueue = spec {
    CONTRACT("IssueQueue")
      .desc(
        "IssueQueue is a rate-matching skid buffer on the frontend/backend boundary. It is NOT " +
          "a validator (validity is decided upstream); it holds only valid slots, drains " +
          "wrong-path entries by eager epoch filtering rather than a flush, and issues in PC order."
      )
      .has(
        intfFrontendIn,
        intfBackendOut,
        funcOnlyValidSlots,
        funcEpochDrain
      )
      .uses(paramIssueQueueDepth)
      .note("The one intentional frontend cycle: it breaks the combinational SCC between backend stall and the fetch request (FCL relay).")
      .build()
  }

  val intfFrontendIn = spec {
    INTERFACE("IssueFrontendIn")
      .desc("Frontend enqueue input: a predecoded, first-taken-pruned slot group.")
      .is(rawReadyValidIntf)
      .uses(bndIssueFrontend)
      .build()
  }

  val intfBackendOut = spec {
    INTERFACE("IssueBackendOut")
      .desc("Backend issue output: up to issueWidth in-order, only-valid, epoch-coherent slots.")
      .is(rawReadyValidIntf)
      .uses(bndIssueBackend)
      .build()
  }

  val funcOnlyValidSlots = spec {
    FUNCTION("OnlyValidSlots")
      .desc(
        "The enqueue mux admits only slots with valid=true; invalid/pruned slots are dropped, " +
          "never stored. At fetchWidth>1 a slot group enqueues atomically (all valid members or none)."
      )
      .table("invariant", "INV-Q1: only-valid enqueue. INV-Q4: atomic group enqueue at fetchWidth>1.")
      .build()
  }

  val funcEpochDrain = spec {
    FUNCTION("EpochDrain")
      .desc(
        "The queue is an enumerated eager-filter vertex (ADR-005 D-5.2): every held entry compares " +
          "entry.epoch === GlobalEpoch combinationally EVERY cycle and self-invalidates on mismatch, " +
          "not only at dequeue. This closes the deep-queue wrap hazard (critique M1). The queue is " +
          "never flushed; wrong-path entries age out by mismatch. Issue is in PC order."
      )
      .table("invariant", "INV-Q2: eager epoch-coherent filtering each cycle. INV-Q3: in-order issue.")
      .note("ADR-005 D-5.1: the compare is NOT deferred to consumption; a deep entry stamped epoch E is dropped, never issued, across a wrap.")
      .build()
  }

  // ADR-009 D-9.7: iqDepth law, defined here per the WP-C work order.
  val paramIssueQueueDepth = spec {
    PARAMETER("IssueQueueDepth")
      .desc(
        "Issue-queue entries. iqDepth = max(prefetchDepth*slotsPerBeat, redirectRecoveryCycles*fetchWidth), base 4."
      )
      .note(
        "Tuning tier - ADR-009 D-9.7. Depth-2 is the functional skid minimum at N=1; 4 is the perf default and the irreducible frontend N=1 floor (in the ADR-008 reference envelope, not counted as tax)."
      )
      .uses(paramPrefetchDepth, paramSlotsPerBeat, paramFetchWidth)
      .build()
  }

  // ADR-009 D-9.7 verification: paired with an @LocalSpec design assert.
  val propIssueQueueLiveness = spec {
    PROPERTY("IssueQueueLiveness")
      .desc(
        "With backend ready eventually true, the queue always drains: a rank function on queue " +
          "occupancy strictly decreases while the head is issuable, so no correct-path slot is " +
          "held forever (no deadlock)."
      )
      .note("ADR-009 D-9.7 / design-constitution liveness: runtime monitor paired with an @LocalSpec design assert (ADR-015 D-15.3).")
      .uses(paramIssueQueueDepth)
      .build()
  }
}
