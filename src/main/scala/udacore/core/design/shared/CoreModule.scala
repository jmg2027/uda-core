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
  def hartId: Int      = params.hartId

  // Derived widths
  def pcWidth: Int = vAddrWidth
  def xLen: Int    = dataWidth
}

/** Core domain abstract classes */
abstract class CoreModule extends Module with HasCoreParams
abstract class CoreBundle extends Bundle with HasCoreParams
