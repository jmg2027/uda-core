package udacore.backend.design.shared

import chisel3._

/** Backend domain parameter access trait */
sealed protected trait HasBackendParams {
  val params: BackendParams

  // Backend-specific parameter access
  def xLen: Int           = params.xLen
  def iLen: Int           = params.iLen
  def regNum: Int         = params.regNum
  def regIdWidth: Int     = params.regIdWidth
  def memOpWidth: Int     = params.memOpWidth
  def hartId: Int    = params.hartId
  def enableMulDiv: Boolean = params.enableMulDiv
  def enableBitAlu: Boolean = params.enableBitAlu
  def epochWidth: Int     = params.epochWidth
  def executionUnits: Int = params.executionUnits

  // Backend constants
  val bytesize: Int = 8
  val halfsize: Int = bytesize * 2
  val wordsize: Int = bytesize * 4

  // RVE support
  def isRVE: Boolean = params.isRVE
}

/** Backend domain abstract classes */
abstract class BackendModule extends Module with HasBackendParams
abstract class BackendBundle extends Bundle with HasBackendParams
