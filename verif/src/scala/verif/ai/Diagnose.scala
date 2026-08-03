package verif.ai

import verif.harness.{MemModel, RunResult}

/** Classifies why a run failed - the lessons of this session encoded so an AI gets "why", not
 *  just "done=false". Order matters: the program-overrun check comes first (a too-long scenario
 *  silently overwrites its own tail via the result stores), then the assembler-gap check (the
 *  stale-assembler false positive), both common self-inflicted causes. */
object Diagnose {
  def of(words: Seq[Long], rr: RunResult): Option[String] = {
    val progWindow = MemModel.RESULT_BASE - MemModel.PROG_BASE   // bytes of program space before results
    val progBytes  = words.size.toLong * 4
    val zeros = words.zipWithIndex.filter(_._1 == 0L).map(z => MemModel.PROG_BASE + z._2 * 4)
    if (progBytes > progWindow && (rr.derailed || !rr.done))
      Some(f"program overrun: ${words.size} instrs ($progBytes bytes) exceed the $progWindow-byte " +
           f"(${progWindow / 4}-instr) window between PROG_BASE (0x${MemModel.PROG_BASE}%X) and " +
           f"RESULT_BASE (0x${MemModel.RESULT_BASE}%X); the first result stores overwrite the program " +
           "tail and it runs off. Split the scenario or cut checks - this is a harness limit, not a core bug.")
    else if (zeros.nonEmpty && (rr.derailed || !rr.done))
      Some(s"image contains illegal 0x00000000 word(s) at " + zeros.map(a => f"0x$a%X").mkString(",") +
           " - the assembler likely does not know a mnemonic (it emits all-zero, which decode-traps). Check coverage with AsmDiff.")
    else if (rr.derailed)
      Some("derailed: committed a pc below 0x80000 (trap-to-vector / ran off). pc-stream tail = " +
           rr.pcStream.takeRight(6).map(x => f"0x$x%X").mkString(" "))
    else if (!rr.done)
      Some(s"timeout/hang: ran ${rr.cycles} cycles without reaching DONE; last committed pc = " +
           rr.pcStream.lastOption.map(x => f"0x$x%X").getOrElse("-") +
           ". Either a real hang or maxCycles too low (raise @maxcycles to disambiguate).")
    else None
  }
}
