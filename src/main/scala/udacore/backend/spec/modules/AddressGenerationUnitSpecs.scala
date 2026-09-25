package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs.funcRecoveryKills

/** AddressGenerationUnit: effective virtual addresses for the LSQ (ADR-019 D-19.5). */
object AddressGenerationUnitSpecs {
  val contAddressGenerationUnit = spec {
    CONTRACT("AddressGenerationUnit")
      .desc(
        "Computes the effective virtual address of a load or store (rs1 + imm), checks natural " +
        "alignment, and delivers {robTag, vaddr, storeData, misaligned} to the LoadStoreQueue. " +
        "It performs no translation and no memory access."
      )
      .has(
        intfAddressGenerationReqIn,
        intfMemAddressOut,
        intfRecoveryEventIn,
        funcAddressGenerate
      )
      .is(rawSpeculativeHolder)
      .note("Speculative-holder stance: holds at most one output token, dropped in the event cycle if funcRecoveryKills selects it.")
      .build()
  }

  val intfAddressGenerationReqIn = spec {
    INTERFACE("AddressGenerationReqIn")
      .desc("Issued load/store uops.")
      .uses(bndIssuedUop)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMemAddressOut = spec {
    INTERFACE("MemAddressOut")
      .desc("Effective address and store data to the LoadStoreQueue entry owned by the uop's robTag.")
      .uses(bndMemAddress)
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

  val funcAddressGenerate = spec {
    FUNCTION("AddressGenerate")
      .desc(
        "vaddr = src1 + imm; misaligned = vaddr not a multiple of the access size; storeData = " +
        "src2 for stores. A misaligned access is reported to the LSQ, which completes the uop " +
        "with a load/store address-misaligned exception (tval = vaddr) without translating it."
      )
      .uses(intfAddressGenerationReqIn, intfMemAddressOut, funcRecoveryKills)
      .build()
  }
}
