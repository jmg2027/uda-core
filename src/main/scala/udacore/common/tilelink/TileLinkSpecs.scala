package udacore.common.tilelink

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.spec.ParamsSpecs._

/** TileLink protocol specifications (ADR-016).
  *
  * UDACore's external memory boundary is standard TileLink (SiFive TileLink
  * Specification 1.8.x), replacing the bespoke request/response pairs the
  * rebuild inherited. The full five-channel protocol is specified so a
  * coherent data cache (TL-C Acquire/Probe/Release) can attach later without a
  * boundary change; a link elaborates only the channels its conformance level
  * needs (TL-UL/TL-UH omit B/C/E entirely).
  */
object TileLinkSpecs {

  val contTileLink = spec {
    CONTRACT("TileLink")
      .desc("""TileLink link contract.
              | A point-to-point master/slave link of up to five channels
              | (A,B,C,D,E), each an independent ready/valid dataflow edge in
              | its prioritized direction. UDACore exposes one instruction
              | link and one data link at CoreTop; internal vertices keep the
              | core's own edge bundles and bus adapter vertices translate at
              | the boundary.
              """)
      .has(bndTLChannelA, bndTLChannelB, bndTLChannelC, bndTLChannelD, bndTLChannelE)
      .uses(paramTLLinkParams)
      .is(rawReadyValidIntf)
      .note(
        "Each channel is a Decoupled edge, so TileLink links obey the UDA edge rules " +
        "unchanged (rawTop wires them with :<>=; register slicing per channel is a legal " +
        "performance move because TileLink forbids intra-message combinational dependencies " +
        "between channels)."
      )
      .note(
        "Conformance levels: TL-UL (Get/Put on A/D), TL-UH (+Arithmetic/Logical/Intent, " +
        "multi-beat bursts), TL-C (+Acquire/Probe/Release/Grant on B/C/E for coherent " +
        "caches). hasBCE selects TL-C; burst support is a property of the agents, not the " +
        "bundle shape."
      )
      .build()
  }

  val paramTLLinkParams = spec {
    PARAMETER("TLLinkParams")
      .desc("Per-link geometry: field widths shared by both endpoints of one TileLink link.")
      .is(rawContractParams)
      .markdownTable(
        List("Field", "Meaning", "Typical derivation"),
        List(
          List("addressBits", "Address width on A/B/C", "core pAddrWidth"),
          List("dataBits", "Beat width on A/B/C/D", "xLen, or cache-fill beat width"),
          List("sourceBits", "Master transaction id width (A/B/C/D)", "log2Ceil(outstanding ids)"),
          List("sinkBits", "Slave grant id width (D/E)", "from the attached fabric"),
          List("sizeBits", "log2 transfer-size field width", "log2Ceil(log2Ceil(maxTransferBytes)+1)"),
          List("hasBCE", "TL-C channels present", "true iff a coherent cache is attached")
        )
      )
      .note(
        "ADR-016: xLen-parametric - nothing in the link fixes 32-bit; dataBits follows the " +
        "integrator's datapath/cache geometry."
      )
      .build()
  }

  val bndTLChannelA = spec {
    BUNDLE("TLChannelA")
      .desc("Request channel, master -> slave. Carries Get/Put/Atomic/Intent/Acquire.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("opcode", "UInt(3)", "PutFullData=0, PutPartialData=1, ArithmeticData=2, LogicalData=3, Get=4, Intent=5, AcquireBlock=6, AcquirePerm=7"),
          List("param", "UInt(3)", "Atomic/hint/permission-grow argument"),
          List("size", "UInt(sizeBits)", "log2 bytes of the full transfer"),
          List("source", "UInt(sourceBits)", "Master transaction id"),
          List("address", "UInt(addressBits)", "Byte address, aligned to size"),
          List("mask", "UInt(dataBits/8)", "Active byte lanes of this beat"),
          List("data", "UInt(dataBits)", "Write data beat"),
          List("corrupt", "Bool", "This beat's data is corrupt")
        )
      )
      .uses(paramTLLinkParams)
      .build()
  }

  val bndTLChannelB = spec {
    BUNDLE("TLChannelB")
      .desc("Probe channel, slave -> master (TL-C only). Recalls or queries cached copies.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("opcode", "UInt(3)", "ProbeBlock=6, ProbePerm=7 (forwarded accesses optional)"),
          List("param", "UInt(3)", "Permission cap requested (toT/toB/toN)"),
          List("size", "UInt(sizeBits)", "log2 bytes"),
          List("source", "UInt(sourceBits)", "Target master id"),
          List("address", "UInt(addressBits)", "Byte address"),
          List("mask", "UInt(dataBits/8)", "Active byte lanes"),
          List("data", "UInt(dataBits)", "Data beat (forwarded ops)"),
          List("corrupt", "Bool", "Beat corrupt")
        )
      )
      .uses(paramTLLinkParams)
      .build()
  }

  val bndTLChannelC = spec {
    BUNDLE("TLChannelC")
      .desc("Release/probe-response channel, master -> slave (TL-C only).")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("opcode", "UInt(3)", "AccessAck=0, AccessAckData=1, HintAck=2, ProbeAck=4, ProbeAckData=5, Release=6, ReleaseData=7"),
          List("param", "UInt(3)", "Permission shrink report (TtoB/TtoN/BtoN/...)"),
          List("size", "UInt(sizeBits)", "log2 bytes"),
          List("source", "UInt(sourceBits)", "Master transaction id"),
          List("address", "UInt(addressBits)", "Byte address"),
          List("data", "UInt(dataBits)", "Writeback data beat"),
          List("corrupt", "Bool", "Beat corrupt")
        )
      )
      .uses(paramTLLinkParams)
      .build()
  }

  val bndTLChannelD = spec {
    BUNDLE("TLChannelD")
      .desc("Response channel, slave -> master. Acks accesses and grants coherence permissions.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(
          List("opcode", "UInt(3)", "AccessAck=0, AccessAckData=1, HintAck=2, Grant=4, GrantData=5, ReleaseAck=6"),
          List("param", "UInt(2)", "Permissions granted (toT/toB/toN)"),
          List("size", "UInt(sizeBits)", "log2 bytes, echoed from the request"),
          List("source", "UInt(sourceBits)", "Echoed master transaction id"),
          List("sink", "UInt(sinkBits)", "Slave grant id, returned on E"),
          List("denied", "Bool", "Slave refused the operation (access fault)"),
          List("data", "UInt(dataBits)", "Read/grant data beat"),
          List("corrupt", "Bool", "Beat corrupt (must be high if denied on a data beat)")
        )
      )
      .uses(paramTLLinkParams)
      .note(
        "ADR-016: bus-side faults arrive as denied/corrupt here and are converted by the bus " +
        "adapters into the core's access-fault exception; address-translation faults never " +
        "reach this boundary once the TLB vertex is in-core."
      )
      .build()
  }

  val bndTLChannelE = spec {
    BUNDLE("TLChannelE")
      .desc("Grant-acknowledge channel, master -> slave (TL-C only). Completes an Acquire.")
      .markdownTable(
        List("Name", "Type", "Description"),
        List(List("sink", "UInt(sinkBits)", "Echoed slave grant id"))
      )
      .uses(paramTLLinkParams)
      .build()
  }

  val propTLChannelPriority = spec {
    PROPERTY("TLChannelPriority")
      .desc(
        "Deadlock freedom: channel priority E > D > C > B > A is absolute. An agent must " +
        "never stall a higher-priority channel while waiting on a lower one, and responses " +
        "may not depend combinationally on same-cycle lower-priority traffic."
      )
      .note(
        "This is the property that makes per-channel register slicing legal, and the one a " +
        "future cache's miss-status logic must be checked against (a paired @LocalSpec assert " +
        "lands with the first bus adapter RTL)."
      )
      .build()
  }
}
