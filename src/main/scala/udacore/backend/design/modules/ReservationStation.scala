package udacore.backend.design.modules

import chisel3._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.ReservationStationSpecs._

/** ReservationStation vertex shell (spec: ReservationStationSpecs).
  *
  * ADR-019 spec phase: every member is a documented placeholder whose contract is
  * the named spec val. Elaboration raises NotImplementedError, which the ADR-018
  * runners report as PENDING (red by construction) until the RTL lands.
  */
@LocalSpec(contReservationStation)
class ReservationStation(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfRsAllocIn)
    val rsAllocIn = ???

    @LocalSpec(intfWakeupBroadcastIn)
    val wakeupBroadcastIn = ???

    @LocalSpec(intfRegisterFileReadReqOut)
    val registerFileReadReqOut = ???

    @LocalSpec(intfRegisterFileReadRespIn)
    val registerFileReadRespIn = ???

    @LocalSpec(intfIssuedUopOut)
    val issuedUopOut = ???

    @LocalSpec(intfFuAvailabilityIn)
    val fuAvailabilityIn = ???

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = ???
  })

  @LocalSpec(funcRsWakeup)
  val rsWakeup = ???

  @LocalSpec(funcSelectOldestReady)
  val selectOldestReady = ???

  @LocalSpec(funcRsRecovery)
  val rsRecovery = ???
}
