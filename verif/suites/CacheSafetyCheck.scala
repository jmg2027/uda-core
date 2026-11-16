package chisel3.simulator

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import chisel3._
import chisel3.util.{HasBlackBoxInline, HasBlackBoxPath}

/** Filesystem-only regression for concurrent cached simulator workspace ownership. */
object CacheSafetyCheck {
  private def check(ok: Boolean, label: String): Unit = {
    require(ok, label)
    println(s"[PASS] $label")
  }

  private def complete(root: Path, hash: String, modified: Long): Path = {
    val dir = root.resolve(hash)
    Files.createDirectories(dir.resolve("workdir-default"))
    Files.createFile(dir.resolve("workdir-default/simulation"))
    Files.setLastModifiedTime(dir, FileTime.fromMillis(modified))
    dir
  }

  private def awaitFile(path: Path): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10)
    require(Files.exists(path), s"timed out waiting for $path")
  }

  def main(args: Array[String]): Unit = {
    if (args.headOption.contains("hold")) {
      val root = Path.of(args(1))
      CachedSimulator.withEntryLock(root, args(2)) {
        Files.createFile(Path.of(args(3)))
        awaitFile(Path.of(args(4)))
      }
      return
    }

    val root = Files.createTempDirectory("verif-cache-safety-")
    try {
      val activeBuild = Files.createDirectory(root.resolve("build-active-123"))
      val incomplete = Files.createDirectory(root.resolve("000000000000000000000000"))
      val old = complete(root, "111111111111111111111111", 1)
      val recent = complete(root, "222222222222222222222222", 2)
      CachedSimulator.prune(root, 1)
      check(!Files.exists(old) && Files.exists(recent), "only the oldest completed entry is evicted")
      check(Files.exists(activeBuild) && Files.exists(incomplete),
        "active build and incomplete workspace directories survive pruning")

      CachedSimulator.withEntryLock(root, recent.getFileName.toString) {
        CachedSimulator.prune(root, 0)
        check(Files.exists(recent), "a workspace leased in this JVM survives pruning")
      }
      CachedSimulator.prune(root, 0)
      check(!Files.exists(recent), "a released workspace becomes eligible for pruning")

      val hash = "333333333333333333333333"
      val ws = complete(root, hash, 3)
      val firstRun = Files.createTempDirectory(root, "run-first-")
      val secondRun = Files.createTempDirectory(root, "run-second-")
      CachedSimulator.withEntryLock(root, hash) {
        CachedSimulator.snapshotBinary(ws, firstRun)
      }
      val firstLog = firstRun.resolve("workdir-default/simulation-log.txt")
      val secondLog = secondRun.resolve("workdir-default/simulation-log.txt")
      Files.writeString(firstLog, "first", StandardCharsets.UTF_8)
      val entered = new CountDownLatch(1)
      val error = new AtomicReference[Throwable]()
      val other = new Thread(() => {
        try CachedSimulator.withEntryLock(root, hash) {
          CachedSimulator.snapshotBinary(ws, secondRun)
          Files.writeString(secondLog, "second", StandardCharsets.UTF_8)
          entered.countDown()
        } catch { case e: Throwable => error.set(e) }
      })
      other.start()
      check(entered.await(5, TimeUnit.SECONDS) && Files.readString(firstLog) == "first" &&
        Files.readString(secondLog) == "second",
        "a second run starts while the first stays active without sharing its log")
      other.join(5000)
      require(!other.isAlive && error.get() == null, "second snapshot failed")

      val ready = root.resolve("child-ready")
      val release = root.resolve("child-release")
      val child = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString,
        "-cp", System.getProperty("java.class.path"), getClass.getName.stripSuffix("$"),
        "hold", root.toString, hash, ready.toString, release.toString).inheritIO().start()
      try {
        awaitFile(ready)
        CachedSimulator.prune(root, 0)
        check(Files.exists(ws), "a workspace leased by another JVM survives pruning")
        Files.createFile(release)
        require(child.waitFor(5, TimeUnit.SECONDS) && child.exitValue() == 0,
          "child lease did not release cleanly")
      } finally if (child.isAlive) child.destroyForcibly()
      CachedSimulator.prune(root, 0)
      check(!Files.exists(ws), "cross-process lease release permits later eviction")
      check(Files.exists(firstRun.resolve("workdir-default/simulation")) &&
        Files.exists(secondRun.resolve("workdir-default/simulation")),
        "private run executables survive source cache eviction")
      println("CacheSafetyCheck: PASS")
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p))
      finally paths.close()
    }
  }
}

/** A model-only change must invalidate the cache even when the top and its FIRRTL are equal. */
object CacheBlackBoxCheck {
  private class InlineModel(value: Int) extends BlackBox with HasBlackBoxInline {
    override def desiredName: String = "CacheInlineSourceFixture"
    val io = IO(new Bundle { val out = Output(UInt(8.W)) })
    setInline("CacheInlineSourceFixture.sv",
      s"module CacheInlineSourceFixture(output [7:0] out); assign out = 8'd$value; endmodule\n")
  }
  private class InlineTop(value: Int) extends Module {
    val io = IO(new Bundle { val out = Output(UInt(8.W)) })
    io.out := Module(new InlineModel(value)).io.out
  }
  private class PathModel(path: String) extends BlackBox with HasBlackBoxPath {
    override def desiredName: String = "CachePathSourceFixture"
    val io = IO(new Bundle { val out = Output(UInt(8.W)) })
    addPath(path)
  }
  private class PathTop(path: String) extends Module {
    val io = IO(new Bundle { val out = Output(UInt(8.W)) })
    io.out := Module(new PathModel(path)).io.out
  }

  private def check(ok: Boolean, label: String): Unit = {
    require(ok, label)
    println(s"[PASS] $label")
  }

  def main(args: Array[String]): Unit = {
    val first = CachedSimulator.sourceFingerprint(new InlineTop(17))
    val repeat = CachedSimulator.sourceFingerprint(new InlineTop(17))
    val changed = CachedSimulator.sourceFingerprint(new InlineTop(29))
    check(first == repeat, "unchanged inline model has a stable cache key")
    check(first != changed, "inline model-only change invalidates the cache key")

    val path = Files.createTempFile("CachePathSourceFixture-", ".sv")
    def write(value: Int): Unit = Files.writeString(path,
      s"module CachePathSourceFixture(output [7:0] out); assign out = 8'd$value; endmodule\n",
      StandardCharsets.UTF_8)
    try {
      write(37)
      val pathFirst = CachedSimulator.sourceFingerprint(new PathTop(path.toString))
      write(43)
      val pathChanged = CachedSimulator.sourceFingerprint(new PathTop(path.toString))
      check(pathFirst != pathChanged, "same-path model content change invalidates the cache key")
    } finally Files.deleteIfExists(path)

    import CachedSimulator._
    Seq(17, 29, 17).foreach { expected =>
      simulate(new InlineTop(expected)) { dut =>
        dut.clock.step()
        check(dut.io.out.peek().litValue == expected,
          s"simulation observes inline model value $expected")
      }
    }
    println("CacheBlackBoxCheck: PASS")
  }
}
