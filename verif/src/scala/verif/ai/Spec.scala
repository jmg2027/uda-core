package verif.ai

import verif.Program

/** A check the AI declared: store `reg` to a result slot and assert it equals `exp`. */
case class Check(slot: Int, reg: String, exp: Int)

/** A parsed `.scn` declarative scenario. See AI-HARNESS.md for the grammar. */
case class Spec(
  name: String,
  asm: String,
  checks: Seq[Check],
  maxCycles: Int,
  iLat: Int,
  dLat: Int,
  expectDerail: Boolean,
  expectDone: Boolean,
  irq: Seq[(Char, Int, Int)] = Nil,  // (kind 't'/'e'/'s', atCycle, durCycles)
  verifies: Seq[String] = Nil        // spec val names this scenario tests (ADR-018 L2)
)

object Spec {
  /** parse a value: 0x-hex or signed decimal, into a 32-bit Int. */
  def parseVal(s: String): Int = {
    val t = s.trim
    if (t.startsWith("0x") || t.startsWith("0X")) (java.lang.Long.parseLong(t.substring(2), 16) & 0xFFFFFFFFL).toInt
    else if (t.startsWith("-0x")) (-java.lang.Long.parseLong(t.substring(3), 16)).toInt
    else t.toLong.toInt
  }

  def parse(text: String): Spec = {
    var name = "scn"; var maxCycles = 300; var iLat = 2; var dLat = 2
    var expectDerail = false; var expectDone = false
    val irq = scala.collection.mutable.ArrayBuffer[(Char, Int, Int)]()
    val verifies = scala.collection.mutable.ArrayBuffer[String]()
    val p = Program()
    p.resultPtr("x31")           // x31 = RESULT_BASE; reserved
    val checks = scala.collection.mutable.ArrayBuffer[Check]()
    var slot = 0
    val labelRe = """^(\w+):\s*(.*)$""".r
    for (rawLine <- text.split("\n")) {
      val line = rawLine.split("#")(0).trim   // strip comments
      if (line.nonEmpty) {
        // peel an optional leading label; apply it to a directive's first instruction (li/check),
        // but pass a labeled raw asm line through verbatim (the assembler handles same-line labels).
        val (label, body) = line match { case labelRe(l, r) if r.nonEmpty => (Some(l), r); case _ => (None, line) }
        val toks = body.split("\\s+").toList
        val isDirective = toks.headOption.exists(t => Set("@name","@maxcycles","@ilat","@dlat","@irq","@verifies","li","check","expect").contains(t))
        if (label.isDefined && isDirective) p.label(label.get)
        toks match {
          case "@name" :: rest          => name = rest.mkString(" ")
          case "@maxcycles" :: v :: _   => maxCycles = v.toInt
          case "@ilat" :: v :: _        => iLat = v.toInt
          case "@dlat" :: v :: _        => dLat = v.toInt
          case "@irq" :: kind :: at :: rest =>   // @irq <t|e|s> <atCycle> [durCycles]
            val dur = rest.headOption.map(_.toInt).getOrElse(10)
            irq += ((kind.trim.toLowerCase.head, at.toInt, dur))
          case "@verifies" :: rest      => verifies ++= rest   // spec val names (ADR-018)
          case "li" :: reg :: v :: _    => p.li(reg, parseVal(v))
          case "check" :: reg :: rest   =>
            val v = rest match { case "==" :: x :: _ => x; case x :: _ => x; case _ => sys.error(s"check needs a value: $line") }
            p.storeResult(reg, slot, "x31"); checks += Check(slot, reg, parseVal(v)); slot += 1
          case "expect" :: "derail" :: _ => expectDerail = true
          case "expect" :: "done" :: _   => expectDone = true
          case _                         => p.line(line)   // verbatim assembler line (label kept)
        }
      }
    }
    p.done()
    Spec(name, p.asm, checks.toSeq, maxCycles, iLat, dLat, expectDerail, expectDone, irq.toSeq, verifies.toSeq)
  }
}
