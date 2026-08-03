package udacore.core.design.shared

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.core.spec.shared.CoreBundlesSpecs._

/** Core bundle implementations.
  *
  * Implements the bundle specifications defined in CoreBundlesSpecs. Field
  * details to be filled in later based on specific requirements.
  */

@LocalSpec(bndGlobalEpoch)
class GlobalEpoch() extends Bundle {
  // Fields: epochTag
  // TODO: Define specific field implementations
}

@LocalSpec(bndEnable)
class Enable() extends Bundle {
  // Fields: enable, reason
  // TODO: Define specific field implementations
}

@LocalSpec(bndInterrupt)
class Interrupt() extends Bundle {
  // Fields: cause, level, meta
  // TODO: Define specific field implementations
}
