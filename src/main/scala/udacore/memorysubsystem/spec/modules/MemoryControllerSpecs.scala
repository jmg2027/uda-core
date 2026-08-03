package udacore.memorysubsystem.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.memorysubsystem.spec.shared.MemorySubsystemBundlesSpecs._
import udacore.memorysubsystem.spec.shared.MemorySubsystemParamsSpecs.paramLoadOutstanding

/** MemoryController (ADR-003 M4).
  *
  * The request MERGE and external bus master. It multiplexes up to
  * LoadOutstanding reads and the single committed write onto one bus port,
  * enforces NO program order (ordering lives solely in the StoreBuffer), and
  * tags/reunites transactions by txnId.
  */
object MemoryControllerSpecs {
  val contMemoryController = spec {
    CONTRACT("MemoryController")
      .desc(
        "Memory controller merges load reads and the single committed store write onto one external bus port; it enforces no program order and reassociates responses by txnId."
      )
      .has(
        intfReadReqIn,
        intfWriteReqIn,
        intfReadRespOut,
        intfWriteAckOut,
        intfExternalBusReqOut,
        intfExternalBusRespIn
      )
      .note("ADR-003 D-3.12: the MERGE role lives here, not in the MemoryDispatcher (a splitter).")
      .note("ADR-003 M4: FENCE is a commit-gate on StoreBuffer.empty (no external transaction). AMO/LR-SC are reserved command encodings, never emitted in the RV32IMC base.")
      .note(
        "The four internal edges reconcile with the MemorySubsystemTop mermaid: lu -- ReadReq --> con (ReadReqIn), sb -- WriteReq --> con (WriteReqIn), con -- ReadResp --> lu (ReadRespOut), con -- WriteAck --> sb (WriteAckOut). The external bus pair carries the merged request out and the response in."
      )
      .build()
  }

  // lu -- ReadReq --> con: read request from the LoadUnit.
  val intfReadReqIn = spec {
    INTERFACE("ReadReqIn")
      .desc("Read request from the LoadUnit entering the memory controller.")
      .uses(bndLoadMemoryReq)
      .is(rawReadyValidIntf)
      .build()
  }

  // sb -- WriteReq --> con: the single committed write from the StoreBuffer.
  val intfWriteReqIn = spec {
    INTERFACE("WriteReqIn")
      .desc("Committed write request from the StoreBuffer HEAD entering the memory controller.")
      .uses(bndControllerReq)
      .is(rawReadyValidIntf)
      .build()
  }

  // con -- ReadResp --> lu: read response reassociated by txnId back to the LoadUnit.
  val intfReadRespOut = spec {
    INTERFACE("ReadRespOut")
      .desc("Read response leaving the memory controller toward the LoadUnit, reassociated by txnId.")
      .uses(bndLoadMemoryResp)
      .is(rawReadyValidIntf)
      .build()
  }

  // con -- WriteAck --> sb: write acknowledgement that frees the StoreBuffer HEAD.
  val intfWriteAckOut = spec {
    INTERFACE("WriteAckOut")
      .desc("Write acknowledgement leaving the memory controller toward the StoreBuffer for the drained committed store.")
      .uses(bndWriteAck)
      .is(rawReadyValidIntf)
      .build()
  }

  // con ---> edmreq: merged request onto the single external bus port.
  val intfExternalBusReqOut = spec {
    INTERFACE("ExternalBusReqOut")
      .desc("Merged request packet driven onto the single external memory bus port.")
      .uses(bndControllerReq)
      .is(rawReadyValidIntf)
      .build()
  }

  // edmresp ---> con: response from the external bus, tagged by txnId.
  val intfExternalBusRespIn = spec {
    INTERFACE("ExternalBusRespIn")
      .desc("Response packet from the external memory bus port, tagged by txnId.")
      .uses(bndControllerResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcRequestArbiter = spec {
    FUNCTION("RequestArbiter")
      .desc(
        "Multiplexes up to LoadOutstanding read requests plus at most one committed store write onto the single bus port, enforcing NO program order; reads fill the bus slots the single committed write does not (ADR-003 M4, D-3.12)."
      )
      .uses(intfReadReqIn, intfWriteReqIn, intfExternalBusReqOut, paramLoadOutstanding)
      .note("Read/write overlap is safe by construction: a committed-but-unacked store is still in the buffer, so a concurrent younger load still forwards from it.")
      .build()
  }

  val funcTxnIdTagging = spec {
    FUNCTION("TxnIdTagging")
      .desc(
        "Assigns a txnId (width ceil(log2(LoadOutstanding))) to each outstanding read and reunites the response with its waiting load by that tag; stores use a reserved single tag (ADR-003 D-3.13)."
      )
      .uses(paramLoadOutstanding)
      .note("At LoadOutstanding=1 the txnId width is 0 and the tagging logic folds away.")
      .build()
  }

  val propOutstandingBound = spec {
    PROPERTY("OutstandingBound")
      .desc(
        "At most LoadOutstanding read requests and at most ONE write are outstanding on the external bus at any time (ADR-003 M4)."
      )
      .uses(paramLoadOutstanding, intfReadReqIn, intfWriteReqIn)
      .note("Paired with an @LocalSpec design assert bounding in-flight reads by LoadOutstanding and writes by 1.")
      .build()
  }
}
