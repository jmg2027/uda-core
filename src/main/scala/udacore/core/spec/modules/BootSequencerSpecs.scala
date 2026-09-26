package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.core.spec.shared.CoreBundlesSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._

object BootSequencerSpecs {
  val contBootSequencer = spec {
    CONTRACT("BootSequencer")
      .desc("Coordinates hart enable and boot address delivery with configurable delay")
      .has(
        intfHartEnIn,
        intfBootAddrIn,
        intfBootAddrOut,
        funcBootDelay,
        funcSingleBootPulse,
        funcResetOnDisable,
        propCounterBound
      )
      .uses(paramBootCycles)
      .note("Holds core in reset for bootCycles after hartEn assertion")
      .note("Delivers single boot pulse via Decoupled interface")
      .build()
  }

  val intfHartEnIn = spec {
    INTERFACE("HartEnIn")
      .desc("Hart enable signal from external system")
      .is(rawNoDecoupled)
      .markdownTable(
        List("Signal", "Direction", "Type", "Description"),
        List(
          List("hartEn", "input", "Bool", "Hart enable command")
        )
      )
      .build()
  }

  val intfBootAddrIn = spec {
    INTERFACE("BootAddrIn")
      .desc("Boot address from external system")
      .is(rawNoDecoupled)
      .markdownTable(
        List("Signal", "Direction", "Type", "Description"),
        List(
          List("bootAddr", "input", "UInt(vAddrWidth)", "Initial PC value for boot")
        )
      )
      .build()
  }

  val intfBootAddrOut = spec {
    INTERFACE("BootAddrOut")
      .desc("Boot address output to frontend")
      .is(rawReadyValidIntf)
      .markdownTable(
        List("Signal", "Direction", "Type", "Description"),
        List(
          List("bootOut", "output", "Decoupled(UInt)", "Boot address with valid/ready handshake")
        )
      )
      .note("Valid asserts for single cycle when boot sequence completes")
      .note(
        "The sink of this boot pulse is the frontend FetchPcGen; a single Decoupled boot pulse seeds the speculative fetch PC."
      )
      .build()
  }

  val funcBootDelay = spec {
    FUNCTION("BootDelay")
      .desc("Counts bootCycles after hartEn assertion before releasing bootOut")
      .code("scala", """
        val counter = RegInit(0.U(cntWidth.W))
        when(io.hartEn && bootReg) {
          counter := counter + 1.U
          when(counter === bootCycles.U) {
            bootReg := false.B
          }
        }
      """)
      .note("Ensures stable boot conditions before starting execution")
      .build()
  }

  val funcSingleBootPulse = spec {
    FUNCTION("SingleBootPulse")
      .desc("Generates single valid pulse on bootOut after delay completes")
      .code("scala", """
        io.bootOut.valid := !bootReg && io.hartEn && !bootSent
        when(!bootReg && io.bootOut.fire) {
          bootSent := true.B
        }
      """)
      .note("bootSent flag prevents multiple boot pulses")
      .build()
  }

  val funcResetOnDisable = spec {
    FUNCTION("ResetOnDisable")
      .desc("Resets boot sequence state when hartEn deasserts")
      .code("scala", """
        when(!io.hartEn) {
          bootReg  := true.B
          counter  := 0.U
          bootSent := false.B
        }
      """)
      .note("Allows clean re-initialization on subsequent hartEn pulses")
      .build()
  }

  val propCounterBound = spec {
    PROPERTY("CounterBound")
      .desc(
        "While the hart is enabled and the boot reset (bootReg) is still asserted, the boot delay counter never exceeds bootCycles; it saturates at the bound rather than overflowing."
      )
      .note(
        "Ensures the boot sequence progresses correctly. Paired with an @LocalSpec design assert in BootSequencer (ADR-015 D-15.2/D-15.3); assertion text is not placed in .code/.note."
      )
      .build()
  }
}
