package udacore.common.spec

import framework.macros.SpecEmit.spec
import framework.specs.Spec._

/** Repository-wide design doctrine (ADR-015, ADR-017, ADR-019 D-19.14).
  *
  * These RAW objects classify interfaces and modules; the PROPERTY objects are
  * the machine checks and the global verification contracts every domain
  * inherits. ADR-019 is the root architecture: program-order speculation is
  * recovered selectively through the common RecoveryEvent, never through a
  * global-epoch kill.
  */
object DesignRuleSpecs {
  val rawDesignRule = spec {
    RAW("DesignRule", "DesignRule")
      .desc("Top-level design rule categories for UDACore")
      .build()
  }

  val rawTop = spec {
    RAW("Top", "Top")
      .desc("RawTop: Graph consists of vertex and edge definitions only.")
      .note(
        "No other logic blocks are allowed inside a raw top. All interfaces are Decoupled (ready/valid) by default in UDA."
      )
      .build()
  }

  val rawDecoupledDefault = spec {
    RAW("DecoupledDefault", "UDADefault")
      .desc(
        "In UDA (Unified Dataflow Architecture), all interfaces are Decoupled (ready/valid) by default."
      )
      .note(
        "Only the enumerated rawNoDecoupled classes may bypass Decoupled; everything else " +
        "is a ready/valid edge. A stall is ready backpressure on an edge, never a dedicated wire."
      )
      .build()
  }

  val rawReadyValidIntf = spec {
    RAW("ReadyValidInterface", "ReadyValidInterface")
      .desc("Interface must implement the ready/valid protocol.")
      .build()
  }

  val rawNoDecoupled = spec {
    RAW("NoDecoupled", "NoDecoupled")
      .desc("Interface bypasses Decoupled protocol.")
      .markdownTable(
        List("Class", "Sanctioned use", "Example"),
        List(
          List("1", "local transaction-generation tag of an uncancelable request/response pair (never a program-order kill)", "fetch generation on I-cache responses"),
          List("2", "asynchronous system inputs", "interrupt lines, debugReq"),
          List("3", "boot-time statics", "bootAddr, hartEn"),
          List("4", "commit-time broadcast strobes and committed-state views", "commitGrant, ROB head position, translation context"),
          List("5", "wakeup broadcast derived from result publication", "WakeupBroadcast"),
          List("6", "ADR-019 speculative RecoveryEvent broadcast", "RecoveryEvent")
        )
      )
      .note(
        "Anything else on this classification is a doctrine violation. The RecoveryEvent " +
        "(class 6) publishes an already-made recovery decision; it is not a queued transfer " +
        "and cannot be backpressured. It is the ONLY sanctioned program-order squash fact: " +
        "no other flush/kill/stall side-channel exists, and each speculative holder derives " +
        "its own younger-than invalidation locally (ADR-019 D-19.9, D-19.14)."
      )
      .note(
        "A broadcast FACT differs from a broadcast TRANSFER: projections of the same event " +
        "that move a queued token (e.g. the committed-store handoff into the StoreBuffer) " +
        "remain ready/valid edges; only the strobe-like projections ride this class."
      )
      .build()
  }

  val rawExtensionContribution = spec {
    RAW("ExtensionContribution", "ExtensionContribution")
      .desc(
        "ADR-017: an optional extension contributes ONLY as (a) an optional vertex wired " +
        "over INTERFACE-spec'd edges in a rawTop, and/or (b) data contributions to the two " +
        "designated merge points: per-extension decode rows (Seq[InstPattern], gated by the " +
        "enable at elaboration) and CSR map entries (Map[Int, Csr]) merged into the single " +
        "CsrAccess.readFromCsr map."
      )
      .note(
        "Forbidden (main-line Feature-pattern mechanisms): connectIo-style functions that " +
        "reach into another vertex's internal signals; hardware constructed as a side " +
        "effect of method calls whose call-site position matters; re-instantiating `def` " +
        "feature handles. A disabled extension is ABSENT from decode so its instructions " +
        "trap illegal (propDisabledExtensionTraps), never compute silently."
      )
      .build()
  }

  val rawSpeculativeHolder = spec {
    RAW("SpeculativeHolder", "SpeculativeHolder")
      .desc(
        "ADR-019 classification for every vertex that holds program-order speculative " +
        "state (ROB, rename checkpoints, RS, FU pipelines, LSQ, FTQ, fetch buffer, " +
        "in-flight fetch). Its CONTRACT MUST state: (1) how entries are ordered; (2) what " +
        "makes an entry live; (3) how the RecoveryEvent decides younger (funcRecoveryKills " +
        "over funcRobOlder, or the FTQ-index instance of the same order rule); (4) what " +
        "state survives recovery; (5) how resources are reclaimed."
      )
      .note(
        "Committed/architectural state (rRAT, committed StoreBuffer entries, cache lines, " +
        "TLB entries, predictor tables) is recovery-exempt and says so explicitly."
      )
      .build()
  }

  val rawZeroCycle = spec {
    RAW("Cycle", "0")
      .desc("Represents a zero-cycle design.")
      .build()
  }

  val rawMultiCycle = spec {
    RAW("Cycle", "N")
      .desc("Represents a multi-cycle design.")
      .build()
  }

  val rawVariableCycle = spec {
    RAW("Cycle", "V")
      .desc("Represents a variable-cycle design.")
      .build()
  }

  // ADR-019 D-19.10: the only remaining role of an epoch/generation field.
  val propGenerationTagScope = spec {
    PROPERTY("GenerationTagScope")
      .desc(
        "A generation tag is permitted only as local bookkeeping for a request/response " +
        "transaction that cannot be canceled after issue; it never decides whether an " +
        "older or younger program-order uop lives. Program-order speculative state (ROB, " +
        "RS, LSQ, FTQ, fetch buffer, rename checkpoints, FU pipelines) is recovered only by " +
        "the RecoveryEvent younger-than rule and MUST NOT be killed by a generation or " +
        "global-epoch mismatch."
      )
      .markdownTable(
        List("Transaction", "Tag owner", "Stale-response meaning"),
        List(
          List("I-cache lookup/fill for a fetch request", "FetchUnit fetch generation", "response for a canceled fetch is dropped; a fill may still install"),
          List("D-cache load lookup/miss for an LQ entry", "LoadStoreQueue per-entry allocation generation", "response for a killed or reallocated LQ entry is dropped; the fill may still install"),
          List("PTW walk for a TLB miss", "requesting TLB miss context", "a walk that raced an SFENCE.VMA does not refill; otherwise the refill may install"),
          List("Committed StoreBuffer drain", "none (exempt)", "committed stores are irrevocable and never canceled")
        )
      )
      .note(
        "ADR-019 supersedes ADR-005 for program-order speculation. Adding a ROB/RS/LSQ/FTQ " +
        "entry to this table is an architecture error. There is no global epoch counter " +
        "and no GlobalEpochUnit in the ADR-019 core."
      )
      .build()
  }

  // WP-D OWNS: the five ADR-015 D-15.2 machine checks.
  val propGraphConsistency = spec {
    PROPERTY("GraphConsistency")
      .desc(
        "Machine check 1: for each rawTop CONTRACT the edges drawn in its mermaid graph reconcile with the union of the child modules' INTERFACE sets; every mermaid edge has a producing and a consuming INTERFACE and every non-terminal INTERFACE appears on exactly one edge."
      )
      .note(
        "ADR-015 D-15.2: recovered from @LocalSpec annotation sites, not from the Unit-valued spec objects. Normative graphs MUST live inside .draw(\"mermaid\", ...), not markdown prose. A mismatch fails the build and names the missing or extra edge."
      )
      .build()
  }

  val propPropertyToAssertion = spec {
    PROPERTY("PropertyToAssertion")
      .desc(
        "Machine check 2: every PROPERTY spec that states an invariant is bound to a concrete @LocalSpec design assert or require carrying the same spec id, or is marked manual via the in-tree allowlist."
      )
      .note(
        "ADR-015 D-15.2/D-15.3: assertion text inside .code/.note is banned because it emits nothing. An unbound PROPERTY fails the coverage gate. BootSequencer propCounterBound is the paired-assert template."
      )
      .build()
  }

  val propLocalSpecCoverage = spec {
    PROPERTY("LocalSpecCoverage")
      .desc(
        "Machine check 3: every INTERFACE/FUNCTION/CONTRACT referenced by a CONTRACT .has/.uses is bound by at least one @LocalSpec annotation in the design file, and every @LocalSpec names a live spec val."
      )
      .note(
        "ADR-015 D-15.2: orphans on either side fail the gate, turning the spec-first rule into an enforced check."
      )
      .build()
  }

  val propRawTopWiringOnly = spec {
    PROPERTY("RawTopWiringOnly")
      .desc(
        "Machine check 4: rawTop module bodies contain only :<>= vertex-to-edge wiring, with no behavioral logic."
      )
      .note(
        "ADR-015 D-15.2 (critique V-MI-1): a rawTop body with an assignment other than :<>= vertex wiring fails the check."
      )
      .build()
  }

  val propRegQueueTagged = spec {
    PROPERTY("RegQueueTagged")
      .desc(
        "Machine check 5: every Reg/Queue design site is @LocalSpec-tagged to a sanctioning BUNDLE/FUNCTION, and every site holding program-order speculative state is tagged to the FUNCTION that states its RecoveryEvent behavior (or to its recovery-exempt classification)."
      )
      .uses(propGenerationTagScope, rawSpeculativeHolder)
      .note(
        "ADR-015 D-15.2 as amended by ADR-019: an untagged Reg, or a speculative Reg with no stated recovery stance, fails the check."
      )
      .build()
  }

  // WP-D OWNS: the retire-stream golden contract (ADR-015 D-15.4 soundness,
  // re-based by ADR-019 from N-equivalence onto ISA-model equivalence).
  val propIsaRetireEquivalence = spec {
    PROPERTY("IsaRetireEquivalence")
      .desc(
        "For every program in the deterministic regression corpus (RV32IM_Zicsr_Zifencei, Svade, U/S, Sv32, " +
        "including page-fault cases) the CommitUnit retire stream is identical " +
        "token-for-token to the ISA reference model's retire stream; only cycle counts differ."
      )
      .markdownTable(
        List("Field", "In compare set"),
        List(
          List("order", "yes"),
          List("pc", "yes"),
          List("insn", "yes"),
          List("rd", "yes"),
          List("wdata", "yes"),
          List("trap", "yes"),
          List("cause", "yes"),
          List("tval", "yes"),
          List("priv", "yes")
        )
      )
      .note(
        "ADR-015 D-15.4 soundness is retained: the equivalence corpus is deterministic and " +
        "interrupt-free; asynchronous-interrupt precision is tested by a separate directed " +
        "suite whose injection is keyed to retire order (after the Kth committed " +
        "instruction). The old N=1-vs-N=8 comparison has no ADR-019 meaning and is removed."
      )
      .build()
  }
}
