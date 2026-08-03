import org.scalatest.funsuite.AnyFunSuite
import assembler.RISCVAssembler
import assembler.CompressedAssembler
import scala.collection.mutable

class RVCEncodeTest extends AnyFunSuite {
  test("C.ADDI t0, 10 should encode correctly") {
    val symbolTable = mutable.Map[String, Long]()
    val hex         = RISCVAssembler.parseLine("C.ADDI x5, 10", 0, symbolTable.toMap, 2)
    assert(hex == 0x2a9)
  }

  test("C.J label should encode correctly with offset") {
    val symbolTable = mutable.Map[String, Long]("target_label" -> 0x10)
    val hex         =
      RISCVAssembler.parseLine("C.J target_label", 0, symbolTable.toMap, 2)
    assert(hex == 0xa801)
  }

  test("C.LW x8, 4(x9) should encode correctly") {
    val symbolTable = mutable.Map[String, Long]()
    val hex         =
      RISCVAssembler.parseLine("C.LW x8, 4(x9)", 0, symbolTable.toMap, 2)
    assert(hex == 0x4204)
  }

  test("C.SW x8, 8(x9) should encode correctly") {
    val symbolTable = mutable.Map[String, Long]()
    val hex         =
      RISCVAssembler.parseLine("C.SW x8, 8(x9)", 0, symbolTable.toMap, 2)
    assert(hex == 0xc284)
  }

  test("C.ADDI x5, -32 should encode correctly (lower boundary)") {
    val symbolTable = mutable.Map[String, Long]()
    val hex         =
      RISCVAssembler.parseLine("C.ADDI x5, -32", 0, symbolTable.toMap, 2)
    assert(hex == 0x1281)
  }

  test("C.ADDI x5, 31 should encode correctly (upper boundary)") {
    val symbolTable = mutable.Map[String, Long]()
    val hex         = RISCVAssembler.parseLine("C.ADDI x5, 31", 0, symbolTable.toMap, 2)
    assert(hex == 0x027d)
  }

  test("C.J with out-of-range offset should throw an error") {
    val symbolTable = mutable.Map[String, Long]("far_label" -> 0x1000)
    val thrown      = intercept[RuntimeException] {
      RISCVAssembler.parseLine("C.J far_label", 0, symbolTable.toMap, 2)
    }
    assert(thrown.getMessage.contains("Branch offset out of range"))
  }

  test("C.BEQZ with out-of-range offset should throw an error") {
    val symbolTable = mutable.Map[String, Long]("far" -> 0x1000)
    val thrown      = intercept[RuntimeException] {
      RISCVAssembler.parseLine("C.BEQZ x8, far", 0, symbolTable.toMap, 2)
    }
    assert(thrown.getMessage.contains("Branch offset out of range"))
  }

  test("C.JAL should throw error if XLEN is not 32") {
    val orig        = CompressedAssembler.XLEN
    CompressedAssembler.XLEN = 64
    val symbolTable = mutable.Map[String, Long]("target" -> 0)
    val thrown      = intercept[RuntimeException] {
      RISCVAssembler.parseLine("C.JAL target", 0, symbolTable.toMap, 2)
    }
    assert(thrown.getMessage.contains("RV32-only"))
    CompressedAssembler.XLEN = orig
  }
}
