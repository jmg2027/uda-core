package verif

import verif.harness.MemModel

/** Assembly program builder. Emits the text the repo's RISCVAssembler consumes.
 *  Helpers: `li` (lui+addi), `resultPtr` (x10 = RESULT_BASE), `storeResult`, `done`. */
class Program {
  private val b = new StringBuilder
  private var pending: Option[String] = None
  /** attach a label to the next emitted instruction line (so directives like li/storeResult can be labeled). */
  def label(l: String): this.type = { pending = Some(l); this }
  def line(s: String): this.type = {
    val withLabel = pending match { case Some(l) => pending = None; s"$l: $s"; case None => s }
    b.append("    " + withLabel + "\n"); this
  }
  def lines(s: String): this.type = { s.split("\n").map(_.trim).filter(_.nonEmpty).foreach(line); this }
  def li(reg: String, v: Int): this.type = {
    val hi = (v + 0x800) >> 12; val lo = v - (hi << 12)
    line(s"lui $reg, 0x${(hi & 0xFFFFF).toHexString}"); line(s"addi $reg, $reg, $lo")
  }
  /** x10 = MemModel.RESULT_BASE (0x80200), the result slot base used by storeResult. */
  def resultPtr(reg: String = "x10"): this.type = { line(s"lui $reg, 0x80"); line(s"addi $reg, $reg, 0x200") }
  def storeResult(reg: String, slot: Int, ptr: String = "x10"): this.type = line(s"sw $reg, ${slot * 4}($ptr)")
  def done(): this.type = lines("lui x11, 0x70000\naddi x12, x0, 1\nsw x12, 0(x11)\njal x0, 0")
  def asm: String = b.toString
}
object Program { def apply(): Program = new Program }

/** Outcome of a single check. Per-slot semantics: a result slot is PASS (stored & correct),
 *  FAIL (stored & wrong), or NOSTORE (never written - the op trapped / produced no result).
 *  Run-level derail is reported separately by the harness, not folded into every slot. */
case class CheckResult(ok: Boolean, stored: Boolean, label: String, detail: String) {
  def tag: String = if (!stored) "NOSTORE" else if (ok) "PASS" else "FAIL"
}
object CheckResult {
  /** compare a result slot (RESULT_BASE + off) to an expected 32-bit value. */
  def slotEq(rr: harness.RunResult, off: Long, exp: Int, label: String): CheckResult = {
    val stored = rr.resultStored(off); val got = rr.result(off)
    CheckResult(stored && got == exp, stored, label, f"got=0x$got%08X exp=0x$exp%08X")
  }
  /** assert the run did not derail (for control-flow scenarios like the F-2 jal-at-redirect test). */
  def noDerail(rr: harness.RunResult, label: String): CheckResult =
    CheckResult(!rr.derailed, stored = true, label, if (rr.derailed) "DERAILED" else "ok")
}

/** A self-checking program: name + assembly + a checker over the run result. */
case class Scenario(
  name: String,
  asm: String,
  maxCycles: Int = 300,
  iLat: Int = 2,
  dLat: Int = 2
)(val check: harness.RunResult => Seq[CheckResult])
