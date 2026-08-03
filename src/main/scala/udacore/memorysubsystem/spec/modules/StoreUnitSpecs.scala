package udacore.memorysubsystem.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.memorysubsystem.spec.shared.MemorySubsystemBundlesSpecs._

/** StoreUnit (ADR-003 M3).
  *
  * The token-shaping / mask-generation front of the StoreBuffer (matches the
  * deleted reference captureIssue). It shapes a dispatched store into the
  * speculative token the buffer enqueues; it holds no ordering or commit state.
  */
object StoreUnitSpecs {
  val contStoreUnit = spec {
    CONTRACT("StoreUnit")
      .desc(
        "Store unit shapes a dispatched store (address/size/data/byte-mask) into the speculative token enqueued into the StoreBuffer."
      )
      .has(
        intfDispatchIn,
        intfMemoryReqOut,
        intfMemoryRespIn
      )
      .note(
        "ADR-003 Q-M3a (open): whether StoreUnit stays a distinct vertex or folds into the StoreBuffer is a panel question; kept separate here as the buffer's shaping front."
      )
      .build()
  }

  val intfDispatchIn = spec {
    INTERFACE("StoreDispatchIn")
      .desc("Dispatch payload entering the store unit.")
      .uses(bndStoreDispatch)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMemoryReqOut = spec {
    INTERFACE("StoreMemoryReqOut")
      .desc("Shaped speculative store token forwarded to the StoreBuffer.")
      .uses(bndStoreMemoryReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMemoryRespIn = spec {
    INTERFACE("StoreMemoryRespIn")
      .desc("Memory acknowledgement consumed by the store unit.")
      .uses(bndStoreMemoryResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcStoreShaping = spec {
    FUNCTION("StoreShaping")
      .desc(
        "Generates the byte-write mask and aligns store data for the addressed word, producing the speculative token the StoreBuffer enqueues with committed=false (ADR-003 M3)."
      )
      .uses(intfDispatchIn, intfMemoryReqOut)
      .note("No ordering or commit-gating state lives here; that is entirely the StoreBuffer's role.")
      .build()
  }
}
