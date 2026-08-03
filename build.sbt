// -----------------------------------------------------------------------------
//  Global settings
// -----------------------------------------------------------------------------
val chiselVer = sys.props.getOrElse("chiselVer", "3")

val scalaVer = chiselVer match {
  case "3" => "2.13.12"
  case _   => "2.13.16"
}
ThisBuild / scalaVersion := scalaVer
ThisBuild / organization := "%ORGANIZATION%"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// -----------------------------------------------------------------------------
//  Chisel version selector (use -DchiselVer=3 or -DchiselVer=6, default: 6)
// -----------------------------------------------------------------------------

val chiselConfig: Map[String, String] = chiselVer match {
  case "3" =>
    Map(
      "org"       -> "edu.berkeley.cs",
      "dep"       -> "chisel3",
      "depVer"    -> "3.6.1",
      "plugin"    -> "chisel3-plugin",
      "pluginVer" -> "3.6.1",
      "test"      -> "chiseltest",
      "testVer"   -> "0.6.0"
    )
  case _   =>
    Map(
      "org"       -> "org.chipsalliance",
      "dep"       -> "chisel",
      "depVer"    -> "6.7.0",
      "plugin"    -> "chisel-plugin",
      "pluginVer" -> "6.7.0",
      "test"      -> "chiseltest",
      "testVer"   -> "0.6.0"
    )
}

// -----------------------------------------------------------------------------
//  Library versions
// -----------------------------------------------------------------------------
val specFwVersion = "0.1.0-SNAPSHOT"
val useSpecPlugin = sys.props.get("useSpecPlugin").contains("true")

// -----------------------------------------------------------------------------
//  Root project
// -----------------------------------------------------------------------------
lazy val root = (project in file("."))
  // SpecPlugin removed for open-source compilation
  .settings(
    (Seq(
      name           := "%NAME%",
      libraryDependencies ++=
        Seq(
          chiselConfig("org") %% chiselConfig("dep")  % chiselConfig("depVer"),
          "org.scalatest"     %% "scalatest"          % "3.2.19" % Test,
          chiselConfig("org") %% chiselConfig("test") % chiselConfig(
            "testVer"
          )                    % Test
        ) ++
          (if (useSpecPlugin)
             Seq(
               "your.company" %% "spec-core"   % specFwVersion,
               "your.company" %% "spec-macros" % specFwVersion
             )
           else Seq.empty),
      scalacOptions ++= Seq(
        "-language:reflectiveCalls",
        "-deprecation",
        "-feature",
        "-Xcheckinit",
        "-Ymacro-annotations",
        s"-Dspec.meta.dir=${(Compile / resourceManaged).value}/spec-meta"
        // For debug
//      "-Ymacro-debug-verbose"
      ),
      initialize     := {
        val _   = initialize.value // Keep existing initialization
        val dir = (Compile / resourceManaged).value / "spec-meta"
        System.setProperty("spec.meta.dir", dir.getAbsolutePath)
      },
      Compile / fork := true,

//      Compile / javaOptions ++= {
//        val dir = (Compile / resourceManaged).value / "spec-meta"
//        Seq(s"-Dspec.meta.dir=${dir.getAbsolutePath}")
//      },

      Compile / resourceGenerators += Def.task {
        val _ = (Compile / resourceManaged).value / "spec-meta"
        Seq.empty[File]
      }.taskValue
    ) ++
      addCompilerPlugin(
        chiselConfig("org") % chiselConfig("plugin") % chiselConfig(
          "pluginVer"
        ) cross CrossVersion.full
      ) ++
      (if (useSpecPlugin)
         Seq(
           addCompilerPlugin(
             "your.company" % "spec-plugin" % specFwVersion cross CrossVersion.full
           )
         )
       else Seq.empty))
  )