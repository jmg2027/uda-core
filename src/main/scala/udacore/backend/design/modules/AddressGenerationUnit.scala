package udacore.backend.design.modules

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.design.shared._
import udacore.backend.spec.modules.AddressGenerationUnitSpecs._

/** AddressGenerationUnit (spec: AddressGenerationUnitSpecs; ADR-019 D-19.5, ADR-019C E-2).
  *
  * vaddr = src1 + imm, natural-alignment check by the MemOp size, storeData = src2 for
  * stores, into a one-entry holder toward the LSQ.
  *
  * Recovery stance (rawSpeculativeHolder): the held MemAddress is dropped in the event cycle
  * when funcRecoveryKills selects it; request ready = !held || drained.
  */
@LocalSpec(contAddressGenerationUnit)
class AddressGenerationUnit(val params: BackendParams) extends BackendModule {
  val io = IO(new Bundle {
    @LocalSpec(intfAddressGenerationReqIn)
    val addressGenerationReqIn = Flipped(Decoupled(new IssuedUop(params)))

    @LocalSpec(intfMemAddressOut)
    val memAddressOut = Decoupled(new MemAddress(params))

    @LocalSpec(intfRecoveryEventIn)
    val recoveryEventIn = Input(new RecoveryEvent(params))
  })

  private val req = io.addressGenerationReqIn
  private val holder = new ResultHolder(new MemAddress(params), (r: MemAddress) => r.robTag, io.recoveryEventIn,
    io.memAddressOut)

  @LocalSpec(funcAddressGenerate)
  val addressGenerate: Unit = {
    val op    = req.bits.op
    val size  = op(1, 0)
    val store = op(3)
    val va    = (req.bits.src1 + req.bits.imm)(vAddrWidth - 1, 0)
    val r = Wire(new MemAddress(params))
    r.robTag     := req.bits.robTag
    r.vaddr      := va
    r.storeData  := Mux(store, req.bits.src2, 0.U)
    r.misaligned := MuxLookup(size, false.B)(Seq(1.U -> va(0), 2.U -> va(1, 0).orR))
    req.ready := holder.canAccept
    holder.load(req.fire, r)
  }
}
