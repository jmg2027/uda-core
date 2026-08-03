package verif.harness

import udacore.core.design.shared.CoreParams

/** One per-cycle sample of the tapped signals (plus the expected instruction word at the
 *  fetched pc). The field set is the main-line one that mattered for its confirmed findings;
 *  the rebuild keeps the shape so Trace and its JSON schema port unchanged, and fills the
 *  taps from rebuild vertices as they land. */
case class Cycle(i: Int, reqAddr: Long, pc: Long, valid: Boolean, instRaw: Long, expInst: Long,
                 flush: Boolean, jump: Boolean, xcpt: Boolean, stall: Boolean, divBusy: Boolean,
                 rd: Int, aluWB: Boolean) {
  def desync: Boolean = valid && instRaw != expInst
}

/** Declarative signal probe: run a program with BoringUtils taps on a curated set of internal
 *  signals and return per-cycle samples - waveform-as-numbers for the ai Trace entrypoint.
 *
 *  Port status: the tap set is bound to concrete vertex signals (a UDACoreProbe wrapper module
 *  tapping FetchUnit/IssueQueue/RedirectUnit/CommitUnit vals) once those vertices carry RTL;
 *  it lands together with the CoreHarness binding. Candidate rebuild taps: the fetched packet
 *  (pc, instRaw, valid), globalEpoch and redirect fire (the rebuild's flush equivalents), the
 *  commit-head seqTag, and per-unit busy. Until then trace() raises [[HarnessNotReady]].
 */
object Probe {
  val taps = Seq("pc", "instRaw", "expInst", "valid", "flush", "jump", "xcpt", "stall",
                 "divBusy", "rd", "aluWB", "reqAddr", "desync")

  def trace(m: MemModel, cycles: Int = 40)(implicit p: CoreParams): Seq[Cycle] =
    throw new HarnessNotReady(
      "Probe taps bind to rebuild vertex signals when the CoreHarness DUT binding lands; " +
      CoreHarness.notReadyMessage)
}
