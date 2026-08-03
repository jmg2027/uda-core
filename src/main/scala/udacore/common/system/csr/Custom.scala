package udacore.common.system.csr

import chisel3._
import framework.macros.LocalSpec
import udacore.common.system.csr.CsrTemplateSpecs._

/**
  * Escape hatch for CSRs whose Zicsr semantics don't fit any other pattern.
  * The user supplies the full `access` handler.
  *
  * Use sparingly - most CSRs should fit Csr / Mirror / Shadow / Indirect / Counter.
  * A claim-on-read interrupt CSR (mnxti style) is the canonical example.
  *
  * The default `storage`, `regNext`, etc. are still provided in case the handler
  * wants to use them, but the handler may also be entirely stateless.
  */
@LocalSpec(funcCsrCustom)
class Custom[T <: Bundle](
  fieldGen:    => T,
  doAccess:    (CsrAccess, Bool) => UInt,
  val default: UInt = 0.U,
) extends Csr[T] {
  def field = fieldGen

  // Fully custom access - keep the per-CSR path, never the shared write-modify.
  override def sharedWritable: Boolean = false

  override def access(csrAccess: CsrAccess, accessThisCsr: Bool): UInt =
    doAccess(csrAccess, accessThisCsr)
}

object Custom {
  def apply[T <: Bundle](
    field:    => T,
    doAccess: (CsrAccess, Bool) => UInt,
    default:  UInt = 0.U,
  ): Custom[T] = new Custom(field, doAccess, default)
}
