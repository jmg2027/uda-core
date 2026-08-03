package assembler

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import assembler.RISCVAssembler

class RVCAssemblerTest extends AnyFlatSpec with Matchers {
  "RISCVAssembler" should "assemble basic compressed instructions" in {
    val text = """
      c.nop
      c.addi x1, 1
      c.j 1f
    1:
      c.jal 1b
      """.stripMargin

    val out = RISCVAssembler.fromString(text).trim.split("\n").toSeq
    out should equal(Seq("0001", "0085", "A009", "2001"))
  }
}
