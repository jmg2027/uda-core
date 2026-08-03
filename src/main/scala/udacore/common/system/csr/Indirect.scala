package udacore.common.system.csr

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.common.system.csr.CsrTemplateSpecs._

/**
  * Decorator: multiplexes one CSR address across N backing CSRs selected by an
  * external index (typically another CSR like `tselect`). Models RISC-V indirect
  * CSRs such as tdata1/2/3 (selected by tselect) or any banked register file on a
  * single Zicsr address. Reads return the selected bank; writes commit only to it,
  * each bank legalizing independently; RVVI wb fires on the selected bank's write.
  *
  * @param banks   the N backing CSRs (non-empty; all share the same Bundle type)
  * @param select  by-name selector index, evaluated once at elaboration on first use
  */
@LocalSpec(funcCsrIndirect)
class Indirect[T <: Bundle](
  val banks:    Seq[Csr[T]],
  select:       => UInt,
) extends Csr[T] {
  require(banks.nonEmpty, "Indirect needs at least one bank.")

  def field   = banks.head.field
  def default = banks.head.default

  // Keep the per-CSR access path for bank-selected routing.
  override def sharedWritable: Boolean = false

  // Capture the by-name once so later references reuse the same signal.
  private lazy val selSig: UInt = select

  // No single storage; each bank owns its own. reg/doWrite/regNext are all
  // overridden, so the base reg lazy val never fires - no spurious Reg allocated.
  override lazy val reg: T = {
    val v = Wire(field)
    v := MuxLookup(selSig, banks.head.reg)(
      banks.zipWithIndex.map { case (b, i) => i.U -> b.reg }
    )
    v
  }

  // Local proposal pipeline: body mutates regNext, then we forward to the selected bank.
  override protected[csr] lazy val regNext: T = {
    val w = WireInit(default.asTypeOf(field))
    w := reg
    w
  }
  override protected[csr] lazy val regNextLegal: T = WireInit(default.asTypeOf(field))

  override protected[csr] def doWrite(body: => Unit): Unit = {
    body
    // Forward the proposal to the selected bank; its own doWrite legalizes, so
    // cross-field constraints stay per-bank.
    banks.zipWithIndex.foreach { case (b, i) =>
      when(selSig === i.U) {
        b.write(regNext)
      }
    }
    // Mirror the selected bank's legal value for the retire stream.
    regNextLegal := MuxLookup(selSig, default.asTypeOf(field))(
      banks.zipWithIndex.map { case (b, i) => i.U -> b.regNextLegal }
    )
  }

  override def rvviWb: Bool =
    banks.zipWithIndex.map { case (b, i) => (selSig === i.U) && b.rvviWb }.reduce(_ || _)

  override def rvviWrittenData: T = {
    val v = Wire(field)
    v := MuxLookup(selSig, default.asTypeOf(field))(
      banks.zipWithIndex.map { case (b, i) => i.U -> b.rvviWrittenData }
    )
    v
  }

  override def named(name: String): this.type = {
    banks.zipWithIndex.foreach { case (b, i) => b.named(s"${name}_$i") }   // materializes each bank
    materializeWires()
    this
  }
}

object Indirect {
  def apply[T <: Bundle](banks: Seq[Csr[T]], select: => UInt): Indirect[T] =
    new Indirect(banks, select)
}
