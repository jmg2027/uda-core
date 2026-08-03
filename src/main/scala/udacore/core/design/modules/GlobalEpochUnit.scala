package udacore.core.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.GlobalEpochUnitSpecs._

@LocalSpec(contGlobalEpochUnit)
class GlobalEpochUnit(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfRedirectFireIn)
    val redirectFireIn = Input(Bool())

    @LocalSpec(intfEpochOut)
    val epochOut = Output(UInt(epochWidth.W))
  })

  val epoch        = RegInit(0.U(epochWidth.W))
  val redirectFire = io.redirectFireIn

  @LocalSpec(funcEpochIncrement)
  val epochIncrement = {
    val nextEpoch =
      if (epochWidth == 1) {
        epoch ^ 1.U(1.W)
      } else {
        (epoch + 1.U)(epochWidth - 1, 0)
      }

    when(redirectFire) { epoch := nextEpoch }
    nextEpoch
  }

  @LocalSpec(funcSameCycleEpochExposure)
  val sameCycleEpochExposure = {
    io.epochOut := Mux(redirectFire, epochIncrement, epoch)
  }

  @LocalSpec(propEpochToggle)
  val epochToggle = {
    // Verification-only history: the epoch register must have changed exactly on
    // the cycle following a redirectFire and held otherwise. These registers feed
    // only the assert; they drive no functional output (ADR-015 D-15.2).
    val prevEpoch    = RegNext(epoch, 0.U(epochWidth.W))
    val prevNext     = RegNext(epochIncrement, 0.U(epochWidth.W))
    val prevRedirect = RegNext(redirectFire, false.B)
    assert(
      epoch === Mux(prevRedirect, prevNext, prevEpoch),
      "Epoch must change exactly when redirect fired in the prior cycle"
    )
  }
}
