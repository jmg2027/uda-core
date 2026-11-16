package chisel3.simulator

import chisel3.{Data, Element, RawModule, Record, Vec}
import chisel3.reflect.DataMirror
import chisel3.ActualDirection
import java.io.File
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.charset.StandardCharsets
import java.nio.file.{
  Files,
  Path,
  Paths,
  StandardCopyOption,
  StandardOpenOption
}
import java.security.MessageDigest
import firrtl.annotations.Annotation
import firrtl.transforms.{BlackBoxInlineAnno, BlackBoxPathAnno}
import svsim._

/** A drop-in for EphemeralSimulator.simulate with a persistent, content-keyed
  * workspace cache.
  *
  * The wall-clock cost of a simulate() is elaboration (Chisel, in-JVM) +
  * firtool (fir -> SV) + verilator (SV -> C++) + make (g++/link). ccache
  * already collapses the g++ step for an unchanged DUT; this cache removes
  * firtool + verilator + make as well:
  *
  *   - every call elaborates the design once to CHIRRTL (that is unavoidable -
  *     the peek/poke API needs the in-JVM module object) and hashes the emitted
  *     .fir, blackbox source contents, and tool versions;
  *   - hash hit: the previously built workspace's simulation binary is reopened
  *     directly (svsim.WorkspaceReopen) - no firtool, no verilator, no make;
  *   - hash miss: the standard full build runs (a second elaboration inside
  *     ChiselStage's systemverilog pipeline - misses happen once per RTL
  *     change), then the workspace is published into the cache under its hash,
  *     atomically. Short per-entry leases protect lookup and publication; each
  *     execution snapshots the binary into its own run directory.
  *
  * Cache root: VERIF_SIMCACHE_DIR (default /tmp/verif-simcache), pruned to the
  * VERIF_SIMCACHE_KEEP (default 8) most recently used entries.
  * VERIF_SIMCACHE=off falls back to build-and-discard (the EphemeralSimulator
  * behaviour). Lives in package chisel3.simulator to reach the
  * private[simulator] ElaboratedModule constructor.
  */
object CachedSimulator extends PeekPokeAPI {

  private def env(name: String, default: String) =
    sys.env.getOrElse(name, default)
  private def cacheRoot                          =
    Paths.get(env("VERIF_SIMCACHE_DIR", "/tmp/verif-simcache"))
  private def keepEntries                        = env("VERIF_SIMCACHE_KEEP", "8").toInt
  private def cacheOff                           = sys.env.get("VERIF_SIMCACHE").contains("off")

  // Mirrors EphemeralSimulator's backend configuration (env.sh carries the make -j / -O0 / ccache
  // knobs through MAKEFLAGS and OBJCACHE, so nothing needs to be set here).
  private def backend         = verilator.Backend.initializeFromProcessEnvironment()
  private def commonSettings  = CommonCompilationSettings()
  private def backendSettings = verilator.Backend.CompilationSettings()

  private lazy val toolFingerprint: String = {
    val v =
      try {
        sys.process.Process(Seq("verilator", "--version")).!!.trim
      } catch { case _: Throwable => "verilator-unknown" }
    s"$v chisel-${chisel3.BuildInfo.version} schema-2"
  }

  /** Port derivation for the CHIRRTL-only (cache hit) path - the same inference
    * ChiselWorkspace.elaborateGeneratedModule performs on the full-build path.
    */
  private def derivePorts(dut: RawModule): Seq[(Data, ModuleInfo.Port)] = {
    def leafPorts(node: Data, name: String): Seq[(Data, ModuleInfo.Port)] =
      node match {
        case record: Record   =>
          record.elements.toSeq.flatMap { case (fieldName, field) =>
            leafPorts(field, s"${name}_$fieldName")
          }
        case vec: Vec[_]      =>
          vec.zipWithIndex.flatMap { case (element, index) =>
            leafPorts(element, s"${name}_$index")
          }
        case element: Element =>
          DataMirror.directionOf(element) match {
            case ActualDirection.Input  =>
              Seq(
                (
                  element,
                  ModuleInfo.Port(name, isGettable = true, isSettable = true)
                )
              )
            case ActualDirection.Output =>
              Seq((element, ModuleInfo.Port(name, isGettable = true)))
            case _                      => Seq()
          }
      }
    DataMirror.modulePorts(dut).flatMap {
      case (name, data: Data) => leafPorts(data, name)
      case _                  => Nil
    }
  }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map("%02x".format(_))
      .mkString
      .take(24)

  // HasBlackBoxResource resolves its resource into BlackBoxInlineAnno in Chisel 6.2.
  // Path annotations carry only a filename, so the source bytes must be read explicitly.
  private def blackBoxSources(annotations: Seq[Annotation]): Array[Byte] = {
    val records = annotations.collect {
      case a: BlackBoxInlineAnno =>
        Seq("inline", a.target.serialize, a.name, a.text)
          .map(_.getBytes(StandardCharsets.UTF_8))
      case a: BlackBoxPathAnno   =>
        Seq("path", a.target.serialize, a.path)
          .map(_.getBytes(StandardCharsets.UTF_8)) :+
          Files.readAllBytes(Paths.get(a.path))
    }
    val bytes   = new java.io.ByteArrayOutputStream()
    val out     = new java.io.DataOutputStream(bytes)
    records.foreach { record =>
      out.writeInt(record.size)
      record.foreach { value => out.writeInt(value.length); out.write(value) }
    }
    out.flush()
    bytes.toByteArray
  }

  private def rmTree(p: Path): Unit = if (Files.exists(p)) {
    Runtime.getRuntime.exec(Array("rm", "-rf", p.toString)).waitFor()
  }

  // Lock files stay outside workspaces and are never removed: replacing a lock file could
  // create two independently locked inodes for one hash. Local monitors also serialize callers
  // in one JVM, where FileChannel.lock would otherwise throw OverlappingFileLockException.
  private val localLocks                                    = scala.collection.mutable.Map[String, Object]()
  private def entryLockPath(root: Path, hash: String): Path = {
    val dir = root.resolve(".locks")
    Files.createDirectories(dir)
    dir.resolve(s"$hash.lock")
  }
  private[simulator] def withEntryLock[A](root: Path, hash: String)(
      body: => A
  ): A = {
    val path    = entryLockPath(root, hash)
    val monitor = localLocks.synchronized {
      localLocks.getOrElseUpdate(
        path.toAbsolutePath.normalize.toString,
        new Object
      )
    }
    monitor.synchronized {
      val channel = FileChannel.open(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE
      )
      try {
        val lock = channel.lock()
        try body
        finally lock.release()
      } finally channel.close()
    }
  }

  /** A private executable link survives cache eviction; logs and invocation
    * scripts stay local.
    */
  private[simulator] def snapshotBinary(workspace: Path, run: Path): Unit = {
    val source = workspace.resolve("workdir-default/simulation")
    val dir    = run.resolve("workdir-default")
    Files.createDirectories(dir)
    val target = dir.resolve("simulation")
    try Files.createLink(target, source)
    catch {
      case _: UnsupportedOperationException =>
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
      case _: java.io.IOException           =>
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
    }
  }

  /** Elaborate to CHIRRTL only: the module object (for peek/poke), its ports,
    * and the .fir hash.
    */
  private def elaborateChirrtl[T <: RawModule](
      module: => T
  ): (T, Seq[(Data, ModuleInfo.Port)], String) = {
    val elabDir = Files.createTempDirectory("verif-elab")
    try {
      var someDut: Option[T] = None
      val annotations        = (new circt.stage.ChiselStage).execute(
        Array("--target", "chirrtl"),
        Seq(
          chisel3.stage.ChiselGeneratorAnnotation { () =>
            val dut = module; someDut = Some(dut); dut
          },
          firrtl.options.TargetDirAnnotation(elabDir.toString)
        )
      )
      val dut                = someDut.get
      val fir                = Option(elabDir.toFile.listFiles((_, n) => n.endsWith(".fir")))
        .getOrElse(Array.empty)
        .sortBy(_.getName)
        .map(f => Files.readAllBytes(f.toPath))
        .fold(Array.empty[Byte])(_ ++ _)
      require(
        fir.nonEmpty,
        "CachedSimulator: chirrtl elaboration produced no .fir"
      )
      val hash               = sha256(
        fir ++ blackBoxSources(annotations) ++ toolFingerprint.getBytes(
          StandardCharsets.UTF_8
        )
      )
      (dut, derivePorts(dut), hash)
    } finally rmTree(elabDir)
  }

  private[simulator] def sourceFingerprint[T <: RawModule](
      module: => T
  ): String =
    elaborateChirrtl(module)._3

  /** Full build into `wsPath` (the EphemeralSimulator flow, persisted): returns
    * the simulation and the elaborated module of the build-side elaboration.
    */
  private def fullBuild[T <: RawModule](
      wsPath: String,
      module: => T
  ): (Simulation, ElaboratedModule[T]) = {
    val workspace  = new Workspace(path = wsPath)
    workspace.reset()
    val elaborated = workspace.elaborateGeneratedModule({ () => module })
    workspace.generateAdditionalSources()
    val simulation = workspace.compile(backend)(
      "default",
      commonSettings,
      backendSettings,
      customSimulationWorkingDirectory = None,
      verbose = false
    )
    (simulation, elaborated)
  }

  private[simulator] def prune(root: Path, keep: Int): Unit = {
    // Only published, complete entries count toward the LRU. Private build-* directories
    // include active compilers and preserved failure logs and must never be pruned here.
    val dirs = Option(root.toFile.listFiles)
      .getOrElse(Array.empty)
      .filter(d =>
        d.isDirectory && d.getName.matches("[0-9a-f]{24}") &&
          Files.isRegularFile(d.toPath.resolve("workdir-default/simulation"))
      )
    dirs.sortBy(-_.lastModified).drop(math.max(0, keep)).foreach { d =>
      val channel = FileChannel.open(
        entryLockPath(root, d.getName),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE
      )
      try {
        val lock =
          try Option(channel.tryLock())
          catch {
            case _: OverlappingFileLockException => None
          }
        lock.foreach { held =>
          try rmTree(d.toPath)
          finally held.release()
        }
      } finally channel.close()
    }
  }

  /** The simulation log (the DUT's $display/$error output) of the most recent
    * simulate() on this thread, captured when the run ends - normally or by an
    * exception such as a design assertion's $fatal. Negative tests read it to
    * attribute a stopped run to the assertion they expect rather than to any
    * other simulator exit.
    */
  private val lastLog           = new ThreadLocal[String] {
    override def initialValue(): String = ""
  }
  def lastSimulationLog: String = lastLog.get

  def simulate[T <: RawModule](module: => T)(body: T => Unit): Unit = {
    def captureLog(wsPath: String): Unit = {
      val f = Paths.get(wsPath, "workdir-default", "simulation-log.txt")
      lastLog.set(
        try new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
        catch { case _: Throwable => "" }
      )
    }
    def runIn(
        wsPath: String,
        simulation: Simulation,
        elaborated: ElaboratedModule[T]
    ): Unit =
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
      try {
        val (sim, elab) = fullBuild(tmp.toString, module);
        runIn(tmp.toString, sim, elab)
      } finally rmTree(tmp)
      return
    }

    Files.createDirectories(cacheRoot)
    val (dut, ports, hash) = elaborateChirrtl(module)
    val ws                 = cacheRoot.resolve(hash)
    val binary             = ws.resolve("workdir-default").resolve("simulation")

    // Diagnostics stay off stdout (run.sh merges streams, and `run --json` consumers parse it);
    // opt in with VERIF_SIMCACHE_LOG=1.
    // Snapshot under the lease, then release it before running user code. A resident serve
    // process can share the cached binary with direct runs without sharing logs or blocking them.
    val run = Files.createTempDirectory(cacheRoot, s"run-$hash-")
    try {
      val elaborated = withEntryLock(cacheRoot, hash) {
        if (sys.env.get("VERIF_SIMCACHE_LOG").contains("1"))
          System.err.println(
            s"[simcache] ${if (Files.exists(binary)) "hit" else "miss"} $hash"
          )
        if (Files.exists(binary)) {
          Files.setLastModifiedTime(
            ws,
            java.nio.file.attribute.FileTime
              .fromMillis(System.currentTimeMillis)
          )
          snapshotBinary(ws, run)
          new ElaboratedModule(dut, ports)
        } else {
          val tmp          = Files.createTempDirectory(cacheRoot, s"build-$hash-")
          var published    = false
          var keepForDebug = false
          try {
            val (_, elab) =
              try fullBuild(tmp.toString, module)
              catch {
                case e: Throwable =>
                  keepForDebug = true
                  System.err.println(
                    s"[simcache] build failed; full log: $tmp/workdir-default/compilation-log.txt"
                  )
                  throw e
              }
            try {
              Files.move(tmp, ws, StandardCopyOption.ATOMIC_MOVE)
              published = true
            } catch {
              case _: Throwable => /* FS refused publication; use the private build */
            }
            snapshotBinary(if (published) ws else tmp, run)
            elab
          } finally {
            if (!published && !keepForDebug) rmTree(tmp)
          }
        }
      }
      val moduleInfo = ModuleInfo(
        name = elaborated.wrapped.name,
        ports = elaborated.ports.map(_._2)
      )
      val simulation = svsim.WorkspaceReopen.simulation(backend)(
        run.toAbsolutePath.toString,
        "default",
        commonSettings,
        backendSettings,
        moduleInfo
      )
      runIn(run.toAbsolutePath.toString, simulation, elaborated)
    } finally {
      rmTree(run)
      prune(cacheRoot, keepEntries)
    }
  }
}
