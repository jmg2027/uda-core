package udacore.common.tilelink

import chisel3._
import chisel3.util._
import framework.macros.LocalSpec
import udacore.common.tilelink.TileLinkSpecs._

/** Standard TileLink bundles (SiFive TileLink Specification 1.8.x), ADR-016.
  *
  * These are the wire-level bundle shapes only - protocol behavior (bursts,
  * atomics, coherence state machines) lives in the agents that will speak
  * them (the CoreTop bus adapters, later the caches). Field names follow the
  * specification so external fabrics and standard tooling read the ports
  * directly.
  */

@LocalSpec(paramTLLinkParams)
case class TLLinkParams(
    addressBits: Int,
    dataBits: Int,
    sourceBits: Int = 1,
    sinkBits: Int = 1,
    sizeBits: Int = 3,
    hasBCE: Boolean = false
) {
  require(addressBits > 0, "TileLink addressBits must be positive")
  require(dataBits >= 8 && dataBits % 8 == 0, "TileLink dataBits must be a positive byte multiple")
  require(isPow2(dataBits / 8), "TileLink beat bytes must be a power of two")
  require(sourceBits >= 1, "TileLink sourceBits must be at least 1")
  require(sinkBits >= 1, "TileLink sinkBits must be at least 1")
  require(sizeBits >= 1, "TileLink sizeBits must be at least 1")
  def maskBits: Int = dataBits / 8
}

object TLLinkParams {
  /** Link geometry for a master issuing up to `maxTransferBytes` transfers from
    * `sourceIds` outstanding ids - the usual way an integrator derives a link. */
  def forMaster(
      addressBits: Int,
      dataBits: Int,
      maxTransferBytes: Int,
      sourceIds: Int = 1,
      sinkBits: Int = 1,
      hasBCE: Boolean = false
  ): TLLinkParams = {
    require(maxTransferBytes >= dataBits / 8, "max transfer must cover one beat")
    TLLinkParams(
      addressBits = addressBits,
      dataBits = dataBits,
      sourceBits = math.max(1, log2Ceil(math.max(2, sourceIds))),
      sinkBits = sinkBits,
      sizeBits = math.max(1, log2Ceil(log2Ceil(maxTransferBytes) + 1)),
      hasBCE = hasBCE
    )
  }
}

/** Channel A/B/C/D opcodes and E, per the TileLink spec opcode tables. */
object TLMessages {
  // Channel A
  def PutFullData    = 0.U(3.W)
  def PutPartialData = 1.U(3.W)
  def ArithmeticData = 2.U(3.W)
  def LogicalData    = 3.U(3.W)
  def Get            = 4.U(3.W)
  def Intent         = 5.U(3.W)
  def AcquireBlock   = 6.U(3.W)
  def AcquirePerm    = 7.U(3.W)
  // Channel B (TL-C)
  def ProbeBlock = 6.U(3.W)
  def ProbePerm  = 7.U(3.W)
  // Channel C (TL-C)
  def ProbeAck     = 4.U(3.W)
  def ProbeAckData = 5.U(3.W)
  def Release      = 6.U(3.W)
  def ReleaseData  = 7.U(3.W)
  // Channel C/D shared access responses
  def AccessAck     = 0.U(3.W)
  def AccessAckData = 1.U(3.W)
  def HintAck       = 2.U(3.W)
  // Channel D (TL-C)
  def Grant      = 4.U(3.W)
  def GrantData  = 5.U(3.W)
  def ReleaseAck = 6.U(3.W)
}

/** Permission transitions carried in param fields (TL-C). */
object TLPermissions {
  // Cap (B.param): probe to at most this permission
  def toT = 0.U(3.W); def toB = 1.U(3.W); def toN = 2.U(3.W)
  // Grow (A.param on Acquire)
  def NtoB = 0.U(3.W); def NtoT = 1.U(3.W); def BtoT = 2.U(3.W)
  // Prune/Report (C.param)
  def TtoB = 0.U(3.W); def TtoN = 1.U(3.W); def BtoN = 2.U(3.W)
  def TtoT = 3.U(3.W); def BtoB = 4.U(3.W); def NtoN = 5.U(3.W)
  // D.param on Grant (permissions granted)
  def grantToT = 0.U(2.W); def grantToB = 1.U(2.W); def grantToN = 2.U(2.W)
}

/** Arithmetic/logical atomic opcodes (A.param for Arithmetic/LogicalData, TL-UH). */
object TLAtomics {
  def MIN = 0.U(3.W); def MAX = 1.U(3.W); def MINU = 2.U(3.W); def MAXU = 3.U(3.W); def ADD = 4.U(3.W)
  def XOR = 0.U(3.W); def OR  = 1.U(3.W); def AND  = 2.U(3.W); def SWAP = 3.U(3.W)
}

@LocalSpec(bndTLChannelA)
class TLBundleA(val params: TLLinkParams) extends Bundle {
  val opcode  = UInt(3.W)
  val param   = UInt(3.W)
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W)
  val address = UInt(params.addressBits.W)
  val mask    = UInt(params.maskBits.W)
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool()
}

@LocalSpec(bndTLChannelB)
class TLBundleB(val params: TLLinkParams) extends Bundle {
  val opcode  = UInt(3.W)
  val param   = UInt(3.W)
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W)
  val address = UInt(params.addressBits.W)
  val mask    = UInt(params.maskBits.W)
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool()
}

@LocalSpec(bndTLChannelC)
class TLBundleC(val params: TLLinkParams) extends Bundle {
  val opcode  = UInt(3.W)
  val param   = UInt(3.W)
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W)
  val address = UInt(params.addressBits.W)
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool()
}

@LocalSpec(bndTLChannelD)
class TLBundleD(val params: TLLinkParams) extends Bundle {
  val opcode  = UInt(3.W)
  val param   = UInt(2.W)
  val size    = UInt(params.sizeBits.W)
  val source  = UInt(params.sourceBits.W)
  val sink    = UInt(params.sinkBits.W)
  val denied  = Bool()
  val data    = UInt(params.dataBits.W)
  val corrupt = Bool()
}

@LocalSpec(bndTLChannelE)
class TLBundleE(val params: TLLinkParams) extends Bundle {
  val sink = UInt(params.sinkBits.W)
}

/** One full link, master view: A/C/E outbound, B/D inbound. B/C/E elaborate
  * only on a TL-C link (hasBCE), so a TL-UL/UH master pays no coherence wires. */
@LocalSpec(contTileLink)
class TLBundle(val params: TLLinkParams) extends Bundle {
  val a = Decoupled(new TLBundleA(params))
  val b = if (params.hasBCE) Some(Flipped(Decoupled(new TLBundleB(params)))) else None
  val c = if (params.hasBCE) Some(Decoupled(new TLBundleC(params))) else None
  val d = Flipped(Decoupled(new TLBundleD(params)))
  val e = if (params.hasBCE) Some(Decoupled(new TLBundleE(params))) else None
}
