package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object CsrControllerSpecs {
  val contCsrController = spec {
    CONTRACT("CsrController")
      .desc(
        "CSR controller services software CSR read/modify/write requests. Per ADR-004 the CSR node computes NO exception and self-writes NO trap CSR; the TrapController is the single trap owner and the CSR only applies the TrapController's CSRTrapWrite verbatim."
      )
      .has(
        intfCsrReqIn,
        intfCsrResultOut,
        intfCsrTrapReadOut,
        intfCsrTrapWriteIn,
        intfCommitGrantIn,
        funcCsrExecuteAtCommit,
        funcSerializingClass,
        funcCsrMapContribution,
        propNoSpeculativeCsrWrite
      )
      .uses(rawExtensionContribution)
      .note(
        "ADR-004 D-4.3: the CSR-internal trap writer and the internal exception encoder are deleted; owner sign-off tracked as OQ-E. This spec change does NOT edit csr/CSR.scala design code."
      )
      .note(
        "ADR-004 D-4.1: the CSR node retains ONLY the commit-gated software CSRRW/RS/RC datapath; the old CSR value rides the publish bus as the rd writeback."
      )
      .build()
  }

  val intfCsrReqIn = spec {
    INTERFACE("CSRReqIn")
      .desc("CSR request stream entering the controller.")
      .uses(bndCsrReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrResultOut = spec {
    INTERFACE("CSRResultOut")
      .desc(
        "CSR execution result stream emitted toward the publish multiplexer; carries the OLD CSR value as the rd writeback (ADR-004 D-4.1)."
      )
      .uses(bndCsrResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrTrapReadOut = spec {
    INTERFACE("CSRTrapReadOut")
      .desc("Live CSR trap snapshot emitted toward the trap controller.")
      .uses(bndCsrTrapRead)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrTrapWriteIn = spec {
    INTERFACE("CSRTrapWriteIn")
      .desc(
        "Trap-driven CSR write stream entering the controller; the CSR applies it verbatim under trapWriteFire as its only trap-driven mutation (ADR-004 D-4.3)."
      )
      .uses(bndCsrTrapWrite)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCommitGrantIn = spec {
    INTERFACE("CommitGrantIn")
      .desc(
        "Per-uop commit strobe (commitGrant view of the unified commit broadcast) qualifying every architectural CSR write."
      )
      .uses(bndCommitBroadcast)
      .is(rawNoDecoupled)
      .note(
        "ADR-012: commitGrant is a projected view {seqTag, epoch, valid} of bndCommitBroadcast keyed by the canonical seqTag (WP-A). The CSR node ANDs commitGrant.valid into every register write-enable; no grant, no write."
      )
      .build()
  }

  val funcCsrExecuteAtCommit = spec {
    FUNCTION("CsrExecuteAtCommit")
      .desc(
        "A CSR uop reads the architectural value at its FU visit and returns the OLD value as the rd writeback CSRRW/RS/RC owe; the intended write is staged and applied ONLY on the matching commitGrant strobe."
      )
      .uses(intfCsrReqIn, intfCsrResultOut, intfCommitGrantIn)
      .note(
        "ADR-004 D-4.1: no CSR register is mutated during the FU visit. At N=1 the FU visit and commitGrant coincide; the cost is one AND term on the existing write-enable."
      )
      .entry(
        "rule",
        "csr_write_enable = decoded_wen AND commitGrant.valid AND (commitGrant.seqTag == req.seqTag) AND (req.epoch == globalEpoch)"
      )
      .build()
  }

  val funcSerializingClass = spec {
    FUNCTION("SerializingClass")
      .desc(
        "CSR, mret, dret, wfi, fence, fence.i, ecall, and ebreak carry a serializing bit; at most one serializing uop is in flight so a CSR read never observes a not-yet-committed CSR write."
      )
      .uses(intfCsrReqIn, intfCommitGrantIn)
      .note(
        "ADR-004 D-4.2: dispatch grants the CSR/system edge ready only when no serializing uop is outstanding, tracked by a 1-bit scoreboard set at dispatch and cleared at commitGrant. Ready backpressure only; no node-internal stall counter. Decode-side tagging is a WP-A backend mechanic."
      )
      .build()
  }

  val propNoSpeculativeCsrWrite = spec {
    PROPERTY("NoSpeculativeCsrWrite")
      .desc(
        "An architectural CSR register write-enable asserts only when the retiring uop holds the matching commitGrant and its epoch equals the global epoch."
      )
      .uses(intfCommitGrantIn)
      .note(
        "ADR-004 verification: monitor that csr_reg_write_enable implies commitGrant.valid and commitGrant.seqTag == req.seqTag and req.epoch == globalEpoch. Simulation-assert per ADR-015 D-15.3, paired with an @LocalSpec design assert when the CSR body lands."
      )
      .build()
  }

  // ADR-017 D-17.3: extension CSRs enter through the single merged map.
  val funcCsrMapContribution = spec {
    FUNCTION("CsrMapContribution")
      .desc(
        "The Zicsr address map is assembled from the base machine map plus each enabled " +
        "extension's Map[Int, Csr] contribution (and optional contiguous banks), merged " +
        "into the one CsrAccess.readFromCsr call this vertex owns. No extension " +
        "instantiates its own Zicsr access path; per-CSR WARL semantics ride each " +
        "contributed Csr's legalize (common/system/csr library, propCsrLegalizeTotal)."
      )
      .note(
        "Imported from the main line's CSRImpl.csrMap contribution shape; the commit-time " +
        "strobe discipline (ADR-004, intfCommitGrantIn) is unchanged - contributions add " +
        "ADDRESSES, never a second write path."
      )
      .build()
  }
}
