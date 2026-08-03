package udacore.core.design.shared

import chisel3._

/** Core domain parameter access trait */
trait HasCoreParams {
  val params: CoreParams

  // Constants
  val bytesize: Int = 8
  val halfsize: Int = bytesize * 2
  val wordsize: Int = bytesize * 4
  val instLen: Int  = wordsize

  // Core parameter access
  def dataWidth: Int   = params.dataWidth
  def vAddrWidth: Int  = params.vAddrWidth
  def pAddrWidth: Int  = params.pAddrWidth
  def hartId: Int = params.hartId
  def epochWidth: Int  = params.epochWidth
  def commitWidth: Int = params.commitWidth

  // Derived widths
  def instAddrWidth: Int    = vAddrWidth
  def fetchTargetWidth: Int = instAddrWidth
  def pcWidth: Int          = vAddrWidth
  def dataAddrWidth: Int    = pAddrWidth
  def xLen: Int             = dataWidth
  def pcLen: Int            = xLen
}

/** Core domain abstract classes */
abstract class CoreModule extends Module with HasCoreParams
abstract class CoreBundle extends Bundle with HasCoreParams
