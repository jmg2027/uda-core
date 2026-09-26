package udacore.frontend.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.spec.ParamsSpecs._

/** Frontend domain parameters (ADR-019 D-19.1/D-19.2/D-19.3).
  *
  * Instructions are fixed 32-bit words at 4-byte aligned PCs; there is no
  * 16-bit slot, instruction-length field, or compressed-instruction parameter.
  * The "v0" entries are the reference configuration.
  */
object FrontendParamsSpecs {

  // ---- Contract tier --------------------------------------------------------

  val paramFetchBytes = spec {
    PARAMETER("FetchBytes")
      .desc(
        "Fetch-block size in bytes. A fetch block is the naturally aligned FetchBytes region " +
        "containing the fetch PC; fetch starting mid-block covers only the slots at or after the PC " +
        "(blockBase and startSlot, ADR-019G E-1)."
      )
      .is(rawContractParams)
      .entry("v0", "16")
      .build()
  }

  val paramFetchWidth = spec {
    PARAMETER("FetchWidth")
      .desc("Instruction slots per fetch block: FetchBytes / 4.")
      .is(rawContractParams)
      .uses(paramFetchBytes)
      .entry("v0", "4")
      .build()
  }

  val paramDecodeWidth = spec {
    PARAMETER("DecodeWidth")
      .desc("Instructions delivered from the fetch buffer to DecodeUnit per cycle.")
      .is(rawContractParams)
      .entry("v0", "2")
      .build()
  }

  // ---- Tuning tier ------------------------------------------------------------

  val paramBtbGeometry = spec {
    PARAMETER("BtbGeometry")
      .desc(
        "BTB sets, ways, and tag bits. Entries are indexed and tagged by fetch-block address; " +
        "each entry tracks one control-flow instruction of its block (slot, type, target). " +
        "v0 uses full tags so a BTB hit on a non-CFI slot occurs only after code modification."
      )
      .is(rawTuningParams)
      .entry("v0", "64 sets x 2 ways, full tag")
      .build()
  }

  val paramTageGeometry = spec {
    PARAMETER("TageGeometry")
      .desc(
        "TAGE base bimodal table size, number of tagged tables, per-table entries, tag bits, " +
        "geometric history lengths, and counter widths."
      )
      .is(rawTuningParams)
      .entry("v0", "bimodal 1024 x 2b; 4 tagged tables x 256 entries, 8-bit tags, 3-bit ctr, 2-bit useful; histories 8/16/32/64")
      .build()
  }

  val paramGhrLength = spec {
    PARAMETER("GhrLength")
      .desc("Speculative global history length in bits: the longest TAGE history.")
      .is(rawTuningParams)
      .uses(paramTageGeometry)
      .entry("v0", "64")
      .build()
  }

  val paramRasDepth = spec {
    PARAMETER("RasDepth")
      .desc("Return address stack entries (circular; overflow overwrites the oldest).")
      .is(rawTuningParams)
      .entry("v0", "8")
      .build()
  }

  val paramFtqDepth = spec {
    PARAMETER("FtqDepth")
      .desc(
        "Fetch target queue entries, one per fetch block from prediction until the block's " +
        "last instruction commits. Power of two. FTQ-full backpressures prediction."
      )
      .is(rawTuningParams)
      .entry("v0", "16")
      .build()
  }

  val paramFtqIdxWidth = spec {
    PARAMETER("FtqIdxWidth")
      .desc("ftqIdx = {wrap, idx} over FtqDepth, ordered by the funcRobOlder rule.")
      .uses(paramFtqDepth)
      .entry("v0", "5 (1 wrap + 4 idx)")
      .build()
  }

  val paramFetchBufferEntries = spec {
    PARAMETER("FetchBufferEntries")
      .desc("Instruction entries in the fetch buffer between the FetchUnit and DecodeUnit.")
      .is(rawTuningParams)
      .entry("v0", "8")
      .build()
  }
}
