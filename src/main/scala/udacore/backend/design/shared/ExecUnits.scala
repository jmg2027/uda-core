package udacore.backend.design.shared

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.backend.spec.shared.BackendBundlesSpecs.{bndFuResult, bndMemAddress, bndMemResult}

/** Shared pieces of the execution wrappers: result payloads, the unit-local op layouts, and
  * the one-entry elastic result holder (ADR-019C E-2).
  */

@LocalSpec(bndFuResult)
class FuResult(val params: BackendParams) extends BackendBundle {
  val robTag     = new RobTag(params)
  val prd        = UInt(physRegIdWidth.W)
  val wen        = Bool()
  val data       = UInt(xLen.W)
  val exception  = new ExceptionInfo(params)
  val cfiOutcome = new CfiOutcome(params)
}

@LocalSpec(bndMemAddress)
class MemAddress(val params: BackendParams) extends BackendBundle {
  val robTag     = new RobTag(params)
  val vaddr      = UInt(vAddrWidth.W)
  val storeData  = UInt(xLen.W)
  val misaligned = Bool()
}

@LocalSpec(bndMemResult)
class MemResult(val params: BackendParams) extends BackendBundle {
  val robTag      = new RobTag(params)
  val prd         = UInt(physRegIdWidth.W)
  val wen         = Bool()
  val data        = UInt(xLen.W)
  val headExecute = Bool()
  val exception   = new ExceptionInfo(params)
}

// ---- Unit-local op layouts (UopOp, 8 bits) ---------------------------------------------------

/** ALU: op[4:0] = AluControl code; op[5] srcB = imm; op[6] srcA = pc (AUIPC); op[7] srcA = 0 (LUI). */
object AluOp {
  def encode(ctrl: Int, immB: Boolean = false, pcA: Boolean = false, zeroA: Boolean = false): Int =
    ctrl | (if (immB) 1 << 5 else 0) | (if (pcA) 1 << 6 else 0) | (if (zeroA) 1 << 7 else 0)
}

/** MUL / DIV: op[2:0] = MultiplierControl / DividerControl code. */
object MulDivOp {
  def encode(ctrl: Int): Int = ctrl
}

/** Load/store: op[1:0] size (0 byte, 1 half, 2 word); op[2] unsigned load; op[3] store. */
object MemOp {
  def encode(size: Int, unsigned: Boolean = false, store: Boolean = false): Int =
    size | (if (unsigned) 1 << 2 else 0) | (if (store) 1 << 3 else 0)
}

/** Branch: op[3:0] = BranchControl code (BEQ..BGEU, JAL, JALR); op[6:4] = CfiType of the
  * instruction (Branch, Jal, Jalr, Call, Ret per the link-register hints). */
object BranchOp {
  def encode(ctrl: Int, cfiType: Int): Int = ctrl | (cfiType << 4)
}

/** One-entry elastic result holder (ADR-019C E-2).
  *
  * The held token is dropped in the event cycle when funcRecoveryKills selects it (its
  * valid falls at once, so a killed result never publishes), and `canAccept` depends only on
  * registered state and the drain of the held token: `!valid || out.ready`.
  */
class ResultHolder[T <: Data](gen: T, tag: T => RobTag, ev: RecoveryEvent, out: DecoupledIO[T]) {
  val valid = RegInit(false.B)
  val bits  = Reg(gen)
  private val killedNow = valid && RobOrder.recoveryKills(ev, tag(bits))
  out.valid := valid && !killedNow
  out.bits  := bits
  val canAccept: Bool = !valid || out.ready
  when(out.fire || killedNow) { valid := false.B }

  /** Load a new result this cycle (never one the same-cycle event kills). */
  def load(en: Bool, d: T): Unit =
    when(en && !RobOrder.recoveryKills(ev, tag(d))) {
      valid := true.B
      bits  := d
    }
}
