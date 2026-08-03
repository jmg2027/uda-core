package udacore.core.spec.modules

import framework.macros.SpecEmit.spec
import framework.specs.Spec._
import udacore.common.spec.DesignRuleSpecs._
import udacore.common.tilelink.TileLinkSpecs._
import udacore.core.spec.shared.CoreParamsSpecs._

/** Data cache vertex specifications (ADR-016, capCacheHierarchy).
  *
  * Spec-only for now: the vertex elaborates when CoreContractParams.dcache is
  * Some(CacheParams), splicing into the DataMemReq/DataMemResp edges between
  * MemorySubsystemTop and the DataBusAdapter at CoreTop level. Configuring it
  * flips the data TileLink link to TL-C (hasBCE): the cache owns lines
  * coherently via Acquire/Probe/Release.
  *
  * The ADR-003 ordering architecture is unchanged: the StoreBuffer above this
  * vertex remains the sole ordering authority and only COMMITTED stores ever
  * reach the cache, which is what makes every byte of cache state
  * architectural (epoch-exempt) by construction.
  */
object DataCacheSpecs {

  val contDataCache = spec {
    CONTRACT("DataCache")
      .desc("""Data cache vertex.
              | An optional write-back, coherent data cache below the memory
              | subsystem. Serves committed loads and stores from its array,
              | acquires lines with the needed permissions on miss, writes
              | back dirty victims as Releases, and services external Probes
              | - all through the DataBusAdapter's TL-C link.
              """)
      .has(
        intfDCacheCoreReqIn,
        intfDCacheCoreRespOut,
        intfDCacheAcquireOut,
        intfDCacheGrantIn,
        intfDCacheReleaseOut,
        intfDCacheProbeIn,
        funcDCacheLookup,
        funcDCacheMissAcquire,
        funcDCacheWritebackRelease,
        funcDCacheProbeService,
        propDCacheCommittedOnly,
        propDCacheProbeLiveness,
        propDCachePermissionSound,
        propDCacheFunctionTransparent
      )
      .uses(paramCacheGeometry, contTileLink, propTLChannelPriority)
      .draw(
        "mermaid",
        """
      | graph LR
      |     corereq@{shape: text, label: DCacheCoreReqIn}
      |     coreresp@{shape: text, label: DCacheCoreRespOut}
      |     acq@{shape: text, label: DCacheAcquireOut}
      |     gnt@{shape: text, label: DCacheGrantIn}
      |     rel@{shape: text, label: DCacheReleaseOut}
      |     prb@{shape: text, label: DCacheProbeIn}
      |
      |     subgraph DataCache
      |         direction LR
      |         lookup[TagDataLookup]
      |         mshr[MissEngine]
      |         wb[WritebackEngine]
      |         snoop[ProbeEngine]
      |         lookup -- MissAcquire --> mshr
      |         mshr -- Refill --> lookup
      |         lookup -- DirtyVictim --> wb
      |         snoop -- PermDowngrade --> lookup
      |         snoop -- ProbeData --> wb
      |     end
      |
      |     corereq --> lookup
      |     lookup --> coreresp
      |     mshr --> acq
      |     gnt --> mshr
      |     wb --> rel
      |     prb --> snoop
      """
      )
      .note(
        "Placement: a CoreTop-level vertex between MemorySubsystemTop and DataBusAdapter " +
        "(funcExternalMemoryBridge). With a DTLB configured, translation happens upstream, " +
        "so the array is physically indexed/tagged at the base point. The PTW (a " +
        "capAddressTranslation element) issues its walks as ordinary read tokens on the " +
        "same core-request edge, so walks are cached like any other read."
      )
      .note(
        "Atomics (A-extension) accommodation: AMOs execute in the cache on owned (T) lines " +
        "- the seam is funcDCacheLookup; no boundary change. Uncacheable/MMIO regions " +
        "bypass to the adapter unchanged (a PMA check upstream of the lookup)."
      )
      .build()
  }

  val intfDCacheCoreReqIn = spec {
    INTERFACE("DCacheCoreReqIn")
      .desc(
        "Committed memory operation edge in (the same ExternalDataMemoryReq bundle " +
        "MemorySubsystemTop emits: loads from LoadUnit misses of the StoreBuffer, " +
        "committed-store drains, PTW walks)."
      )
      .is(rawReadyValidIntf)
      .note("Backpressure is the only stall mechanism; no side-band stall or kill wires.")
      .build()
  }

  val intfDCacheCoreRespOut = spec {
    INTERFACE("DCacheCoreRespOut")
      .desc(
        "Response edge out (the same ExternalDataMemoryResp bundle), reunited by txnId for " +
        "loads and by seqTag for store completions (ADR-003 D-3.13)."
      )
      .is(rawReadyValidIntf)
      .build()
  }

  val intfDCacheAcquireOut = spec {
    INTERFACE("DCacheAcquireOut")
      .desc(
        "Miss/upgrade request edge toward the adapter: TileLink channel-A AcquireBlock/" +
        "AcquirePerm with the permission-grow param (NtoB for a read, NtoT/BtoT for a write)."
      )
      .is(rawReadyValidIntf)
      .uses(contTileLink)
      .build()
  }

  val intfDCacheGrantIn = spec {
    INTERFACE("DCacheGrantIn")
      .desc(
        "Grant edge back (channel-D Grant/GrantData beats plus the sink id the cache " +
        "acknowledges on channel E via the adapter)."
      )
      .is(rawReadyValidIntf)
      .uses(contTileLink)
      .build()
  }

  val intfDCacheReleaseOut = spec {
    INTERFACE("DCacheReleaseOut")
      .desc(
        "Writeback/permission-shrink edge (channel-C Release/ReleaseData for dirty victims " +
        "and ProbeAck/ProbeAckData answering Probes)."
      )
      .is(rawReadyValidIntf)
      .uses(contTileLink)
      .build()
  }

  val intfDCacheProbeIn = spec {
    INTERFACE("DCacheProbeIn")
      .desc("External coherence probe edge in (channel-B ProbeBlock/ProbePerm).")
      .is(rawReadyValidIntf)
      .uses(contTileLink)
      .build()
  }

  val funcDCacheLookup = spec {
    FUNCTION("DCacheLookup")
      .desc(
        "Tag/data/permission lookup: a load hits on >=Branch (shared) permission, a store " +
        "hits on Trunk (owned) permission; hits answer on DCacheCoreRespOut. Insufficient " +
        "permission is a miss (upgrade). Base point: blocking, one outstanding miss; " +
        "outstanding-miss depth is a tuning parameter that must not change function."
      )
      .build()
  }

  val funcDCacheMissAcquire = spec {
    FUNCTION("DCacheMissAcquire")
      .desc(
        "Miss service: Acquire the block with the needed grow-permission, collect Grant " +
        "beats, install line+permission, GrantAck on channel E, replay the missing request. " +
        "A dirty victim is handed to the writeback engine before install."
      )
      .build()
  }

  val funcDCacheWritebackRelease = spec {
    FUNCTION("DCacheWritebackRelease")
      .desc(
        "Writeback: dirty victims leave as ReleaseData (TtoN), clean victims as Release " +
        "permission-shrink; the slave's ReleaseAck (channel D) retires the writeback. " +
        "Victim selection is per-set replacement policy (private parameter)."
      )
      .build()
  }

  val funcDCacheProbeService = spec {
    FUNCTION("DCacheProbeService")
      .desc(
        "Probe service: a channel-B Probe downgrades the line to the capped permission, " +
        "answering ProbeAck (clean) or ProbeAckData (dirty) on channel C, regardless of " +
        "whether the core-side request stream is stalled."
      )
      .build()
  }

  val propDCacheCommittedOnly = spec {
    PROPERTY("DCacheCommittedOnly")
      .desc(
        "Only committed effects reach this vertex: speculative stores are held in the " +
        "StoreBuffer above (ADR-003) and speculative loads that get epoch-killed are " +
        "dropped upstream. Therefore every line, dirty bit, and permission here is " +
        "architectural state - the cache is epoch-exempt by construction and carries no " +
        "epoch field."
      )
      .note("The redirect/epoch mechanism never touches cache state; this is what keeps " +
            "coherence sound under the single global epoch (ADR-011).")
      .build()
  }

  val propDCacheProbeLiveness = spec {
    PROPERTY("DCacheProbeLiveness")
      .desc(
        "Probe service never waits on core-initiated forward progress: B-channel work " +
        "depends only on the array and the C channel, per the TileLink priority rule " +
        "(E > D > C > B > A). A probe arriving while a miss is outstanding to the same " +
        "line is answered per the granted-permission state, not deferred indefinitely."
      )
      .uses(propTLChannelPriority)
      .note("Paired design assert lands with the vertex RTL (ADR-015 D-15.2).")
      .build()
  }

  val propDCachePermissionSound = spec {
    PROPERTY("DCachePermissionSound")
      .desc(
        "No access exceeds granted permission: reads require >=Branch, writes require " +
        "Trunk, and every permission transition on a line is one of the legal TileLink " +
        "grow/cap/shrink transitions. The dirty bit implies Trunk."
      )
      .build()
  }

  val propDCacheFunctionTransparent = spec {
    PROPERTY("DCacheFunctionTransparent")
      .desc(
        "With dcache=None and dcache=Some the single-hart core is function-equivalent " +
        "(same retire stream for the same program); the cache may only change timing. " +
        "Acceptance: retire-stream equivalence across the two configs, plus the " +
        "coherence-visible cases once a second agent exists (out of scope until then, " +
        "OQ-D)."
      )
      .build()
  }
}
