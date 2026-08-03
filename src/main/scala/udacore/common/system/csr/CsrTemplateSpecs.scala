package udacore.common.system.csr

import framework.macros.SpecEmit.spec
import framework.specs.Spec._

/** CSR register template library specifications.
  *
  * Ported from the main line's proven decorator family (its include/csr package): one
  * base abstraction models a single Zicsr-accessible CSR, and special
  * access/storage patterns are composable decorators. This library is
  * MECHANISM only - CSR ownership and the execute-at-commit discipline stay
  * with TrapController/CsrController per ADR-004; the library's access()/
  * commit() calls are what those owners invoke at the retire boundary.
  */
object CsrTemplateSpecs {

  val contCsrTemplate = spec {
    CONTRACT("CsrTemplate")
      .desc("""CSR register template library.
              | Csr[T] models one CSR responding to Zicsr accesses with a
              | three-stage write proposal pipeline (regNext -> legalize ->
              | storage). Decorators compose special patterns on the same
              | protocol: Mirror (field aliasing to a canonical CSR), Shadow
              | (windowed storage inside a parent CSR), Indirect (bank
              | selection on one address), Counter (self-increment), Custom
              | (fully bespoke access).
              """)
      .has(funcCsrWriteProtocol, funcCsrSharedWritePath, funcCsrMirror, funcCsrShadow,
           funcCsrIndirect, funcCsrCounter, funcCsrCustom, propCsrLegalizeTotal)
      .note(
        "ADR-004: the library never decides WHEN a CSR write happens - the serialized " +
        "commit-time strobe from CommitUnit/TrapController drives access()/commit(). " +
        "The rvviWb/rvviWrittenData hooks feed the ADR-010 retire stream."
      )
      .note(
        "Not an edge protocol: CSRs are state inside the CSR-owning vertex, so this " +
        "library is deliberately outside the ready/valid edge rules. Only the owning " +
        "vertex's interfaces are dataflow edges."
      )
      .build()
  }

  val funcCsrWriteProtocol = spec {
    FUNCTION("CsrWriteProtocol")
      .desc(
        "Write proposal pipeline: doWrite bodies mutate regNext (defaulted from the read " +
        "view each cycle); legalize(regNext) produces regNextLegal; storage commits " +
        "regNextLegal at the clock edge. Reads always go through the read view `reg` " +
        "(equal to storage for a plain CSR; decorators may overlay)."
      )
      .note(
        "Zicsr access(): RW writes `in`, RS ors it into the read value, RC clears those " +
        "bits; csrrs/csrrc with rs1=x0 (register form, in===0) are reads with no write " +
        "side effect (writeEnable false)."
      )
      .build()
  }

  val funcCsrSharedWritePath = spec {
    FUNCTION("CsrSharedWritePath")
      .desc(
        "readFromCsr builds the access circuit for a CSR map: sharedWritable CSRs read " +
        "through readView and receive ONE globally computed write-modify value via " +
        "commit() (a CSR write is one-hot, so the RW/RS/RC modify network is built once, " +
        "not per CSR). Decorators with custom semantics opt out (sharedWritable=false) " +
        "and keep the coupled per-CSR access() path. Contiguous banks register as blocks " +
        "(one range check + shared one-hot) instead of per-entry comparators."
      )
      .build()
  }

  val funcCsrMirror = spec {
    FUNCTION("CsrMirror")
      .desc(
        "Mirror decorator: listed fields alias a canonical field of another CSR. Reads " +
        "return the canonical value; writes forward to the canonical CSR (whose own " +
        "legalize runs); rvviWrittenData overlays the target's legalized value."
      )
      .note("RISC-V aliased-field semantics (mstatus views, CLIC xstatus etc.).")
      .build()
  }

  val funcCsrShadow = spec {
    FUNCTION("CsrShadow")
      .desc(
        "Shadow decorator: this CSR is a window onto a subfield of a parent CSR's " +
        "storage (fflags/frm inside fcsr). No standalone register; writes go through " +
        "the shadow's own legalize into the parent's subfield."
      )
      .build()
  }

  val funcCsrIndirect = spec {
    FUNCTION("CsrIndirect")
      .desc(
        "Indirect decorator: one Zicsr address multiplexes N backing CSRs selected by an " +
        "external index (tselect -> tdata1/2/3 style). Reads return the selected bank; " +
        "writes commit only to it with per-bank legalize; RVVI writeback fires on the " +
        "selected bank."
      )
      .build()
  }

  val funcCsrCounter = spec {
    FUNCTION("CsrCounter")
      .desc(
        "Counter decorator: self-incrementing CSR (cycle/instret/hpmcounter). Increments " +
        "by `inc` when `inhibit` is low; a same-cycle Zicsr write wins by last-connect " +
        "semantics, matching the priority the RISC-V counter spec requires."
      )
      .build()
  }

  val funcCsrCustom = spec {
    FUNCTION("CsrCustom")
      .desc(
        "Custom decorator: full access() escape hatch for CSRs whose Zicsr semantics fit " +
        "no other pattern (claim-on-read interrupt CSRs like mnxti). Use sparingly."
      )
      .build()
  }

  val propCsrLegalizeTotal = spec {
    PROPERTY("CsrLegalizeTotal")
      .desc(
        "Every value committed to CSR storage has passed that CSR's legalize function - " +
        "there is no write path around doWrite. WARL behaviour is therefore total: " +
        "software can never observe an illegal stored value."
      )
      .note(
        "Paired design assert lands with the first CSR-owning vertex RTL (ADR-015 D-15.2): " +
        "assert(storage subsumed by legalize fixpoint) per CSR, or a checker over doWrite " +
        "call sites."
      )
      .build()
  }
}
