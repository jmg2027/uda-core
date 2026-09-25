package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import udacore.backend.design.modules.PhysicalRegisterFile
import udacore.backend.design.shared.{BackendParams, BackendTuningParams}

/** L1 SpecTests for the ADR-019 PhysicalRegisterFile (ADR-018; spec 242feaf + ADR-019B E-3).
  *
  * The test plays PublishMux (the write port), the ReservationStation (operand reads), and
  * the CommitUnit (the usingRvvi-only commit read). Both read paths answer in the request
  * cycle; a same-cycle write of the requested prd is bypassed; p0 reads zero.
  */
object PhysicalRegisterFileSpecTests {

  val pv = BackendParams(usingRvvi = true)
  val pn = BackendParams(usingRvvi = false)

  class Drv(val dut: PhysicalRegisterFile, rvvi: Boolean) {
    val io = dut.io
    def idle(): Unit = {
      io.physicalRegWriteIn.valid.poke(false.B)
      io.registerFileReadReqIn.valid.poke(false.B)
      io.registerFileReadRespOut.ready.poke(true.B)
      if (rvvi) { io.commitPrfReadReqIn.get.valid.poke(false.B); io.commitPrfReadRespOut.get.ready.poke(true.B) }
    }
    def pokeWrite(prd: Int, data: Long): Unit = {
      io.physicalRegWriteIn.valid.poke(true.B)
      io.physicalRegWriteIn.bits.prd.poke(prd.U)
      io.physicalRegWriteIn.bits.data.poke(data.U)
    }
    def write(prd: Int, data: Long): Boolean = {
      pokeWrite(prd, data); val r = io.physicalRegWriteIn.ready.peek().litToBoolean
      dut.clock.step(); idle(); r
    }
    /** RS read in the current cycle (optionally with a same-cycle write); returns
      * (req.ready, resp.valid, src1, src2). */
    def read(prs1: Int, prs2: Int, w: Option[(Int, Long)] = None, respReady: Boolean = true): (Boolean, Boolean, Long, Long) = {
      w.foreach { case (p, d) => pokeWrite(p, d) }
      io.registerFileReadReqIn.valid.poke(true.B)
      io.registerFileReadReqIn.bits.prs1.poke(prs1.U)
      io.registerFileReadReqIn.bits.prs2.poke(prs2.U)
      io.registerFileReadRespOut.ready.poke(respReady.B)
      val r = (io.registerFileReadReqIn.ready.peek().litToBoolean, io.registerFileReadRespOut.valid.peek().litToBoolean,
        io.registerFileReadRespOut.bits.src1.peek().litValue.toLong, io.registerFileReadRespOut.bits.src2.peek().litValue.toLong)
      dut.clock.step(); idle(); r
    }
    /** Commit read in the current cycle: (req.ready, resp.valid, data). */
    def commitRead(prd: Int, w: Option[(Int, Long)] = None): (Boolean, Boolean, Long) = {
      w.foreach { case (p, d) => pokeWrite(p, d) }
      val q = io.commitPrfReadReqIn.get; val a = io.commitPrfReadRespOut.get
      q.valid.poke(true.B); q.bits.prd.poke(prd.U)
      val r = (q.ready.peek().litToBoolean, a.valid.peek().litToBoolean, a.bits.data.peek().litValue.toLong)
      dut.clock.step(); idle(); r
    }
  }

  def withDrv(t: SpecTest, p: BackendParams = pv)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new PhysicalRegisterFile(p)) { dut => val d = new Drv(dut, p.usingRvvi); d.idle(); dut.clock.step(); body(d) }

  def chk(ok: Boolean, label: String, detail: => String): TCheck = TCheck(ok, label, if (ok) "" else detail)

  def mustAssert(t: SpecTest, label: String, expect: String)(body: Drv => Unit): TCheck = {
    var reachedEnd = false
    try {
      withDrv(t) { d => body(d); d.dut.clock.step(3); reachedEnd = true; Nil }
      TCheck(false, label, "simulation ended normally: the design assertion did not fire")
    } catch {
      case e: NotImplementedError => throw e
      case e: Throwable =>
        val fired = chisel3.simulator.CachedSimulator.lastSimulationLog.linesIterator.filter(_.contains("Assertion failed")).toSeq
        val ok = !reachedEnd && fired.exists(_.contains(expect))
        TCheck(ok, label, if (ok) "" else s"expected '$expect'; got: ${fired.headOption.getOrElse(e.getClass.getSimpleName)}")
    }
  }

  // ---- funcReadAtSelect: RS operand reads --------------------------------------------------

  val rsRead = new SpecTest("prf.rsRead", Seq("funcReadAtSelect")) {
    def run(): Seq[TCheck] = {
      val main = withDrv(this) { d =>
        val wr = Seq((1, 0x11L), (7, 0x77L), (47, 0xfeedbeefL), (32, 0x3232L)).map { case (p, v) => d.write(p, v) }
        val r1 = d.read(7, 47)
        val r0 = d.read(0, 32)
        val by = d.read(5, 7, w = Some((5, 0x555L)))   // prs1 written this cycle
        val by2 = d.read(40, 40, w = Some((40, 0xabcL))) // both operands bypassed
        val after = d.read(5, 40)
        val bp = d.read(1, 7, respReady = false)
        Seq(
          chk(wr.forall(identity), "the write port is always ready", s"$wr"),
          chk(r1._1 && r1._2 && r1._3 == 0x77 && r1._4 == 0xfeedbeefL, "both operands are read in the request cycle", s"$r1"),
          chk(r0._3 == 0 && r0._4 == 0x3232, "p0 reads zero", s"$r0"),
          chk(by._3 == 0x555 && by._4 == 0x77, "a same-cycle write of prs1 is bypassed", s"$by"),
          chk(by2._3 == 0xabc && by2._4 == 0xabc, "a same-cycle write of both operands is bypassed", s"$by2"),
          chk(after._3 == 0x555 && after._4 == 0xabc, "bypassed writes are also stored", s"$after"),
          chk(!bp._1 && bp._2, "the request is not accepted while the RS does not take the answer", s"$bp")
        )
      }
      main :+ mustAssert(this, "a write to p0 stops the run", "ReadAtSelect: p0 is never written") { d => d.write(0, 5) }
    }
  }

  // ---- funcReadAtSelect: the ADR-019B E-3 commit read port -----------------------------------

  val commitRead = new SpecTest("prf.commitRead", Seq("funcReadAtSelect")) {
    def run(): Seq[TCheck] = {
      val live = withDrv(this) { d =>
        d.write(33, 0x1234)
        val c1 = d.commitRead(33)
        val c2 = d.commitRead(34, w = Some((34, 0x4321L)))
        val c3 = d.commitRead(0)
        // The commit read and an RS read in the same cycle do not disturb each other.
        d.io.commitPrfReadReqIn.get.valid.poke(true.B); d.io.commitPrfReadReqIn.get.bits.prd.poke(33.U)
        val both = d.read(34, 33)
        Seq(
          chk(c1._1 && c1._2 && c1._3 == 0x1234, "the commit read is always ready and answered in the same cycle", s"$c1"),
          chk(c2._1 && c2._2 && c2._3 == 0x4321, "a same-cycle write of the read prd returns the new value, still without backpressure", s"$c2"),
          chk(c3._3 == 0, "p0 reads zero on the commit port", s"$c3"),
          chk(both._3 == 0x4321 && both._4 == 0x1234, "the commit read port is separate from the operand ports", s"$both")
        )
      }
      def fir(p: BackendParams) = circt.stage.ChiselStage.emitCHIRRTL(new PhysicalRegisterFile(p))
      val on = fir(pv); val off = fir(pn)
      val names = Seq("commitPrfReadReqIn", "commitPrfReadRespOut")
      live ++ Seq(
        chk(names.forall(on.contains), "control: usingRvvi = true elaborates the commit read port", ""),
        chk(!names.exists(off.contains), "usingRvvi = false removes the commit read port and its logic", names.filter(off.contains).mkString(","))
      ) ++ withDrv(this, pn) { d =>
        d.write(3, 9); val r = d.read(3, 0)
        Seq(chk(r._3 == 9, "usingRvvi = false keeps the operand read path", s"$r"))
      }
    }
  }

  // ---- propPrfPortsFixed ------------------------------------------------------------------------

  val portsFixed = new SpecTest("prf.portsFixed", Seq("propPrfPortsFixed")) {
    def run(): Seq[TCheck] = {
      /** The field names of the io bundle type (every port of the vertex), in order. */
      def ports(p: BackendParams): Seq[String] = {
        val f  = circt.stage.ChiselStage.emitCHIRRTL(new PhysicalRegisterFile(p))
        val io = f.linesIterator.map(_.trim).find(_.startsWith("output io :")).getOrElse("")
        "(flip )?([A-Za-z0-9_]+) :".r.findAllMatchIn(io.stripPrefix("output io :")).map(_.group(2)).toSeq
      }
      /** The io type with bit widths normalized: Vec sizes (port counts) remain, while the
        * prd width - legitimately log2 of the depth - is erased. */
      def shape(p: BackendParams): String = {
        val f = circt.stage.ChiselStage.emitCHIRRTL(new PhysicalRegisterFile(p))
        f.linesIterator.map(_.trim).find(_.startsWith("output io :")).getOrElse("").replaceAll("<[0-9]+>", "<w>")
      }
      val shapeBase = shape(pv)
      val base  = ports(pv)
      val prf64 = ports(pv.copy(tuning = BackendTuningParams(integerPrfEntries = 64)))
      val rob32 = ports(pv.copy(tuning = BackendTuningParams(robDepth = 32, integerPrfEntries = 64)))
      Seq(
        chk(base.nonEmpty && base == prf64 && shapeBase == shape(pv.copy(tuning = BackendTuningParams(integerPrfEntries = 128))),
          "port set and port counts are independent of PRF depth", s"$base vs $prf64"),
        chk(base == rob32 && shapeBase == shape(pv.copy(tuning = BackendTuningParams(robDepth = 32, integerPrfEntries = 64))),
          "port set and port counts are independent of ROB depth", s"$base vs $rob32"),
        chk(base.count(_.endsWith("ReadReqIn")) == 2 && base.count(_ == "physicalRegWriteIn") == 1, "read request ports: one operand port (IssueWidth 1) + one commit port (CommitWidth 1, usingRvvi)", s"$base")
      )
    }
  }

  val all: Seq[SpecTest] = Seq(rsRead, commitRead, portsFixed)
}
