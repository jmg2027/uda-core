package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.frontend.spec.shared.FrontendBundlesSpecs.bndFetchPacket
import udacore.frontend.spec.shared.FrontendParamsSpecs.paramDecodeWidth

/** DecodeUnit: RV32IM_Zicsr_Zifencei + privileged-instruction decode, two per cycle
  * (ADR-019 D-19.1, D-19.3; ADR-017 decode contributions).
  */
object DecodeUnitSpecs {
  val contDecodeUnit = spec {
    CONTRACT("DecodeUnit")
      .desc(
        "Decodes up to DecodeWidth fixed 32-bit instructions per cycle from the frontend " +
        "FetchPacket into DecodedUops in program order, classifies control-flow, memory, and " +
        "serializing uops, checks the frontend prediction against the decoded instruction " +
        "class, and turns fetch faults and illegal encodings into precise exception payloads."
      )
      .has(
        intfFetchPacketIn,
        intfDecodedPacketOut,
        intfRecoveryEventIn,
        funcDecodeRv32im,
        funcExtensionDecodeContribution,
        funcSerializingTag,
        funcPredictionCheck,
        funcDecodeRecovery,
        propDisabledExtensionTraps,
        propNoCompressedDecode
      )
      .uses(rawExtensionContribution, paramDecodeWidth)
      .is(rawSpeculativeHolder)
      .note(
        "Speculative-holder stance: decode holds at most one packet, in program order, with no " +
        "robTag yet. Every held uop is younger than every renamed uop, so any RecoveryEvent " +
        "discards the whole packet."
      )
      .build()
  }

  val intfFetchPacketIn = spec {
    INTERFACE("FetchPacketIn")
      .desc("Instruction packets from the frontend FetchBuffer.")
      .uses(bndFetchPacket)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDecodedPacketOut = spec {
    INTERFACE("DecodedPacketOut")
      .desc(
        "Up to DecodeWidth DecodedUops, oldest first, to RenameUnit. RenameUnit consumes " +
        "RenameWidth lanes per cycle; the packet transfer completes when its last lane is renamed."
      )
      .uses(bndDecodedUop)
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

  val funcDecodeRv32im = spec {
    FUNCTION("DecodeRv32im")
      .desc(
        "Decode RV32I (including FENCE, ECALL, EBREAK), M, Zicsr, Zifencei (FENCE.I), and the " +
        "privileged MRET, SRET, WFI, and " +
        "SFENCE.VMA. Any other encoding, and any privileged instruction executed below its " +
        "required privilege (SRET in U, MRET below M, SFENCE.VMA in U or with mstatus.TVM, WFI " +
        "per mstatus.TW), becomes an illegal-instruction exception payload (cause 2, tval = " +
        "instruction bits). ECALL/EBREAK become their exception payloads. A fetch fault on " +
        "the slot overrides decoding."
      )
      .uses(intfFetchPacketIn, intfDecodedPacketOut)
      .build()
  }

  val funcExtensionDecodeContribution = spec {
    FUNCTION("ExtensionDecodeContribution")
      .desc(
        "The decode table is the concatenation of the base-ISA rows and each enabled " +
        "extension's Seq[InstPattern] contribution, selected at elaboration by the " +
        "extension's enable parameter. v0 enables exactly M, Zicsr, and Zifencei; bit-manipulation, A, " +
        "F, and C contribute no rows. Contributions are pure data; no extension owns decode logic."
      )
      .uses(rawExtensionContribution)
      .build()
  }

  val funcSerializingTag = spec {
    FUNCTION("SerializingTag")
      .desc(
        "Set serialize for every CSR instruction, MRET, SRET, WFI, FENCE, FENCE.I, and " +
        "SFENCE.VMA, and classify sysOp: Mret, Sret, Wfi, Fence, FenceI, SfenceVma for those " +
        "(fuType System); CsrWrite for CSRRW/CSRRWI, for CSRRS/CSRRC with rs1 != x0, and for " +
        "CSRRSI/CSRRCI with zimm != 0 (fuType Csr); None for read-only CSR instructions (which " +
        "keep serialize), for every other instruction, and for any uop carrying a fetch or " +
        "decode exception. RenameUnit renames a serialize uop only into an empty ROB and renames " +
        "nothing after it until the ROB is empty again (ADR-004 D-4.2, re-based on the ROB)."
      )
      .note("ADR-019A E-2: sysOp is an explicit DecodedUop field; op never carries commit semantics.")
      .uses(intfDecodedPacketOut)
      .build()
  }

  val funcPredictionCheck = spec {
    FUNCTION("PredictionCheck")
      .desc(
        "Classify control flow (Branch, Jal, Jalr, and the Call/Ret link-register hints) and " +
        "set isCfi. If the frontend marked a slot predictedTaken but it decodes as a non-CFI, " +
        "set predictionFault: the uop executes normally and its commit triggers an " +
        "ArchRedirect(Refetch) to pc + 4. Decode never redirects fetch itself."
      )
      .uses(intfDecodedPacketOut)
      .note("With full-tag BTB entries (paramBtbGeometry) this arises only after code modification; it is never on the performance path.")
      .build()
  }

  val funcDecodeRecovery = spec {
    FUNCTION("DecodeRecovery")
      .desc("On any RecoveryEvent, discard the held packet and any unaccepted DecodedPacketOut token in the event cycle.")
      .uses(intfRecoveryEventIn)
      .build()
  }

  val propDisabledExtensionTraps = spec {
    PROPERTY("DisabledExtensionTraps")
      .desc(
        "A disabled extension's instructions are absent from the assembled decode table and " +
        "therefore decode to the illegal-instruction default; any other architectural result " +
        "is a specification violation."
      )
      .note(
        "Main-line finding F-3 (Zbc disabled but clmul silently returned 0) is the " +
        "counterexample this forbids. Acceptance: a per-config probe per disabled extension " +
        "(a .scn) plus an elaboration check that no disabled rows are present."
      )
      .build()
  }

  val propNoCompressedDecode = spec {
    PROPERTY("NoCompressedDecode")
      .desc(
        "An instruction word whose bits[1:0] != 2'b11 (a C-extension encoding) always decodes " +
        "to an illegal-instruction exception in the v0 core; no slot is ever interpreted as a " +
        "16-bit instruction."
      )
      .uses(funcDecodeRv32im)
      .build()
  }
}
