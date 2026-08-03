# sbt command reference for UDACore

This guide keeps the most common `sbt` workflows in one place so you can run
local checks without hunting through the repository. Commands appear in the
order you should consider them during daily development. All paths are relative
to the repository root.

## 1. Quick syntax checks
- `sbt compile` - Runs the Scala compiler without generating Verilog. Use it for
  rapid feedback while editing specs or RTL.

## 2. Formatting gate
- `sbt scalafmtCheckAll` - Verifies that every Scala file is formatted according
  to the shared configuration. Run this before committing to avoid CI failures.

## 3. Unit test sweep
- `sbt test` - Executes the full ScalaTest suite. It is the minimum regression
  gate for any change that touches specs, RTL, or shared bundles.

## 4. Cluster smoke test
- `sbt 'testOnly *SingleCoreMulDivClusterTest* -- -z "Store test 0"'` - Runs the
  most simple cluster-level smoke. Capture the log with `tee` when you need to
  share results.
- `sbt 'testOnly *SingleCoreMulDivClusterTest*'` - Runs all cluster-level smoke tests.

## 5. Targeted reruns
Use the following patterns when debugging a specific failure:
- `sbt "testOnly <SuiteName>"` - Limits execution to one ScalaTest suite.
- `sbt "testOnly <SuiteName> -- -z <keyword>"` - Narrows the run to tests whose
  names include the given keyword.

## 6. RTL elaboration
- `sbt runMain UDACoreElab` - Elaborates the configured design into Verilog
  under `./UDACoreElab`. Run this after major structural changes or when you
  need waveforms for debugging.

## 7. Tips for reliable runs
- Always start from a clean terminal session. `sbt` caches class loaders, so
  restarting the shell avoids stale state.
- Use `sbt --client <command>` only when the server is already running and you
  need a quick follow-up command. Otherwise, prefer launching a fresh session to
  inherit the latest build definition.
- When diagnosing a failure, copy the full `sbt` invocation into your working
  notes along with the log snippet so reviewers can replay the issue.
