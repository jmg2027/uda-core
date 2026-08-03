package udacore.frontend.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.frontend.spec.shared.FrontendParamsSpecs._
// WP-D publishes the canonical epoch width (ADR-005 D-5.3); consumed read-only.
import udacore.core.spec.shared.CoreParamsSpecs.paramEpochWidth

/** Frontend data-bundle specifications.
  *
  * Field tables below are the ADR-009 section 6 normative payloads. Widths refer
  * to the frontend params (FrontendParamsSpecs) and the core-wide epoch width
  * (WP-D). Every epoch-carrying bundle rides the epoch as data (control-as-data):
  * staleness is a comparison, never a flush wire (ADR-005).
  */
object FrontendBundlesSpecs {

  // ---- Shared sub-bundles ---------------------------------------------------

  // Frontend view of the I-fetch exception (ADR-009 D-9.4 INV-S5). The frontend
  // only TAGS; the backend raises the precise trap at commit (ADR-004).
  val bndException = spec {
    BUNDLE("Exception")
      .desc(
        "Frontend I-fetch exception tag. The frontend never traps; it tags the slot and the backend raises the precise trap at commit (ADR-009 D-9.4, ADR-004)."
      )
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("instrAddrMisaligned", "Bool", "Control transfer targets a 2-byte-but-not-4-byte address with RVC disabled (INV-S5)."),
          List("programMemFault", "Bool", "Program-memory access fault reported on the fetch response."),
          List("excCause", "UInt(redirectCauseWidth)", "Cause code the backend uses to set mcause at commit.")
        )
      )
      .uses(paramRedirectCauseWidth)
      .note("ADR-009 D-9.4: the frontend-tag / backend-raise handoff for I-fetch access fault.")
      .build()
  }

  // ADR-009 s6: Slot = one candidate instruction position.
  val bndSlot = spec {
    BUNDLE("Slot")
      .desc(
        "One candidate instruction position after slicing. Payload is always 32 bits (RVC right-packed, len marks width); carries its own PC, validity, exception tag, and epoch."
      )
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("bits", "UInt(32)", "Raw instruction bits (16b RVC right-packed or 32b RVI)."),
          List("pc", "UInt(programMemoryAddrWidth)", "2-byte-aligned PC of this slot (INV-S4)."),
          List("len", "UInt(2)", "Instruction length: 2 for RVC, 4 for RVI."),
          List("valid", "Bool", "On the (speculative) program path; pruned/invalid slots set false (INV-Q1)."),
          List("exc", "Bool", "I-fetch exception tagged on this slot (INV-S5); backend raises at commit."),
          List("excCause", "UInt(redirectCauseWidth)", "Exception cause code for the backend trap unit."),
          List("epoch", "UInt(epochWidth)", "Epoch stamped upstream; eager-filtered each cycle (ADR-005 D-5.2).")
        )
      )
      .uses(paramProgramMemoryAddrWidth, paramRedirectCauseWidth, paramEpochWidth)
      .build()
  }

  // ---- Top-boundary and pipeline bundles ------------------------------------

  val bndBootAddr = spec {
    BUNDLE("BootAddr")
      .desc("Boot address seed from BootSequencer; enters NextPcGen, not FetchUnit (ADR-009 D-9.1).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("bootAddr", "UInt(programMemoryAddrWidth)", "Boot start PC; the first NextPc token seeds from it with epoch 0.")
        )
      )
      .uses(paramProgramMemoryAddrWidth)
      .build()
  }

  val bndNextPcIssue = spec {
    BUNDLE("NextPcIssue")
      .desc("Selected epoch-tagged next fetch address from NextPcGen to FetchUnit (ADR-009 D-9.2).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("pc", "UInt(programMemoryAddrWidth)", "Next fetch address selected by the priority mux (funcPcSelect)."),
          List("epoch", "UInt(epochWidth)", "Epoch stamped this cycle; on a redirect cycle the POST-increment value (ADR-006 D-6.3, G1).")
        )
      )
      .uses(paramProgramMemoryAddrWidth, paramEpochWidth)
      .build()
  }

  val bndRedirect = spec {
    BUNDLE("Redirect")
      .desc("Backend redirect entering NextPcGen; the single frontend redirect sink (ADR-009 D-9.3).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("target", "UInt(programMemoryAddrWidth)", "New fetch PC; highest priority in funcPcSelect."),
          List("cause", "UInt(redirectCauseWidth)", "Redirect class per the merge ladder (ADR-013)."),
          List("epoch", "UInt(epochWidth)", "The POST-increment epoch the redirect installs (G1, ADR-006 D-6.1).")
        )
      )
      .uses(paramProgramMemoryAddrWidth, paramRedirectCauseWidth, paramEpochWidth)
      .build()
  }

  val bndGlobalEpoch = spec {
    BUNDLE("GlobalEpoch")
      .desc("Global epoch broadcast to every epoch-filtering frontend vertex (rawNoDecoupled).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("epoch", "UInt(epochWidth)", "Current global epoch; compared combinationally each cycle (ADR-005 D-5.1).")
        )
      )
      .uses(paramEpochWidth)
      .note("ADR-006: the commit/redirect epoch path is 0 stages; the speculative copy fed to frontend consumers may be registered at high N.")
      .build()
  }

  val bndExternalProgramMemoryReq = spec {
    BUNDLE("ExternalProgramMemoryReq")
      .desc("Program-memory read request leaving FetchUnit at the frontend top boundary (ADR-009 D-9.2).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("addr", "UInt(programMemoryAddrWidth)", "Beat-aligned fetch address."),
          List("epoch", "UInt(epochWidth)", "Latched request epoch so the response can be matched/killed (G2).")
        )
      )
      .uses(paramProgramMemoryAddrWidth, paramEpochWidth)
      .note("Size/byte-enable field is open (ADR-009 Q5.2); base config is a full-beat read.")
      .build()
  }

  val bndExternalProgramMemoryResp = spec {
    BUNDLE("ExternalProgramMemoryResp")
      .desc("Program-memory response entering FetchUnit from the frontend top boundary.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("data", "UInt(memDataWidth)", "One fetch beat of instruction memory."),
          List("exception", "Exception", "Access-fault tag (programMemFault) folded into slot metadata downstream.")
        )
      )
      .has(bndException)
      .uses(paramProgramMemoryDataWidth)
      .build()
  }

  val bndFetchResponse = spec {
    BUNDLE("FetchResponse")
      .desc("One fetched beat (aligned to memDataWidth) from FetchUnit toward SlotSlicer (ADR-009 D-9.2).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("data", "UInt(memDataWidth)", "One fetch beat (slotsPerBeat half-words)."),
          List("pc", "UInt(programMemoryAddrWidth)", "Base PC of the low half-word of the beat."),
          List("epoch", "UInt(epochWidth)", "Request epoch; a mismatched beat is dropped at the FetchUnit response boundary (G2).")
        )
      )
      .uses(paramProgramMemoryDataWidth, paramProgramMemoryAddrWidth, paramEpochWidth)
      .build()
  }

  val bndSlotGroup = spec {
    BUNDLE("SlotGroup")
      .desc("SlotSlicer output: up to fetchWidth 16-bit-granular instruction slots sliced from one beat plus the straddle carry (ADR-009 D-9.4).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("slots", "Vec(fetchWidth, Slot)", "The sliced candidate instruction slots in PC order."),
          List("count", "UInt(log2Ceil(fetchWidth+1))", "Number of valid slots produced this cycle.")
        )
      )
      .has(bndSlot)
      .uses(paramFetchWidth)
      .build()
  }

  val bndPredictorOutput = spec {
    BUNDLE("PredictorOutput")
      .desc("One epoch-tagged first-taken prediction per slot group, from BranchPredecoder to NextPcGen (ADR-009 D-9.5).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("valid", "Bool", "A prediction is produced this group."),
          List("taken", "Bool", "A taken control transfer exists in the group; else fall through."),
          List("target", "UInt(programMemoryAddrWidth)", "Predicted target (direct/predicted-taken); DONTCARE for indirect."),
          List("srcPc", "UInt(programMemoryAddrWidth)", "PC of the first-taken slot t."),
          List("srcEpoch", "UInt(epochWidth)", "Epoch of the producing group; consumed only if srcEpoch === GlobalEpoch (G3)."),
          List("indirectStall", "Bool", "Set for an indirect jump with unknown target; NextPcGen stalls fetch until redirect (G4).")
        )
      )
      .uses(paramProgramMemoryAddrWidth, paramEpochWidth)
      .build()
  }

  val bndIssueBackend = spec {
    BUNDLE("IssueBackend")
      .desc("IssueQueue output to the backend: up to issueWidth in-order, only-valid, epoch-coherent slots (ADR-009 D-9.7).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("slots", "Vec(issueWidth, Slot)", "Issued slots in PC order (INV-Q3); issueWidth derived from the backend dispatch width."),
          List("count", "UInt", "Number of slots issued this cycle.")
        )
      )
      .has(bndSlot)
      .note("issueWidth is derived from the backend dispatch width (single source of truth in the backend, ADR-009 Q4.2).")
      .build()
  }

  // ---- Internal pipeline bundles retained for the design LocalSpec sites -----

  val bndBoot = spec {
    BUNDLE("Boot")
      .desc("Legacy boot channel bundle retained for design name resolution; boot now rides BootAddr into NextPcGen (ADR-009 D-9.1).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("bootPc", "UInt(programMemoryAddrWidth)", "Boot start PC."),
          List("enable", "Bool", "Hart enable pulse.")
        )
      )
      .uses(paramProgramMemoryAddrWidth)
      .note("Superseded by bndBootAddr; kept only for the FrontendBundles design LocalSpec.")
      .build()
  }

  val bndInstruction = spec {
    BUNDLE("Instruction")
      .desc("Legacy instruction-block bundle retained for design name resolution; superseded by FetchResponse/SlotGroup.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("bytes", "UInt(memDataWidth)", "Instruction bytes for a beat."),
          List("pc", "UInt(programMemoryAddrWidth)", "Base PC."),
          List("epoch", "UInt(epochWidth)", "Request epoch.")
        )
      )
      .uses(paramProgramMemoryDataWidth, paramProgramMemoryAddrWidth, paramEpochWidth)
      .note("Superseded by bndFetchResponse; kept only for the FrontendBundles design LocalSpec.")
      .build()
  }

  val bndAlignInput = spec {
    BUNDLE("AlignInput")
      .desc("SlotSlicer input view retained for design name resolution; the live edge is FetchResponse.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("bytes", "UInt(memDataWidth)", "Fetched beat bytes."),
          List("pc", "UInt(programMemoryAddrWidth)", "Base PC of the beat."),
          List("epoch", "UInt(epochWidth)", "Request epoch for straddle-carry coherence (INV-S3).")
        )
      )
      .uses(paramProgramMemoryDataWidth, paramProgramMemoryAddrWidth, paramEpochWidth)
      .note("Superseded by bndFetchResponse; kept only for the FrontendBundles design LocalSpec.")
      .build()
  }

  val bndAlignOutput = spec {
    BUNDLE("AlignOutput")
      .desc("SlotSlicer output view retained for design name resolution; the live edge is SlotGroup.")
      .has(bndSlot)
      .note("Superseded by bndSlotGroup; kept only for the FrontendBundles design LocalSpec.")
      .build()
  }

  val bndRvcInput = spec {
    BUNDLE("RvcInput")
      .desc("RvcExpander input: one 32-bit right-packed slot payload (RVC or RVI).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("bits", "UInt(32)", "Raw slot bits; bits[1:0] != 11 marks a compressed encoding."),
          List("pc", "UInt(programMemoryAddrWidth)", "Slot PC."),
          List("epoch", "UInt(epochWidth)", "Slot epoch.")
        )
      )
      .uses(paramProgramMemoryAddrWidth, paramEpochWidth)
      .build()
  }

  val bndRvcOutput = spec {
    BUNDLE("RvcOutput")
      .desc("RvcExpander output: a guaranteed 32-bit RV32I slot payload.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("bits", "UInt(32)", "Expanded RV32I instruction."),
          List("pc", "UInt(programMemoryAddrWidth)", "Slot PC."),
          List("epoch", "UInt(epochWidth)", "Slot epoch.")
        )
      )
      .uses(paramProgramMemoryAddrWidth, paramEpochWidth)
      .build()
  }

  val bndPredictorInput = spec {
    BUNDLE("PredictorInput")
      .desc("BranchPredecoder input: an expanded slot group to classify and prune (ADR-009 D-9.5).")
      .has(bndSlot)
      .note("Carries the expanded slots the predecoder classifies into JAL/BR/JALR and prunes first-taken.")
      .build()
  }

  val bndIssueFrontend = spec {
    BUNDLE("IssueFrontend")
      .desc("IssueQueue enqueue input: a predecoded, first-taken-pruned slot group (ADR-009 D-9.7 INV-Q1/Q4).")
      .has(bndSlot)
      .note("Only valid slots are admitted (INV-Q1); at fetchWidth>1 a group enqueues atomically (INV-Q4).")
      .build()
  }
}
