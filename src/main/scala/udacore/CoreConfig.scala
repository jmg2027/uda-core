package udacore

import udacore.core.design.shared._

/** Core domain configuration using new parameter architecture.
  *
  * Only manages Core domain parameters. Other domains (Backend, Frontend, 
  * MemorySubsystem) should manage their own default configurations within
  * their respective domains.
  */
object DefaultCoreConfig {
  
  /** Default core contract parameters */
  val defaultCoreContract = CoreContractParams(
    dataWidth = 32,              // 32-bit data width
    vAddrWidth = 32,             // 32-bit virtual addresses
    pAddrWidth = 32,             // 32-bit physical addresses
    hartId = 0                   // Hart identifier matches zero by default
  )
  
  /** Default core tuning parameters */
  val defaultCoreTuning = CoreTuningParams(
    epochWidth = 2,              // Epoch counter width
    commitWidth = 1,             // Single-issue in-order
    robDepth = 0                 // No reorder buffer (in-order)
  )
  
  /** Default core private parameters */
  val defaultCorePrivate = CorePrivateParams(
    bootCycles = 8,              // 8-cycle boot sequence
    debugFeatures = false        // Disable debug features for performance
  )
  
  /** Complete default core configuration */
  val default = CoreParams(
    contract = defaultCoreContract,
    tuning = defaultCoreTuning,
    priv = defaultCorePrivate
  )
  
  /** Minimal configuration for basic tests */
  val minimal = CoreParams(
    contract = CoreContractParams(
      dataWidth = 32,
      vAddrWidth = 32,
      pAddrWidth = 32,
      hartId = 0
    ),
    tuning = CoreTuningParams(
      epochWidth = 2,             // Minimum legal width under the ADR-005 wrap bound
      commitWidth = 1,
      robDepth = 0
    ),
    priv = CorePrivateParams(
      bootCycles = 4,             // Faster boot for tests
      debugFeatures = true        // Enable debug for testing
    )
  )
}
