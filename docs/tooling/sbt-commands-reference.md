# sbt command reference for UDACore

Run commands from the repository root. Both build paths use Scala 2.13.12,
Chisel 6.2.0, and the sibling spec-framework source checkout. See
[framework setup](spec-framework.md) for dependencies and export paths.

## Compile and export

```bash
sbt compile
sbt specCheck
```

`compile` checks Scala specs and RTL without emitting Verilog. `specCheck`
performs a clean build and exports the real spec/tag indexes to
`target/spec-report/`; it does not replace the separate ADR-015/018 checker.

The active local verification path is:

```bash
bash verif/bin/build.sh
python3 tools/spec-check.py
bash verif/bin/test-spec-framework.sh
verif/bin/run.sh verif.spectest.RunSpecTests
verif/bin/scn.sh run verif/scn/add.scn --json
```

Use [the verification guide](../../verif/README.md) for scenario options, Trace,
and product CoreTop synthesis/STA. The former `UDACoreElab` command and old
cluster smoke tests are not the current CoreTop verification entry points.

## Required pre-commit attempt

```bash
sbt scalafmtCheckAll test
```

Run this command before committing and report its actual outcome.
`scalafmtCheckAll` validates formatting; `test` compiles and runs the ScalaTest
sources under `src/test`. A failure in the first command can prevent the tests
from running. A passing direct L1 regression does not imply this sbt gate passed.

The protected historical cluster/assembler tests target older APIs. The latest
recorded run stopped at formatting and a source-parser error in protected
`src/test/scala/cluster/RV32BTest.scala`; a separate `sbt test` also stopped before
running tests. [The live handoff](../../document/HANDOFF.md) records current
results and log paths. Report incompatibilities without altering protected
infrastructure or weakening checks unless the owner explicitly authorizes it.

For an available ScalaTest suite, targeted reruns use:

```bash
sbt 'testOnly <SuiteName>'
sbt 'testOnly <SuiteName> -- -z <keyword>'
```

These still compile the Test source set first, so they do not bypass a parser
or API error elsewhere in that source set. Keep the complete invocation and
first relevant error with any reported result.
