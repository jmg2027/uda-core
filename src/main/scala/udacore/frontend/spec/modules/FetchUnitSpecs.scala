package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.frontend.spec.shared.FrontendParamsSpecs._

/** FetchUnit: the frontend memory-bridge vertex (ADR-009 D-9.2). */
object FetchUnitSpecs {
  val contFetchUnit = spec {
    CONTRACT("FetchUnit")
      .desc(
        "FetchUnit is the frontend memory-bridge vertex. It consumes NextPc tokens, " +
          "issues epoch-tagged program-memory requests, accepts responses, and emits " +
          "FetchResponse beats to the slot slicer. It holds no pipeline register: the " +
          "only architectural state is the outstanding-request epoch latch."
      )
      .has(
        intfNextPcIn,
        intfProgMemReqOut,
        intfProgMemRespIn,
        intfFetchResponseOut,
        funcOutstandingTracking,
        funcEpochTagging
      )
      .note("Boot does NOT enter FetchUnit; boot seeds NextPcGen (ADR-009 D-9.1).")
      .note("The stray intfBootIn/intfInstructionOut are deleted (ADR-009 D-9.2).")
      .build()
  }

  val intfNextPcIn = spec {
    INTERFACE("NextPcIn")
      .desc("Next fetch address (epoch-tagged) entering the fetch unit from NextPcGen.")
      .is(rawReadyValidIntf)
      .uses(bndNextPcIssue)
      .build()
  }

  val intfProgMemReqOut = spec {
    INTERFACE("ProgMemReqOut")
      .desc("Program-memory read request leaving the fetch unit at the frontend top boundary.")
      .is(rawReadyValidIntf)
      .uses(bndExternalProgramMemoryReq)
      .build()
  }

  val intfProgMemRespIn = spec {
    INTERFACE("ProgMemRespIn")
      .desc("Program-memory response entering the fetch unit from the frontend top boundary.")
      .is(rawReadyValidIntf)
      .uses(bndExternalProgramMemoryResp)
      .build()
  }

  val intfFetchResponseOut = spec {
    INTERFACE("FetchResponseOut")
      .desc("One fetched beat (aligned to the memory data width) toward the slot slicer.")
      .is(rawReadyValidIntf)
      .uses(bndFetchResponse)
      .build()
  }

  val funcOutstandingTracking = spec {
    FUNCTION("OutstandingTracking")
      .desc(
        "Tracks in-flight program-memory requests so responses are matched to the PC " +
          "and epoch that requested them. At the base config exactly one request may be " +
          "outstanding (prefetchDepth==1); depth is a tuning knob."
      )
      .note("Backpressure: NextPcIn.ready is deasserted while the outstanding set is full.")
      .uses(paramPrefetchDepth)
      .build()
  }

  val funcEpochTagging = spec {
    FUNCTION("EpochTagging")
      .desc(
        "Each request latches the GlobalEpoch at issue time. On response, if the latched " +
          "request epoch =/= current GlobalEpoch the beat is dropped locally (valid gated low) " +
          "instead of buffered. The outstanding-fetch latch is an enumerated eager-filter " +
          "vertex (ADR-005 D-5.2): it self-invalidates on mismatch every cycle it holds a " +
          "request, so an in-flight fetch dies at the first redirect and never survives a wrap (G2/G5)."
      )
      .note("No flush wire: staleness is a comparison (ADR-005 D-5.1).")
      .uses(paramGlobalEpochWidth)
      .build()
  }
}
