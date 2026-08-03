package udacore.core.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.BootSequencerSpecs._

@LocalSpec(contBootSequencer)
class BootSequencer(bootCycles: Int = 2, val params: CoreParams)
    extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfHartEnIn)
    val hartEnIn = Input(Bool())

    @LocalSpec(intfBootAddrIn)
    val bootAddrIn = Input(UInt(vAddrWidth.W))

    @LocalSpec(intfBootOut)
    val bootOut = Decoupled(UInt(vAddrWidth.W))
  })

  val bootReg  = RegInit(true.B)
  val cntWidth = log2Ceil(bootCycles + 1)
  val counter  = RegInit(0.U(cntWidth.W))
  val bootSent = RegInit(false.B)

  val bootAddr = Wire(UInt(vAddrWidth.W))
  bootAddr := io.bootAddrIn

  @LocalSpec(funcBootDelay)
  val bootDelay = when(io.hartEnIn) {
    when(bootReg) {
      counter := counter + 1.U
      when(counter === bootCycles.U) { bootReg := false.B }
    }
  }

  @LocalSpec(funcSingleBootPulse)
  val singleBootPulse = when(io.hartEnIn) {
    when(!bootReg && io.bootOut.fire) {
      bootSent := true.B
    }
  }

  @LocalSpec(funcResetOnDisable)
  val resetOnDisable = when(!io.hartEnIn) {
    bootReg  := true.B
    counter  := 0.U
    bootSent := false.B
  }

  @LocalSpec(propCounterBound)
  val counterBound = when(io.hartEnIn) {
    assert(!bootReg || counter <= bootCycles.U)
  }

  io.bootOut.valid := !bootReg && io.hartEnIn && !bootSent
  io.bootOut.bits  := bootAddr
}
