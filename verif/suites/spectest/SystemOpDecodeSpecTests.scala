package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.shared.{DecodePrivView, SysOp, SystemOpClass, SystemOpDecode}

/** L1 SpecTest for the sysOp / serialize / fuType classification of funcSerializingTag
  * (ADR-019A E-2), pinned in SystemOpDecode ahead of the DecodeUnit RTL. Every CSR
  * write/read-only boundary is covered: CSRRW/CSRRWI always write, CSRRS/CSRRC write iff
  * rs1 != x0, CSRRSI/CSRRCI write iff zimm != 0. ECALL/EBREAK are decode exceptions and
  * are not classified here.
  */
object SystemOpDecodeSpecTests {

  private class Wrap extends Module {
    val io = IO(new Bundle {
      val insn = Input(UInt(32.W))
      val out  = Output(new SystemOpClass)
    })
    io.out := SystemOpDecode.classify(io.insn)
  }

  private def csr(funct3: Int, rs1: Int, rd: Int = 1, addr: Int = 0x300): Long =
    (addr.toLong << 20) | (rs1.toLong << 15) | (funct3.toLong << 12) | (rd.toLong << 7) | 0x73L

  /** (name, insn, isSystem, isCsr, serialize, sysOp) */
  private val N = SysOp.None; private val W = SysOp.CsrWrite
  private val table: Seq[(String, Long, Boolean, Boolean, Boolean, UInt)] = Seq(
    ("fence", 0x0ff0000fL, true, false, true, SysOp.Fence),
    ("fence.i", 0x0000100fL, true, false, true, SysOp.FenceI),
    ("sfence.vma x0,x0", 0x12000073L, true, false, true, SysOp.SfenceVma),
    ("sfence.vma a0,a1", 0x12b50073L, true, false, true, SysOp.SfenceVma),
    ("wfi", 0x10500073L, true, false, true, SysOp.Wfi),
    ("mret", 0x30200073L, true, false, true, SysOp.Mret),
    ("sret", 0x10200073L, true, false, true, SysOp.Sret),
    ("dret (ADR-019E E-3)", 0x7b200073L, true, false, true, SysOp.Dret),
    ("csrrw x1,csr,x2", csr(1, 2), false, true, true, W),
    ("csrrw x1,csr,x0", csr(1, 0), false, true, true, W),
    ("csrrw x0,csr,x2", csr(1, 2, rd = 0), false, true, true, W),
    ("csrrs x1,csr,x0", csr(2, 0), false, true, true, N),
    ("csrrs x1,csr,x5", csr(2, 5), false, true, true, W),
    ("csrrc x1,csr,x0", csr(3, 0), false, true, true, N),
    ("csrrc x1,csr,x5", csr(3, 5), false, true, true, W),
    ("csrrwi x1,csr,0", csr(5, 0), false, true, true, W),
    ("csrrsi x1,csr,0", csr(6, 0), false, true, true, N),
    ("csrrsi x1,csr,3", csr(6, 3), false, true, true, W),
    ("csrrci x1,csr,0", csr(7, 0), false, true, true, N),
    ("csrrci x1,csr,31", csr(7, 31), false, true, true, W),
    ("rdcycle (csrrs x1,cycle,x0)", csr(2, 0, addr = 0xc00), false, true, true, N),
    ("addi", 0x00300193L, false, false, false, N),
    ("lw", 0x0000a083L, false, false, false, N),
    ("jal", 0x0080006fL, false, false, false, N),
    ("ecall (decode exception)", 0x00000073L, false, false, false, N),
    ("ebreak (decode exception)", 0x00100073L, false, false, false, N)
  )

  val classify = new SpecTest("decode.sysOpClassify", Seq("funcSerializingTag")) {
    def run(): Seq[TCheck] = sim(new Wrap) { dut =>
      table.map { case (name, insn, sys, isCsr, ser, op) =>
        dut.io.insn.poke(insn.U)
        val got = (dut.io.out.isSystem.peek().litToBoolean, dut.io.out.isCsr.peek().litToBoolean,
          dut.io.out.serialize.peek().litToBoolean, dut.io.out.sysOp.peek().litValue.toInt)
        val exp = (sys, isCsr, ser, op.litValue.toInt)
        TCheck(got == exp, s"$name -> (system, csr, serialize, sysOp) = $exp",
          if (got == exp) "" else s"got $got")
      }
    }
  }

  // ---- funcSystemPrivLegality (ADR-019E E-4) -------------------------------------------------

  private class LegalWrap extends Module {
    val io = IO(new Bundle {
      val insn  = Input(UInt(32.W))
      val view  = Input(new DecodePrivView)
      val legal = Output(Bool())
    })
    io.legal := SystemOpDecode.privLegal(io.insn, io.view)
  }

  /** Reference model: p = debugMode ? M : priv; ECALL/MRET/SRET are illegal in Debug Mode (ADR-019F E-5). */
  def legalRef(name: String, priv: Int, dm: Boolean, tvm: Boolean, tw: Boolean, tsr: Boolean): Boolean = {
    val p = if (dm) 3 else priv
    name match {
      case "ecall"      => !dm
      case "mret"       => !dm && p == 3
      case "sret"       => !dm && (p == 3 || (p == 1 && !tsr))
      case "sfence.vma" => p == 3 || (p == 1 && !tvm)
      case "wfi"        => p == 3 || (p == 1 && !tw)
      case "dret"       => dm
      case _            => true
    }
  }
  val privInsns = Seq("mret" -> 0x30200073L, "sret" -> 0x10200073L, "sfence.vma" -> 0x12b50073L, "wfi" -> 0x10500073L,
    "dret" -> 0x7b200073L, "ecall" -> 0x00000073L, "ebreak" -> 0x00100073L, "fence" -> 0x0ff0000fL, "csrrw" -> csr(1, 2), "addi" -> 0x00300193L)

  val privLegality = new SpecTest("decode.sysPrivLegality", Seq("funcSystemPrivLegality")) {
    def run(): Seq[TCheck] = sim(new LegalWrap) { dut =>
      val combos = for (priv <- Seq(0, 1, 3); dm <- Seq(false, true); tvm <- Seq(false, true); tw <- Seq(false, true);
        tsr <- Seq(false, true)) yield (priv, dm, tvm, tw, tsr)
      privInsns.map { case (name, insn) =>
        val bad = combos.flatMap { case c @ (priv, dm, tvm, tw, tsr) =>
          dut.io.insn.poke(insn.U); dut.io.view.priv.poke(priv.U); dut.io.view.debugMode.poke(dm.B)
          dut.io.view.tvm.poke(tvm.B); dut.io.view.tw.poke(tw.B); dut.io.view.tsr.poke(tsr.B)
          val got = dut.io.legal.peek().litToBoolean
          if (got != legalRef(name, priv, dm, tvm, tw, tsr)) Some(s"$c -> $got") else None
        }
        TCheck(bad.isEmpty, s"$name: legality follows DecodePrivView (priv, debugMode, TVM, TW, TSR)", bad.take(3).mkString("; "))
      }
    }
  }

  val all: Seq[SpecTest] = Seq(classify, privLegality)
}
