package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.frontend.spec.shared.FrontendBundlesSpecs._
import udacore.frontend.spec.modules.RvcExpanderSpecs.contRvcExpander

/** SlotSlicer: the one-half-word straddle machine (ADR-009 D-9.4). */
object SlotSlicerSpecs {
  val contSlotSlicer = spec {
    CONTRACT("SlotSlicer")
      .desc(
        "SlotSlicer converts fixed-width fetch beats into 16-bit-granular instruction slots, " +
          "resolving RVC straddle across beats via a single half-word carry. It owns exactly " +
          "one architectural state element (the carry); it holds no pipeline register."
      )
      .has(intfInputIn, intfOutputOut, funcStraddleCarry, funcSlotScan, funcMisalignTag)
      .uses(contRvcExpander)
      .note("The carry (carryValid, carryData, carryPc, carryEpoch) is the only legal state; it is required by the function (ADR-009 D-9.4).")
      .note(
        "SlotSlicer applies the RvcExpander helper per slot BEFORE slot emission (it is an internal helper, not a graph vertex), so every emitted slot - and therefore everything IssueQueue receives - is an expanded 32-bit instruction."
      )
      .build()
  }

  val intfInputIn = spec {
    INTERFACE("SlotSlicerInputIn")
      .desc("One fetch beat (memDataWidth bits + base PC + epoch) from FetchUnit.")
      .is(rawReadyValidIntf)
      .uses(bndFetchResponse)
      .build()
  }

  val intfOutputOut = spec {
    INTERFACE("SlotSlicerOutputOut")
      .desc("A slot group (up to slotsPerBeat entries), each a 32b payload + pc + len + valid + exc + epoch.")
      .is(rawReadyValidIntf)
      .uses(bndSlotGroup)
      .build()
  }

  val funcStraddleCarry = spec {
    FUNCTION("StraddleCarry")
      .desc(
        "Holds the trailing half-word of a beat when it begins an RVI, to pair with the next " +
          "beat. On epoch change the carry is dropped so a wrong-path tail never fuses with a " +
          "correct-path head. The carry is an enumerated eager-filter vertex (ADR-005 D-5.2): " +
          "it evaluates carryEpoch === GlobalEpoch every cycle it holds a half-word."
      )
      .table(
        "invariant",
        "INV-S2: carryValid implies exactly one 16-bit half-word held. INV-S3: carry dropped on epoch change (eager filter)."
      )
      .build()
  }

  val funcSlotScan = spec {
    FUNCTION("SlotScan")
      .desc(
        "Left-to-right half-word scan classifying RVC (bits[1:0] != 11, len=2, 1 half-word) vs " +
          "RVI (len=4, 2 half-words), emitting one slot per decoded instruction with pc = basePc + 2*i. " +
          "At wide fetch this is an O(slotsPerBeat) parallel-prefix length network."
      )
      .table(
        "invariant",
        "INV-S1: byte conservation - every fetched byte is emitted in a slot or held as carry. INV-S4: every emitted slot PC is 2-byte aligned."
      )
      .build()
  }

  val funcMisalignTag = spec {
    FUNCTION("MisalignTag")
      .desc(
        "Tags instrAddrMisaligned (and programMemFault from the fetch response) on the offending " +
          "slot and invalidates it and its successors in the beat. The exception rides the slot " +
          "metadata {exc, excCause} to the backend, which raises the precise trap at commit with " +
          "correct mepc/mtval; the frontend never traps (ADR-009 D-9.4 INV-S5, ADR-004)."
      )
      .table("invariant", "INV-S5: frontend TAGS the fault; backend RAISES at commit.")
      .uses(bndException)
      .build()
  }
}
