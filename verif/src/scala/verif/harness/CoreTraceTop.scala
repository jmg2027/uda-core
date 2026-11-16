package verif.harness

import chisel3._
import chisel3.util.experimental.BoringUtils
import udacore.core.design.shared.CoreParams
import udacore.core.design.top.CoreTop

/** Simulation-only observation wrapper. No probe feeds a product input or
  * handshake.
  */
class CoreTraceTop(p: CoreParams) extends CoreTop(p) {
  override def desiredName: String = "CoreTraceTop"
  val trace                        = IO(Output(new Bundle {
    val fetch       = chiselTypeOf(frontend.io.fetchPacketOut.bits)
    val fetchValid  = Bool()
    val fetchReady  = Bool()
    val recovery    = chiselTypeOf(backend.io.recoveryEventOut)
    val translation = chiselTypeOf(backend.io.translationContextOut)
    val divBusy     = Bool()
    val jump        = Bool()
    val aluWB       = Bool()
  }))

  trace.fetch       := frontend.io.fetchPacketOut.bits
  trace.fetchValid  := frontend.io.fetchPacketOut.valid
  trace.fetchReady  := frontend.io.fetchPacketOut.ready
  trace.recovery    := backend.io.recoveryEventOut
  trace.translation := backend.io.translationContextOut
  trace.divBusy     := BoringUtils.bore(backend.div.busy)
  trace.jump        := BoringUtils.bore(backend.bru.io.branchResultOut.valid) &&
    BoringUtils.bore(backend.bru.io.branchResultOut.ready) &&
    BoringUtils.bore(backend.bru.io.branchResultOut.bits.cfiOutcome.taken)
  trace.aluWB       := BoringUtils.bore(backend.alu.io.aluResultOut.valid) &&
    BoringUtils.bore(backend.alu.io.aluResultOut.ready)
}
