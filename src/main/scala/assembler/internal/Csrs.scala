package assembler

import scala.util.Try

import assembler.ObjectUtils._

case class Csr(
    name: String,
    offset: Long
)

protected object Csrs {
  def apply(csr: String): Option[Long] = {
    val ret = csrs.find(_.name == csr.toUpperCase).map(_.offset)
    ret match {
      case Some(v) => Some(v)
      case None    => {
        Try(csr.parseToLong()).toOption
      }
    }
  }

  val csrs = List(
    Csr(name = "MISA", offset = 0x301),
    Csr(name = "MHARTID", offset = 0xf14),
    Csr(name = "MSTATUS", offset = 0x300),
    Csr(name = "MIP", offset = 0x344),
    Csr(name = "MIE", offset = 0x304),
    Csr(name = "MCAUSE", offset = 0x342),
    Csr(name = "MTVEC", offset = 0x305),
    Csr(name = "MSCRATCH", offset = 0x340),
    Csr(name = "SSCRATCH", offset = 0x140),
    Csr(name = "MEPC", offset = 0x341),
    Csr(name = "SEPC", offset = 0x141),
    Csr(name = "SATP", offset = 0x180)
  )
}
