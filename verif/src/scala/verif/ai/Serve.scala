package verif.ai

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import udacore.core.design.shared.CoreParams
import verif.config.CoreConfigs
import verif.harness.{CoreHarness, HarnessNotReady}

/** The scn daemon: compile the DUT once, stay resident, and serve `.scn` requests in seconds
 *  instead of a fresh multi-minute compile per invocation. One daemon serves one config
 *  (tag = the config name, default "default"); `scn.sh serve` manages its lifecycle and
 *  `scn.sh run <file.scn> --daemon` is the client.
 *
 *  Protocol (file-based, so the client stays plain bash):
 *    spool/req/<id>.scn   request: raw scenario text, moved in atomically by the client
 *    spool/resp/<id>.json response: the RunSpec result JSON, or {"error": ...} on a bad scn
 *    spool/ready          touched once the DUT compile is done and the loop is polling
 *    spool/stop           shut the daemon down
 *    spool/meta           "<tag> <pid> <classes-fingerprint>" at startup; the client refuses a
 *                         daemon older than the compiled classes (it would serve a stale DUT)
 */
object Serve {

  /** Newest mtime (seconds) under the compiled-classes tree - the staleness fingerprint. */
  def classesFingerprint(dir: File): Long = {
    var newest = 0L
    def walk(f: File): Unit =
      if (f.isDirectory) Option(f.listFiles).getOrElse(Array.empty).foreach(walk)
      else if (f.lastModified > newest) newest = f.lastModified
    walk(dir)
    newest / 1000
  }

  def main(args: Array[String]): Unit = {
    val (tag, cfg) = CoreConfigs.fromArgs(args)
    implicit val p: CoreParams = cfg

    val spool = Paths.get(sys.env.getOrElse("VERIF_DAEMON_SPOOL", "/tmp/verif-daemon"), tag)
    val reqDir = spool.resolve("req"); val respDir = spool.resolve("resp")
    Files.createDirectories(reqDir); Files.createDirectories(respDir)
    val stopFile = spool.resolve("stop"); Files.deleteIfExists(stopFile)
    val classesDir = new File(sys.env.getOrElse("VERIF_CLASSES", "verif/out/classes"))
    val pid = ProcessHandle.current.pid
    Files.write(spool.resolve("meta"),
      s"$tag $pid ${classesFingerprint(classesDir)}\n".getBytes(StandardCharsets.UTF_8))
    println(s"[serve] config=$tag pid=$pid spool=$spool - compiling the DUT (first request served after this)")

    def writeResp(id: String, text: String): Unit = {
      val tmp = respDir.resolve(s"$id.json.tmp")
      Files.write(tmp, text.getBytes(StandardCharsets.UTF_8))
      Files.move(tmp, respDir.resolve(s"$id.json"))
    }

    // The single-threaded serve loop: next() blocks (polling) until a request parses into a
    // scenario or stop is seen; pending carries the request context to onResult. A scn that fails
    // to parse/assemble answers with an error JSON and never reaches the DUT.
    var pending: Option[(String, Spec, Seq[Long])] = None
    def next(): Option[CoreHarness.Scenario] = {
      if (!Files.exists(spool.resolve("ready"))) Files.createFile(spool.resolve("ready"))
      while (true) {
        if (Files.exists(stopFile)) { println("[serve] stop requested"); return None }
        val reqs = Option(reqDir.toFile.listFiles((_, n) => n.endsWith(".scn"))).getOrElse(Array.empty).sortBy(_.getName)
        reqs.headOption match {
          case Some(f) =>
            val id = f.getName.stripSuffix(".scn")
            val text = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
            Files.delete(f.toPath)
            try {
              val (spec, words, scn) = RunSpec.toScenario(text)
              pending = Some((id, spec, words))
              println(s"[serve] run: $id (${spec.name})")
              return Some(scn)
            } catch {
              case e: Throwable =>
                println(s"[serve] bad scn $id: ${e.getMessage}")
                writeResp(id, Js.of("error" -> Js.S(s"${e.getClass.getSimpleName}: ${e.getMessage}")).render)
            }
          case None => Thread.sleep(50)
        }
      }
      None
    }

    try
      CoreHarness.runServer(() => next()) { (_, rr) =>
        pending.foreach { case (id, spec, words) => writeResp(id, RunSpec.Result(spec, rr, words).json.render) }
        pending = None
      }
    catch {
      case e: HarnessNotReady =>
        println(s"[serve] harness-not-ready: ${e.getMessage}")
        Files.deleteIfExists(spool.resolve("ready"))
        Files.deleteIfExists(spool.resolve("meta"))
        sys.exit(3)
    }

    Files.deleteIfExists(spool.resolve("ready"))
    Files.deleteIfExists(spool.resolve("meta"))
    Files.deleteIfExists(stopFile)
    println("[serve] shut down")
  }
}
