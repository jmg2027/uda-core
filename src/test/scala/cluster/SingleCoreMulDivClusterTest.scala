package cluster

import chisel3._
import chiseltest._
import cluster.util.ClusterTest

import udacore.core.design.top.UDACore
import udacore.DefaultCoreConfig
import udacore.common.HeartXcpt

object SingleCoreMulDivClusterTest {
  class Cluster(
      instLatency: Int = 1,
      dataLatency: Int = 1
  )(val params: CoreParams)
      extends TestCluster(instLatency, dataLatency) {
    val core = Module(new UDACore(params))

    // Connection between TestCluster and UDACore

  }
}

class SingleCoreMulDivClusterTest extends ClusterTest {
  val Self = SingleCoreMulDivClusterTest

  val params: CoreParams = DefaultCoreConfig.default

  val textEnd =
    """
    lui  x31, 0x70000
    addi x30, x0, 0x1
    sw   x30, 0(x31)
    # Simple loop instead of ebreak for now
    loop:
    j loop
    """.stripMargin

  def testMem(dut: TestCluster)(tc: Seq[(Long, Long)]) = {
    tc.foreach { case (a, v) =>
      val addr = 0x40000000L + a
      val data = dut.mem.load(addr)
      println(f"Addr(0x$addr%X) data(0x$data%X) expect(0x$v%X)")
      assert(data == v)
    }
  }

  "Store" - {
    "test 0" in {
      val text =
        """
          lui  x1, 0x12345
          addi x1, x1, 0x678
          lui  x31, 0x40000
          sw   x1, 0(x31)
          sh   x1, 4(x31)
          sb   x1, 8(x31)
          addi x31, x31, 0x40
          sh   x1, 6(x31)
          sb   x1, 7(x31)
          """.stripMargin

      test(new Self.Cluster(params)).withAnnotations(useTreadle) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.run()
        testMem(dut)(
          Seq(
            0x0L  -> 0x12345678L,
            0x4L  -> 0x5678L,
            0x8L  -> 0x78L,
            0x44L -> 0x78780000L
          )
        )
      }
    }
  }

  "Load" - {
    "test 0" in {
      val text =
        """
         lui x31, 0x50000
         lui x30, 0x40000
         lw  x1, 0(x31)
         lh  x2, 2(x31)
         lb  x3, 3(x31)
         sb  x3, 8(x30)
         sh  x2, 4(x30)
         sw  x1, 0(x30)
         lw  x10, 0(x31)
         addi x10, x10, 0x1
         sw  x10, 12(x30)
         lw x11, 0(x31)
         addi x12, x11, 0x1
         sw  x12, 16(x30)
         """.stripMargin
      val data =
        """
         12345678
         """.stripMargin
      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.mem.setupData(0x50000000L, data)
        dut.run()
        testMem(dut)(
          Seq(
            0x0L  -> 0x12345678L,
            0x4L  -> 0x1234L,
            0x8L  -> 0x12L,
            0xcL  -> 0x12345679L,
            0x10L -> 0x12345679L
          )
        )
      }
    }
  }

  "test sign ext" in {
    val text =
      """
        lui x31, 0x50000
        lh  x1, 0(x31)
        lb  x2, 0(x31)
        lhu x3, 0(x31)
        lbu x4, 0(x31)
        lui x31, 0x40000
        sw  x1, 0(x31)
        sw  x2, 4(x31)
        sw  x3, 8(x31)
        sw  x4, 12(x31)
        """.stripMargin
    val data =
      """
        12348786
        """.stripMargin
    test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
      dut.mem.setupText(0x80000000L, text + textEnd)
      dut.mem.setupData(0x50000000L, data)
      dut.run()
      testMem(dut)(
        Seq(
          0L  -> 0xffff8786L,
          4L  -> 0xffffff86L,
          8L  -> 0x00008786L,
          12L -> 0x00000086L
        )
      )
    }
  }

  "Alu" - {
    "test 0" in {
      val text =
        """
           // Store tc to 0x40000000
           lui x31, 0x40000

           // x1: 0x12345678
           // x2: 0x87654321
           lui  x1, 0x12345
           addi x1, x1, 0x678
           lui  x2, 0x87654
           addi x2, x2, 0x321

           add  x3, x1, x2
           sub  x4, x1, x2
           xor  x5, x1, x2
           or   x6, x1, x2
           and  x7, x1, x2
           slt  x8, x1, x2
           slt  x9, x2, x1
           sltu x10, x1, x2
           sltu x11, x2, x1

           sw  x3, 0x0(x31)
           sw  x4, 0x4(x31)
           sw  x5, 0x8(x31)
           sw  x6, 0xC(x31)
           sw  x7, 0x10(x31)
           sw  x8, 0x14(x31)
           sw  x9, 0x18(x31)
           sw  x10, 0x1C(x31)
           sw  x11, 0x20(x31)

           // x1: 0x3
           // x2: 0x87654321
           addi x1, x0, 0x3

           sll  x3, x2, x1
           srl  x4, x2, x1
           sra  x5, x2, x1

           sw  x3, 0x24(x31)
           sw  x4, 0x28(x31)
           sw  x5, 0x2C(x31)

           // x1: 0x12345678
           // x2: 0x87654321
           lui  x1, 0x12345
           addi x1, x1, 0x678

           addi x3, x1, 0x123
           xori x4, x1, 0x123
           ori  x5, x1, 0x123
           andi x6, x1, 0x123
           slli x7, x2, 0x5
           srli x8, x2, 0x5
           srai x9, x2, 0x5
           slti x10, x2, 0x123
           sltiu x11, x2, 0x123

           sw  x3, 0x30(x31)
           sw  x4, 0x34(x31)
           sw  x5, 0x38(x31)
           sw  x6, 0x3C(x31)
           sw  x7, 0x40(x31)
           sw  x8, 0x44(x31)
           sw  x9, 0x48(x31)
           sw  x10, 0x4C(x31)
           sw  x11, 0x50(x31)

           """.stripMargin
      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.run()

        val unsignedA = 0x12345678L
        val unsignedB = 0x87654321L
        val signedA   = 0x12345678.toLong
        val signedB   = 0x87654321.toLong
        testMem(dut)(
          Seq(
            0x0L  -> (signedA + signedB),
            0x4L  -> (signedA - signedB),
            0x8L  -> (signedA ^ signedB),
            0xcL  -> (signedA | signedB),
            0x10L -> (signedA & signedB),
            0x14L -> (if (signedA < signedB) 1L else 0L),
            0x18L -> (if (signedA > signedB) 1L else 0L),
            0x1cL -> (if (unsignedA < unsignedB) 1L else 0L),
            0x20L -> (if (unsignedA > unsignedB) 1L else 0L),
            0x24L -> (unsignedB << 3L),
            0x28L -> (unsignedB >> 3L),
            0x2cL -> (signedB >> 3L),
            0x30L -> (signedA + 0x123L),
            0x34L -> (signedA ^ 0x123L),
            0x38L -> (signedA | 0x123L),
            0x3cL -> (signedA & 0x123L),
            0x40L -> (unsignedB << 0x5),
            0x44L -> (unsignedB >> 0x5),
            0x48L -> (signedB >> 0x5),
            0x4cL -> (if (signedB < 0x123L) 1L else 0L),
            0x50L -> (if (unsignedB < 0x123L) 1L else 0L)
          ).map { case (k, v) =>
            (k, v & 0xffffffffL)
          }
        )
      }
    }
  }

  "MulDiv" - {
    "mul test 0" in {
      val text =
        """
           lui  x31, 0x40000

           // x1: 0x87654321
           // x2: -0x7
           lui  x1, 0x87654
           addi x1, x1, 0x321
           addi x2, x0, 0x7
           sub x2, x0, x2

           mul    x3, x1, x2
           mulh   x4, x1, x2
           mulhsu x5, x1, x2
           mulhu  x6, x1, x2

           sw x3, 0x0(x31)
           sw x4, 0x4(x31)
           sw x5, 0x8(x31)
           sw x6, 0xC(x31)
           """.stripMargin

      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.run()

        val unsignedA = 0x87654321L
        val unsignedB = 0xfffffff9L
        val signedA   = 0x87654321.toLong
        val signedB   = 0xfffffff9.toLong
        testMem(dut)(
          Seq(
            0x0L -> ((unsignedA * unsignedB) & 0xffffffffL),
            0x4L -> (((signedA * signedB) >> 32) & 0xffffffffL),
            0x8L -> (((signedA * unsignedB) >> 32) & 0xffffffffL),
            0xcL -> (((unsignedA * unsignedB) >> 32) & 0xffffffffL)
          )
        )
      }
    }

    "div test 0" in {
      val text =
        """
           lui  x31, 0x40000

           // x1: 0x87654321
           // x2: -0x7
           lui  x1, 0x87654
           addi x1, x1, 0x321
           addi x2, x0, 0x7
           sub x2, x0, x2

           div    x3, x1, x2
           divu   x4, x2, x1
           rem    x5, x1, x2
           remu   x6, x2, x1

           sw x3, 0x0(x31)
           sw x4, 0x4(x31)
           sw x5, 0x8(x31)
           sw x6, 0xC(x31)
           """.stripMargin

      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.run()

        val unsignedA = 0x87654321L
        val unsignedB = 0xfffffff9L
        val signedA   = 0x87654321.toLong
        val signedB   = 0xfffffff9.toLong
        testMem(dut)(
          Seq(
            0x0L -> ((signedA / signedB) & 0xffffffffL),
            0x4L -> ((unsignedB / unsignedA) & 0xffffffffL),
            0x8L -> ((signedA   % signedB) & 0xffffffffL),
            0xcL -> ((unsignedB % unsignedA) & 0xffffffffL)
          )
        )
      }
    }
  }

  "Branch" - {
    "test 0" in {
      val text =
        """
           // Store tc to 0x40000000
           lui  x31, 0x40000

           // x1: 0x12345678
           // x2: 0x87654321
           lui x1, 0x12345
           addi x1, x1, 0x678
           lui x2, 0x87654
           addi x2, x2, 0x321

           addi x3, x0, 0x1
           beq x1, x1, 0x8
           addi x3, x3, 0x1
           sw x3, 0x0(x31)

           addi x3, x0, 0x1
           beq x1, x2, 0x8
           addi x3, x3, 0x1
           sw x3, 0x4(x31)

           addi x3, x0, 0x1
           bne x1, x1, 0x8
           addi x3, x3, 0x1
           sw x3, 0x8(x31)

           addi x3, x0, 0x1
           bne x1, x2, 0x8
           addi x3, x3, 0x1
           sw x3, 0xC(x31)

           addi x3, x0, 0x1
           blt x1, x2, 0x8
           addi x3, x3, 0x1
           sw x3, 0x10(x31)

           addi x3, x0, 0x1
           blt x2, x1, 0x8
           addi x3, x3, 0x1
           sw x3, 0x14(x31)

           addi x3, x0, 0x1
           bge x1, x2, 0x8
           addi x3, x3, 0x1
           sw x3, 0x18(x31)

           addi x3, x0, 0x1
           bge x2, x1, 0x8
           addi x3, x3, 0x1
           sw x3, 0x1C(x31)

           addi x3, x0, 0x1
           bltu x1, x2, 0x8
           addi x3, x3, 0x1
           sw x3, 0x20(x31)

           addi x3, x0, 0x1
           bltu x2, x1, 0x8
           addi x3, x3, 0x1
           sw x3, 0x24(x31)

           addi x3, x0, 0x1
           bgeu x1, x2, 0x8
           addi x3, x3, 0x1
           sw x3, 0x28(x31)

           addi x3, x0, 0x1
           bgeu x2, x1, 0x8
           addi x3, x3, 0x1
           sw x3, 0x2C(x31)
           """.stripMargin

      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.run()

        val unsignedA = 0x12345678L
        val unsignedB = 0x87654321L
        val signedA   = 0x12345678.toLong
        val signedB   = 0x87654321.toLong
        testMem(dut)(
          Seq(
            0x0L  -> (if (signedA == signedA) 0x1L else 0x2L),
            0x4L  -> (if (signedA == signedB) 0x1L else 0x2L),
            0x8L  -> (if (signedA != signedA) 0x1L else 0x2L),
            0xcL  -> (if (signedA != signedB) 0x1L else 0x2L),
            0x10L -> (if (signedA < signedB) 0x1L else 0x2L),
            0x14L -> (if (signedB < signedA) 0x1L else 0x2L),
            0x18L -> (if (signedA >= signedB) 0x1L else 0x2L),
            0x1cL -> (if (signedB >= signedA) 0x1L else 0x2L),
            0x1cL -> (if (signedB >= signedA) 0x1L else 0x2L),
            0x20L -> (if (unsignedA < unsignedB) 0x1L else 0x2L),
            0x24L -> (if (unsignedB < unsignedA) 0x1L else 0x2L),
            0x28L -> (if (unsignedA >= unsignedB) 0x1L else 0x2L),
            0x2cL -> (if (unsignedB >= unsignedA) 0x1L else 0x2L)
          )
        )
      }
    }
  }

  "Jump" - {
    "test" in {
      val text =
        """
           lui  x31, 0x40000         // load addr base 0x40000000
           lui  x30, 0xdeadb
           addi x30, x30, 0xeef      // error code 0xdeadbeef
           lui  x29, 0x80000         // text base addr 0x80000000

           jal  x3, 0x8              // jump to 0x80000018
           sw   x30, 0x0(x31)        // if not jump, store error code
           sw   x3, 0x4(x31)         // 0x80000018

           jalr x4, x29, 0x24        // jump to 0x80000024
           sw   x30, 0x8(x31)        // if not jump, store error code
           sw   x4, 0xC(x31)         // 0x80000024
           """.stripMargin
      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.run()
        testMem(dut)(
          Seq(
            0x0L -> 0x0L,
            0x4L -> 0x80000014L,
            0x8L -> 0x0L,
            0xcL -> 0x80000020L
          )
        )
      }
    }
  }

  "Imm" - {
    "test" in {
      val text =
        """
           lui  x31, 0x40000
           lui  x1, 0x12345
           sw   x1, 0x0(x31)   // store 0x12345000

           auipc x1, 0x12345   // store 0x8000000C + 0x12345000
           sw   x1, 0x4(x31)   // store 0x12345000
           """.stripMargin
      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.run()
        testMem(dut)(
          Seq(
            0x0L -> 0x12345000L,
            0x4L -> (0x8000000cL + 0x12345000L)
          )
        )
      }
    }
  }

  "Env" - {
    "ecall test" in {
      val text =
        """
           lui  x31, 0x40000
           addi x1, x0, 0x123
           sw   x1, 0x0(x31)
           ecall
           """.stripMargin

      val handler =
        """
           addi x2, x1, 0x123
           sw   x2, 0x4(x31)
           """.stripMargin
      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text)
        dut.mem.setupText(0x0, handler + textEnd)
        dut.run()
        testMem(dut)(
          Seq(
            0x0L -> 0x123L,
            0x4L -> 0x246L
          )
        )
      }
    }
  }

  "CSR" - {
    "inst test" in {
      val text =
        """
           lui x31, 0x40000

           addi x1, x0, 0x123
           // mscratch: 0x123
           csrrw x0, mscratch, x1

           addi x1, x1, 0x123
           // mscratch: 0x246, x2: 0x123
           csrrw x2, mscratch, x1
           sw x2, 0x0(x31)

           lui x1, 0x87654
           // mscratch: 0x87654246, x2: 0x246
           csrrs x2, mscratch, x1
           sw x2, 0x4(x31)

           lui x1, 0x00654
           // mscratch: 0x87000246, x2: 0x87654246
           csrrc x2, mscratch, x1
           sw x2, 0x8(x31)

           // mscratch: 0x5, x2: 0x87000246
           csrrwi x2, mscratch, 0x5
           sw x2, 0xC(x31)

           // mscratch: 0x1D, x2: 0x5
           csrrsi x2, mscratch, 0x18
           sw x2, 0x10(x31)

           // mscratch: 0x15, x2: 0x1D
           csrrci x2, mscratch, 0x8
           sw x2, 0x14(x31)

           // mscratch: 0x0, x2: 0x15
           csrrwi x2, mscratch, 0x0
           sw x2, 0x18(x31)
           """.stripMargin
      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.run()
        testMem(dut)(
          Seq(
            0x0L  -> 0x123L,
            0x4L  -> 0x246L,
            0x8L  -> 0x87654246L,
            0xcL  -> 0x87000246L,
            0x10L -> 0x5L,
            0x14L -> 0x1dL,
            0x18L -> 0x15L
          )
        )
      }
    }

    "misa mhartid" in {
      val text =
        """
           lui  x31, 0x40000

           csrrw x1, misa, x0
           sw x1, 0x0(x31)
           csrrw x1, mhartid, x0
           sw x1, 0x4(x31)
           """.stripMargin
      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.run()
        testMem(dut)(
          Seq(
            0x0L -> 0x40001104L,
            0x4L -> 0x0L
          )
        )
      }
    }

    "ecall" in {
      val text =
        """
           lui  x31, 0x40000

           lui x1, 0x10000
           csrrw x0, mtvec, x1
           ecall
           addi x1, x0, 0x123
           sw x1, 0xC(x31)
           """.stripMargin

      val handler =
        """
           csrrsi x1, mepc, 0x0
           csrrsi x2, mcause, 0x0
           csrrsi x3, mtvec, 0x0
           sw x1, 0x0(x31)
           sw x2, 0x4(x31)
           sw x3, 0x8(x31)

           // Because ecall mepc address is ecall itself,
           // It need to increase 4 to return next of ecall
           addi  x1, x1, 0x4
           csrrw x0, mepc, x1
           mret
           """
      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.mem.setupText(0x10000000L, handler)
        dut.run()
        testMem(dut)(
          Seq(
            0x0L -> 0x8000000cL,
            0x4L -> 0xbL,
            0x8L -> 0x10000000L,
            0xcL -> 0x123L
          )
        )
      }
    }
  }

  "Interrupt" - {
    "test 0" in {
      val text =
        """
           lui  x31, 0x40000
           addi x30, x0, 0x100

           lui  x1, 0x10000
           csrrw x0, mtvec, x1
           addi  x1, x0, 0x888
           csrrs x0, mie, x1       // Set MIE

           wfi
           csrrsi x1, mip, 0x0
           csrrsi x0, mstatus, 0x8  // Set MSTATUS MIE
           """.stripMargin

      val handler =
        """
           csrrsi x1, mepc, 0x0
           csrrsi x2, mcause, 0x0
           csrrsi x3, mtvec, 0x0
           csrrsi x4, mstatus, 0x0

           add x29, x31, x30
           sw x1, 0x0(x29)
           sw x2, 0x4(x29)
           sw x3, 0x8(x29)
           sw x4, 0xC(x29)

           // Clear interrupt
           lui x29, 0x70000
           addi x28, x0, 0x1

           addi x27, x0, 0xB
           addi x26, x0, 0x7
           addi x25, x0, 0x3

           bne, x2, x27, 0xC
           sw x28, 0x4(x29)
           fence
           bne, x2, x26, 0xC
           sw x28, 0x8(x29)
           fence
           bne, x2, x25, 0xC
           sw x28, 0xC(x29)
           fence

           addi x30, x30, 0x100
           mret
           """
      test(new Self.Cluster(params)(dataLatency = 8)).withAnnotations(useVerilator) {
        dut =>
          dut.mem.setupText(0x80000000L, text + textEnd)
          dut.mem.setupText(0x10000000L, handler)
          (0 until 160).foreach(_ => dut.tick())
          dut.extInterrupt(true)
//           dut.timerInterrupt(true)
//           dut.softwareInterrupt(true)
          dut.run()
          testMem(dut)(
            Seq(
              0x100L -> 0x8000001cL,
              0x104L -> 0xbL,
              0x108L -> 0x10000000L
//             0x10CL  -> 0x1880L,
//             0x200L  -> 0x8000001CL,
//             0x204L  -> 0x7L,
//             0x208L  -> 0x10000000L,
//             0x20CL  -> 0x1880L,
//             0x300L  -> 0x8000001CL,
//             0x304L  -> 0x3L,
//             0x308L  -> 0x10000000L,
//             0x30CL  -> 0x1880L,
            )
          )
      }
    }
  }
}
