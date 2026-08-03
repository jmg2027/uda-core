package udacore.frontend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

object RvcExpanderSpecs {
  val contRvcExpander = spec {
    CONTRACT("RvcExpander")
      .desc("Expands RISC-V compressed instructions to standard 32-bit format")
      .has(
        intfCompressedIn,
        intfExpandedOut,
        funcRvcDecode
      )
      .note("RVC decoder inflates 16-bit C extension instructions to standard RV32I encoding")
      .note(
        "RvcExpander is an internal per-slot combinational helper of SlotSlicer, NOT a rawTop graph vertex (chief-architect ruling; ONBOARDING: modules inside a vertex's box are not vertices). SlotSlicer applies it per slot before slot emission, so the FrontendTop graph draws align --SlotGroup--> predecbp directly with no RvcExpander node."
      )
      .note(
        "Its scalar CompressedIn/ExpandedOut (rawNoDecoupled) are a documented exception: per-slot helper wires, not ready/valid graph edges."
      )
      .build()
  }

  val intfCompressedIn = spec {
    INTERFACE("CompressedIn")
      .desc("32-bit instruction input that may contain compressed encoding")
      .is(rawNoDecoupled)
      .markdownTable(
        List("Signal", "Direction", "Type", "Description"),
        List(
          List("in", "input", "UInt(32.W)", "Instruction word (compressed or standard)")
        )
      )
      .build()
  }

  val intfExpandedOut = spec {
    INTERFACE("ExpandedOut")
      .desc("Guaranteed 32-bit expanded instruction output")
      .is(rawNoDecoupled)
      .markdownTable(
        List("Signal", "Direction", "Type", "Description"),
        List(
          List("out", "output", "UInt(32.W)", "Expanded instruction in RV32I encoding")
        )
      )
      .build()
  }

  val funcRvcDecode = spec {
    FUNCTION("RvcDecode")
      .desc("Detects compressed encoding and expands to full 32-bit instruction")
      .code("scala", """
        when(io.in(1, 0) =/= 3.U) {
          io.out := new RVCDecoder(
            io.in,
            xLen = instLen,
            useAddiForMv = false
          ).decode.bits
        }.otherwise {
          io.out := io.in
        }
      """)
      .note("Instructions with bits[1:0] != 11 are compressed and require expansion")
      .note("Standard 32-bit instructions pass through unchanged")
      .build()
  }
}
