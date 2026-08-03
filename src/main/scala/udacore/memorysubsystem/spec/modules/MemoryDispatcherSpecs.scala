package udacore.memorysubsystem.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.memorysubsystem.spec.shared.MemorySubsystemBundlesSpecs._
import udacore.backend.spec.shared.BackendBundlesSpecs.bndMemoryOpReq

/** MemoryDispatcher (ADR-003 M3, D-3.12).
  *
  * A SPLITTER: one incoming MemoryOpReq is routed to the load path OR the store
  * path by the op's load/store attribute. The MERGE role belongs to the
  * MemoryController's request arbiter, not here. This resolves the direction
  * contradiction between the old merge-flavored CONTRACT and the splitter top
  * mermaid.
  */
object MemoryDispatcherSpecs {
  val contMemoryDispatcher = spec {
    CONTRACT("MemoryDispatcher")
      .desc(
        "Splits one incoming MemoryOpReq into a load-path token or a store-path token by the op's load/store attribute."
      )
      .has(
        intfMemOpIn,
        intfLoadOut,
        intfStoreOut
      )
      .note("ADR-003 D-3.12: splitter, not merger. Exactly one of load/store fires per input; asserts not(isLoad && isStore).")
      .note("Backpressure: memOpIn.ready = (isLoad ? loadOut.ready : storeOut.ready).")
      .build()
  }

  val intfMemOpIn = spec {
    INTERFACE("DispatcherMemOpIn")
      .desc("Unified memory op from the AGU/backend.")
      .uses(bndMemoryOpReq)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfLoadOut = spec {
    INTERFACE("DispatcherLoadOut")
      .desc("Routed load-path token to the LoadUnit.")
      .uses(bndDispatcherLoad)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfStoreOut = spec {
    INTERFACE("DispatcherStoreOut")
      .desc("Routed store-path token to the StoreUnit.")
      .uses(bndDispatcherStore)
      .is(rawReadyValidIntf)
      .build()
  }

  val propSplitExclusive = spec {
    PROPERTY("SplitExclusive")
      .desc(
        "The dispatcher never asserts both loadOut.valid and storeOut.valid for the same input; exactly one path is selected by the load/store bit (ADR-003 D-3.12)."
      )
      .uses(intfMemOpIn, intfLoadOut, intfStoreOut)
      .note("Paired with an @LocalSpec design assert !(loadOut.valid && storeOut.valid).")
      .build()
  }
}
