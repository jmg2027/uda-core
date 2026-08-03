package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.frontend.spec.shared.FrontendParamsSpecs._

/** NextPcGen: the frontend's single PC-selection vertex (ADR-009 D-9.1/D-9.3/D-9.6). */
object NextPcGenSpecs {
  val contNextPcGen = spec {
    CONTRACT("NextPcGen")
      .desc(
        "NextPcGen is the frontend's single PC-selection vertex. It seeds from boot, then " +
          "each cycle selects the next fetch PC from, in priority order: redirect (backend), " +
          "first-taken prediction (predecoder), sequential fall-through. It is the sole " +
          "epoch-stamp owner for the fetch stream."
      )
      .has(
        intfBootIn,
        intfRedirectIn,
        intfPredictionIn,
        intfNextPcOut,
        funcPcSelect,
        funcEpochStamp
      )
      .build()
  }

  val intfBootIn = spec {
    INTERFACE("BootIn")
      .desc("Boot address seed from BootSequencer (crosses the frontend top boundary).")
      .is(rawReadyValidIntf)
      .uses(bndBootAddr)
      .note("Same edge as FrontendTop.intfBootAddrIn; referenced, not re-minted (ADR-009 D-9.3).")
      .build()
  }

  val intfRedirectIn = spec {
    INTERFACE("RedirectIn")
      .desc("Backend redirect (crosses the frontend top boundary). Highest PC priority.")
      .is(rawReadyValidIntf)
      .uses(bndRedirect)
      .note("Same edge as FrontendTop.intfRedirectIn; the single frontend redirect sink (ADR-009 D-9.3).")
      .build()
  }

  val intfPredictionIn = spec {
    INTERFACE("PredictionIn")
      .desc("First-taken branch prediction feedback from the predecoder (frontend-internal).")
      .is(rawReadyValidIntf)
      .uses(bndPredictorOutput)
      .build()
  }

  val intfNextPcOut = spec {
    INTERFACE("NextPcOut")
      .desc("Selected epoch-tagged next fetch address toward FetchUnit.")
      .is(rawReadyValidIntf)
      .uses(bndNextPcIssue)
      .build()
  }

  val funcPcSelect = spec {
    FUNCTION("PcSelect")
      .desc("Priority mux: redirect > prediction > sequential. Boot seeds the initial value (ADR-009 D-9.6 G1).")
      .markdownTable(
        List("Source", "Priority", "Condition", "Next PC"),
        List(
          List("Redirect", "0 (highest)", "redirect.valid", "redirect.target"),
          List("Prediction", "1", "prediction.valid && taken && srcEpoch === GlobalEpoch", "prediction.target"),
          List("Sequential", "2 (lowest)", "otherwise", "pc + fetchStride")
        )
      )
      .note("fetchStride = memDataWidth/8 BYTES (memDataWidth is a bit width; aligns with FrontendParams fetchStrideBytes, ADR-009 D-9.8). An indirect prediction with indirectStall deasserts NextPcOut.valid until a redirect (G4).")
      .uses(paramProgramMemoryAddrWidth, paramMemDataWidth)
      .build()
  }

  val funcEpochStamp = spec {
    FUNCTION("EpochStamp")
      .desc(
        "Every emitted NextPc carries the GlobalEpoch observed this cycle. A redirect consumed " +
          "this cycle stamps the combinational POST-increment epoch on NextPc (ADR-006 D-6.3, G1), " +
          "even if the speculative epoch copy fed to other consumers is registered."
      )
      .uses(paramGlobalEpochWidth)
      .build()
  }

  // ADR-006 D-6.3 / critique MI-1: paired with an @LocalSpec design assert.
  val propNextPcEpoch = spec {
    PROPERTY("NextPcEpoch")
      .desc(
        "On a redirect cycle the emitted NextPc.epoch equals the combinational post-increment " +
          "global epoch (redirect.epoch), never a stale or registered value."
      )
      .note("ADR-006 D-6.3 (critique MI-1): assert redirect.valid -> NextPcOut.epoch === epochIncrement. Runtime monitor paired with an @LocalSpec design assert (ADR-015 D-15.3); assertion text is not placed in .code/.note.")
      .uses(funcEpochStamp)
      .build()
  }
}
