package udacore.backend.design.shared

import chisel3._
import chisel3.util._
import udacore.common.ControlSignal._

/** Temporary Bundle definitions for ALU migration.
  *
  * These should be replaced with proper AluReq and AluResult when the
  * backend bundle system is fully implemented.
  */

/** ALU Request Bundle - temporary implementation */
class AluReqIn extends Bundle {
  val A     = UInt(32.W)   // Left operand
  val B     = UInt(32.W)   // Right operand
  val ctrl  = AluControl() // ALU control signal
  val rd    = UInt(5.W)    // Destination register
  val seq   = UInt(32.W)   // Sequence number
  val epoch = UInt(2.W)    // Epoch tag
}

/** ALU Result Bundle - temporary implementation */
class Result extends Bundle {
  val value = UInt(32.W) // Result value
  val tag   = UInt(5.W)  // Destination register tag
  val seq   = UInt(32.W) // Sequence number
  val epoch = UInt(2.W)  // Epoch tag
  val trap  = new Bundle {
    val hasTrap = Bool()
    val cause   = UInt(32.W)
    val tval    = UInt(32.W)
  }
}

/** BitALU Request Bundle - temporary implementation */
class BitAluReqIn extends Bundle {
  val A     = UInt(32.W)      // Left operand
  val B     = UInt(32.W)      // Right operand
  val ctrl  = BitAluControl() // Bit manipulation control
  val rd    = UInt(5.W)       // Destination register
  val seq   = UInt(32.W)      // Sequence number
  val epoch = UInt(2.W)       // Epoch tag
}
