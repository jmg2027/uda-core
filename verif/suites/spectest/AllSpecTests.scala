package verif.spectest

/** The L1 SpecTest registry + runner entrypoint (ADR-018).
  *
  *   verif/bin/run.sh verif.spectest.RunSpecTests [nameFilter] [--json]
  *
  * Registration is explicit: add new tests to `registry`. The runner exits 1 on
  * any FAIL; PENDING (spec-shell DUT) is reported but does not fail the run.
  */
object RunSpecTests {
  val registry: Seq[SpecTest] =
    UnitSpecTests.all ++ CsrSpecTests.all ++ ParamSpecTests.all ++ RenameUnitSpecTests.all ++ ReorderBufferSpecTests.all ++
      SystemOpDecodeSpecTests.all ++ RecoveryControllerSpecTests.all

  def main(args: Array[String]): Unit = SpecTestRunner.run(registry, args)
}
