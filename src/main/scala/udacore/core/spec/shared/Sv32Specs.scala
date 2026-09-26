package udacore.core.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.core.spec.shared.CoreParamsSpecs._
import udacore.core.spec.shared.MemoryBundlesSpecs._

/** Sv32 translation semantics shared by the InstructionTlb, DataTlb, and
  * PageTableWalker (ADR-019 D-19.6). Stated once here; the vertices .uses them.
  */
object Sv32Specs {

  val funcSv32Decompose = spec {
    FUNCTION("Sv32Decompose")
      .desc(
        "A 32-bit virtual address is {VPN[1] = va[31:22], VPN[0] = va[21:12], offset = " +
        "va[11:0]}. A 4 KiB leaf maps to pa = {PPN[1], PPN[0], offset} (34 bits); a 4 MiB " +
        "superpage leaf found at level 1 maps to pa = {PPN[1], VPN[0], offset}."
      )
      .uses(paramVAddrWidth, paramPAddrWidth, bndSv32Pte)
      .build()
  }

  val funcTlbMatch = spec {
    FUNCTION("TlbMatch")
      .desc(
        "An entry matches a lookup iff valid, (entry.global or entry.asid == satp.ASID), and " +
        "(superpage ? entry.vpn[19:10] == VPN[1] : entry.vpn == VPN). At most one entry " +
        "matches (a refill never installs a duplicate)."
      )
      .uses(bndTlbEntry)
      .build()
  }

  val funcTranslationMode = spec {
    FUNCTION("TranslationMode")
      .desc(
        "Translation applies iff satp.MODE = Sv32 and the effective privilege is S or U " +
        "(instruction side: priv; data side: dataPriv = MPRV ? MPP : priv). Otherwise the " +
        "access is Bare: pa = zero-extended va, and only funcPmaCheck applies."
      )
      .build()
  }

  val funcSv32PermissionCheck = spec {
    FUNCTION("Sv32PermissionCheck")
      .desc(
        "On a translated hit: Fetch requires X; Load requires R, or X when mstatus.MXR; Store " +
        "requires W. U-mode requires U = 1. S-mode may not fetch from a U page and may load or " +
        "store a U page only when mstatus.SUM. A/D policy: v0 implements the Svade extension - " +
        "hardware never sets A or D; an access to a leaf with A = 0, or a store to a leaf with " +
        "D = 0, is a page fault and software updates the PTE. Any violation is the page fault " +
        "of the access type: instruction (12), load (13), store (15)."
      )
      .build()
  }

  val funcPmaCheck = spec {
    FUNCTION("PmaCheck")
      .desc(
        "After translation (or in Bare mode) the physical address is checked against " +
        "paramPmaMap: an address in no region, a fetch from a non-executable region, or a " +
        "store to a non-writable region is the access fault of the access type (1, 5, 7). The " +
        "region's cacheable attribute is returned with a hit."
      )
      .uses(paramPmaMap)
      .build()
  }

  val propNoFaultCaching = spec {
    PROPERTY("NoFaultCaching")
      .desc("A TLB never installs an entry from a walk that ended in a page fault or access fault, and never installs an entry whose leaf fails the Sv32 legality rules.")
      .uses(funcTlbMatch)
      .build()
  }

  val propSfenceFlushesAll = spec {
    PROPERTY("SfenceFlushesAll")
      .desc(
        "v0 SFENCE.VMA: after the flush token completes, no ITLB or DTLB entry is valid, and " +
        "no walk that started before the flush installs an entry afterwards, for every rs1/rs2 encoding."
      )
      .uses(bndTlbFlush)
      .note("ADR-019 D-19.6: selective address/ASID invalidation is a later optimization.")
      .build()
  }
}
