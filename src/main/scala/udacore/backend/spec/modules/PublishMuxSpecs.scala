package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._

/** PublishMux: the single result lane (ADR-014 retained for v0 by ADR-019). */
object PublishMuxSpecs {
  val contPublishMux = spec {
    CONTRACT("PublishMux")
      .desc(
        "Arbitrates the execution-unit results and LSQ memory completions onto one publish " +
        "lane per cycle. A published result writes the PRF (when it has a destination), " +
        "broadcasts the wakeup of its prd, and completes its ROB entry, all in one transfer."
      )
      .has(
        intfAluResultIn,
        intfBitAluResultIn,
        intfMultiplierResultIn,
        intfDividerResultIn,
        intfBranchResultIn,
        intfCsrResultIn,
        intfMemResultIn,
        intfPhysicalRegWriteOut,
        intfWakeupBroadcastOut,
        intfRobCompletionOut,
        funcPublishArbitrate,
        funcPublishFanout,
        propSingleDrain
      )
      .uses(paramPublishWidth, funcRobOlder)
      .note(
        "Recovery stance: PublishMux holds no tokens. Every candidate is held on its producer's " +
        "edge, and the producer drops it when funcRecoveryKills selects it, so a killed uop " +
        "never writes the PRF, wakes a consumer, or completes a ROB entry."
      )
      .build()
  }

  val intfAluResultIn = spec {
    INTERFACE("AluResultIn")
      .desc("AluUnit results.")
      .uses(bndFuResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBitAluResultIn = spec {
    INTERFACE("BitAluResultIn")
      .desc("BitAluUnit results (optional extension, absent in v0).")
      .uses(bndFuResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMultiplierResultIn = spec {
    INTERFACE("MultiplierResultIn")
      .desc("MultiplierUnit results.")
      .uses(bndFuResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDividerResultIn = spec {
    INTERFACE("DividerResultIn")
      .desc("DividerUnit results.")
      .uses(bndFuResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBranchResultIn = spec {
    INTERFACE("BranchResultIn")
      .desc("BranchUnit completions (link value and cfiOutcome).")
      .uses(bndFuResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrResultIn = spec {
    INTERFACE("CsrResultIn")
      .desc("CsrController results (old CSR value, or illegal-access exception).")
      .uses(bndCsrResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMemResultIn = spec {
    INTERFACE("MemResultIn")
      .desc("LoadStoreQueue completions (load values, store resolutions, memory exceptions).")
      .uses(bndMemResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPhysicalRegWriteOut = spec {
    INTERFACE("PhysicalRegWriteOut")
      .desc("PRF write of the published value.")
      .uses(bndPhysicalRegWrite)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfWakeupBroadcastOut = spec {
    INTERFACE("WakeupBroadcastOut")
      .desc("Wakeup of the published prd to the ReservationStation and RenameUnit.")
      .uses(bndWakeupBroadcast)
      .is(rawNoDecoupled)
      .note("rawNoDecoupled class 5.")
      .build()
  }

  val intfRobCompletionOut = spec {
    INTERFACE("RobCompletionOut")
      .desc("Completion of the published uop to the ReorderBuffer.")
      .uses(bndRobCompletion)
      .is(rawReadyValidIntf)
      .build()
  }

  val funcPublishArbitrate = spec {
    FUNCTION("PublishArbitrate")
      .desc(
        "Each cycle grant the oldest valid candidate by funcRobOlder; losers stay valid on " +
        "their edges (backpressure, never drop). Oldest-first guarantees the ROB head's result " +
        "is never starved."
      )
      .uses(funcRobOlder)
      .build()
  }

  val funcPublishFanout = spec {
    FUNCTION("PublishFanout")
      .desc(
        "The granted result fires PhysicalRegWriteOut (if wen), WakeupBroadcastOut (if wen), " +
        "and RobCompletionOut in the same cycle; the grant is withheld while a needed " +
        "ready/valid output is not ready."
      )
      .uses(intfPhysicalRegWriteOut, intfWakeupBroadcastOut, intfRobCompletionOut)
      .build()
  }

  val propSingleDrain = spec {
    PROPERTY("SingleDrain")
      .desc("At most one PRF write, one wakeup broadcast, and one ROB completion occur per cycle, always for the same uop.")
      .uses(paramPublishWidth)
      .note("ADR-014 D-14.1 single lane, retained for v0. Simulation assert.")
      .build()
  }
}
