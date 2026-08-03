package udacore.common.system.csr

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.common.ControlSignal.CSRControl
import udacore.common.system.csr.CsrTemplateSpecs._
import scala.collection.mutable

/** Zicsr access command into the CSR library (the main line's CSRCtrl, freed of
  * its implicit Parameters). The CSR-owning vertex forms one of these per
  * serialized CSR uop at the commit boundary (ADR-004) and calls
  * [[CsrAccess.readFromCsr]] against its address map.
  */
class CsrAccess(val xLen: Int) extends Bundle {
  import CSRControl._

  val inst  = CSRControl()
  val isImm = Bool()
  val in    = UInt(xLen.W)
  val addr  = UInt(12.W)

  def instAccessCsr: Bool = inst =/= CSRControl.default
  private def isReadOnly = (!isImm && in === 0.U) && ((inst === RS) || (inst === RC))
  def writeEnable: Bool = instAccessCsr && !isReadOnly

  // Access privilege: addr(9,8) is the privilege mode, addr(11,10) the access
  // privilege (b11 = read-only per the RISC-V CSR address convention).
  def accessMode: UInt = addr(9, 8)
  def accessReadOnlyPriv: Bool = addr(11, 10) === "b11".U

  def rw: UInt = in
  def rs(setData: UInt): UInt = in | setData
  def rc(clearData: UInt): UInt = (in | clearData) & (~in).asUInt
  def writeToCsr(readData: UInt): UInt = Mux1H(Seq(
    (inst === RW) -> rw,
    (inst === RS) -> rs(readData),
    (inst === RC) -> rc(readData),
  ))

  /** Builds the CSR access circuit (funcCsrSharedWritePath). sharedWritable CSRs read
    * here and have the single shared write-modify value routed in via commit(); the
    * rest keep the per-CSR access() (read+write coupled) for custom semantics. `blocks`
    * lists contiguous sharedWritable banks: their selects come from one range check +
    * shared UIntToOH(addr - base) instead of per-entry comparators. Blocks stay in
    * `csrMap` for the address-hit check and are filtered out of the per-entry path so
    * they are not driven twice. */
  def readFromCsr(csrMap: mutable.Map[Int, Csr[_ <: Bundle]],
                  blocks: Seq[(Int, Seq[Csr[_ <: Bundle]])] = Nil): UInt = {
    val blockAddrs = blocks.flatMap { case (base, csrs) => csrs.indices.map(base + _) }.toSet
    val blockEntries = blocks.flatMap { case (base, csrs) =>
      val inBlock = (addr >= base.U) && (addr < (base + csrs.size).U)
      val oneHot  = UIntToOH(addr - base.U, csrs.size)
      csrs.zipWithIndex.map { case (csr, i) =>
        require(csr.sharedWritable, "readFromCsr block entries must be sharedWritable")
        (csr, inBlock && oneHot(i), csr.readView)
      }
    }
    val perEntry = csrMap.toSeq.collect {
      case (csrAddr, csr) if !blockAddrs.contains(csrAddr) =>
        val sel = addr === csrAddr.U
        val readData = if (csr.sharedWritable) csr.readView else csr.access(this, sel)
        (csr, sel, readData)
    } ++ blockEntries
    val selectedRead = Mux1H(perEntry.map { case (_, sel, readData) => sel -> readData })
    val sharedWrite  = writeToCsr(selectedRead)
    perEntry.foreach { case (csr, sel, _) =>
      if (csr.sharedWritable) csr.commit(sharedWrite, writeEnable && sel)
    }
    selectedRead
  }
}

/**
  * =CSR base abstraction= (ported from the main line's include/csr/Csr.scala)
  *
  * `Csr[T]` models a single CSR responding to RISC-V Zicsr accesses. Subclass for
  * legalize logic that inspects `reg` (the current value), or use the [[Csr.apply]]
  * factory when legalize is a pure function of the proposed value. Special patterns
  * (mirror/shadow/indirect/counter/custom) are decorator subclasses in this package
  * and compose freely.
  *
  * ==Write protocol== [[doWrite]]'s body mutates [[regNext]] (via the [[write]] APIs);
  * mirrors/forwarders propagate `regNext`; [[legalize]] runs on it to produce
  * `regNextLegal`, which commits to [[storage]] on the next clock edge. Reads always go
  * through [[reg]] (Bundle-typed): for a plain CSR `reg eq storage`, but decorators may
  * return a view Wire overlaying mirrors or selecting from indirect banks.
  *
  * ==Conventions== Override `storage` to share backing with another CSR (Shadow). Override
  * `reg` only for fully custom read views (prefer a Mirror for field-level aliasing).
  * Override `legalize` to clamp/zero fields. Override `access` for fully custom Zicsr
  * access (mnxti).
  *
  * @tparam T Bundle which defines fields of CSR
  */
@LocalSpec(contCsrTemplate)
abstract class Csr[T <: Bundle] {
  def field: T
  def default: UInt

  // Storage and read view

  /** Physical backing register. Override to share with another CSR's Reg subfield.
    * Visible to the whole tree (not just this package) so reset/storage invariants
    * are testable from external test specs. */
  protected[udacore] lazy val storage: T = RegInit(default.asTypeOf(field))

  /** Public read view. Default: `storage`. Decorators may override with a view Wire. */
  def reg: T = storage

  // Write proposal pipeline

  // Lazy so subclass val params (used in `reg`/`field`/`default` overrides) are bound
  // before the first connect (`w := reg`). Force-materialized from `named()`/`access()`
  // so the connects land outside any later `when(...)` write context.
  protected[csr] lazy val regNext: T = {
    val w = WireInit(default.asTypeOf(field))
    w := reg
    w
  }
  protected[csr] lazy val regNextLegal: T = WireInit(default.asTypeOf(field))

  /**
    * Force materialization of the proposal-pipeline wires. Must run in a top-level
    * (when-free) chisel context so the lazy vals' `:=` connects stay unconditional in
    * the emitted RTL. `named`/`access` call this automatically; decorators that hide an
    * inner CSR from `csrMap` (Mirror, Indirect) must also call `inner.materializeWires()`
    * from their `named` override.
    */
  protected[csr] def materializeWires(): Unit = {
    regNext
    regNextLegal
  }

  // Access protocol

  /** Zicsr access. Returns the read value (pre-write semantics). */
  @LocalSpec(funcCsrWriteProtocol)
  def access(csrAccess: CsrAccess, accessThisCsr: Bool): UInt = {
    materializeWires()
    val writeThisCsr = csrAccess.writeEnable && accessThisCsr
    when(writeThisCsr) {
      doWrite {
        regNext := csrAccess.writeToCsr(read.asUInt).asTypeOf(field)
      }
    }
    read.asUInt
  }

  /** Shared CSR write-modify path (default, funcCsrSharedWritePath). A decorator
    * overriding `access`, `reg`/`storage`/`regNext`, or writing `regNext` from its own
    * body/method (auto-increment, target propagation) must set this false and keep the
    * per-CSR [[access]] path. */
  def sharedWritable: Boolean = true

  /** Read view with no write side effect (for the read mux of the shared path). */
  def readView: UInt = { materializeWires(); read.asUInt }

  /** Commit an externally computed write value (shared path), gated by `enable`. legalize
    * still runs in `doWrite`, so per-CSR WARL behaviour is preserved. */
  def commit(value: UInt, enable: Bool): Unit = {
    materializeWires()
    when(enable) {
      doWrite { regNext := value.asTypeOf(field) }
    }
  }

  /** Suggest the underlying Reg's name and materialize proposal wires. */
  def named(name: String): this.type = {
    storage.suggestName(name)
    materializeWires()
    this
  }

  /** Pure legalization of the proposed value. Override to clamp/zero fields. */
  @LocalSpec(propCsrLegalizeTotal)
  protected def legalize(proposed: T): T = proposed

  /** Primitive write. The body should set fields on `regNext`. */
  protected[csr] def doWrite(body: => Unit): Unit = {
    body
    regNextLegal := legalize(regNext)
    storage := regNextLegal
  }

  def write[D <: Data](wdata: D): Unit = doWrite {
    regNext := wdata.asTypeOf(regNext)
  }

  def read[D <: Data]: T = reg

  protected def modify(wrFieldsBlock: T => Unit): Unit = wrFieldsBlock(regNext)
  def write(wrFieldsBlock: T => Unit): Unit = doWrite { modify(wrFieldsBlock) }

  protected def set[D <: Data](selField: T => D)(wdata: D): Unit = selField(regNext) := wdata
  def write[D <: Data](selField: T => D)(wdata: D): Unit = doWrite { set(selField)(wdata) }

  // Retire-stream hooks (ADR-010): did this access write, and what landed.

  def rvviWb: Bool = !(reg.asUInt === regNext.asUInt)
  def rvviWrittenData: T = regNextLegal

  // Field handle (ergonomic accessor)

  final class Handle[D <: Data](selField: T => D) {
    def read: D = selField(reg)
    def write(wdata: D): Unit = doWrite { selField(regNext) := wdata }
  }

  final protected def handle[D <: Data](selField: T => D) = new Handle(selField)
}

object Csr {
  /**
    * Convenience factory for the common case: no special storage/access pattern.
    *
    * @param field    fresh Bundle template (passed by-name so each chisel use gets a new instance)
    * @param default  reset value
    * @param legalize pure function applied to the proposed write before commit
    */
  def apply[T <: Bundle](
    field:    => T,
    default:  UInt   = 0.U,
    legalize: T => T = (x: T) => x,
  ): Csr[T] = {
    val _field    = () => field
    val _default  = default
    val _legalize = legalize
    new Csr[T] {
      def field   = _field()
      def default = _default
      override protected def legalize(proposed: T): T = _legalize(proposed)
    }
  }
}
