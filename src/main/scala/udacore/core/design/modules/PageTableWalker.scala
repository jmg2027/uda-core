package udacore.core.design.modules

import chisel3._
import chisel3.util._
import udacore.backend.design.shared.TranslationContext
import framework.macros.LocalSpec
import udacore.core.design.shared._
import udacore.core.spec.modules.PageTableWalkerSpecs._
import udacore.core.spec.shared.Sv32Specs.propNoFaultCaching

/** PageTableWalker (spec: PageTableWalkerSpecs; Sv32Specs; ADR-019 D-19.6).
  *
  * One walk at a time (PtwOutstanding = 1). When idle and no result is pending, a walk request is
  * accepted from the ITLB or the DTLB (round-robin when both request; the pointer moves only on an
  * accepted request); the requester, the VPN and the complete TranslationContext are captured and are
  * authoritative for the whole walk (no live satp is ever read). The loser stays backpressured.
  *
  * Walk (funcSv32Walk): level 1 at {rootPpn, VPN[1], 00}, level 0 at {PPN, VPN[0], 00}, both 34-bit
  * physical. Before each read the PTE address passes PmaLookup: unmapped or not readable ends the walk
  * with AccessFault and no read (cacheable is not required; a readable uncacheable address is read and
  * the DataCache serves it uncached). A PtwMemReq stays stable until accepted; exactly one PtwMemResp is
  * awaited before anything else. accessFault -> AccessFault (the PTE is not used). PTE: V = 0 or
  * (R = 0 and W = 1) -> PageFault; R or X -> leaf (at level 1 a 4 MiB superpage that needs PPN[0] = 0,
  * else PageFault); otherwise a pointer (followed from level 1; a PageFault at level 0). G is ORed over
  * every PTE read. Leaf permissions, A/D and the target PMA are not walk faults: they are the TLBs'.
  *
  * Result: one stable WalkResp holder, offered only on the requester's edge; no new walk until it
  * transfers. entry = {captured vpn, superpage, leaf PPN, captured ASID, global, R/W/X/U/A/D, advisory
  * PMA of the walked page}.
  *
  * SFENCE.VMA (funcPtwFlush): forked atomically - both TLB flush tokens fire in the same cycle or
  * neither is offered. It is not accepted while a result is held (a presented result is never changed),
  * and it wins over a new walk request in its cycle. A walk it overlaps finishes every memory read it
  * has issued or presented (nothing is retracted) and answers Retry.
  */
@LocalSpec(contPageTableWalker)
class PageTableWalker(val params: CoreParams) extends CoreModule {
  val io = IO(new Bundle {
    @LocalSpec(intfItlbWalkReqIn)
    val itlbWalkReqIn = Flipped(Decoupled(new WalkReq))

    @LocalSpec(intfItlbWalkRespOut)
    val itlbWalkRespOut = Decoupled(new WalkResp)

    @LocalSpec(intfDtlbWalkReqIn)
    val dtlbWalkReqIn = Flipped(Decoupled(new WalkReq))

    @LocalSpec(intfDtlbWalkRespOut)
    val dtlbWalkRespOut = Decoupled(new WalkResp)

    @LocalSpec(intfPtwMemReqOut)
    val ptwMemReqOut = Decoupled(new PtwMemReq(params.pAddrWidth))

    @LocalSpec(intfPtwMemRespIn)
    val ptwMemRespIn = Flipped(Decoupled(new PtwMemResp))

    @LocalSpec(intfSfenceVmaIn)
    val sfenceVmaIn = Flipped(Decoupled(new TlbFlush(params.vAddrWidth)))

    @LocalSpec(intfItlbFlushOut)
    val itlbFlushOut = Decoupled(new TlbFlush(params.vAddrWidth))

    @LocalSpec(intfDtlbFlushOut)
    val dtlbFlushOut = Decoupled(new TlbFlush(params.vAddrWidth))
  })

  private val paW = params.pAddrWidth
  require(paW == 34, "the Sv32 walker composes 34-bit physical PTE addresses")

  // ---- State ------------------------------------------------------------------------------------
  val sIdle :: sReq :: sWait :: sResp :: Nil = Enum(4)
  val state    = RegInit(sIdle)
  /** Round-robin pointer: set = the DTLB is preferred when both request. */
  val preferD  = RegInit(false.B)
  val fromD    = Reg(Bool())                 // requester of the active walk
  val vpn      = Reg(UInt(20.W))
  val ctx      = Reg(new TranslationContext)
  val level1   = Reg(Bool())
  val ptrPpn   = Reg(UInt(22.W))             // level-1 pointer PPN (valid at level 0)
  val addr     = Reg(UInt(paW.W))            // the PTE address being read
  val globalSeen = Reg(Bool())
  /** The active walk overlapped an SFENCE: it answers Retry. */
  val stale    = Reg(Bool())
  val res      = Reg(new WalkResp)

  private val iq = io.itlbWalkReqIn
  private val dq = io.dtlbWalkReqIn
  private val mq = io.ptwMemReqOut
  private val mr = io.ptwMemRespIn
  private val sf = io.sfenceVmaIn
  private val sfFire = sf.fire
  private val vpn1 = vpn(19, 10)
  private val vpn0 = vpn(9, 0)

  // ---- funcPtwArbitrate ---------------------------------------------------------------------------
  private val canAccept = state === sIdle && !sf.valid   // an SFENCE wins its cycle
  private val grantD    = dq.valid && (!iq.valid || preferD)

  @LocalSpec(funcPtwArbitrate)
  val ptwArbitrate: Unit = {
    iq.ready := canAccept && !grantD
    dq.ready := canAccept && grantD
    when(iq.fire || dq.fire) {
      val r = Mux(grantD, dq.bits, iq.bits)
      fromD := grantD; vpn := r.vpn; ctx := r.context
      level1 := true.B; globalSeen := false.B; stale := false.B
      addr := Cat(r.context.rootPpn, r.vpn(19, 10), 0.U(2.W))
      state := sReq
      preferD := !grantD
    }
    io.itlbWalkRespOut.valid := state === sResp && !fromD
    io.dtlbWalkRespOut.valid := state === sResp && fromD
    io.itlbWalkRespOut.bits  := res
    io.dtlbWalkRespOut.bits  := res
    when(io.itlbWalkRespOut.fire || io.dtlbWalkRespOut.fire) { state := sIdle }
    assert(!(iq.fire && dq.fire), "PtwArbitrate: both walk requests accepted in one cycle")
  }

  // ---- funcPtwPhysicalAccess ----------------------------------------------------------------------
  private val (pmaHit, pmaAttr) = PmaLookup(params.pma, addr)
  private val addrLegal = pmaHit && pmaAttr.readable

  @LocalSpec(funcPtwPhysicalAccess)
  val ptwPhysicalAccess: Unit = {
    mq.valid      := state === sReq && addrLegal
    mq.bits.paddr := addr
    mr.ready      := state === sWait
    when(mq.fire) { state := sWait }
  }

  // ---- funcSv32Walk -------------------------------------------------------------------------------
  private val pte     = mr.bits.pte
  private val gNow    = globalSeen || pte.g
  private val invalid = !pte.v || (!pte.r && pte.w)
  private val isLeaf  = pte.r || pte.x
  private val misSp   = level1 && pte.ppn0 =/= 0.U
  private val leafPa  = Mux(level1, Cat(pte.ppn1, vpn0, 0.U(12.W)), Cat(pte.ppn, 0.U(12.W)))
  private val (leafHit, leafAttr) = PmaLookup(params.pma, leafPa)

  /** Load the result holder: Retry if an SFENCE overlapped the walk (including this cycle). */
  private def finish(status: UInt, leafOk: Bool): Unit = {
    state := sResp
    res.vpn := vpn
    res.status := Mux(stale || sfFire, WalkStatus.Retry, status)
    val e = res.entry
    e.valid := leafOk; e.vpn := vpn; e.superpage := level1; e.ppn := pte.ppn; e.asid := ctx.asid
    e.global := gNow; e.r := pte.r; e.w := pte.w; e.x := pte.x; e.u := pte.u; e.a := pte.a; e.d := pte.d
    e.pma := leafAttr
    when(!leafHit) { e.pma := 0.U.asTypeOf(new PmaAttr) }
  }

  @LocalSpec(funcSv32Walk)
  val sv32Walk: Unit = {
    when(state === sReq && !addrLegal) {
      finish(WalkStatus.AccessFault, false.B)
      res.entry := 0.U.asTypeOf(new TlbEntry)
    }
    when(mr.fire) {
      when(mr.bits.accessFault) {
        finish(WalkStatus.AccessFault, false.B)
        res.entry := 0.U.asTypeOf(new TlbEntry)
      }.otherwise {
        globalSeen := gNow
        when(invalid || (isLeaf && misSp) || (!isLeaf && !level1)) {
          finish(WalkStatus.PageFault, false.B)
        }.elsewhen(isLeaf) {
          finish(WalkStatus.Leaf, true.B)
        }.otherwise {                           // level-1 pointer: descend
          level1 := false.B; ptrPpn := pte.ppn
          addr := Cat(pte.ppn, vpn0, 0.U(2.W))
          state := sReq
        }
      }
    }
  }

  // ---- funcPtwFlush -------------------------------------------------------------------------------
  @LocalSpec(funcPtwFlush)
  val ptwFlush: Unit = {
    val free = state =/= sResp                 // a presented result drains first
    io.itlbFlushOut.valid := sf.valid && free && io.dtlbFlushOut.ready
    io.dtlbFlushOut.valid := sf.valid && free && io.itlbFlushOut.ready
    io.itlbFlushOut.bits  := sf.bits
    io.dtlbFlushOut.bits  := sf.bits
    sf.ready := free && io.itlbFlushOut.ready && io.dtlbFlushOut.ready
    when(sfFire && (state === sReq || state === sWait)) { stale := true.B }
    assert(io.itlbFlushOut.fire === io.dtlbFlushOut.fire && io.itlbFlushOut.fire === sfFire,
      "PtwFlush: the TLB flush tokens did not fire together with the SFENCE")
  }

  // ---- propPtwNoRecursiveTranslation / propWalkFaultTyping ------------------------------------------
  @LocalSpec(propPtwNoRecursiveTranslation)
  val ptwNoRecursiveTranslation: Unit = {
    val expect = Mux(level1, Cat(ctx.rootPpn, vpn1, 0.U(2.W)), Cat(ptrPpn, vpn0, 0.U(2.W)))
    when(mq.valid) {
      assert(mq.bits.paddr(1, 0) === 0.U, "PtwNoRecursiveTranslation: a PtwMemReq is not 4-byte aligned")
      assert(mq.bits.paddr === expect, "PtwNoRecursiveTranslation: a PtwMemReq is not the expected physical PTE address")
    }
    assert(!mr.valid || state === sWait, "PtwMemResp: a PTE answer arrived with no read outstanding")
  }

  @LocalSpec(propWalkFaultTyping)
  val walkFaultTyping: Unit = {
    // Fault typing is checked where the result is decided: PageFault only from PTE structure,
    // AccessFault only from the PTE address PMA or a denied read; a flush overlap is Retry.
    val pfCause = mr.fire && !mr.bits.accessFault && (invalid || (isLeaf && misSp) || (!isLeaf && !level1))
    val afCause = (state === sReq && !addrLegal) || (mr.fire && mr.bits.accessFault)
    val decided = (state === sReq && !addrLegal) || (mr.fire && (mr.bits.accessFault || invalid || isLeaf || !level1))
    val nextStatus = RegNext(Mux(stale || sfFire, WalkStatus.Retry,
      Mux(afCause, WalkStatus.AccessFault, Mux(pfCause, WalkStatus.PageFault, WalkStatus.Leaf))))
    when(RegNext(decided, false.B)) {
      assert(res.status === nextStatus, "WalkFaultTyping: the walk result status does not match its cause")
    }
    when(state === sResp) {
      assert(res.status =/= WalkStatus.Leaf || res.entry.valid, "WalkFaultTyping: a Leaf result without a valid entry")
    }
  }

  /** propNoFaultCaching (walker side): every Leaf result is a legal Sv32 leaf, so a TLB that installs
    * only Leaf results never caches a fault or an illegal leaf (TlbLogic.assertNoFaultCaching). */
  @LocalSpec(propNoFaultCaching)
  val noFaultCaching: Unit = when(state === sResp && res.status === WalkStatus.Leaf) {
    val e = res.entry
    assert((e.r || e.x) && !(e.w && !e.r) && (!e.superpage || e.ppn(9, 0) === 0.U),
      "NoFaultCaching: a Leaf result is not a legal Sv32 leaf")
  }
}
