package udacore.memorysubsystem.design.shared

import chisel3._

/** Memory subsystem domain parameter access trait */
trait HasMemorySubsystemParams {
  val params: MemorySubsystemParams

  // Memory subsystem parameter access
  def addressWidth: Int   = params.addressWidth
  def memOpWidth: Int     = params.memOpWidth
  def memoryPorts: Int    = params.memoryPorts
  def loadQueueSize: Int  = params.loadQueueSize
  def storeQueueSize: Int = params.storeQueueSize
  def loadUnitCount: Int  = params.loadUnitCount
  def storeUnitCount: Int = params.storeUnitCount

  // Memory constants
  val bytesize: Int = 8
  val halfsize: Int = bytesize * 2
  val wordsize: Int = bytesize * 4
}

/** Memory subsystem domain abstract classes */
abstract class MemorySubsystemModule
    extends Module
    with HasMemorySubsystemParams
abstract class MemorySubsystemBundle
    extends Bundle
    with HasMemorySubsystemParams
