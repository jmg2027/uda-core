package udacore.frontend.design.shared

import chisel3._

/** Frontend domain parameter access trait. */
trait HasFrontendParams {
  val params: FrontendParams

  def fetchBytes: Int  = params.fetchBytes
  def fetchWidth: Int  = params.fetchWidth
  def decodeWidth: Int = params.decodeWidth
  def vAddrWidth: Int  = params.vAddrWidth
  def ghrLength: Int   = params.ghrLength
  def ftqDepth: Int    = params.ftqDepth

  // Fixed-width ISA (ADR-019 D-19.3): every instruction is one 32-bit word.
  val instBits: Int = 32
}

/** Frontend domain abstract classes */
abstract class FrontendModule extends Module with HasFrontendParams
abstract class FrontendBundle extends Bundle with HasFrontendParams
