package udacore.backend.design.shared

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.spec.modules.DecodeUnitSpecs.funcSerializingTag

/** The system-instruction classification of funcSerializingTag (ADR-019A E-2).
  *
  * Pinned here, ahead of the DecodeUnit RTL, so the serialize / sysOp / fuType table is
  * fixed by an L1 test before any decoder uses it. It classifies a legal 32-bit
  * instruction word only; a uop carrying a fetch or decode exception gets sysOp None
  * from the DecodeUnit regardless of this result.
  */
class SystemOpClass extends Bundle {
  val isSystem  = Bool() // fuType System: execution-free, ROB-only
  val isCsr     = Bool() // fuType Csr: executes through the RS
  val serialize = Bool()
  val sysOp     = UInt(SysOp.width.W)
}

object SystemOpDecode {
  private val OpSystem  = "b1110011".U(7.W)
  private val OpMiscMem = "b0001111".U(7.W)

  @LocalSpec(funcSerializingTag)
  def classify(insn: UInt): SystemOpClass = {
    val c      = Wire(new SystemOpClass)
    val opcode = insn(6, 0)
    val funct3 = insn(14, 12)
    val rs1    = insn(19, 15) // rs1, or zimm for the immediate CSR forms
    val rd     = insn(11, 7)
    val funct7 = insn(31, 25)

    val fence     = opcode === OpMiscMem && funct3 === 0.U
    val fenceI    = opcode === OpMiscMem && funct3 === 1.U
    val priv      = opcode === OpSystem && funct3 === 0.U
    val mret      = insn === "h30200073".U
    val sret      = insn === "h10200073".U
    val wfi       = insn === "h10500073".U
    val sfenceVma = priv && funct7 === "b0001001".U && rd === 0.U
    val csr       = opcode === OpSystem && funct3 =/= 0.U && funct3 =/= 4.U
    // CSRRW/CSRRWI always write; CSRRS/CSRRC/CSRRSI/CSRRCI write iff rs1/zimm != 0.
    val csrWrite  = csr && (funct3(1, 0) === 1.U || rs1 =/= 0.U)

    c.isSystem  := fence || fenceI || mret || sret || wfi || sfenceVma
    c.isCsr     := csr
    c.serialize := c.isSystem || csr
    c.sysOp := MuxCase(SysOp.None, Seq(
      fence     -> SysOp.Fence,
      fenceI    -> SysOp.FenceI,
      sfenceVma -> SysOp.SfenceVma,
      wfi       -> SysOp.Wfi,
      mret      -> SysOp.Mret,
      sret      -> SysOp.Sret,
      csrWrite  -> SysOp.CsrWrite
    ))
    c
  }
}
