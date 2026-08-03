import org.scalatest.funsuite.AnyFunSuite
import assembler.{RISCVAssembler, RVCInstructions, RVInstructions}
import scala.collection.mutable

class MixRVIRVCTest extends AnyFunSuite {
  test("Mixed RVI and RVC instructions should assemble correctly") {
    val assembler = RISCVAssembler
    val source    = """
      .org 0x0
      addi x1, x0, 10
      c.addi x2, 20
      jal x0, label_target
      c.jal label_rvc
      c.nop
    label_target:
      add x3, x1, x2
    label_rvc:
      c.add x4, x5
      """.stripMargin

    val lines       = source.split("\n").map(_.trim).filter(_.nonEmpty)
    val symbolTable = mutable.Map[String, Long]()
    var pc: Long    = 0
    // first pass
    for (line <- lines) {
      if (line.endsWith(":")) {
        symbolTable += (line.dropRight(1) -> pc)
      } else if (!line.startsWith(".")) {
        val inst = line.split("\\s+")(0).toUpperCase
        if (RVCInstructions.get(inst).isDefined) pc += 2
        else if (RVInstructions.get(inst).isDefined) pc += 4
      }
    }

    pc = 0
    val assembled = mutable.Buffer[Int]()
    for (line <- lines) {
      if (!line.endsWith(":") && !line.startsWith(".")) {
        val inst  = line.split("\\s+")(0).toUpperCase
        val value = assembler.parseLine(line, pc, symbolTable.toMap, 2)
        if (RVCInstructions.get(inst).isDefined) {
          assembled += (value & 0xffff)
          pc += 2
        } else if (RVInstructions.get(inst).isDefined) {
          assembled += value
          pc += 4
        }
      }
    }

    assert(assembled.nonEmpty)
  }
}
