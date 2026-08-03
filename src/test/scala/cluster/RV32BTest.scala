package cluster

import chisel3._
import chiseltest._
import cluster.util.ClusterTest
import udacore.DefaultCoreConfig

class RV32BTest extends ClusterTest {
  val Self = SingleCoreMulDivClusterTest

  val params: CoreParams = DefaultCoreConfig.default

  val textEnd =
    """
    lui  x31, 0x70000
    addi x30, x0, 0x1
    sw   x30, 0(x31)
    auipc x30, 0x0
    jalr  x0, x30, 0x0
    """.stripMargin

  def testMem(dut: TestCluster(params))(tc: Seq[(Long, Long)]) = {
    tc.foreach { case (a, v) =>
      val addr = 0x40000000L + a
      val data = dut.mem.load(addr)
      println(f"Addr(0x$addr%X) data(0x$data%X) expect(0x$v%X)")
      assert(data == v)
    }
  }

  "RV32B" - {
    "basic operations" in {
      val text =
        """
        lui  x31, 0x40000
        lui  x1, 0x12345
        addi x1, x1, 0x678
        lui  x2, 0x87654
        addi x2, x2, 0x321

        bclr  x3,  x1, x2
        bset  x4,  x1, x2
        binv  x5,  x1, x2
        bext  x6,  x1, x2
        bclri x7,  x1, 1
        bseti x8,  x1, 1
        binvi x9,  x1, 1
        bexti x10, x1, 1
        clz   x11, x1
        ctz   x12, x1
        cpop  x13, x1
        rev8  x14, x1
        sh1add x15, x1, x2
        clmul x16, x1, x2

        sw x3,  0(x31)
        sw x4,  4(x31)
        sw x5,  8(x31)
        sw x6,  12(x31)
        sw x7,  16(x31)
        sw x8,  20(x31)
        sw x9,  24(x31)
        sw x10, 28(x31)
        sw x11, 32(x31)
        sw x12, 36(x31)
        sw x13, 40(x31)
        sw x14, 44(x31)
        sw x15, 48(x31)
        sw x16, 52(x31)
        """.stripMargin

      test(new Self.Cluster(params)).withAnnotations(useVerilator) { dut =>
        dut.mem.setupText(0x80000000L, text + textEnd)
        dut.run()
        testMem(dut)(
          Seq(
            0x0L  -> 0x12345678L,
            0x4L  -> 0x1234567aL,
            0x8L  -> 0x1234567aL,
            0xcL  -> 0x0L,
            0x10L -> 0x12345678L,
            0x14L -> 0x1234567aL,
            0x18L -> 0x1234567aL,
            0x1cL -> 0x0L,
            0x20L -> 0x3L,
            0x24L -> 0x3L,
            0x28L -> 0xdL,
            0x2cL -> 0x78563412L,
            0x30L -> 0xabcdf011L,
            0x34L -> 0x2b421178L
          )
        )
      }
    }
  }
}
