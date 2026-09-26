package chisel3.simulator

import chisel3.{Data, Element, RawModule, Record, Vec}
import chisel3.reflect.DataMirror
import chisel3.ActualDirection
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.security.MessageDigest
import svsim._

/** A drop-in for EphemeralSimulator.simulate with a persistent, content-keyed workspace cache.
 *
 *  The wall-clock cost of a simulate() is elaboration (Chisel, in-JVM) + firtool (fir -> SV) +
 *  verilator (SV -> C++) + make (g++/link). ccache already collapses the g++ step for an
 *  unchanged DUT; this cache removes firtool + verilator + make as well:
 *
 *  - every call elaborates the design once to CHIRRTL (that is unavoidable - the peek/poke API
 *    needs the in-JVM module object) and hashes the emitted .fir together with the tool
 *    versions;
 *  - hash hit: the previously built workspace's simulation binary is reopened directly
 *    (svsim.WorkspaceReopen) - no firtool, no verilator, no make;
 *  - hash miss: the standard full build runs (a second elaboration inside ChiselStage's
 *    systemverilog pipeline - misses happen once per RTL change), then the workspace is
 *    published into the cache under its hash, atomically, so concurrent builders cannot corrupt
 *    each other (the loser keeps its private build).
 *
 *  Cache root: VERIF_SIMCACHE_DIR (default /tmp/verif-simcache), pruned to the
 *  VERIF_SIMCACHE_KEEP (default 8) most recently used entries. VERIF_SIMCACHE=off falls back to
 *  build-and-discard (the EphemeralSimulator behaviour). Lives in package chisel3.simulator to
 *  reach the private[simulator] ElaboratedModule constructor.
 */
object CachedSimulator extends PeekPokeAPI {

  private def env(name: String, default: String) = sys.env.getOrElse(name, default)
  private def cacheRoot = Paths.get(env("VERIF_SIMCACHE_DIR", "/tmp/verif-simcache"))
  private def keepEntries = env("VERIF_SIMCACHE_KEEP", "8").toInt
  private def cacheOff = sys.env.get("VERIF_SIMCACHE").contains("off")

  // Mirrors EphemeralSimulator's backend configuration (env.sh carries the make -j / -O0 / ccache
  // knobs through MAKEFLAGS and OBJCACHE, so nothing needs to be set here).
  private def backend = verilator.Backend.initializeFromProcessEnvironment()
  private def commonSettings = CommonCompilationSettings()
  private def backendSettings = verilator.Backend.CompilationSettings()

  private lazy val toolFingerprint: String = {
    val v = try {
      sys.process.Process(Seq("verilator", "--version")).!!.trim
    } catch { case _: Throwable => "verilator-unknown" }
    s"$v chisel-${chisel3.BuildInfo.version} schema-1"
  }

  /** Port derivation for the CHIRRTL-only (cache hit) path - the same inference
   *  ChiselWorkspace.elaborateGeneratedModule performs on the full-build path. */
  private def derivePorts(dut: RawModule): Seq[(Data, ModuleInfo.Port)] = {
    def leafPorts(node: Data, name: String): Seq[(Data, ModuleInfo.Port)] = node match {
      case record: Record =>
        record.elements.toSeq.flatMap { case (fieldName, field) => leafPorts(field, s"${name}_$fieldName") }
      case vec: Vec[_] =>
        vec.zipWithIndex.flatMap { case (element, index) => leafPorts(element, s"${name}_$index") }
      case element: Element =>
        DataMirror.directionOf(element) match {
          case ActualDirection.Input  => Seq((element, ModuleInfo.Port(name, isGettable = true, isSettable = true)))
          case ActualDirection.Output => Seq((element, ModuleInfo.Port(name, isGettable = true)))
          case _                      => Seq()
        }
    }
    DataMirror.modulePorts(dut).flatMap {
      case (name, data: Data) => leafPorts(data, name)
      case _                  => Nil
    }
  }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString.take(24)

  private def rmTree(p: Path): Unit = if (Files.exists(p)) {
    Runtime.getRuntime.exec(Array("rm", "-rf", p.toString)).waitFor()
  }

  /** Elaborate to CHIRRTL only: the module object (for peek/poke), its ports, and the .fir hash. */
  private def elaborateChirrtl[T <: RawModule](module: => T): (T, Seq[(Data, ModuleInfo.Port)], String) = {
    val elabDir = Files.createTempDirectory("verif-elab")
    try {
      var someDut: Option[T] = None
      (new circt.stage.ChiselStage).execute(
        Array("--target", "chirrtl"),
        Seq(
          chisel3.stage.ChiselGeneratorAnnotation { () =>
            val dut = module; someDut = Some(dut); dut
          },
          firrtl.options.TargetDirAnnotation(elabDir.toString)
        )
      )
      val dut = someDut.get
      val fir = Option(elabDir.toFile.listFiles((_, n) => n.endsWith(".fir"))).getOrElse(Array.empty)
        .sortBy(_.getName).map(f => Files.readAllBytes(f.toPath)).fold(Array.empty[Byte])(_ ++ _)
      require(fir.nonEmpty, "CachedSimulator: chirrtl elaboration produced no .fir")
      val hash = sha256(fir ++ toolFingerprint.getBytes(StandardCharsets.UTF_8))
      (dut, derivePorts(dut), hash)
    } finally rmTree(elabDir)
  }

  /** Full build into `wsPath` (the EphemeralSimulator flow, persisted): returns the simulation
   *  and the elaborated module of the build-side elaboration. */
  private def fullBuild[T <: RawModule](wsPath: String, module: => T): (Simulation, ElaboratedModule[T]) = {
    val workspace = new Workspace(path = wsPath)
    workspace.reset()
    val elaborated = workspace.elaborateGeneratedModule({ () => module })
    workspace.generateAdditionalSources()
    val simulation = workspace.compile(backend)(
      "default", commonSettings, backendSettings, customSimulationWorkingDirectory = None, verbose = false)
    (simulation, elaborated)
  }

  private def prune(): Unit = {
    val dirs = Option(cacheRoot.toFile.listFiles).getOrElse(Array.empty).filter(_.isDirectory)
    dirs.sortBy(-_.lastModified).drop(keepEntries).foreach(d => rmTree(d.toPath))
  }

  /** The simulation log (the DUT's $display/$error output) of the most recent simulate() on
   *  this thread, captured when the run ends - normally or by an exception such as a design
   *  assertion's $fatal. Negative tests read it to attribute a stopped run to the assertion
   *  they expect rather than to any other simulator exit. */
  private val lastLog = new ThreadLocal[String] { override def initialValue(): String = "" }
  def lastSimulationLog: String = lastLog.get

  def simulate[T <: RawModule](module: => T)(body: T => Unit): Unit = {
    def captureLog(wsPath: String): Unit = {
      val f = Paths.get(wsPath, "workdir-default", "simulation-log.txt")
      lastLog.set(try new String(Files.readAllBytes(f), StandardCharsets.UTF_8) catch { case _: Throwable => "" })
    }
    def runIn(wsPath: String, simulation: Simulation, elaborated: ElaboratedModule[T]): Unit =
      try {
        simulation.runElaboratedModule(elaboratedModule = elaborated) { m =>
          body(m.wrapped)
          m.completeSimulation()
        }
      } finally captureLog(wsPath)
    lastLog.set("")

    if (cacheOff) {
      // Build-and-discard, the EphemeralSimulator behaviour.
      val tmp = Files.createTempDirectory("verif-sim")
      try { val (sim, elab) = fullBuild(tmp.toString, module); runIn(tmp.toString, sim, elab) }
      finally rmTree(tmp)
      return
    }

    Files.createDirectories(cacheRoot)
    val (dut, ports, hash) = elaborateChirrtl(module)
    val ws = cacheRoot.resolve(hash)
    val binary = ws.resolve("workdir-default").resolve("simulation")

    // Diagnostics stay off stdout (run.sh merges streams, and `run --json` consumers parse it);
    // opt in with VERIF_SIMCACHE_LOG=1.
    if (sys.env.get("VERIF_SIMCACHE_LOG").contains("1"))
      System.err.println(s"[simcache] ${if (Files.exists(binary)) "hit" else "miss"} $hash")
    if (Files.exists(binary)) {
      // Hit: reopen the built binary; peek/poke maps onto the fresh chirrtl elaboration.
      Files.setLastModifiedTime(ws, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis))
      val moduleInfo = ModuleInfo(name = dut.name, ports = ports.map(_._2))
      val simulation = svsim.WorkspaceReopen.simulation(backend)(
        ws.toAbsolutePath.toString, "default", commonSettings, backendSettings, moduleInfo)
      runIn(ws.toAbsolutePath.toString, simulation, new ElaboratedModule(dut, ports))
    } else {
      // Miss: full build in a private directory, run from there, then publish atomically. A
      // concurrent builder of the same hash can win the rename; the loser just discards its copy.
      val tmp = Files.createTempDirectory(cacheRoot, s"build-$hash-")
      var published = false
      var keepForDebug = false
      try {
        val (sim, elab) = try fullBuild(tmp.toString, module) catch {
          case e: Throwable =>
            // The exception text is the verilator/make log, which downstream filters may eat -
            // keep the build directory and leave a durable pointer before rethrowing.
            keepForDebug = true
            System.err.println(s"[simcache] build failed; full log: $tmp/workdir-default/compilation-log.txt")
            throw e
        }
        // The build is valid whatever the body does (a design assertion may stop the run), so
        // it is published even when the run throws; the exception is rethrown afterwards.
        val outcome = scala.util.Try(runIn(tmp.toString, sim, elab))
        try {
          Files.move(tmp, ws, StandardCopyOption.ATOMIC_MOVE)
          published = true
        } catch { case _: Throwable => /* lost the publish race or FS refused; keep cache as-is */ }
        outcome.get
      } finally {
        if (!published && !keepForDebug) rmTree(tmp)
        prune()
      }
    }
  }
}
