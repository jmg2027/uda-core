package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._
import udacore.core.spec.shared.MemoryBundlesSpecs._
import udacore.core.spec.shared.Sv32Specs._

/** PageTableWalker: the shared Sv32 walker for both TLBs (ADR-019 D-19.6). */
object PageTableWalkerSpecs {
  val contPageTableWalker = spec {
    CONTRACT("PageTableWalker")
      .desc(
        "Serves ITLB and DTLB walk requests with the Sv32 two-level walk. Every PTE read is a " +
        "physical-address request to the DataCache (so page tables are cached with, and " +
        "coherent with, committed stores); the walker has no path through the DTLB. It is " +
        "also the SFENCE.VMA distribution point: it flushes both TLBs and prevents any walk " +
        "that overlapped the flush from refilling."
      )
      .has(
        intfItlbWalkReqIn,
        intfItlbWalkRespOut,
        intfDtlbWalkReqIn,
        intfDtlbWalkRespOut,
        intfPtwMemReqOut,
        intfPtwMemRespIn,
        intfSfenceVmaIn,
        intfItlbFlushOut,
        intfDtlbFlushOut,
        funcPtwArbitrate,
        funcSv32Walk,
        funcPtwPhysicalAccess,
        funcPtwFlush,
        propPtwNoRecursiveTranslation,
        propWalkFaultTyping
      )
      .uses(paramPtwOutstanding, funcSv32Decompose, funcPmaCheck, propSfenceFlushesAll)
      .note(
        "State classes: the outstanding walk is an uncancelable transaction (its requester may " +
        "have been canceled); it always completes and answers its TLB (propGenerationTagScope). " +
        "Walk results are microarchitectural refills."
      )
      .build()
  }

  val intfItlbWalkReqIn = spec {
    INTERFACE("ItlbWalkReqIn")
      .desc("Walk requests from the InstructionTlb.")
      .uses(bndWalkReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfItlbWalkRespOut = spec {
    INTERFACE("ItlbWalkRespOut")
      .desc("Walk results to the InstructionTlb.")
      .uses(bndWalkResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbWalkReqIn = spec {
    INTERFACE("DtlbWalkReqIn")
      .desc("Walk requests from the DataTlb.")
      .uses(bndWalkReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbWalkRespOut = spec {
    INTERFACE("DtlbWalkRespOut")
      .desc("Walk results to the DataTlb.")
      .uses(bndWalkResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPtwMemReqOut = spec {
    INTERFACE("PtwMemReqOut")
      .desc("Physical PTE reads to the DataCache.")
      .uses(bndPtwMemReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPtwMemRespIn = spec {
    INTERFACE("PtwMemRespIn")
      .desc("PTE data from the DataCache.")
      .uses(bndPtwMemResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfSfenceVmaIn = spec {
    INTERFACE("SfenceVmaIn")
      .desc("SFENCE.VMA tokens from the backend CommitUnit; accepted in the cycle both TLB flush tokens fire.")
      .uses(bndTlbFlush)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfItlbFlushOut = spec {
    INTERFACE("ItlbFlushOut")
      .desc("Flush token to the InstructionTlb.")
      .uses(bndTlbFlush)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDtlbFlushOut = spec {
    INTERFACE("DtlbFlushOut")
      .desc("Flush token to the DataTlb.")
      .uses(bndTlbFlush)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcPtwArbitrate = spec {
    FUNCTION("PtwArbitrate")
      .desc(
        "One walk at a time (PtwOutstanding = 1 in v0). When both TLBs request, alternate " +
        "(round-robin) so neither side starves; the answer returns on the requester's response edge."
      )
      .uses(intfItlbWalkReqIn, intfDtlbWalkReqIn)
      .build()
  }

  val funcSv32Walk = spec {
    FUNCTION("Sv32Walk")
      .desc(
        "a = satp.PPN * 4096; read the level-1 PTE at a + VPN[1] * 4. If V = 0, or R = 0 and W " +
        "= 1: PageFault. If R or X: a leaf at level 1 - a superpage, which requires PPN[0] = 0 " +
        "(else PageFault). Otherwise read the level-0 PTE at PPN * 4096 + VPN[0] * 4 and apply " +
        "the same validity rules; a non-leaf at level 0 is a PageFault. Each PTE address must " +
        "pass funcPmaCheck (readable, else AccessFault); a denied read is an AccessFault. " +
        "Global accumulation: globalSeen starts at 0 and is ORed with G of every PTE read on " +
        "the walk (a non-leaf PTE with G = 1 makes every mapping below it global); the refill " +
        "entry's global = globalSeen. The leaf's R/W/X/U/A/D, the walk ASID, and the PMA " +
        "attribute complete the refill entry; permission checks against the access are made " +
        "by the TLB, not the walker."
      )
      .uses(funcSv32Decompose, funcPmaCheck, bndSv32Pte)
      .build()
  }

  val funcPtwPhysicalAccess = spec {
    FUNCTION("PtwPhysicalAccess")
      .desc(
        "Every PTE read leaves on PtwMemReqOut with the physical address computed by " +
        "funcSv32Walk; the DataCache serves it through its array (hit or fill) like any " +
        "physical read, and never translates it."
      )
      .uses(intfPtwMemReqOut, intfPtwMemRespIn)
      .build()
  }

  val funcPtwFlush = spec {
    FUNCTION("PtwFlush")
      .desc(
        "Accept SfenceVma only when ItlbFlushOut and DtlbFlushOut can fire in the same cycle; " +
        "a walk outstanding at that moment finishes its memory reads but answers Retry, so it " +
        "installs nothing."
      )
      .uses(intfSfenceVmaIn, intfItlbFlushOut, intfDtlbFlushOut, propSfenceFlushesAll)
      .build()
  }

  val propPtwNoRecursiveTranslation = spec {
    PROPERTY("PtwNoRecursiveTranslation")
      .desc(
        "The PageTableWalker has no DataTlb interface, and every PtwMemReq address is a " +
        "physical address derived from satp or a PTE; a walk can never trigger another " +
        "translation or another walk."
      )
      .uses(funcPtwPhysicalAccess)
      .note("Structural (interface set) plus a simulation assert.")
      .build()
  }

  val propWalkFaultTyping = spec {
    PROPERTY("WalkFaultTyping")
      .desc(
        "A walk reports PageFault exactly for the Sv32 PTE-validity failures of funcSv32Walk " +
        "and AccessFault exactly for PMA-illegal or bus-denied PTE reads; the TLB maps them to " +
        "the page-fault or access-fault cause of the original access type."
      )
      .uses(funcSv32Walk)
      .build()
  }
}
