package udacore.backend.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._

import udacore.backend.spec.shared.BackendBundlesSpecs._
import udacore.backend.spec.shared.BackendParamsSpecs._

object PublishMuxSpecs {
  val contPublishMux = spec {
    CONTRACT("PublishMux")
      .desc(
        "Result bus: arbitrates FU results, writes physical register file, broadcasts wakeup."
      )
      .has(
        intfAluResultIn,
        intfBitAluResultIn,
        intfMultiplierResultIn,
        intfDividerResultIn,
        intfBranchUnitResultIn,
        intfCsrResultIn,
        intfMemoryOpRespIn,
        intfPhysicalRegWriteOut,
        intfWakeupBroadcastOut,
        intfPublishResultOut,
        propSingleDrain
      )
      .note("Unified result collection point for all functional units")
      .note("Writes result to physical register file")
      .note("Broadcasts (prd, valid) to reservation station for wakeup")
      .note("Forwards result to commit unit for retirement tracking")
      .note("Base build: ONE arbitrated publish/result bus feeding ONE PRF write port; lane count = 1, independent of SpeculativeRegNum (ADR-014 D-14.1).")
      .note("The publish->PRF-write edge carries real back-pressure: the always-ready claim is STRUCK; a contending producer that is not granted stalls, not drops (ADR-014 D-14.2).")
      .note("Multi-lane publish (peak IPC > 1) is a proposed extension gated on measured need (ADR-014 D-14.4).")
      .build()
  }

  // ADR-014 D-14.1/D-14.2: single-drain base contract.
  val propSingleDrain = spec {
    PROPERTY("SingleDrain")
      .desc("In the base build there is at most one PRF write, one wakeup broadcast, and one retire per cycle.")
      .note("peakIssue = peakPublish = peakRetire = 1 at every N; N=32 is an MLP/latency-hiding machine, not IPC>1 (ADR-014 D-14.1). Lane count references SpeculativeRegNum only to assert independence: lanes = 1 regardless of N.")
      .note("Pair with a design assert; also assert the publish->PRF-write ready is a real signal, not tied high (ADR-014 D-14.2, ADR-015 D-15.3).")
      .uses(paramSpeculativeRegNum)
      .build()
  }

  val intfAluResultIn = spec {
    INTERFACE("ALUResultIn")
      .desc("Integer ALU result input.")
      .uses(bndAluResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBitAluResultIn = spec {
    INTERFACE("BitALUResultIn")
      .desc("Bit ALU result input.")
      .uses(bndBitAluResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMultiplierResultIn = spec {
    INTERFACE("MultiplierResultIn")
      .desc("Multiplier result input.")
      .uses(bndMultiplierResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDividerResultIn = spec {
    INTERFACE("DividerResultIn")
      .desc("Divider result input.")
      .uses(bndDividerResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfBranchUnitResultIn = spec {
    INTERFACE("BranchUnitResultIn")
      .desc("Branch resolution result input.")
      .uses(bndBranchUnitResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfCsrResultIn = spec {
    INTERFACE("CSRResultIn")
      .desc("CSR execution result input.")
      .uses(bndCsrResult)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfMemoryOpRespIn = spec {
    INTERFACE("MemoryOpRespIn")
      .desc("Memory response input.")
      .uses(bndMemoryOpResp)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfPhysicalRegWriteOut = spec {
    INTERFACE("PhysicalRegWriteOut")
      .desc("Physical register write command output.")
      .uses(bndPhysicalRegWrite)
      .is(rawReadyValidIntf)
      .build()
  }

  val intfWakeupBroadcastOut = spec {
    INTERFACE("WakeupBroadcastOut")
      .desc("Wakeup broadcast output for dependency resolution.")
      .uses(bndWakeupBroadcast)
      .is(rawNoDecoupled)
      .note(
        "Sanctioned broadcast class (rawNoDecoupled note 5, ADR-014): a wakeup is a "+
        "non-negotiable published fact riding the result publish; consumers epoch-qualify, "+
        "never backpressure."
      )
      .build()
  }

  val intfPublishResultOut = spec {
    INTERFACE("PublishResultOut")
      .desc("Aggregated result output to commit unit.")
      .uses(bndPublishResult)
      .is(rawReadyValidIntf)
      .build()
  }
}
