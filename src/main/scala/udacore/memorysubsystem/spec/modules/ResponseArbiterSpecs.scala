package udacore.memorysubsystem.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.memorysubsystem.spec.shared.MemorySubsystemBundlesSpecs._

/** ResponseArbiter (ADR-003 M4).
  *
  * Merges load responses and store-completion tokens toward the backend. It
  * reassociates loads by txnId and StoreComplete by seqTag; responses are
  * delivered UNORDERED to the backend, which matches them by tag.
  */
object ResponseArbiterSpecs {
  val contResponseArbiter = spec {
    CONTRACT("ResponseArbiter")
      .desc(
        "Response arbiter merges load responses (keyed by txnId) and StoreComplete tokens (keyed by seqTag) toward the backend as an unordered, tag-reassociated stream."
      )
      .has(
        intfControllerIn,
        intfRespOut
      )
      .build()
  }

  val intfControllerIn = spec {
    INTERFACE("ControllerRespIn")
      .desc("Response packet entering the response arbiter.")
      .uses(bndControllerResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRespOut = spec {
    INTERFACE("RespArbOut")
      .desc("Merged response stream leaving the response arbiter.")
      .uses(bndRespArb)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcResponseReassoc = spec {
    FUNCTION("ResponseReassoc")
      .desc(
        "Reassociates a load response with its waiting load by txnId and a StoreComplete with its store by seqTag; the merged stream is UNORDERED to the backend, which keys on the tag not the position (ADR-003 D-3.13, M4)."
      )
      .uses(intfControllerIn, intfRespOut)
      .build()
  }

  val propNoOrphanResponse = spec {
    PROPERTY("NoOrphanResponse")
      .desc(
        "Every response leaving the arbiter carries a txnId (load) or seqTag (StoreComplete) that matches an outstanding request; no orphan response is produced (ADR-003 M4)."
      )
      .uses(intfControllerIn, intfRespOut)
      .note("Paired with an @LocalSpec design assert that every response tag matches an outstanding request tag.")
      .build()
  }
}
