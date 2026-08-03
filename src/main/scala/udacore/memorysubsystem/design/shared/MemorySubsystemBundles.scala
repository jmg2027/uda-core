package udacore.memorysubsystem.design.shared

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.memorysubsystem.spec.shared.MemorySubsystemBundlesSpecs._
import udacore.memorysubsystem.design.shared.MemorySubsystemParams

/** Memory subsystem bundle implementations.
  *
  * Implements the bundle specifications defined in MemorySubsystemBundlesSpecs.
  * Field details to be filled in later based on specific requirements.
  */

@LocalSpec(bndLoadDispatch)
class LoadDispatch(params: MemorySubsystemParams) extends Bundle {
  // Fields: uopId, addr, size, sign, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndLoadMemoryReq)
class LoadMemoryReq(params: MemorySubsystemParams) extends Bundle {
  // Fields: addr, size, id, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndLoadMemoryResp)
class LoadMemoryResp(params: MemorySubsystemParams) extends Bundle {
  // Fields: data, id, fault, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndStoreDispatch)
class StoreDispatch(params: MemorySubsystemParams) extends Bundle {
  // Fields: uopId, addr, size, data, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndStoreMemoryReq)
class StoreMemoryReq(params: MemorySubsystemParams) extends Bundle {
  // Fields: addr, size, data, id, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndStoreMemoryResp)
class StoreMemoryResp(params: MemorySubsystemParams) extends Bundle {
  // Fields: id, fault, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndControllerReq)
class ControllerReq(params: MemorySubsystemParams) extends Bundle {
  // Fields: addr, data, size, cmd, id
  // TODO: Define specific field implementations
}

@LocalSpec(bndControllerResp)
class ControllerResp(params: MemorySubsystemParams) extends Bundle {
  // Fields: data, id, fault
  // TODO: Define specific field implementations
}

@LocalSpec(bndDispatcherLoad)
class DispatcherLoad(params: MemorySubsystemParams) extends Bundle {
  // Fields: addr, size, id, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndDispatcherStore)
class DispatcherStore(params: MemorySubsystemParams) extends Bundle {
  // Fields: addr, size, data, id, meta
  // TODO: Define specific field implementations
}

@LocalSpec(bndRespArb)
class RespArb(params: MemorySubsystemParams) extends Bundle {
  // Fields: data, id, source, fault
  // TODO: Define specific field implementations
}
