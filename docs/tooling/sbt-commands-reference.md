# sbt command reference for UDACore

Run commands from the repository root. Both build paths use Scala 2.13.12,
Chisel 6.2.0 with its matching compiler plugin, and the sibling spec-framework
source checkout. The owner's minimum is Chisel 6; the exact supported tuple
remains pinned to 6.2.0. See
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

## Required pre-commit gate

```bash
sbt scalafmtCheckAll test
```

Run this command before committing and report its actual outcome.
`scalafmtCheckAll` validates all repository main Scala (including specs and
assembler), verification engine/suites, and Test sources. Its task aggregation is
limited to this repository's formatting ownership; external framework compilation
and the existing compile/test dependencies remain unchanged. Dependency formatting
is the upstream build's responsibility. `scalafmtSbtCheck` separately checks build
definitions when those files change.

`test` compiles the full Test source set, including the verification engine/suites,
then runs ScalaTest. The 2026-11-16 owner waiver replaced six superseded
cluster/harness files with bridges to the complete active L1 registry (minimum
376) and discovered scenario corpus (minimum 23). The 11 assembler tests remain
with exact output/encoding assertions and necessary assembler repairs. L1 pending
or non-PASS verdicts fail; scenarios must complete and pass every result check.
No test exclusion is used to make the command pass. The waiver is limited to this
migration; [AGENTS.md](../../AGENTS.md) retains the protected-file policy.

Provision tools before running sbt. Native simulator tests fork with the JDK 17
and selected environment resolved by `verif/bin/env.sh`, matching the direct
verification path; tests run serially. Resolved machine paths stay in ignored
`verif/.toolchain.env`.

The migrated combined gate passed 410 tests with failures, cancellations,
ignored tests, and pending tests all zero. Earlier
parser/formatting failures apply to the pre-migration tree. A formatting failure
can still prevent the chained tests from running, and a passing direct L1 run
does not establish a passing sbt gate. [The live handoff](../../document/HANDOFF.md)
records the latest measured result and log paths.

For an available ScalaTest suite, targeted reruns use:

```bash
sbt 'testOnly <SuiteName>'
sbt 'testOnly <SuiteName> -- -z <keyword>'
```

These still compile the Test source set first, so they do not bypass a parser
or API error elsewhere in that source set. Keep the complete invocation and
first relevant error with any reported result.
