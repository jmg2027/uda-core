package udacore.backend.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.spec.ParamsSpecs._

/** Backend domain parameters and the shared program-order rule (ADR-019).
  *
  * The values in the "v0" entries are the ADR-019 D-19.1 reference
  * configuration. Every age comparison in the backend (ROB, RS, LSQ, FU
  * pipelines, recovery) and the FTQ-index comparison in the frontend reference
  * the single wrap-aware order function funcRobOlder defined here; no vertex
  * restates its own ordering rule.
  */
object BackendParamsSpecs {

  // ---- Contract tier ------------------------------------------------------

  val paramXLen = spec {
    PARAMETER("XLen")
      .desc("Integer register and datapath width in bits. The v0 configuration is 32 (RV32IM).")
      .is(rawContractParams)
      .entry("v0", "32")
      .build()
  }

  val paramArchRegNum = spec {
    PARAMETER("ArchRegNum")
      .desc(
        "Architectural integer registers: 32. x0 is not renamed: it maps permanently to " +
        "physical register p0, which reads zero, is never allocated, and is never freed."
      )
      .is(rawContractParams)
      .entry("v0", "32")
      .build()
  }

  // ---- Tuning tier (v0 reference values, ADR-019 D-19.1) -----------------

  val paramRenameWidth = spec {
    PARAMETER("RenameWidth")
      .desc("Uops renamed and allocated (ROB, RS, LSQ, checkpoint, prd) per cycle.")
      .is(rawTuningParams)
      .entry("v0", "1")
      .build()
  }

  val paramIssueWidth = spec {
    PARAMETER("IssueWidth")
      .desc("Uops selected out of order from the integer RS per cycle.")
      .is(rawTuningParams)
      .entry("v0", "1")
      .build()
  }

  val paramPublishWidth = spec {
    PARAMETER("PublishWidth")
      .desc(
        "Results published per cycle (PRF write port, wakeup broadcast lane, ROB completion). " +
        "ADR-014 single result lane is retained for v0."
      )
      .is(rawTuningParams)
      .entry("v0", "1")
      .build()
  }

  val paramCommitWidth = spec {
    PARAMETER("CommitWidth")
      .desc("ROB entries retired per cycle, in program order from the ROB head.")
      .is(rawTuningParams)
      .entry("v0", "1")
      .build()
  }

  val paramRobDepth = spec {
    PARAMETER("RobDepth")
      .desc("Reorder buffer entries (one per in-flight uop). Power of two.")
      .is(rawTuningParams)
      .entry("v0", "16")
      .build()
  }

  val paramIntegerPrfEntries = spec {
    PARAMETER("IntegerPrfEntries")
      .desc(
        "Unified integer physical register file entries, including p0. Legality: " +
        "IntegerPrfEntries >= ArchRegNum + RobDepth, so that with x0 unrenamed a free " +
        "physical register exists whenever a ROB entry does (p0 + 31 committed mappings + " +
        "RobDepth in-flight destinations)."
      )
      .is(rawTuningParams)
      .entry("v0", "48")
      .uses(paramArchRegNum, paramRobDepth)
      .build()
  }

  val paramIntegerRsEntries = spec {
    PARAMETER("IntegerRsEntries")
      .desc("Unified integer reservation station entries.")
      .is(rawTuningParams)
      .entry("v0", "8")
      .build()
  }

  val paramLoadQueueDepth = spec {
    PARAMETER("LoadQueueDepth")
      .desc("Load queue entries (speculative loads in program order).")
      .is(rawTuningParams)
      .entry("v0", "8")
      .build()
  }

  val paramStoreQueueDepth = spec {
    PARAMETER("StoreQueueDepth")
      .desc("Store queue entries (speculative, not-yet-committed stores in program order).")
      .is(rawTuningParams)
      .entry("v0", "8")
      .build()
  }

  val paramStoreBufferDepth = spec {
    PARAMETER("StoreBufferDepth")
      .desc(
        "Committed store-drain buffer entries below the SQ. Only committed stores occupy it; " +
        "a full buffer backpressures store commit."
      )
      .is(rawTuningParams)
      .entry("v0", "4")
      .build()
  }

  val paramBranchCheckpointCount = spec {
    PARAMETER("BranchCheckpointCount")
      .desc(
        "Rename checkpoints available for unresolved or uncommitted control-flow uops. Rename " +
        "backpressures a control-flow uop when none is free."
      )
      .is(rawTuningParams)
      .entry("v0", "4")
      .build()
  }

  // ---- Derived widths -----------------------------------------------------

  val paramRobTagWidth = spec {
    PARAMETER("RobTagWidth")
      .desc(
        "robTag = {wrap, idx}: idx is log2(RobDepth) bits naming the ROB slot, wrap is one " +
        "phase bit toggled each time the allocation pointer passes the last slot."
      )
      .uses(paramRobDepth)
      .entry("v0", "5 (1 wrap + 4 idx)")
      .build()
  }

  val paramPhysRegIdWidth = spec {
    PARAMETER("PhysRegIdWidth")
      .desc("Physical register id width: log2Ceil(IntegerPrfEntries).")
      .uses(paramIntegerPrfEntries)
      .entry("v0", "6")
      .build()
  }

  val paramCheckpointIdWidth = spec {
    PARAMETER("CheckpointIdWidth")
      .desc("Branch checkpoint id width: log2Ceil(BranchCheckpointCount).")
      .uses(paramBranchCheckpointCount)
      .entry("v0", "2")
      .build()
  }

  // ---- The shared program-order rule --------------------------------------

  val funcRobOlder = spec {
    FUNCTION("RobOlder")
      .desc(
        "The single wrap-aware order function. For two live circular-pointer tags a and b " +
        "of the same order domain, robOlder(a, b) is true iff a was allocated before b: " +
        "(a.wrap == b.wrap) ? (a.idx < b.idx) : (a.idx > b.idx). The comparison is exact " +
        "whenever both tags are live in a window no larger than the pointer range, which " +
        "RobDepth (and FtqDepth for the FTQ instance) guarantees by construction."
      )
      .markdownTable(
        List("Order domain", "Tag", "Users"),
        List(
          List("program order (primary)", "robTag", "ROB, RenameUnit, RS, FU pipelines, BranchUnit, LSQ, RecoveryController, CommitUnit"),
          List("fetch-block order", "ftqIdx", "FetchTargetQueue recovery truncation")
        )
      )
      .uses(paramRobTagWidth)
      .note(
        "Replaces ADR-012 seqOlder. robTag lifetime is exactly the ROB lifetime: an entry " +
        "leaves the order domain at commit (committed stores in the StoreBuffer are older " +
        "than every live uop by construction and need no age compare) or when a RecoveryEvent " +
        "kills it. A naive unsigned '<' on tags is forbidden."
      )
      .build()
  }

  val funcRecoveryKills = spec {
    FUNCTION("RecoveryKills")
      .desc(
        "The common younger-than predicate every speculative holder applies to a RecoveryEvent " +
        "e: an entry with tag t is killed iff e.kind == ArchRedirect, or " +
        "(e.kind == BranchMispredict and robOlder(e.robTag, t)). The recovering branch itself " +
        "(t == e.robTag) and every older entry survive a branch recovery."
      )
      .uses(funcRobOlder)
      .note(
        "Every holder evaluates the predicate in the same cycle it observes the event, for " +
        "every entry and every token it holds on an outgoing edge (valid && !ready), so no " +
        "killed uop's completion can reach a reallocated tag. After any RecoveryEvent the " +
        "next allocated robTag is e.robTag + 1 (ADR-019 D-19.9)."
      )
      .build()
  }

  val propRobTagUniqueness = spec {
    PROPERTY("RobTagUniqueness")
      .desc(
        "No two live uops share a robTag, and the number of live robTags never exceeds " +
        "RobDepth, so funcRobOlder is exact for every pair of live tags."
      )
      .uses(paramRobTagWidth, funcRobOlder)
      .note("Simulation assert in the ROB (ADR-015 D-15.3).")
      .build()
  }

  val propPrfSizingCoversRob = spec {
    PROPERTY("PrfSizingCoversRob")
      .desc(
        "Elaboration legality: IntegerPrfEntries >= ArchRegNum + RobDepth and RobDepth is a " +
        "power of two, so rename never waits for a physical register while the ROB has a " +
        "free entry."
      )
      .uses(paramIntegerPrfEntries, paramRobDepth, paramArchRegNum)
      .note("Elaboration require in BackendParams (ADR-015 D-15.3).")
      .build()
  }
}
