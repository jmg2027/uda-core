package verif.suites

import udacore.core.design.shared.CoreParams
import verif.{CheckResult, Scenario, Suite}
import verif.config.CoreConfigs

/** The first suite to run when CoreTop starts elaborating: a handful of RV32I scenarios that
 *  prove fetch, decode, the ALU path, memory stores and the DONE sentinel end to end. Ported as
 *  a template from the main-line AluSuite shape; it compiles today and raises harness-not-ready
 *  until the CoreHarness DUT binding lands. */
object SmokeSuite {
  def scenarios: Seq[Scenario] = Seq(
    Scenario("addi-chain",
      """
      addi x1, x0, 5
      addi x2, x1, 7
      lui x10, 0x80
      addi x10, x10, 0x200
      sw x2, 0(x10)
      lui x11, 0x70000
      addi x12, x0, 1
      sw x12, 0(x11)
      jal x0, 0
      """)(rr => Seq(CheckResult.slotEq(rr, 0, 12, "addi chain 5+7"))),
    Scenario("branch-taken",
      """
      addi x1, x0, 1
      addi x2, x0, 1
      beq x1, x2, target
      addi x3, x0, 111
      target: addi x3, x0, 42
      lui x10, 0x80
      addi x10, x10, 0x200
      sw x3, 0(x10)
      lui x11, 0x70000
      addi x12, x0, 1
      sw x12, 0(x11)
      jal x0, 0
      """)(rr => Seq(CheckResult.slotEq(rr, 0, 42, "beq skips fallthrough"),
                     CheckResult.noDerail(rr, "no derail")))
  )

  def main(args: Array[String]): Unit = {
    implicit val p: CoreParams = CoreConfigs.fromArgs(args)._2
    Suite("Smoke", scenarios).runMain
  }
}
