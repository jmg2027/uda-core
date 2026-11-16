package verif.spectest

import chisel3._
import chisel3.simulator.CachedSimulator._
import scala.collection.mutable.ArrayBuffer
import udacore.core.design.shared.AccessType
import udacore.frontend.design.modules.FetchUnit
import verif.spectest.FrontendTestKit._

/** L1 SpecTests for the ADR-019 FetchUnit (ADR-018; spec 242feaf +
  * ADR-019A..G).
  *
  * The driver plays the FTQ (FetchRequest), the ITLB and I-cache request ports
  * (recording what was sent), the I-cache response port (answering each lookup
  * after `latency` cycles with the block words base + 4 * slot and an optional
  * fault), and the RecoveryEvent broadcast.
  */
object FetchUnitSpecTests {

  case class FReq(
      ftqIdx: Int,
      fetchPc: Long,
      lastSlot: Int,
      exitTaken: Boolean = false,
      exitTarget: Long = 0
  )
  case class Blk(
      ftqIdx: Int,
      basePc: Long,
      insts: Seq[Long],
      valid: Seq[Boolean],
      exitTaken: Boolean,
      exitTarget: Long,
      fault: Int
  )

  class Drv(val dut: FetchUnit) {
    val io                      = dut.io
    var cyc                     = 0
    var req: Option[FReq]       = None
    var latency                 = 1
    var fault                   = 0
    var blockReady              = true
    var tlbReady                = true
    var icReady                 = true
    var recovery: Option[Event] = None
    val tlbReqs                 = ArrayBuffer[(Int, Long, Int)]() // (cyc, vaddr, access)
    val icReqs                  = ArrayBuffer[(Int, Long)]()      // (cyc, vaddr)
    val icIds                   = ArrayBuffer[Int]()
    val blocks                  = ArrayBuffer[(Int, Blk)]()
    val offers                  = ArrayBuffer[(Int, Blk)]()
    private val pending         =
      ArrayBuffer[(Int, Int, Long, Int)]() // (due, reqId, block vaddr, fault)
    var reqFire = false

    def word(a: Long): Long         = (a * 0x10001L + 0x13L) & 0xffffffffL
    def cycle(): Unit               = {
      val f   = io.fetchRequestIn
      f.valid.poke(req.nonEmpty.B)
      req.foreach { x =>
        f.bits.ftqIdx.poke(x.ftqIdx.U); f.bits.fetchPc.poke(x.fetchPc.U);
        f.bits.lastSlot.poke(x.lastSlot.U)
        f.bits.exitTaken.poke(x.exitTaken.B);
        f.bits.exitTarget.poke(x.exitTarget.U)
      }
      io.iTlbReqOut.ready.poke(tlbReady.B);
      io.iCacheReqOut.ready.poke(icReady.B);
      io.fetchBlockOut.ready.poke(blockReady.B)
      pokeEvent(io.recoveryEventIn, recovery)
      val due = pending.headOption.filter(_._1 <= cyc)
      val r   = io.iCacheRespIn
      r.valid.poke(due.nonEmpty.B)
      due.foreach { case (_, id, va, fl) =>
        r.bits.reqId.poke(id.U); r.bits.fault.poke(fl.U)
        r.bits.data.zipWithIndex.foreach { case (w, i) =>
          w.poke(word(va + 4 * i).U)
        }
      }
      reqFire = req.nonEmpty && b(f.ready)
      val tv  = b(io.iTlbReqOut.valid); val iv = b(io.iCacheReqOut.valid)
      if (tv && tlbReady)
        tlbReqs += ((
          cyc,
          l(io.iTlbReqOut.bits.vaddr),
          l(io.iTlbReqOut.bits.access).toInt
        ))
      if (iv && icReady) {
        val va = l(io.iCacheReqOut.bits.vaddr)
        val id = l(io.iCacheReqOut.bits.reqId).toInt
        icReqs += ((cyc, va)); icIds += id;
        pending += ((cyc + latency, id, va, fault))
      }
      if (due.nonEmpty && b(r.ready)) pending.remove(0)
      val o   = io.fetchBlockOut
      if (b(o.valid)) {
        val block = Blk(
          l(o.bits.ftqIdx).toInt,
          l(o.bits.basePc),
          o.bits.insts.map(l),
          o.bits.slotValid.map(b),
          b(o.bits.exitTaken),
          l(o.bits.exitTarget),
          l(o.bits.fault).toInt
        )
        offers += ((cyc, block))
        if (blockReady) blocks += ((cyc, block))
      }
      dut.clock.step(); cyc += 1; recovery = None
    }
    def run(n: Int): Unit           = (0 until n).foreach(_ => cycle())
    def fetch(x: FReq): Option[Blk] = {
      val n0               = blocks.size
      req = Some(x); var k = 0
      cycle(); while (!reqFire && k < 20) { cycle(); k += 1 }
      req = None
      k = 0; while (blocks.size == n0 && k < 20) { cycle(); k += 1 }
      blocks.drop(n0).headOption.map(_._2)
    }
  }

  def withDrv(t: SpecTest)(body: Drv => Seq[TCheck]): Seq[TCheck] =
    t.sim(new FetchUnit(fp)) { dut =>
      val d = new Drv(dut); d.cycle(); body(d)
    }

  // ---- ADR-019G E-8: mid-block fetch-PC semantics ---------------------------------------------------

  val midBlockSlots = new SpecTest(
    "fu.midBlockSlots",
    Seq("funcParallelFetchLookup", "funcFetchBlockAssembly")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      val s2    = d.fetch(FReq(3, 0x1008, 3))
      val tl    = d.tlbReqs.lastOption; val ic = d.icReqs.lastOption
      val s0    = d.fetch(FReq(4, 0x2000, 3))
      val ex    = d.fetch(FReq(5, 0x3004, 2, exitTaken = true, 0x5000))
      val words = (0 until 4).map(i => d.word(0x1000L + 4 * i))
      Seq(
        chk(
          tl.exists(t =>
            t._2 == 0x1008 && t._3 == AccessType.Fetch.litValue.toInt
          ) && ic.exists(_._2 == 0x1000),
          "ITLB is asked for the requested fetch PC 0x1008, the I-cache for blockBase 0x1000",
          s"$tl $ic"
        ),
        chk(
          s2.exists(x =>
            x.ftqIdx == 3 && x.basePc == 0x1000 && x.insts == words && x.valid == Seq(
              false,
              false,
              true,
              true
            ) &&
              x.fault == 0
          ),
          "a slot-2 request delivers the whole block with only slots 2 and 3 valid (basePc = blockBase)",
          s"$s2"
        ),
        chk(
          s0.exists(_.valid == Seq(true, true, true, true)),
          "a slot-0 request fetches all four slots",
          s"$s0"
        ),
        chk(
          ex.exists(x =>
            x.valid == Seq(
              false,
              true,
              true,
              false
            ) && x.exitTaken && x.exitTarget == 0x5000
          ),
          "a slot-1 request with a taken exit at slot 2 delivers slots 1..2 and echoes the exit",
          s"$ex"
        )
      )
    }
  }

  val midBlockFault =
    new SpecTest("fu.midBlockFault", Seq("funcFetchBlockAssembly")) {
      def run(): Seq[TCheck] = withDrv(this) { d =>
        d.fault = 1
        val s2 = d.fetch(FReq(1, 0x1008, 3, exitTaken = true, 0x5000))
        d.fault = 2
        val s0 = d.fetch(FReq(2, 0x2000, 3))
        Seq(
          chk(
            s2.exists(x =>
              x.fault == 1 && x.valid == Seq(
                false,
                false,
                true,
                false
              ) && !x.exitTaken
            ),
            "a fetch fault on a slot-2 request delivers exactly one valid faulting slot: slot 2",
            s"$s2"
          ),
          chk(
            s0.exists(x =>
              x.fault == 2 && x.valid == Seq(true, false, false, false)
            ),
            "a fetch fault on a slot-0 request delivers only slot 0",
            s"$s0"
          )
        )
      }
    }

  val atomicAndBackpressure = new SpecTest(
    "fu.atomicAndBackpressure",
    Seq("funcParallelFetchLookup", "funcFetchBlockAssembly", "propFetchInOrder")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.req = Some(FReq(7, 0x4004, 3)); d.tlbReady = false; d.cycle()
      val noTlb   = d.tlbReqs.isEmpty && d.icReqs.isEmpty && !d.reqFire
      d.tlbReady = true; d.icReady = false; d.cycle()
      val noCache = d.tlbReqs.isEmpty && d.icReqs.isEmpty && !d.reqFire
      d.icReady = true; d.cycle(); d.req = None
      val paired  = d.reqFire && d.tlbReqs.size == 1 && d.icReqs.size == 1 &&
        d.tlbReqs.head._1 == d.icReqs.head._1
      d.blockReady = false; d.run(4)
      val held    = d.offers.takeRight(2).map(_._2)
      d.req = Some(FReq(8, 0x5000, 3)); d.cycle()
      val stopped = !d.reqFire && d.icReqs.size == 1
      d.blockReady = true; d.cycle(); d.cycle(); d.req = None; d.run(4)
      Seq(
        chk(
          noTlb && noCache && paired,
          "ITLB and I-cache requests transfer atomically",
          s"${d.tlbReqs} ${d.icReqs}"
        ),
        chk(
          held.size == 2 && held.head == held.last && stopped,
          "the assembled block is stable while blocked and the next request waits",
          s"$held ${d.icReqs}"
        ),
        chk(
          d.blocks.map(_._2.ftqIdx) == Seq(7, 8),
          "blocks leave in accepted request order",
          s"${d.blocks}"
        )
      )
    }
  }

  val generationWrap = new SpecTest(
    "fu.generationWrap",
    Seq("funcFetchGeneration", "propNoStaleFetchBlock")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.latency = 30
      d.req = Some(FReq(1, 0x6000, 3)); d.cycle(); d.req = None
      val oldId = d.icIds.headOption
      for (_ <- 0 until 16) { d.recovery = Some(Event()); d.cycle() }
      d.latency = 1
      d.req = Some(FReq(2, 0x7000, 3))
      var early = false
      while (d.icReqs.size == 1 && d.cyc < 45) {
        d.cycle(); if (d.cyc < 32 && d.reqFire) early = true
      }
      d.req = None; d.run(5)
      Seq(
        chk(
          !early && d.icReqs.size == 2 && d.icIds.lastOption == oldId,
          "a wrapped generation is not reused until the stale response drains",
          s"ids=${d.icIds} reqs=${d.icReqs}"
        ),
        chk(
          d.blocks.map(_._2.ftqIdx) == Seq(2),
          "the old response is dropped after 16 recoveries; the new block survives",
          s"${d.blocks}"
        )
      )
    }
  }

  val recoveryRaces = new SpecTest(
    "fu.recoveryRaces",
    Seq("funcFetchGeneration", "propNoStaleFetchBlock")
  ) {
    def run(): Seq[TCheck] = withDrv(this) { d =>
      d.req = Some(FReq(1, 0x8000, 3)); d.cycle()
      d.req = Some(FReq(2, 0x9000, 3)); d.recovery = Some(Event()); d.cycle()
      val responseRace = !d.reqFire && d.blocks.isEmpty && d.offers.isEmpty
      d.req = None; d.run(2)

      d.blockReady = false
      d.req = Some(FReq(3, 0xa000, 3)); d.cycle(); d.req = None; d.run(3)
      val held       = d.offers.nonEmpty && d.blocks.isEmpty
      val offerCount = d.offers.size
      d.recovery = Some(Event()); d.cycle()
      val heldRace   = d.offers.size == offerCount && d.blocks.isEmpty
      d.blockReady = true; d.run(2)
      val fresh      = d.fetch(FReq(4, 0xb000, 3))
      Seq(
        chk(
          responseRace,
          "recovery drops a same-cycle I-cache response and blocks a new request",
          s"${d.icReqs} ${d.blocks}"
        ),
        chk(
          held && heldRace && d.blocks.map(_._2.ftqIdx) == Seq(4),
          "recovery suppresses a held block in its cycle and afterward",
          s"${d.offers} ${d.blocks}"
        ),
        chk(
          fresh.exists(_.ftqIdx == 4),
          "a fresh lookup works after both recoveries",
          s"$fresh"
        )
      )
    }
  }

  val all: Seq[SpecTest] = Seq(
    midBlockSlots,
    midBlockFault,
    atomicAndBackpressure,
    generationWrap,
    recoveryRaces
  )
}
