package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.ReservationStationSpecs

/** ReservationStation vertex shell described by ReservationStation.
  */
@LocalSpec(ReservationStationSpecs.contReservationStation)
class ReservationStation(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    // Buffer ready uops and arbitrate issue per ReservationStation.
  })
}
