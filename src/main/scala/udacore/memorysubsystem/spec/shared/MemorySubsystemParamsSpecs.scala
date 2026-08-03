package udacore.memorysubsystem.spec.shared

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.spec.ParamsSpecs._
import udacore.core.spec.shared.CoreParamsSpecs.paramSpeculativeRegNum

/** Memory subsystem parameter specifications.
  *
  * Defines the parameter specifications for the memory subsystem. Parameters
  * are organized in three tiers: Contract (must be specified by integrators),
  * Tuning (performance optimization), and Private (internal implementation
  * details).
  *
  * ADR-003 M5 as amended by ADR-016: XLEN-parametric TCM base. The multi-load-unit
  * server template is deleted; the single window axis N (paramSpeculativeRegNum,
  * WP-D) drives the only two derived depth knobs, paramStoreBufferDepth and
  * paramLoadOutstanding, and both fold to their N=1 single-entry point.
  */
object MemorySubsystemParamsSpecs {

  // Contract Tier - Parent integrators must specify these.
  // ADR-003 D-3.14 as amended by ADR-016: datapath width follows XLEN.
  val paramAddressWidth = spec {
    PARAMETER("AddressWidth")
      .desc("Data memory address width in bits.")
      .is(rawContractParams)
      .entry("default", "32")
      .note("ADR-016 amends ADR-003 D-3.14: follows the integrator's XLEN (32 or 64); 32 is the base point.")
      .build()
  }

  val paramMemOpWidth = spec {
    PARAMETER("MemOpWidth")
      .desc("Memory operation datapath width in bits.")
      .is(rawContractParams)
      .entry("default", "32")
      .note("ADR-016 amends ADR-003 D-3.14: follows the integrator's XLEN (32 or 64); 32 is the base point.")
      .build()
  }

  // ADR-003 D-3.1/D-3.7/M5: the store-path depth knob, published for WP-A maxInFlight.
  val paramStoreBufferDepth = spec {
    PARAMETER("StoreBufferDepth")
      .desc("Speculative store capacity = max(1, min(N, StoreBufferDepthMax)).")
      .is(rawContractParams)
      .entry("default", "1")
      .uses(paramSpeculativeRegNum)
      .note("Contract tier - sets speculative store window and forwarding CAM width.")
      .note("ADR-003 D-3.7: N=1 => depth 1 => commit-gated holding register (in-order store). StoreBufferDepthMax default 8.")
      .note(
        "Published read-only to WP-A (BackendParamsSpecs) for maxInFlight = allocFifoDepth + storeBufferDepth + serializingStageDepth (ADR-012)."
      )
      .build()
  }

  // ADR-003 M4/D-3.13/M5: outstanding-read knob, derives txnId width, published for the bus.
  val paramLoadOutstanding = spec {
    PARAMETER("LoadOutstanding")
      .desc("Max concurrent outstanding external read requests (default 1).")
      .is(rawTuningParams)
      .entry("default", "1")
      .note("ADR-003 D-3.13: derives DataMemoryTxnIdWidth = ceil(log2(LoadOutstanding)); 0 at single-outstanding.")
      .note("At most LoadOutstanding reads and at most ONE committed write outstanding on the bus at once.")
      .build()
  }

  // ADR-003 D-3.10/D-3.11: base disambiguation policy (conservative unless a wide-OoO build).
  val paramMemDisambig = spec {
    PARAMETER("MemDisambig")
      .desc("Enables speculative load disambiguation past unresolved older stores; default N>1.")
      .is(rawTuningParams)
      .entry("default", "false")
      .uses(paramSpeculativeRegNum)
      .note(
        "ADR-003 D-3.10: at false (base and N=1) a load MUST NOT read past an unresolved older store; it only forwards from resolved buffered stores."
      )
      .note(
        "ADR-003 D-3.11 (proposed): at true a load may issue speculatively; a late store-address match triggers a memory-order redirect at the commit head (ADR-011), NOT a selective per-tag replay. The whole replay path elaborates away at the false base."
      )
      .build()
  }

  // Tuning Tier - Performance optimization parameters.
  val paramMemoryPorts = spec {
    PARAMETER("MemoryPorts")
      .desc("Number of external memory ports.")
      .is(rawTuningParams)
      .entry("default", "1")
      .note("ADR-003 D-3.14: single memory port at the TCM base point.")
      .build()
  }

  val paramStoreBufferDepthMax = spec {
    PARAMETER("StoreBufferDepthMax")
      .desc("Upper bound the derived StoreBufferDepth saturates at.")
      .is(rawTuningParams)
      .entry("default", "8")
      .note("ADR-003 D-3.7: StoreBufferDepth = max(1, min(N, StoreBufferDepthMax)).")
      .build()
  }

  // Private Tier - Internal implementation details.
  val paramWritePriority = spec {
    PARAMETER("WritePriority")
      .desc("Reads may not starve the single committed write on the shared bus port.")
      .is(rawPrivateParams)
      .entry("default", "true")
      .note(
        "ADR-003 M5: replaces the over-general string arbitration policy; one committed write vs LoadOutstanding reads on one port has a fixed policy."
      )
      .build()
  }
}
