package verif.ai

import udacore.core.design.shared.CoreParams
import verif.config.CoreConfigs
import verif.harness.{Cycle, HarnessNotReady, MemModel, Probe}

/** Declarative signal probe: run a `.scn` program with the BoringUtils taps and emit a per-cycle
 *  table. Each row carries `instRaw` next to `expInst` (= mem[pc]) with a `desync` flag - the AI
 *  catches a PC/instruction-data desync by comparing two numbers, never by reading a waveform. */
object Trace {
  def field(c: Cycle, name: String): Js = name match {
    case "i" => Js.N(c.i)
    case "reqAddr" => Js.hex(c.reqAddr)
    case "pc" => Js.hex(c.pc)
    case "valid" => Js.B(c.valid)
    case "instRaw" => Js.hex(c.instRaw)
    case "expInst" => Js.hex(c.expInst)
    case "desync" => Js.B(c.desync)
    case "flush" => Js.B(c.flush)
    case "jump" => Js.B(c.jump)
    case "xcpt" => Js.B(c.xcpt)
    case "stall" => Js.B(c.stall)
    case "divBusy" => Js.B(c.divBusy)
    case "rd" => Js.N(c.rd)
    case "aluWB" => Js.B(c.aluWB)
    case _ => Js.Null
  }

  def main(args: Array[String]): Unit = {
    implicit val cfg: CoreParams = CoreConfigs.fromArgs(args)._2
    val files = args.filterNot(_.startsWith("--"))
    val json = args.contains("--json")
    val cyc = args.find(_.startsWith("--cycles=")).map(_.stripPrefix("--cycles=").toInt).getOrElse(40)
    val taps = args.find(_.startsWith("--taps=")).map(_.stripPrefix("--taps=").split(",").toSeq)
                   .getOrElse(Seq("i","reqAddr","pc","valid","instRaw","expInst","desync","stall","flush","jump","xcpt"))
    val text = files.headOption.map(f => scala.io.Source.fromFile(f).mkString).getOrElse(scala.io.Source.stdin.mkString)
    val spec = Spec.parse(text)
    val words = RunSpec.assemble(spec.asm)
    try {
      val rows = Probe.trace(MemModel.fromWords(words, spec.iLat, spec.dLat), cyc)
      if (json) {
        val out = Js.of(
          "name" -> Js.S(spec.name),
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
