package udacore.frontend.design.shared

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.frontend.spec.shared.FrontendBundlesSpecs._

/** Frontend bundle implementations.
  *
  * Implements the bundle specifications defined in FrontendBundlesSpecs. Field
  * details to be filled in later based on specific requirements.
  */

@LocalSpec(bndBoot)
class Boot() extends Bundle {
  // Fields: bootPc, enable
  // TODO: Define specific field implementations
}

@LocalSpec(bndInstruction)
class Instruction() extends Bundle {
  // Fields: bytes, pc, meta, epochTag
  // TODO: Define specific field implementations
}

@LocalSpec(bndAlignInput)
class AlignInput() extends Bundle {
  // Fields: bytes, pc, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndAlignOutput)
class AlignOutput() extends Bundle {
  // Fields: bytes, pc, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndRvcInput)
class RvcInput() extends Bundle {
  // Fields: bytes, pc, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndRvcOutput)
class RvcOutput() extends Bundle {
  // Fields: insts, pc, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndPredictorInput)
class PredictorInput() extends Bundle {
  // Fields: insts, pc, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndPredictorOutput)
class PredictorOutput() extends Bundle {
  // Fields: target, taken, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndIssueFrontend)
class IssueFrontend() extends Bundle {
  // Fields: insts, pc, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndIssueBackend)
class IssueBackend() extends Bundle {
  // Fields: insts, pc, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndRedirect)
class Redirect() extends Bundle {
  // Fields: pc, target, epochTag
  // TODO: Define specific field implementations
}

@LocalSpec(bndNextPcIssue)
class NextPcIssue() extends Bundle {
  // Fields: pc, epochTag
  // TODO: Define specific field implementations
}
