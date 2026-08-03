package udacore.backend.design.modules.csr

import chisel3._
import chisel3.util._
import udacore.common.system.CSRFields.MControl6
import udacore.common._
import udacore.common.system.SystemConstants
import udacore.backend.design.shared._

// AGENT: DO NOT TOUCH CORE LOGIC
class TriggerUnitIO(val params: BackendParams) extends BackendBundle {
  val regDebugMode = Input(Bool())
  val priv         = Input(UInt(2.W))
  val mcontrol6    = Input(new MControl6)
  val tdata2       = Input(UInt(xLen.W))
  val trigSrc      = Input(new CSRTriggerSource(params))
  val trigFire     = Output(new CSRTriggerFire(params))
  val fire         = Output(Bool())
  val trigLdDataEn = Output(Bool())
}

class TriggerUnit(val params: BackendParams) extends BackendModule {
  private val usingTrigger = true

  val io = IO(new TriggerUnitIO(params))

  val trigFire     = WireInit(0.U.asTypeOf(new CSRTriggerFire(params)))
  val fire         = Wire(Bool())
  val trigLdDataEn = Wire(Bool())

  if (usingTrigger) {
    val trigEn = (io.mcontrol6.ctrltype === SystemConstants.DebugType.MControl6) &&
      (io.mcontrol6.valmatch === 0.U) && (
        (io.mcontrol6.u && io.priv === SystemConstants.Privilege.UMode) ||
          (io.mcontrol6.s && io.priv === SystemConstants.Privilege.SMode) ||
          (io.mcontrol6.m && io.priv === SystemConstants.Privilege.MMode)
      )

    val trigExeAddrEn = io.mcontrol6.execute && !io.mcontrol6.select
    val trigExeDataEn = io.mcontrol6.execute && io.mcontrol6.select
    val trigLdAddrEn  = io.mcontrol6.load && !io.mcontrol6.select
    val trigLdDataEnW = io.mcontrol6.load && io.mcontrol6.select
    val trigStAddrEn  = io.mcontrol6.store && !io.mcontrol6.select
    val trigStDataEn  = io.mcontrol6.store && io.mcontrol6.select

    val fireExe    = WireInit(false.B)
    val fireLdAddr = WireInit(false.B)
    val fireLdData = WireInit(false.B)
    val fireSt     = WireInit(false.B)

    when(!io.regDebugMode && trigEn) {
      when(trigExeDataEn) {
        fireExe := io.tdata2 === io.trigSrc.instruction
      }.elsewhen(trigExeAddrEn) {
        fireExe := io.tdata2 === io.trigSrc.pc
      }
      when(trigLdAddrEn) { fireLdAddr := io.tdata2 === io.trigSrc.loadAddr }
      when(trigLdDataEnW) { fireLdData := io.tdata2 === io.trigSrc.loadData }
      when(trigStAddrEn) { fireSt := io.tdata2 === io.trigSrc.storeAddr }
        .elsewhen(trigStDataEn) { fireSt := io.tdata2 === io.trigSrc.storeData }
    }

    val fireAll = fireExe || fireSt || fireLdAddr || fireLdData

    trigFire.breakPoint.exe   := (io.mcontrol6.action === SystemConstants.ActionType.Breakpoint) && fireExe
    trigFire.breakPoint.load  := (io.mcontrol6.action === SystemConstants.ActionType.Breakpoint) && (fireLdAddr || fireLdData)
    trigFire.breakPoint.store := (io.mcontrol6.action === SystemConstants.ActionType.Breakpoint) && fireSt
    trigFire.debugMode        := (io.mcontrol6.action === SystemConstants.ActionType.DMode) && fireAll
    trigFire.loadData         := fireLdData

    fire         := fireAll
    trigLdDataEn := trigLdDataEnW
  } else {
    fire         := false.B
    trigLdDataEn := false.B
  }

  io.trigFire     := trigFire
  io.fire         := fire
  io.trigLdDataEn := trigLdDataEn
}
