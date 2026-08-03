package udacore.backend.design.modules.csr

import chisel3._
import chisel3.util._
import udacore.common._
import udacore.common.system.SystemConstants
import udacore.backend.design.shared._

// AGENT: DO NOT TOUCH CORE LOGIC
class DebugUnitIO(val params: BackendParams) extends BackendBundle {
  val debugReq          = Input(Bool())
  val sysEbreak         = Input(Bool())
  val ieInstValid       = Input(Bool())
  val dret              = Input(Bool())
  val step              = Input(Bool())
  val ebreaks           = Input(Bool())
  val ebreaku           = Input(Bool())
  val ebreakm           = Input(Bool())
  val trigDebug         = Input(Bool())
  val priv              = Input(UInt(2.W))
  val debugFire         = Output(Bool())
  val regDebugMode      = Output(Bool())
  val ebreakToDebugMode = Output(Bool())
  val singleStep        = Output(Bool())
}

class DebugUnit(val params: BackendParams) extends BackendModule {
  private val usingDebug = true

  val io = IO(new DebugUnitIO(params))

  val regDebugMode      = RegInit(false.B)
  val ebreakToDebugMode = Wire(Bool())
  val debugFire         = Wire(Bool())
  val singleStep        = Wire(Bool())

  if (usingDebug) {
    val allowEbreak =
      (io.ebreaks && io.priv === SystemConstants.Privilege.SMode) ||
        (io.ebreaku && io.priv === SystemConstants.Privilege.UMode) ||
        (io.ebreakm && io.priv === SystemConstants.Privilege.MMode)

    ebreakToDebugMode := io.sysEbreak && allowEbreak
    singleStep        := io.ieInstValid && !regDebugMode && io.step
    debugFire         := (io.debugReq && !regDebugMode) || singleStep || io.trigDebug
    regDebugMode      := Mux(
      io.dret && regDebugMode,
      false.B,
      debugFire || regDebugMode
    )
  } else {
    ebreakToDebugMode := false.B
    singleStep        := false.B
    debugFire         := false.B
    regDebugMode      := false.B
  }

  io.debugFire         := debugFire
  io.regDebugMode      := regDebugMode
  io.ebreakToDebugMode := ebreakToDebugMode
  io.singleStep        := singleStep
}
