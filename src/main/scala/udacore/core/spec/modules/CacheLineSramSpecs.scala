package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.spec.Spec._
import udacore.common.spec.DesignRuleSpecs._

object CacheLineSramSpecs {
  val contCacheLineSram     = spec {
    CONTRACT("CacheLineSram")
      .desc(
        "Clocked banked SRAM storage for complete L1 data lines (ADR-020). Each row is way * sets + set. Protocol state is cache-local and recovery-exempt; accepted reads and writes complete regardless of branch recovery."
      )
      .has(
        intfSramReadReq,
        intfSramReadResp,
        intfSramWriteReq,
        bndCacheLineWrite,
        funcSramRead,
        funcSramWrite,
        funcSramPhysicalBanks,
        rawCacheSramMacro
      )
      .build()
  }
  val intfSramReadReq       = spec {
    INTERFACE("SramReadReq")
      .desc(
        "Cache to SRAM row request. One outstanding read; a new request may transfer with the prior response. A same-row write takes priority."
      )
      .is(rawReadyValidIntf)
      .build()
  }
  val intfSramReadResp      = spec {
    INTERFACE("SramReadResp")
      .desc(
        "SRAM to cache complete data line. After an accepted request the macro reads on the intervening falling edge, and the response may transfer at the next rising edge. A blocked response retains all bytes at sampling edges until transfer."
      )
      .is(rawReadyValidIntf)
      .build()
  }
  val intfSramWriteReq      = spec {
    INTERFACE("SramWriteReq")
      .desc(
        "Cache to SRAM byte-masked line write. All enabled bytes update after the accepting rising edge and before the next rising edge; different-row reads may proceed concurrently."
      )
      .uses(bndCacheLineWrite)
      .is(rawReadyValidIntf)
      .build()
  }
  val bndCacheLineWrite     = spec {
    BUNDLE("CacheLineWrite")
      .desc(
        "Logical row, words data vector, and one byte-mask vector entry per word. Row/word widths derive from rows, words and wordBits."
      )
      .markdownTable(
        List("Field", "Meaning"),
        List(
          List("row", "Logical cache-line row"),
          List("data", "Complete line word vector"),
          List("mask", "One enable bit per byte in every word")
        )
      )
      .build()
  }
  val funcSramRead          = spec {
    FUNCTION("SramRead")
      .desc(
        "Issue one clocked read to the selected physical page of every word bank. Preserve identity and bytes through response backpressure. An explicit line holder captures a blocked macro output before the next output-invalidating edge. Read data must not be used when no read response is valid."
      )
      .uses(intfSramReadReq, intfSramReadResp)
      .build()
  }
  val funcSramWrite         = spec {
    FUNCTION("SramWrite")
      .desc(
        "Write exactly the selected bytes and retain all unmasked bytes. Reset never initializes bulk memory. Same-row read/write is serialized with write priority, preventing undefined macro collisions."
      )
      .uses(intfSramWriteReq, intfSramReadReq)
      .build()
  }
  val funcSramPhysicalBanks = spec {
    FUNCTION("SramPhysicalBanks")
      .desc(
        "Instantiate actual sky130_sram_1kbyte_1rw1r_32x256_8 subcores, one per 32-bit word bank and 256-row page. Supported wordBits is 32. Logical rows beyond 256 select additional pages; no line-wide asynchronous Mem is synthesized as the backing store."
      )
      .uses(rawCacheSramMacro)
      .build()
  }
  val rawCacheSramMacro     = spec {
    RAW("Sky130CacheSram", "subcore")
      .desc(
        "Pinned OpenRAM SRAM leaf with one RW port used for byte writes and one read port. Inputs capture on rising edges and reads/writes execute on falling edges. The functional view omits arbitrary model delays; synthesis resolves the cell from its physical library (ADR-020)."
      )
      .build()
  }
}
