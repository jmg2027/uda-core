package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._

object DecodeUnitSpecs {
  val contDecodeUnit = spec {
    CONTRACT("DecodeUnit")
      .desc("Decode unit translates issue payloads into backend uops.")
      .has(
        intfInstructionIssueIn,
        intfDecodedUopOut,
        funcSerializingTag,
        funcExtensionDecodeContribution,
        propDisabledExtensionTraps
      )
      .uses(rawExtensionContribution)
      .build()
  }

  // ADR-017 D-17.2: the decode table is assembled from per-extension contributions.
  val funcExtensionDecodeContribution = spec {
    FUNCTION("ExtensionDecodeContribution")
      .desc(
        "The decode table is the concatenation of the base-ISA rows and each enabled " +
        "extension's Seq[InstPattern] contribution, selected at elaboration by the " +
        "extension's enable parameter (M, C, bit-manip sub-extensions Zba/Zbb/Zbs/Zbc, " +
        "and future extensions alike). Contributions are pure data; no extension owns " +
        "decode logic."
      )
      .note(
        "Imported from the main line's DecoderImpl contribution shape (its one clean part); " +
        "the weaving mechanism around it is forbidden by ADR-017 D-17.1."
      )
      .build()
  }

  // ADR-017 D-17.2: the F-3 lesson as a specification obligation.
  val propDisabledExtensionTraps = spec {
    PROPERTY("DisabledExtensionTraps")
      .desc(
        "A disabled extension's instructions are ABSENT from the assembled decode table " +
        "and therefore decode to the illegal-instruction default. An instruction of a " +
        "disabled extension that produces any architectural result other than an " +
        "illegal-instruction trap is a specification violation."
      )
      .note(
        "Main-line finding F-3 (Zbc disabled but clmul silently returned 0) is the " +
        "counterexample this forbids. Acceptance per config: probe one instruction per " +
        "disabled extension and expect the trap (a verif .scn per config, plus a paired " +
        "elaboration check that the table contains no rows from disabled extensions)."
      )
      .build()
  }

  // ADR-004 D-4.2: tag context-changing ops as serializing.
  val funcSerializingTag = spec {
    FUNCTION("SerializingTag")
      .desc("Set the serializing bit on the decoded uop for CSR, mret, dret, wfi, fence, fence.i, ecall, and ebreak.")
      .note("The serializing bit drives the dispatch single-in-flight gate (ADR-004 D-4.2); it is a decode-time classification only, no state.")
      .uses(intfDecodedUopOut)
      .build()
  }

  val intfInstructionIssueIn = spec {
    INTERFACE("InstructionIssueIn")
      .desc("Instruction issue input.")
      .is(rawReadyValidIntf)
      .uses(bndInstructionIssue)
      .build()
  }

  val intfDecodedUopOut = spec {
    INTERFACE("DecodedUopOut")
      .desc("Decoded uop output.")
      .is(rawReadyValidIntf)
      .uses(bndDecodedUop)
      .build()
  }
}
