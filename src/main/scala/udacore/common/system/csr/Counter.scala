package udacore.common.system.csr

import chisel3._
import framework.macros.LocalSpec
import udacore.common.system.csr.CsrTemplateSpecs._

/**
  * Self-incrementing CSR (cycle / instret / hpmcounterN style).
  *
  * Each clock, if `inhibit` is low, the register increments by `inc`. Software
  * writes via Zicsr still work: the base class's access path runs inside a
  * `when(writeThisCsr)` block whose connect overrides this body's connect under
  * the same condition (last-connect-wins).
  *
  * @param dataWidth counter width in bits (XLEN, or 32 for the high halves)
  * @param inc      by-name increment (typically 1 when a counted event fires)
  * @param inhibit  by-name disable signal (from mcountinhibit etc.)
  * @param default  reset value (usually 0)
  */
@LocalSpec(funcCsrCounter)
class Counter(
  val dataWidth: Int,
  inc:         => UInt,
  inhibit:     => Bool,
  val default: UInt = 0.U,
) extends Csr[CounterField] {
  def field = new CounterField(dataWidth)

  // Auto-increments regNext from its own body - keep the per-CSR access path.
  override def sharedWritable: Boolean = false

  // Force materialize before the auto-increment when-block so default drivers
  // for regNext (`w := reg`) are unconditional.
  materializeWires()
  when(!inhibit) {
    write(reg.data + inc)
  }
}

class CounterField(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
}

object Counter {
  def apply(dataWidth: Int, inc: => UInt, inhibit: => Bool, default: UInt = 0.U): Counter =
    new Counter(dataWidth, inc, inhibit, default)
}
