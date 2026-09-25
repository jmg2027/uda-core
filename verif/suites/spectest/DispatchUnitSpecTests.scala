package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.modules.DispatchUnit
import udacore.backend.design.shared.{BackendParams, FuType, RecoveryKind}

/** L1 SpecTests for the ADR-019 DispatchUnit (ADR-018; spec 242feaf + ADR-019A/C).
  *
  * The test plays the ReservationStation (IssuedUopIn), every execution wrapper (per-class
  * request ready), and the RecoveryController. FuAvailability must equal the downstream
  * capacity per class and must not depend on the presented request (ADR-019C E-2).
  */
object DispatchUnitSpecTests {

  val p = BackendParams()
  val D = p.robDepth
  def tagOf(s: Int): (Boolean, Int) = ((s / D) % 2 == 1, s % D)

  val classes = Seq("alu" -> FuType.Alu, "mul" -> FuType.Mul, "div" -> FuType.Div, "branch" -> FuType.Branch,
    "mem" -> FuType.Mem, "csr" -> FuType.Csr)

  case class Obs(inReady: Boolean, outs: Map[String, Boolean], avail: Map[String, Boolean], payload: Map[String, (Int, Long)])

  class Drv(val dut: DispatchUnit) {
    val io = dut.io
    var ready: Map[String, Boolean] = classes.map(_._1 -> true).toMap
    var insnOut: (Long, Int) = (0L, 0)
    private def outPort(n: String) = n match {
      case "alu" => io.aluReqOut; case "mul" => io.multiplierReqOut; case "div" => io.dividerReqOut
      case "branch" => io.branchUnitReqOut; case "mem" => io.addressGenerationReqOut; case "csr" => io.csrReqOut
    }
    def cycle(uop: Option[(Int, UInt, Long)] = None, event: Option[(UInt, Int)] = None, step: Boolean = true): Obs = {
      val i = io.issuedUopIn
      i.valid.poke(uop.nonEmpty.B)
      uop.foreach { case (s, fu, src1) =>
        i.bits.robTag.wrap.poke(tagOf(s)._1.B); i.bits.robTag.idx.poke(tagOf(s)._2.U)
        i.bits.fuType.poke(fu); i.bits.op.poke(0.U); i.bits.src1.poke(src1.U); i.bits.src2.poke(7.U)
        i.bits.imm.poke(0x300.U); i.bits.prd.poke(33.U)
        i.bits.insn.poke((0x30012073L | (s.toLong << 7 & 0xf80L)).U); i.bits.sysOp.poke(7.U)
      }
      for ((n, _) <- classes) outPort(n).ready.poke(ready(n).B)
      val e = io.recoveryEventIn
      e.valid.poke(event.nonEmpty.B)
      event.foreach { case (k, s) => e.kind.poke(k); e.robTag.wrap.poke(tagOf(s)._1.B); e.robTag.idx.poke(tagOf(s)._2.U) }
      val av = io.fuAvailabilityOut
      val avail = Map("alu" -> av.alu, "mul" -> av.mul, "div" -> av.div, "branch" -> av.branch, "mem" -> av.mem, "csr" -> av.csr)
        .map { case (k, v) => k -> v.peek().litToBoolean }
      val outs = classes.map { case (n, _) => n -> outPort(n).valid.peek().litToBoolean }.toMap
      val payload = classes.map { case (n, _) =>
        n -> (outPort(n).bits.robTag.idx.peek().litValue.toInt, outPort(n).bits.src1.peek().litValue.toLong) }.toMap
      insnOut = io.csrReqOut.bits.insn.peek().litValue.toLong -> io.csrReqOut.bits.sysOp.peek().litValue.toInt
      val o = Obs(i.ready.peek().litToBoolean, outs, avail, payload)
      if (step) dut.clock.step()
      o
    }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new DispatchUnit(p)) { dut => val d = new Drv(dut); d.cycle(); body(d) }

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  // ---- funcFuRoute ----------------------------------------------------------------------------

  val route = new SpecTest("dispatch.route", Seq("funcFuRoute")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val routed = classes.zipWithIndex.map { case ((n, fu), k) =>
        val o = d.cycle(Some((k + 1, fu, 0x100L + k)))
        (n, o)
      }
      val single = routed.map { case (n, o) =>
        chk(o.outs(n) && o.outs.count(_._2) == 1 && o.inReady,
          s"a $n uop is offered on its own request edge only and accepted", s"$o") }
      val pay = routed.map { case (n, o) =>
        chk(o.payload(n)._2 == 0x100L + classes.indexWhere(_._1 == n), s"the $n edge carries the IssuedUop payload", s"${o.payload(n)}") }
      val csr = routed.find(_._1 == "csr").get._2
      val csrInsn = d.insnOut
      // Backpressure: the presented class not ready -> not accepted, others unaffected.
      d.ready = d.ready.updated("div", false)
      val bp = d.cycle(Some((9, FuType.Div, 1L)))
      val other = d.cycle(Some((10, FuType.Alu, 2L)))
      // A token killed by a same-cycle RecoveryEvent is not routed.
      d.ready = d.ready.updated("div", true)
      val killed = d.cycle(Some((12, FuType.Alu, 3L)), event = Some((RecoveryKind.BranchMispredict, 11)))
      val older  = d.cycle(Some((11, FuType.Branch, 4L)), event = Some((RecoveryKind.BranchMispredict, 11)))
      val arch   = d.cycle(Some((13, FuType.Mul, 5L)), event = Some((RecoveryKind.ArchRedirect, 13)))
      single ++ pay ++ Seq(
        chk(csr.payload("csr")._1 == 6 && csr.payload("csr")._2 == 0x100L + 5, "a CSR uop reaches the CSR edge as an IssuedUop with its robTag and operand (ADR-019D E-1)", s"$csr"),
        chk(csrInsn == ((0x30012073L | (6L << 7)) -> 7), "the CSR edge carries insn and sysOp (ADR-019D E-2)", s"$csrInsn"),
        chk(!bp.inReady && bp.outs("div") && bp.outs.count(_._2) == 1, "an unready class does not accept its uop", s"$bp"),
        chk(other.inReady && other.outs("alu"), "other classes are unaffected", s"$other"),
        chk(!killed.outs.exists(_._2), "a token killed by the same-cycle RecoveryEvent is not routed", s"$killed"),
        chk(older.outs("branch"), "the recovering branch itself is still routed", s"$older"),
        chk(!arch.outs.exists(_._2), "an ArchRedirect kills the presented token", s"$arch")
      )
    }
  }

  // ---- funcFuAvailability ------------------------------------------------------------------------

  val availability = new SpecTest("dispatch.availability", Seq("funcFuAvailability")) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val rnd = new scala.util.Random(79)
      var mismatch, dependence, lostOrDup = Seq.empty[String]
      for (step <- 0 until 200) {
        d.ready = classes.map(_._1 -> rnd.nextBoolean()).toMap
        // Same downstream readiness, different presented requests: availability must not move.
        val base = d.cycle(None, step = false).avail
        for ((n, fu) <- classes) {
          val o = d.cycle(Some((step % 16, fu, rnd.nextInt(1000).toLong)), step = false)
          if (o.avail != base) dependence :+= s"step $step $n: ${o.avail} vs $base"
          if (o.inReady != d.ready(n)) lostOrDup :+= s"step $step $n: in.ready ${o.inReady} vs ${d.ready(n)}"
          if (o.outs.count(_._2) != 1) lostOrDup :+= s"step $step $n: ${o.outs}"
        }
        if (base != d.ready) mismatch :+= s"step $step: avail $base vs downstream ${d.ready}"
        d.cycle(None)
      }
      Seq(
        chk(mismatch.isEmpty, "each availability bit equals the downstream capacity of its class; unready classes read false", mismatch.take(3).mkString("; ")),
        chk(dependence.isEmpty, "availability does not depend on the presented request's valid or payload", dependence.take(3).mkString("; ")),
        chk(lostOrDup.isEmpty, "every presented uop is routed to exactly one edge; in.ready equals its class availability", lostOrDup.take(3).mkString("; ")),
        chk(d.io.fuAvailabilityOut.bitAlu.isEmpty, "no bitAlu bit when the BitAluUnit is not elaborated (v0)", "")
      )
    }
  }

  val all: Seq[SpecTest] = Seq(route, availability)
}
