package udacore.common.spec

import framework.macros.SpecEmit.spec
import framework.specs.Spec._

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
        "is a ready/valid edge."
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
      .note(
        "Sanctioned classes only: (1) the global epoch broadcast; (2) asynchronous system " +
        "inputs (interrupt lines, debugReq); (3) boot-time static signals (bootAddr, " +
        "hartEn); (4) commit-time broadcast strobes and their payloads (commitGrant, " +
        "archMapRestore, redirectFire, interruptCtrl view) - these publish an already-made " +
        "commit-head decision, so backpressure is meaningless (a consumer cannot un-commit; " +
        "ADR-011/ADR-012); (5) the wakeup broadcast (a non-negotiable fact derived from the " +
        "publish bus, ADR-014). Anything else on this classification is a doctrine violation."
      )
      .note(
        "A broadcast FACT differs from a broadcast TRANSFER: projections of the same event " +
        "that move a queued token (e.g. the StoreCommit view draining into the StoreBuffer) " +
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

  // WP-D OWNS: the epoch-holding-vertex enumeration (ADR-005 D-5.2).
  val propEpochVertexEnumeration = spec {
    PROPERTY("EpochVertexEnumeration")
      .desc(
        "Every epoch-holding vertex is classified exactly one of eager-filter (self-invalidates each cycle) or epoch-exempt-by-construction (holds an epoch but never uses it for a correctness compare)."
      )
      .markdownTable(
        List("Vertex", "Class", "Survivable generations for this vertex (bound is the MAX across vertices, not a sum)"),
        List(
          List("Reservation-station entry", "eager-filter", "1"),
          List("FU request latch", "eager-filter", "1"),
          List("Edge register", "eager-filter", "1"),
          List("Fetch outstanding latch", "eager-filter", "1"),
          List("Issue-queue entry", "eager-filter", "1"),
          List("Slot-slicer straddle carry", "eager-filter", "1"),
          List("Speculative store-buffer entry", "eager-filter", "1"),
          List("Committed store-buffer entry", "epoch-exempt", "0"),
          List("Retire-token epoch cross-check field", "epoch-exempt", "0")
        )
      )
      .note(
        "ADR-005 D-5.2: maxSurvivableGenerations = max over eager-filter vertices of cycles a token is held with a deferred compare = 1 under the eager-filter invariant. Adding a vertex that defers its compare raises the number and forces epochWidth up (GlobalEpochUnit propEpochWrapBound). Committed store-buffer entries are exempt because they are irrevocable and drain regardless of epoch (ADR-003 D-3.5)."
      )
      .note(
        "Machine check 5 (propRegQueueTagged) requires every epoch-holding Reg/Queue design site to be tagged eager-filter or epoch-exempt against this table."
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
        "ADR-015 D-15.2/D-15.3: assertion text inside .code/.note is banned because it emits nothing. An unbound PROPERTY fails the coverage gate. GlobalEpochUnit propEpochToggle is the paired-assert template."
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
        "Machine check 5: every Reg/Queue design site is @LocalSpec-tagged to a sanctioning BUNDLE/FUNCTION, and every such site that holds an epoch is additionally tagged eager-filter or epoch-exempt."
      )
      .uses(propEpochVertexEnumeration)
      .note(
        "ADR-015 D-15.2 / ADR-005 D-5.2: an untagged Reg or an untagged epoch-holding Reg fails the check."
      )
      .build()
  }

  // WP-D OWNS: N-equivalence soundness (ADR-015 D-15.4).
  val propNEquivalence = spec {
    PROPERTY("NEquivalence")
      .desc(
        "For every program in the deterministic interrupt-free regression corpus the CommitUnit retire stream is identical token-for-token across SpeculativeRegNum in {1,2,4,8,...}; only cycle counts differ."
      )
      .markdownTable(
        List("Field", "In compare set"),
        List(
          List("order", "yes"),
          List("pc", "yes"),
          List("rd", "yes"),
          List("wdata", "yes"),
          List("trap", "yes"),
          List("cause", "yes"),
          List("epoch", "no (excluded)")
        )
      )
      .note(
        "ADR-015 D-15.4: epoch is excluded because it legitimately differs between N=1 and N=8 (different redirect counts reach the same instruction); it is a same-run cross-check field only. The corpus is restricted to deterministic interrupt-free programs; async-interrupt precision is proven by a separate order-keyed directed suite (fire after the Kth committed instruction, not the Kth cycle). A first divergent token names the failing instruction and both wdata values."
      )
      .build()
  }
}
