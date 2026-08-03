package cluster

import scala.collection.{mutable => mut}
import chisel3._
import chiseltest._
import chisel3.experimental.BundleLiterals._

import udacore.common.types._
import udacore.common.HeartXcpt
import udacore.DefaultCoreConfig

case class TestInterrupt(var e: Boolean, var t: Boolean, var s: Boolean)

abstract class TestCluster(
    epmLatency: Int = 2,
    edmLatency: Int = 2
) extends Module {
  // Use CoreTop(UDACoreTop interfaces and parameters using core api!)
  // Need debug verbosity configuration controlability
  
  // Implement CoreTop interface here
  val epm      = IO(new ExternalProgramMemoryInterface)
  val irq      = IO(Input(new Interrupt))
  val edm      = IO(new ExternalDataMemoryInterface())
  val hartEn   = IO(Input(Bool()))
  val bootAddr = IO(Input(UInt(vaddrWidth.W)))
  val debugReq = IO(Input(Bool()))

  val xcpt   = new HeartXcpt
  val noXcpt = xcpt.Lit(
    _.loc -> false.B,
    _.ma  -> false.B,
    _.pf  -> false.B,
    _.gf  -> false.B,
    _.ae  -> false.B
  )

  val mem = new ClusterTestMem(
    epmLatency,
    edmLatency,
    debugConfig
  )(this)

  var interrupt = TestInterrupt(false, false, false)

  def extInterrupt(v: Boolean = true)      = interrupt.e = v
  def timerInterrupt(v: Boolean = true)    = interrupt.t = v
  def softwareInterrupt(v: Boolean = true) = interrupt.s = v
  def checkInterruptEnd()                  = {
    Seq(
      (0x70000004L, () => interrupt.e = false),
      (0x70000008L, () => interrupt.t = false),
      (0x7000000cL, () => interrupt.s = false)
    ).map {
      case (a, f) => {
        if (mem.load(a) != BigInt(0)) {
          f()
          mem.store(a, 0x0)
        }
      }
    }
  }

  var fetchCount            = 0
  var cyclesSinceLastFetch  = 0
  val maxCyclesWithoutFetch = 10

  def tick() = {
    checkInterruptEnd()
    irq.e.poke(interrupt.e)
    irq.t.poke(interrupt.t)
    irq.s.poke(interrupt.s)
    debugReq.poke(false.B)

    // Monitor fetch requests
    if (
      epm.req.valid.peek().litToBoolean && epm.req.ready.peek().litToBoolean
    ) {
      fetchCount += 1
      cyclesSinceLastFetch = 0
      val addr  = epm.req.bits.addr.peek().litValue
      val txnId = epm.req.bits.txnId.peek().litValue
      TestDebug.print(
        debugConfig.testSetup,
        f"[FETCH] Request #$fetchCount from addr 0x$addr%08X, txnId $txnId"
      )
    } else {
      cyclesSinceLastFetch += 1
      if (cyclesSinceLastFetch >= maxCyclesWithoutFetch && fetchCount > 0) {
        TestDebug.print(
          debugConfig.testSetup,
          s"[FETCH] TIMEOUT: No fetch for $cyclesSinceLastFetch cycles after $fetchCount fetches"
        )
        throw new RuntimeException(
          s"Fetch timeout: No fetch request for $cyclesSinceLastFetch cycles"
        )
      }
    }

    // Monitor EPM responses
    if (
      epm.resp.valid.peek().litToBoolean && epm.resp.ready.peek().litToBoolean
    ) {
      val txnId = epm.resp.bits.txnId.peek().litValue
      val data  = epm.resp.bits.data.peek().litValue
      TestDebug.print(
        debugConfig.testSetup,
        f"[EPM] Response txnId $txnId, data 0x$data%08X"
      )
    }

    mem.tick()
    clock.step()
  }

  def isDone() = mem.load(0x70000000L) != BigInt(0)

  def runFuncWhileCond(iter: Int, func: => Unit, cond: => Boolean): Boolean = {
    var i        = 0
    var continue = cond
    while (i < iter && continue) {
      func
      i += 1
      continue = cond
    }
    TestDebug.print(debugConfig.testSetup, continue.toString)
    continue
  }

  def initialize() = {
    hartEn.poke(false.B)
    bootAddr.poke(0x80000000L.U)
    epm.req.ready.poke(true.B)
    epm.resp.valid.poke(false.B)
    epm.resp.bits.data.poke(0.U)
    epm.resp.bits.txnId.poke(0.U)
    epm.resp.bits.xcpt.poke(noXcpt)
    edm.req.ready.poke(true.B)
    edm.resp.valid.poke(false.B)
    edm.resp.bits.rdata.poke(0.U)
    edm.resp.bits.xcpt.poke(noXcpt)
    edm.resp.bits.txnId.poke(0.U)
    clock.step()
    hartEn.poke(true.B)
    clock.step()
  }

  def run() = {
    val maxIteration = 100 // [IMPORTANT]: DO NOT CHANGE THIS VALUE
    initialize()
    if (!runFuncWhileCond(maxIteration, tick(), !isDone())) {
      TestDebug.print(debugConfig.testSetup, "Done")
    } else {
      TestDebug.print(debugConfig.testSetup, "Test Timeout")
    }
  }
}

class ClusterTestMem(
    instLatency: Int,
    dataLatency: Int,
    debugConfig: TestDebugConfig
)(dut: TestCluster)
    extends TestMem {
  case class TestLoadRequest(
      ld_req: Boolean,
      ld_vaddr: BigInt,
      ld_kill: Boolean
  ) {
    def debug = TestDebug.print(
      debugConfig.testMem,
      f"[Mem] Load request from addr(${ld_vaddr}%X)"
    )
  }

  case class TestStoreRequest(
      st_req: Boolean,
      st_paddr: BigInt,
      st_wdata: BigInt,
      st_mask: BigInt,
      st_mmio: Boolean
  ) {
    def debug = TestDebug.print(
      debugConfig.testMem,
      f"[Mem] Store request to addr(${st_paddr}%X) data(${st_wdata}%X)"
    )
  }
//  case class TestDataRequest extends MemoryOpConstants(
  case class TestDataRequest(
      cmd: BigInt,
      load: TestLoadRequest,
      store: TestStoreRequest
  ) extends MemoryOpConstants {
    def cmdtoString: String = {
      if (cmd == M_XRD.litValue) {
        "Load"
      } else if (cmd == M_XWR.litValue) {
        "Store"
      } else if (cmd == M_PWR.litValue) {
        "Store Partial"
      } else {
        "Others"
      }
    }

    def isLoad: Boolean       = cmdtoString == "Load"
    def isStore: Boolean      =
      cmdtoString == "Store" | cmdtoString == "Store Partial"
    def queueContents: String = if (isLoad) f"(${load.ld_vaddr}%X)"
    else if (isStore) f"${store.st_paddr}%X"
    else "Empty"

    override def toString: String = f"<$cmdtoString: Addr: $queueContents>"
  }

  class TestQueue[T] {
    val queue = mut.Queue[T]()

    def log(method: String): Unit = {
      TestDebug.print(
        debugConfig.testSetup,
        s"[$method] Current Queue: $toString"
      )
    }

    def enqueue(elem: T): Unit = {
      // Allow only signle enqueue
      queue.enqueue(elem)
//      log("enqueue")
    }

    def dequeue(): T = {
      val elem = queue.dequeue()
//      log("dequeue")
      elem
    }

    def front(): T  = queue.front
    def size(): Int = queue.size

    override def toString: String = queue.mkString(s"[", " | ", "]")

  }

  case class DataMemReq(
      addr: BigInt,
      wdata: BigInt,
      mask: BigInt,
      isWrite: Boolean,
      txnId: BigInt
  )

//  val dataReqQueue = mut.Queue[TestDataRequest]()
  val dataReqQueue        = new TestQueue[Option[DataMemReq]]
  val loadReqInitialValue = TestLoadRequest(
    ld_req = false,
    ld_vaddr = 0,
    ld_kill = false
  )

  val storeReqInitialValue = TestStoreRequest(
    st_req = false,
    st_paddr = 0,
    st_wdata = 0,
    st_mask = 0,
    st_mmio = false
  )

  for (_ <- 0 until dataLatency) {
    dataReqQueue.enqueue(None)
  }

  case class TestInstRequest(
      req: Boolean,
      addr: BigInt,
      txnId: BigInt
  ) {
    override def toString: String = ""
  }

  val instReqInitialValue = TestInstRequest(
    req = false,
    addr = 0,
    txnId = 0
  )
//  val instReqQueue = mut.Queue[TestInstRequest]()
  val instReqQueue        = new TestQueue[TestInstRequest]

  for (i <- 0 until instLatency) {
    instReqQueue.enqueue(instReqInitialValue)
  }

  def tick() = {
    instTick()
    dataTick()
  }

  // 0: New request, reset to epmLatency
  // 1: Latency ended
  var instCnt = 0

  def instTick() = {
    val req = instReqQueue.front()
    if (!req.req) {
      dut.epm.resp.valid.poke(false.B)
      dut.epm.resp.bits.data.poke(0.U)
      dut.epm.resp.bits.txnId.poke(0.U)
      dut.epm.resp.bits.xcpt.poke(dut.noXcpt)

      instReqQueue.dequeue()
    } else {
      val data = load(req.addr)
      TestDebug.print(
        debugConfig.testMem,
        f"[Inst] Fetch from addr(${req.addr}%X) data($data%X)"
      )
      dut.epm.resp.valid.poke(true.B)
      dut.epm.resp.bits.data.poke(data)
      dut.epm.resp.bits.txnId.poke(req.txnId)
      dut.epm.resp.bits.xcpt.poke(dut.noXcpt)

      instReqQueue.dequeue()
    }

    if (instReqQueue.size() == instLatency - 1) {
      if (dut.epm.req.valid.peekBoolean()) {
        instReqQueue.enqueue(
          TestInstRequest(
            req = dut.epm.req.valid.peekBoolean(),
            addr = dut.epm.req.bits.addr.peekInt(),
            txnId = dut.epm.req.bits.txnId.peekInt()
          )
        )
      } else {
        instReqQueue.enqueue(instReqInitialValue)
      }
    }
    dut.epm.req.ready.poke(true.B)
  }

  def dataTick()                                          = {
    val respOpt = dataReqQueue.dequeue()
    dut.edm.resp.valid.poke(respOpt.isDefined.B)
    respOpt.foreach { r =>
      if (r.isWrite) {
        storeStrb(r.addr, r.wdata, r.mask)
        TestDebug.print(
          debugConfig.testMem,
          f"[Mem] Store To addr(${r.addr}%X) data(${r.wdata}%X) mask(${r.mask}%X)"
        )
        dut.edm.resp.bits.rdata.poke(0)
      } else {
        val data = load(r.addr)
        TestDebug.print(
          debugConfig.testMem,
          f"[Mem] Load from addr(${r.addr}%X) data($data%X)"
        )
        dut.edm.resp.bits.rdata.poke(data)
      }
      dut.edm.resp.bits.xcpt.poke(dut.noXcpt)
      dut.edm.resp.bits.txnId.poke(r.txnId.U)
    }
    if (respOpt.isEmpty) { dut.edm.resp.bits.txnId.poke(0.U) }

    val ready = dataReqQueue.size() == dataLatency - 1
    dut.edm.req.ready.poke(ready.B)
    if (ready && dut.edm.req.valid.peekBoolean()) {
      val b        = dut.edm.req.bits
      // Instrumentation: print each enqueued data memory request
      val addrHex  = b.addr.peekInt().toString(16)
      val wdataHex = b.wdata.peekInt().toString(16)
      val maskHex  = b.mask.peekInt().toString(16)
      println(
        s"[EDM Req] addr=0x$addrHex wdata=0x$wdataHex mask=0x$maskHex isWrite=${b.isWrite
            .peekBoolean()} txid=${b.txnId.peekInt()}"
      )
      if (b.addr.peekInt() == BigInt("40000010", 16)) {
        println(
          s"[HIT] saw request to 0x40000010, wdata=0x$wdataHex mask=0x$maskHex isWrite=${b.isWrite.peekBoolean()}"
        )
      }
      dataReqQueue.enqueue(
        Some(
          DataMemReq(
            addr = b.addr.peekInt(),
            wdata = b.wdata.peekInt(),
            mask = b.mask.peekInt(),
            isWrite = b.isWrite.peekBoolean(),
            txnId = b.txnId.peekInt()
          )
        )
      )
    } else {
      dataReqQueue.enqueue(None)
    }
  }
  def storeStrb(addr: BigInt, data: BigInt, strb: BigInt) = {
    for (i <- 0 until 2) {
      val curAddr   = addr + i * 4
      val curOrigin = load(curAddr)
      val curData   = data >> (i * 32)
      val curStrb   = (strb >> (i * 4)) & 0xf
      if (curStrb != 0) {
        val v = Seq
          .tabulate(4) { j =>
            val dataMask = BigInt(0xff) << (j * 8)
            if ((curStrb & (1 << j)) != 0) curData & dataMask
            else curOrigin & dataMask
          }
          .reduce(_ | _)
        store(curAddr, v)
        TestDebug.print(
          debugConfig.testMem,
          f"[Mem] Store To addr(${curAddr}%X) data(${v}%X)"
        )
      }
    }
  }
}
