logLevel                    := Level.Warn
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.9")
// The proprietary spec plugin is unavailable in this environment. Stub macros
// have been added under `framework.macros` and `framework.spec` to allow the
// codebase to compile without this plugin.
// addSbtPlugin("your.company" % "spec-plugin" % "0.1.0-SNAPSHOT")
