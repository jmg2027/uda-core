package udacore.common.system.csr

import chisel3._
import framework.macros.LocalSpec
import udacore.common.system.csr.CsrTemplateSpecs._

/**
  * One field-level mirror: this CSR's `selfSel` field aliases `target.targetSel`. RISC-V
  * "aliased field" semantics: reads return the canonical value, writes through any
  * standard write API forward to the canonical CSR. Use [[MirrorSpec.apply]] (types
  * inferred).
  */
final class MirrorSpec[T <: Bundle, U <: Bundle, D <: Data](
  val selfSel:   T => D,
  val target:    Csr[U],
  val targetSel: U => D,
)

object MirrorSpec {
  def apply[T <: Bundle, U <: Bundle, D <: Data](
    selfSel:   T => D,
    target:    Csr[U],
    targetSel: U => D,
  ): MirrorSpec[T, U, D] = new MirrorSpec(selfSel, target, targetSel)
}

/**
  * Decorator wrapping `inner` so the listed [[MirrorSpec]]s alias fields to other CSRs:
  *  - Reads of mirrored `reg.<field>` return the target CSR's value.
  *  - Writes forward to the target CSR (whose own legalize then runs).
  *  - The local storage bits on `inner` are unused; callers may zero them in
  *    `inner.legalize` for clean waveforms (not required for correctness).
  *  - `rvviWrittenData` overlays the target's `regNextLegal` so the trace reflects what
  *    a read would return after commit.
  *
  * Composes with Indirect/Shadow. Prefer Mirror outside Indirect: `Mirror(Indirect(banks,
  * sel), ...)` makes any bank write fan out to the same canonical CSR. Inside-Indirect
  * mirrors are per-bank - useful only when banks have distinct canonicals.
  */
@LocalSpec(funcCsrMirror)
class Mirror[T <: Bundle](
  val inner: Csr[T],
  val specs: Seq[MirrorSpec[T, _ <: Bundle, _ <: Data]],
) extends Csr[T] {
  require(specs.nonEmpty,
    "Mirror requires at least one MirrorSpec; if no aliasing is needed, use the inner Csr directly.")

  def field   = inner.field
  def default = inner.default

  // Custom read overlay and target propagation on write - keep the per-CSR access path.
  override def sharedWritable: Boolean = false

  // Share inner's storage Reg. We don't allocate our own.
  override protected[udacore] lazy val storage: T = inner.storage

  // inner.reg with mirrored fields overlaid from each target. lazy: materialize the view once.
  override lazy val reg: T = {
    val view = Wire(field)
    view := inner.reg
    specs.foreach(applyAliasRead(_, view))
    view
  }

  // Local proposal pipeline whose default driver is the *view* (so partial writes
  // to non-mirrored fields don't clobber canonical fields on the target CSR).
  override protected[csr] lazy val regNext: T = {
    val w = WireInit(default.asTypeOf(field))
    w := reg
    w
  }
  override protected[csr] lazy val regNextLegal: T = inner.regNextLegal

  override protected[csr] def doWrite(body: => Unit): Unit = {
    body  // mutates Mirror.regNext
    // Push the bulk proposal into inner's pipeline (runs inner's legalize + storage commit).
    // Only forward a mirrored field when the proposed value differs from the current view,
    // so an unrelated partial write doesn't clobber canonical fields just because regNext
    // defaults to their current values.
    inner.doWrite {
      inner.regNext := regNext
      specs.foreach { spec =>
        when(differs(spec)) {
          applyForwardWrite(spec)
        }
      }
    }
  }

  private def differs[U <: Bundle, D <: Data](s: MirrorSpec[T, U, D]): Bool =
    s.selfSel(regNext).asUInt =/= s.selfSel(reg).asUInt

  override def rvviWb: Bool = inner.rvviWb

  override def rvviWrittenData: T = {
    val v = Wire(field)
    v := inner.rvviWrittenData
    specs.foreach(applyAliasLegal(_, v))
    v
  }

  override def named(name: String): this.type = {
    inner.named(name)       // also materializes inner's proposal wires
    materializeWires()      // materialize self
    // Unconditional connect driving inner.storage's mirrored bits from target.storage. As
    // the Reg's default next-value driver, mirrored bits track target.storage cycle-by-cycle
    // when no write fires. On a write, doWrite's `storage := regNextLegal` overrides, but
    // regNext defaults to the view (current target.storage), so legalize passes the same
    // canonical value through - storage stays consistent either way.
    specs.foreach(applyStorageSync(_))
    this
  }

  private def applyStorageSync[U <: Bundle, D <: Data](
    s: MirrorSpec[T, U, D]): Unit =
      s.selfSel(inner.storage) := s.targetSel(s.target.storage)

  // Typed helpers - capture the existential D/U per spec so chisel ops type-check.
  private def applyAliasRead[U <: Bundle, D <: Data](
    s: MirrorSpec[T, U, D], view: T): Unit =
      s.selfSel(view) := s.targetSel(s.target.reg)

  private def applyForwardWrite[U <: Bundle, D <: Data](
    s: MirrorSpec[T, U, D]): Unit =
      s.target.write(s.targetSel)(s.selfSel(regNext))

  private def applyAliasLegal[U <: Bundle, D <: Data](
    s: MirrorSpec[T, U, D], view: T): Unit =
      s.selfSel(view) := s.targetSel(s.target.regNextLegal)
}

object Mirror {
  /** Construct a Mirror decorator with one or more inline specs. */
  def apply[T <: Bundle](
    inner: Csr[T],
    spec:  MirrorSpec[T, _ <: Bundle, _ <: Data],
    more:  MirrorSpec[T, _ <: Bundle, _ <: Data]*
  ): Mirror[T] =
    new Mirror(inner, spec +: more)
}
