package udacore.core.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.core.spec.shared.CoreParamsSpecs._

/** Core boundary bundles and the core-wide exception payload (ADR-019). */
object CoreBundlesSpecs {
  val bndBootAddr = spec {
    BUNDLE("BootAddr")
      .desc("Boot address for the core domain; seeds FetchPcGen.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("bootAddr", "UInt(vAddrWidth)", "First fetch PC, fetched in M-mode with translation off."))
      )
      .uses(paramVAddrWidth)
      .build()
  }

  val bndHartEnable = spec {
    BUNDLE("HartEnable")
      .desc("Core enable command from outer domains.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("hartEnable", "Bool", "Core enable command from outer domains."))
      )
      .build()
  }

  val bndInterrupt = spec {
    BUNDLE("Interrupt")
      .desc("Interrupt source lines from outer domains.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("e", "Bool", "Machine external interrupt"),
          List("t", "Bool", "Machine timer interrupt"),
          List("s", "Bool", "Machine software interrupt"),
          List("se", "Bool", "Supervisor external interrupt")
        )
      )
      .note(
        "Raw source lines; mip/mie/mideleg evaluation stays in the CSR and the decision to " +
        "take an interrupt is made by CommitUnit at a retire boundary (ADR-004 D-4.5)."
      )
      .build()
  }

  val bndDebugReq = spec {
    BUNDLE("DebugReq")
      .desc("Debug request from external system.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("debugReq", "Bool", "Debug request signal from debug module."))
      )
      .build()
  }

  val bndExceptionInfo = spec {
    BUNDLE("ExceptionInfo")
      .desc(
        "Precise-exception payload carried by a uop from the point of detection to the ROB; " +
        "raised only when the uop reaches the ROB head."
      )
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("valid", "Bool", "The uop faulted."),
          List("cause", "UInt(5)", "RISC-V exception code."),
          List("tval", "UInt(XLen)", "Faulting virtual address or instruction bits.")
        )
      )
      .markdownTable(
        List("Fault", "Cause", "Detected by"),
        List(
          List("instruction address misaligned", "0", "BranchUnit (target not 4-byte aligned)"),
          List("instruction access fault", "1", "ITLB PMA check or I-cache fill denied"),
          List("illegal instruction", "2", "DecodeUnit / CsrController"),
          List("breakpoint", "3", "DecodeUnit (ebreak)"),
          List("load address misaligned", "4", "AddressGenerationUnit"),
          List("load access fault", "5", "DTLB PMA check, PTW PMA check, or D-cache fill denied"),
          List("store address misaligned", "6", "AddressGenerationUnit"),
          List("store access fault", "7", "DTLB PMA check or PTW PMA check"),
          List("environment call from U/S/M", "8/9/11", "DecodeUnit (ecall)"),
          List("instruction page fault", "12", "ITLB"),
          List("load page fault", "13", "DTLB"),
          List("store page fault", "15", "DTLB")
        )
      )
      .note(
        "Page faults and access faults are distinct causes (ADR-019 D-19.6); a PTW read that " +
        "hits a PMA-illegal address is an access fault of the original access type."
      )
      .build()
  }
}
