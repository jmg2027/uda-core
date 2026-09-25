package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.frontend.spec.shared.FrontendParamsSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs.bndRecoveryEvent

/** FetchBuffer: decouples I-side latency from decode (ADR-019 D-19.2). */
object FetchBufferSpecs {
  val contFetchBuffer = spec {
    CONTRACT("FetchBuffer")
      .desc(
        "An instruction FIFO between the FetchUnit and the backend DecodeUnit. It accepts the " +
        "valid slots of one FetchBlock at a time and delivers up to DecodeWidth consecutive " +
        "instructions per cycle in program order, tagging each with its PC, FTQ reference, " +
        "slot, blockEnd, prediction, and fetch fault."
      )
      .has(
        intfFetchBlockIn,
        intfFetchPacketOut,
        intfRecoveryEventIn,
        funcFetchBufferEnqueue,
        funcFetchPacketDequeue,
        funcFetchBufferRecovery,
        propFetchBufferProgramOrder
      )
      .uses(paramFetchBufferEntries, paramDecodeWidth)
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: entries are FIFO ordered. Every buffered instruction is " +
        "younger than every uop in the backend (delivery is in order), so any RecoveryEvent " +
        "discards all entries. Reclaim: pointers reset."
      )
      .build()
  }

  val intfFetchBlockIn = spec {
    INTERFACE("FetchBlockIn")
      .desc("Fetch blocks from the FetchUnit. Ready only when all valid slots of the block fit.")
      .uses(bndFetchBlock)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfFetchPacketOut = spec {
    INTERFACE("FetchPacketOut")
      .desc("Up to DecodeWidth instructions per transfer to the backend DecodeUnit.")
      .uses(bndFetchPacket)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfRecoveryEventIn = spec {
    INTERFACE("RecoveryEventIn")
      .desc("The common RecoveryEvent broadcast.")
      .uses(bndRecoveryEvent)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 6.")
      .build()
  }

  val funcFetchBufferEnqueue = spec {
    FUNCTION("FetchBufferEnqueue")
      .desc(
        "Append the valid slots of an accepted FetchBlock in slot order; the last appended " +
        "slot is marked blockEnd, the predicted-taken exit slot carries predictedTaken and " +
        "predictedTarget from the block's exitTaken/exitTarget."
      )
      .uses(intfFetchBlockIn)
      .build()
  }

  val funcFetchPacketDequeue = spec {
    FUNCTION("FetchPacketDequeue")
      .desc("Offer the oldest min(DecodeWidth, occupancy) instructions as one packet; they leave together when the packet transfers.")
      .uses(intfFetchPacketOut)
      .build()
  }

  val funcFetchBufferRecovery = spec {
    FUNCTION("FetchBufferRecovery")
      .desc("On any RecoveryEvent, discard every buffered instruction and any unaccepted packet in the event cycle.")
      .uses(intfRecoveryEventIn)
      .build()
  }

  val propFetchBufferProgramOrder = spec {
    PROPERTY("FetchBufferProgramOrder")
      .desc("Between RecoveryEvents, the instructions leaving on FetchPacketOut are exactly the valid slots accepted on FetchBlockIn, in the same order, none duplicated or skipped.")
      .uses(intfFetchBlockIn, intfFetchPacketOut)
      .build()
  }
}
