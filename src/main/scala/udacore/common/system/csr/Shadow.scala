package udacore.common.system.csr

import chisel3._
import framework.macros.LocalSpec
import udacore.common.system.csr.CsrTemplateSpecs._

/**
  * Decorator: this CSR shares physical storage with a subfield of `parent`'s
  * register. Used for RISC-V "windowed" CSRs like fflags/frm (subfields of fcsr).
  *
  *  - `field`, `reg`, and `storage` all project through `sel`.
  *  - Writes update the subfield of `parent.storage` via the base [[Csr.doWrite]]
  *    path, with this Shadow's optional [[legalize]] applied first.
  *  - No standalone Reg is allocated.
  *
  * Note: writes go through this Shadow's legalize only - not `parent.legalize`.
  * If the parent has constraints that span the whole bundle, prefer routing
  * writes through the parent CSR directly.
  */
@LocalSpec(funcCsrShadow)
class Shadow[T <: Bundle, P <: Bundle](
  val parent:    Csr[P],
  val sel:       P => T,
  legalizeFn:    T => T = (x: T) => x,
) extends Csr[T] {
  // Fresh subfield template each call - parent.field is itself fresh-per-call.
  def field   = sel(parent.field)
  def default = 0.U   // unused; storage is parent's subfield, already initialized

  // Storage is a shared subfield of the parent - keep the per-CSR access path.
  override def sharedWritable: Boolean = false

  override protected[udacore] lazy val storage: T = sel(parent.storage)

  override protected def legalize(proposed: T): T = legalizeFn(proposed)
}

object Shadow {
  def apply[T <: Bundle, P <: Bundle](
    parent:   Csr[P],
    sel:      P => T,
    legalize: T => T = (x: T) => x,
  ): Shadow[T, P] = new Shadow(parent, sel, legalize)
}
