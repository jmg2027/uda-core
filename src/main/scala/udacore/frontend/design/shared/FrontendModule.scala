package udacore.frontend.design.shared

import chisel3._

/** Frontend domain parameter access trait */
trait HasFrontendParams {
  val params: FrontendParams

  // Frontend-specific parameter access
  def fetchWidth: Int             = params.fetchWidth
  def instructionCacheSize: Int   = params.instructionCacheSize
  def instAddrWidth: Int          = params.instAddrWidth
  def fetchTargetWidth: Int       = params.fetchTargetWidth
  def pcWidth: Int                = params.pcWidth
  def epochWidth: Int             = params.epochWidth
  def branchPredictorEntries: Int = params.branchPredictorEntries

  // Frontend constants
  val bytesize: Int = 8
  val instLen: Int  = params.instLen
}

/** Frontend domain abstract classes */
abstract class FrontendModule extends Module with HasFrontendParams
abstract class FrontendBundle extends Bundle with HasFrontendParams
