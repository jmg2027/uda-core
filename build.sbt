// Compile the sibling framework sources before UDA specs, then compile RTL.
// SPEC_FRAMEWORK_HOME overrides the default ../spec-framework checkout.
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "udacore"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val frameworkHome = {
  val path = file(
    sys.env.getOrElse("SPEC_FRAMEWORK_HOME", "../spec-framework")
  ).getCanonicalFile
  require(
    (path / "spec-core" / "src/main/scala").isDirectory,
    s"Missing spec-framework at $path; clone it beside uda-core or set SPEC_FRAMEWORK_HOME"
  )
  path
}
val udaHome       = file(".").getCanonicalFile
val specMeta      = udaHome / "target" / "spec-meta"
val chiselVersion = "6.2.0"

// Native simulator tests use the same provisioned tools as verif/bin/run.sh.
// Keep resolved machine paths in the ignored environment, not in this build.
val verificationEnvironment = {
  val resolved = udaHome / "verif/.toolchain.env"
  if (resolved.isFile) {
    val keys = Set(
      "PATH",
      "JAVA_HOME",
      "SDKROOT",
      "CHISEL_FIRTOOL_PATH",
      "FIRTOOL_DIR",
      "OBJCACHE",
      "CCACHE_DIR",
      "CCACHE_MAXSIZE",
      "CCACHE_BASEDIR",
      "CCACHE_SLOPPINESS",
      "CCACHE_NOHASHDIR",
      "MAKEFLAGS",
      "VERIF_SIMCACHE_DIR",
      "VERIF_SIMCACHE_KEEP",
      "VERIF_SIMCACHE"
    )
    scala.sys.process
      .Process(
        Seq(
          "bash",
          "-c",
          "source \"$1\"; env -0",
          "uda-test-env",
          (udaHome / "verif/bin/env.sh").getAbsolutePath
        ),
        udaHome
      )
      .!!
      .split("\u0000")
      .iterator
      .flatMap { entry =>
        val pair = entry.split("=", 2)
        if (pair.length == 2 && keys(pair(0))) Some(pair(0) -> pair(1))
        else None
      }
      .toMap
  } else Map.empty[String, String]
}

ThisBuild / initialize := {
  val previous = (ThisBuild / initialize).value
  System.setProperty("spec.meta.dir", specMeta.getAbsolutePath)
}

lazy val frameworkCore = (project in file("target/spec-build/core"))
  .settings(
    name                                 := "uda-spec-core",
    Compile / unmanagedSourceDirectories := Seq(
      frameworkHome / "spec-core/src/main/scala"
    ),
    // Use the same uPickle as the pinned Chisel verification toolchain.
    libraryDependencies += "com.lihaoyi" %% "upickle" % "3.1.0",
    publish / skip                       := true
  )

lazy val frameworkMacros = (project in file("target/spec-build/macros"))
  .dependsOn(frameworkCore)
  .settings(
    name                                   := "uda-spec-macros",
    Compile / unmanagedSourceDirectories   := Seq(
      frameworkHome / "spec-macros/src/main/scala"
    ),
    libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    Compile / scalacOptions += "-Ymacro-annotations",
    publish / skip                         := true
  )

lazy val udaSpecs = (project in file("target/spec-build/specs"))
  .dependsOn(frameworkCore, frameworkMacros)
  .settings(
    name                       := "uda-specs",
    Compile / unmanagedSources := ((udaHome / "src/main/scala") ** "*Specs.scala").get,
    Compile / scalacOptions += "-Ymacro-annotations",
    publish / skip             := true
  )

lazy val exportSpecIndex =
  taskKey[Unit]("Compile and export the real framework's spec and tag indexes")

lazy val root = (project in file("."))
  .aggregate(frameworkCore, frameworkMacros, udaSpecs)
  .dependsOn(udaSpecs, frameworkCore, frameworkMacros)
  .settings(
    name                                            := "uda-core",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel"    % chiselVersion,
      "org.scalatest"     %% "scalatest" % "3.2.19" % Test
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
    Compile / unmanagedSources ~= (_.filterNot(
      _.getName.endsWith("Specs.scala")
    )),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations"
    ),
    Compile / fork                                  := true,
    Test / unmanagedSourceDirectories ++= Seq(
      udaHome / "verif/src/scala",
      udaHome / "verif/suites"
    ),
    Test / fork                                     := true,
    Test / parallelExecution                        := false,
    Test / envVars ++= verificationEnvironment,
    Test / javaHome                                 := verificationEnvironment.get("JAVA_HOME").map(file),
    // Formatter ownership is this repository. Dependency compilation and tests
    // remain aggregated; external framework style belongs to its own build.
    scalafmtCheckAll / aggregate                    := false,
    scalafmtAll / aggregate                         := false,
    Compile / scalafmt / unmanagedSourceDirectories := Seq(
      udaHome / "src/main/scala",
      udaHome / "verif/src/scala",
      udaHome / "verif/suites"
    ),
    Compile / scalafmt / unmanagedSources           :=
      (Compile / scalafmt / unmanagedSourceDirectories).value
        .flatMap(d => (d ** "*.scala").get)
        .distinct,
    Test / scalafmt / unmanagedSources              := (Test / unmanagedSources).value,
    Test / scalafmt / unmanagedSourceDirectories    :=
      (Test / unmanagedSourceDirectories).value,
    cleanFiles += specMeta,
    exportSpecIndex                                 := {
      val _       = (Compile / compile).value
      val cp      = (Compile / fullClasspath).value.files
        .mkString(java.io.File.pathSeparator)
      val output  = target.value / "spec-report"
      val javaBin = (javaHome.value
        .map(_ / "bin/java")
        .getOrElse(file(sys.props("java.home")) / "bin/java"))
        .getAbsolutePath
      val result  = scala.sys.process
        .Process(
          Seq(
            javaBin,
            "-cp",
            cp,
            "framework.spec.SpecCheck",
            specMeta.getAbsolutePath,
            output.getAbsolutePath,
            "--top=CoreTop"
          ),
          baseDirectory.value
        )
        .!
      if (result != 0) sys.error("SpecCheck failed")
      val checked = scala.sys.process
        .Process(
          Seq(
            "python3",
            "tools/spec-artifacts-check.py",
            specMeta.getAbsolutePath,
            output.getAbsolutePath
          ),
          baseDirectory.value
        )
        .!
      if (checked != 0)
        sys
          .error("Invalid spec artifacts; run sbt specCheck for a clean export")
    }
  )

// Macro artifacts use unique filenames; clean all projects before a full export.
addCommandAlias("specCheck", ";clean;exportSpecIndex")
