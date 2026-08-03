package udacore.core.design.api

import udacore.core.design.shared.{
  CoreParams,
  CoreContractParams,
  CoreTuningParams
}

/** Core domain API exports for parent integrators.
  *
  * Exposes only the contract-safe parameter views and essential interfaces that
  * upper domains need to integrate with the core. Private implementation
  * details remain encapsulated within the core domain.
  */

/** Contract-safe view of core parameters for system integration. Only exposes
  * parameters that parent domains need to know about.
  */
case class CoreApiParams(
    dataWidth: Int,
    vAddrWidth: Int,
    pAddrWidth: Int,
    hartId: Int,
    epochWidth: Int,
    commitWidth: Int
)

object CoreApiParams {

  /** Extract API-safe parameters from complete CoreParams. This is the boundary
    * between core internal configuration and external API.
    */
  def fromCoreParams(params: CoreParams): CoreApiParams = {
    CoreApiParams(
      dataWidth = params.dataWidth,
      vAddrWidth = params.vAddrWidth,
      pAddrWidth = params.pAddrWidth,
      hartId = params.hartId,
      epochWidth = params.epochWidth,
      commitWidth = params.commitWidth
    )
  }
}

/** Core domain interface specifications for external connections. These define
  * the contract that upper domains can depend on.
  */
trait CoreDomainApi {
  def params: CoreApiParams

  // Interface width calculations
  def epochWidth: Int = params.epochWidth
  def addrWidth: Int  = params.vAddrWidth
  def dataWidth: Int  = params.dataWidth
  def hartId: Int = params.hartId

  // Derived parameters for interface sizing
  def epochMask: Long    = (1L << epochWidth) - 1
  def maxAddrValue: Long = (1L << addrWidth) - 1
}

/** Template for domain API objects. Each domain should provide similar API
  * boundary definitions.
  */
object CoreDomainApi {
  def apply(coreParams: CoreParams): CoreDomainApi = new CoreDomainApi {
    val params: CoreApiParams = CoreApiParams.fromCoreParams(coreParams)
  }
}
