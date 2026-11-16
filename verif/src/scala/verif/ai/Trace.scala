package verif.ai

import udacore.core.design.shared.CoreParams
import verif.config.CoreConfigs
import verif.harness.{Cycle, HarnessNotReady, MemModel, Probe}

/** Per-cycle observations from the simulation-only CoreTraceTop wrapper. */
object Trace {
  def fetchJson(f: verif.harness.FetchSample): Js = Js.of(
    "lane" -> Js.N(f.lane), "pc" -> Js.hex(f.pc), "instRaw" -> Js.hex(f.instRaw),
    "expInst" -> (if (f.compareValid) Js.hex(f.expInst) else Js.Null),
    "fault" -> Js.N(f.fault), "compareValid" -> Js.B(f.compareValid), "desync" -> Js.B(f.desync))

  def field(c: Cycle, name: String): Js = name match {
    case "i" => Js.N(c.i)
    case "reqAddr" => Js.hex(c.reqAddr)
    case "pc" => Js.hex(c.pc)
    case "valid" => Js.B(c.valid)
    case "instRaw" => Js.hex(c.instRaw)
    case "expInst" => if (c.fetch.headOption.exists(_.compareValid)) Js.hex(c.expInst) else Js.Null
    case "desync" => Js.B(c.desync)
    case "flush" => Js.B(c.flush)
    case "jump" => Js.B(c.jump)
    case "xcpt" => Js.B(c.xcpt)
    case "stall" => Js.B(c.stall)
    case "divBusy" => Js.B(c.divBusy)
    case "rd" => Js.N(c.rd)
    case "aluWB" => Js.B(c.aluWB)
    case "reqValid" => Js.B(c.reqValid)
    case "fetchReady" => Js.B(c.fetchReady)
    case "fetch" => Js.Arr(c.fetch.map(fetchJson))
    case "retire" => c.retire.map(r => Js.of(
      "order" -> Js.S(r.order.toString), "pc" -> Js.hex(r.pc), "insn" -> Js.hex(r.insn),
      "rd" -> Js.N(r.rd), "wdata" -> Js.hex(r.wdata), "wen" -> Js.B(r.wen),
      "trap" -> Js.B(r.trap), "source" -> Js.N(r.source), "cause" -> Js.N(r.cause),
      "tval" -> Js.hex(r.tval))).getOrElse(Js.Null)
    case "recovery" => c.recovery.map(r => Js.of(
      "kind" -> Js.N(r.kind), "cause" -> Js.N(r.cause), "target" -> Js.hex(r.target),
      "robIdx" -> Js.N(r.robIdx), "robWrap" -> Js.B(r.robWrap))).getOrElse(Js.Null)
    case _ => Js.Null
  }

  def main(args: Array[String]): Unit = {
    implicit val cfg: CoreParams = CoreConfigs.fromArgs(args)._2
    val files = args.filterNot(_.startsWith("--"))
    val json = args.contains("--json")
    val cyc = args.find(_.startsWith("--cycles=")).map(_.stripPrefix("--cycles=").toInt).getOrElse(40)
    val taps = args.find(_.startsWith("--taps=")).map(_.stripPrefix("--taps=").split(",").toSeq)
                   .getOrElse(Seq("i","reqAddr","reqValid","fetch","fetchReady","desync","retire","recovery","divBusy","aluWB"))
    require(taps.forall(Probe.taps.contains), s"unknown taps: ${taps.filterNot(Probe.taps.contains).mkString(",")}")
    val text = files.headOption.map(f => scala.io.Source.fromFile(f).mkString).getOrElse(scala.io.Source.stdin.mkString)
    val spec = Spec.parse(text)
    val words = RunSpec.assemble(spec.asm)
    try {
      val irq = spec.irq.map { case (kind, at, dur) => verif.harness.CoreHarness.IrqPulse(kind, at, dur) }
      val rows = Probe.trace(MemModel.fromWords(words, spec.iLat, spec.dLat), cyc, irq)
      if (json) {
        val out = Js.of(
          "name" -> Js.S(spec.name),
          "sample_phase" -> Js.S("before each post-reset rising edge; cycle 0 is the first driven cycle"),
          "comparison" -> Js.S("mapped Bare/M-mode bytes only; faults and translated fetches are not compared; self-modifying code can mismatch legally before FENCE.I"),
          "taps" -> Js.Arr(taps.map(Js.S)),
          "desync_cycles" -> Js.Arr(rows.filter(_.desync).map(c => Js.N(c.i))),
          "rows" -> Js.Arr(rows.map(c => Js.Obj(taps.map(t => t -> field(c, t)))))
        )
        println(out.render)
      } else {
        println(s"# trace ${spec.name} taps=${taps.mkString(",")}")
        rows.foreach { c =>
          val mark = if (c.desync) "  <== DESYNC(instRaw!=mem[pc])" else ""
          println(taps.map(t => s"$t=${field(c, t).render.replaceAll("\"", "")}").mkString(" ") + mark)
        }
      }
    } catch {
      case e: HarnessNotReady =>
        if (json) println(RunSpec.notReadyJson(e).render) else println(s"harness-not-ready: ${e.getMessage}")
        sys.exit(3)
    }
  }
}
