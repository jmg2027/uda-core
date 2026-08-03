package verif.harness

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.core.design.shared.CoreParams
import udacore.core.design.top.CoreTop
import scala.collection.mutable

/** Result of running the DUT on a program. Field semantics are identical to the main-line
 *  harness so every consumer (Suite, RunSpec, Gate, Diagnose) ports unchanged. */
case class RunResult(
  stores:   mutable.Map[Long, Long], // final memory image (program + data stores)
  pcStream: Seq[Long],               // committed-pc stream from the retire interface
  done:     Boolean,                 // store to MemModel.DONE observed
  cycles:   Int,
  derailed: Boolean                  // committed a pc below PROG_BASE (ran off into a trap vector etc.)
) {
  def result(off: Long): Int = (stores.getOrElse(MemModel.RESULT_BASE + off, MemModel.SENTINEL) & 0xFFFFFFFFL).toInt
  def resultStored(off: Long): Boolean = stores.contains(MemModel.RESULT_BASE + off)
}

/** Raised by every DUT-facing entrypoint while CoreTop is still a spec shell. The ai layer
 *  turns this into a structured error instead of a stack trace, so `scn.sh run` stays useful
 *  as a status probe during the RTL fill-in. */
class HarnessNotReady(msg: String) extends RuntimeException(msg)

/** The one DUT driver: instantiates CoreTop, serves its external program/data memory
 *  interfaces from a [[MemModel]], and captures stores plus the committed-pc stream.
 *
 *  Port status. The engine around this object (Scenario/Suite/ai) is fully ported from main
 *  and compiles against this API. The per-cycle DUT binding itself cannot exist yet: CoreTop's
 *  io bundle and every vertex are spec shells (`???`), and the committed-pc stream depends on
 *  the ADR-010 retire interface (`bndRetireToken` / `intfRetireStreamOut`, gated by
 *  `paramUsingRvvi`), whose design port is to be built together with the CommitUnit RTL - not
 *  bolted on here. Until then [[run]]/[[runBatch]]/[[runServer]] raise [[HarnessNotReady]].
 *
 *  Binding contract for the CommitUnit session (mirrors the main-line runScenario):
 *   - drive static inputs each cycle: bootAddrIn = MemModel.PROG_BASE, hartEnIn on,
 *     interruptIn/debugReqIn idle (overridden by scheduled IrqPulse windows);
 *   - serve externalProgramMemoryReqOut -> externalProgramMemoryRespIn from MemModel.load
 *     with an iLat-deep in-order delay queue (ready/valid, not the main line's req/ack pins);
 *   - serve externalDataMemoryReqOut -> externalDataMemoryRespIn with a dLat-deep queue;
 *     stores apply byte masks via MemModel.storeStrb; a store to MemModel.DONE ends the run;
 *   - sample the retire stream every cycle into pcStream; derail = any committed pc below
 *     MemModel.PROG_BASE;
 *   - reset between scenarios inside one compiled DUT (runBatch/runServer) - programs must
 *     stay self-contained, the physical register file is not reset.
 */
object CoreHarness {

  /** A scheduled interrupt pulse: hold line `kind` high during cycles [at, at+dur). Kinds
   *  follow the main-line convention ('t' timer / 'e' external / 's' software); the rebuild
   *  interruptIn bundle decides how they map when its fields land. */
  case class IrqPulse(kind: Char, at: Int, dur: Int)

  /** One scenario for [[runBatch]] (all scenarios in a batch share one CoreParams config). */
  case class Scenario(name: String, m: MemModel, maxCycles: Int = 600, verbose: Boolean = false,
                      irq: Seq[IrqPulse] = Nil)

  val notReadyMessage: String =
    "CoreTop is a spec shell (io and vertices are unimplemented); the harness DUT binding " +
    "activates with the CommitUnit RTL and the ADR-010 retire stream. Engine, scn grammar, " +
    "gates and the synthesis flow are ready - see verif/README.md."

  /** Elaborate-or-explain: turns the spec shell's NotImplementedError into the actionable
   *  harness status. Once CoreTop elaborates, this is where the per-cycle binding loop from
   *  the contract above replaces the failure path. */
  private def withDut[A](p: CoreParams)(body: CoreTop => A): A = {
    var out: Option[A] = None
    try
      simulate(new CoreTop(p)) { dut => out = Some(body(dut)) }
    catch {
      case _: NotImplementedError => throw new HarnessNotReady(notReadyMessage)
    }
    out.get
  }

  private def runScenario(dut: CoreTop, s: Scenario): RunResult =
    // Binding placeholder (documented in the object doc above): replaced together with the
    // CommitUnit RTL. Reaching this point means CoreTop elaborated, so the shell era is over
    // and this placeholder is the next thing to build.
    throw new HarnessNotReady(
      "CoreTop elaborates but the harness per-cycle binding is not written yet - " +
      "implement CoreHarness.runScenario against the binding contract in its doc comment.")

  def run(name: String, m: MemModel, maxCycles: Int = 600, verbose: Boolean = false,
          irq: Seq[IrqPulse] = Nil)(implicit p: CoreParams): RunResult =
    withDut(p)(dut => runScenario(dut, Scenario(name, m, maxCycles, verbose, irq)))

  /** Compile the DUT once, run many scenarios (reset between each). The dominant per-run cost
   *  (FIRRTL -> SV -> Verilator elaborate + link) is paid once instead of per scenario. */
  def runBatch(scenarios: Seq[Scenario])(implicit p: CoreParams): Seq[RunResult] =
    withDut(p)(dut => scenarios.map(s => runScenario(dut, s)))

  /** Hold one compiled DUT open and serve scenarios as they arrive (the scn daemon). `next` is
   *  polled for work: Some(scenario) runs it (reset between, exactly as runBatch), None shuts
   *  the server down. */
  def runServer(next: () => Option[Scenario])(onResult: (Scenario, RunResult) => Unit)
               (implicit p: CoreParams): Unit =
    withDut(p) { dut =>
      var live = true
      while (live) next() match {
        case Some(s) => onResult(s, runScenario(dut, s))
        case None    => live = false
      }
    }
}
