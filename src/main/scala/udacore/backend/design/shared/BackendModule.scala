package udacore.backend.design.shared

import chisel3._

/** Backend domain parameter access trait */
sealed protected trait HasBackendParams {
  val params: BackendParams

  def xLen: Int             = params.xLen
  def iLen: Int             = params.iLen
  def regNum: Int           = params.regNum
  def regIdWidth: Int       = params.regIdWidth
  def hartId: Int           = params.hartId
  def enableMulDiv: Boolean = params.enableMulDiv
  def enableBitAlu: Boolean = params.enableBitAlu
  def robTagWidth: Int      = params.robTagWidth
  def physRegIdWidth: Int   = params.physRegIdWidth

  /** Only the legacy csr/CSR.scala reads this (removed when it is rewritten). */
  def epochWidth: Int = params.legacyCsrEpochWidth

  val bytesize: Int = 8
  val halfsize: Int = bytesize * 2
  val wordsize: Int = bytesize * 4
}

/** Backend domain abstract classes */
abstract class BackendModule extends Module with HasBackendParams
abstract class BackendBundle extends Bundle with HasBackendParams
