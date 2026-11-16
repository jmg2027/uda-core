# Handoff: runnable OoO core and SRAM caches (2026-11-16)

This is the live implementation summary. Earlier session records are preserved in
[the handoff archive](archive/HANDOFF-2026-11-16-history.md); their branch names,
pending tasks, and test counts are historical. Use git for current integration state.

## Goal

Maintain a personal, conventional out-of-order RISC-V core under the Unified
Dataflow Architecture discipline. The binding v0 architecture is
[ADR-019](adr/ADR-019-conventional-ooo-root-architecture.md) and its amendments;
[ADR-020](adr/ADR-020-cache-sram-storage.md) defines cache data storage.

## Current implementation

- The v0 target is RV32IM_Zicsr_Zifencei + Svade, M/S/U privilege, Sv32,
  fixed 32-bit instructions, and two TileLink master links. This repository
  is independent of the historical company KLASE32 main line.
- FrontendTop and CoreTop connect the predictor/FTQ/fetch pipeline, backend,
  ITLB/DTLB/shared PTW, I/D caches, and bus adapters. CoreHarness executes
  whole-core assembly scenarios through both TileLink links and observes
  the architectural retire stream.
- The backend has a data-less ROB, sRAT/rRAT/free list, PRF, RS, LSQ,
  out-of-order issue/completion, in-order retirement, and selective
  RecoveryEvent recovery that preserves older correct-path work.
- Bulk I/D cache data is clocked CacheLineSram storage: 16 OpenRAM
  sky130_sram_1kbyte_1rw1r_32x256_8 macros per cache, 32 total, for 32 KiB.
  Tags, valid/dirty/replacement state, MSHRs, and bounded transaction holders
  remain registers. Native byte masks implement committed partial stores.
- CoreTraceTop provides simulation-only per-cycle fetch-lane, retire,
  recovery, divider-busy, and ALU-publication observations. Product CoreTop
  synthesis excludes Trace and retire instrumentation.
- The simulator cache uses model-content hashes, per-entry build/publication
  leases, completed-entry-only pruning, and private executable snapshots.
  Concurrent daemon/direct runs retain independent binaries and logs.

## Repository cleanup

The live README, agent entry point, documentation index, and this handoff now
separate current implementation status from methodology and historical records.
The archived handoff retains the detailed red-first implementation chronology,
resolved architecture issues, earlier results, and old environment workarounds.
Unused Debug APIs, redundant defaults, obsolete scratch notes, and non-ASCII
document text were removed. Protected files and the external framework were
kept unchanged. The pre-commit hook now checks staged bytes, including renames,
without relying on GNU grep. CLI results use stdout; elaboration diagnostics
are preserved on stderr and Java exit codes propagate directly.
`python3 verif/bin/test-cli.py` preserves these regression controls. Independent
cold-initialization and exception probes verified output-stream restoration.

## Validation status

Build, spec-check, active L1, scenarios, cache controls, framework controls, and
SRAM model comparison were rerun during the 2026-11-16 cleanup. The full STA run
is carried forward with byte-identical synthesis-input verification. Generated
evidence under `verif/out/` is local and gitignored; reproduce it with the commands in
[the verification guide](../verif/README.md).

| Check | Latest recorded result |
| --- | --- |
| Direct build and spec export | PASS; 699 specs, 610 source tags |
| Spec-check | 0 errors; 4 existing LocalSpec coverage warnings |
| Active L1 SpecTests | 376 PASS, 0 FAIL, 0 PENDING |
| Whole-core scenarios | All 23 PASS |
| SRAM/vendor model comparison | 9217 cycles, 26112 defined read checks PASS in 4-state Icarus |
| Simulator cache controls | CacheSafetyCheck 8 PASS; CacheBlackBoxCheck 6 PASS |
| Framework integration controls | All 4 groups PASS, including compiler-failure propagation |
| CLI regression | 8 PASS: run, batch, trace, gate, describe, check failure, Java failure, emission |
| Formatting | 208 existing unprotected Scala/build files and the new diagnostics helper PASS |
| Product synthesis/STA | Completed; exactly 32 SRAM macros retained; setup timing fails |
| Required sbt gate (cleanup session) | FAIL; formatting and protected historical-test parser errors |

The cleanup session attempted `sbt scalafmtCheckAll test`; formatting mismatches
and the invalid `TestCluster(params)` type syntax at protected
`src/test/scala/cluster/RV32BTest.scala:22` blocked completion. A separate
`sbt test` reported three parser errors in the same source and ran zero tests.
Logs: `verif/out/cleanup/sbt-gate-final.log` and `verif/out/cleanup/sbt-test-final.log`.
No protected test files were changed for those attempts.

The SRAM checks cover masks, backpressure, page boundaries, collision arbitration,
and sustained responses. A red-first D-cache test reproduced stale completed-MSHR
copies after a partial store; the corrected visibility test passes. Model comparison
includes next-rising-edge consumer capture and excludes undefined same-row macro
collisions, which the wrapper serializes. Evidence is in `verif/out/sram/`.

The cleanup session also reran the vendor comparison through the tracked
`bash verif/bin/test-sram-model.sh` entry point: 9217 cycles and 26112 read
checks PASS in Icarus 4-state simulation. It extracts the current
CacheLineSram.scala inline model on every run and verifies the vendor checksum.
The vendor adaptation only moves its memory declaration before its first
reference and adds a 1ns/1ps timescale; DELAY=3 and T_HOLD=1 remain intact.
Generated models/results are in `verif/out/sram-model/`, not tracked model copies.
Negative controls rejected a corrupt vendor source and detected a mutated
functional read output. The wrapper sources the repo toolchain environment while
preserving explicit VERIF_STA_HOME/VERIF_SRAM_HOME overrides; default STA home is
the persistent sibling `../.uda-toolchain/sta`. Provision missing vendor views
with `setup-sram.sh` first.

### Direct whole-core OoO evidence

The tracked [OoO scenario](../verif/scn/ooo_issue_overlap.scn) and
`trace.oooIssueOverlap` L1 test use a fence, a long DIV,
an older DIV-dependent ADD, a younger independent ADD, and another fence.
The first fence retires at cycle 59. DIV is busy during cycles 62-93, while
ALU publication occurs at cycle 64. Fence boundaries exclude other ALU
producers and the dependent ADD cannot yet execute, identifying the younger
independent ADD as the overlapping result. Retirement stays ordered: DIV
at 95, dependent ADD at 97, independent ADD at 98 in the recorded default
Trace. All three result checks pass; completion is observed at cycle 145.
The L1 regression checks fence isolation and temporal order without hard-coded
cycle numbers. Original raw observations remain in `verif/out/ooo-audit/`.

This establishes a directed functional OoO case in RTL simulation. Broad ISA-model
differential coverage, representative-workload IPC, CoreMark/Dhrystone, speedup
over an in-order baseline, and FPGA/silicon operating frequency remain unmeasured.
Image words divided by harness cycles are not an IPC measurement: boot, fetch
misses, result stores, and completion code are included in those cycles.

### Whole-core STA

Latest SRAM report: `verif/out/sta/core_default_sram/`. The older flop-only
comparison remains in `verif/out/sta/core_default/`. The cleanup re-emitted the
product with pinned Espresso and ran `sv2v -DSYNTHESIS`: all 46 modules are
byte-identical to the measured synthesis input (SHA256
`087c83bb93d97150cef9e97c2162a6dba4793b971061b74655969ab1f7851516`).
See `verif/out/cleanup/espresso-product-comparison.json`. STA was not rerun
for formatting or diagnostics changes.

- Conditions: sky130 HD TT 25 C / 1.80 V, 10 ns clock, Small statistical wire
  load, ideal clock/reset, 6 ns TileLink IO delay, 3 ns other IO delay,
  and 0.05 pF output load.
- Setup WNS: -99.384 ns; worst hold slack: +0.407 ns. Analysis completion
  and a zero exit code do not mean the 10 ns target is met.
- 376949 standard cells, including 66294 flops; standard-cell area
  3420701.974408 um2 plus SRAM macro area 6102801.6 um2, totaling
  9523503.574408 um2 of mapped footprint.
- The remaining worst launch is the D-cache tag read-address register
  (fanout 5632). `critical-source.json` records netlist/source correspondence.
  DRV pin counts are slew 202235, fanout 1924, and capacitance 576.
- `sram-timing.txt` includes macro clock arcs: read-data-to-core setup
  +3.424 ns, core-to-SRAM write-data setup -15.910 ns, read-address setup
  -94.838 ns, and SRAM data hold +5.455 ns. Macro paths are timed.

The SRAM Liberty uses analytical Elmore timing without PEX. There is no
placement, CTS, routing, or extracted parasitic model. These reports establish
neither physical Fmax nor final die area, and no PPA acceptance bar was chosen.

## Open questions needing the OWNER

- OQ-C: ADR-019 supersedes the ADR-008 N=1 PPA bar. Confirm what, if any,
  PPA acceptance bar the v0 reference point should meet.
- OQ-H: the protected assembler lacks SFENCE.VMA and raw `.word` support
  for `.scn`. Permission to add the mnemonic or directive to
  `src/main/scala/assembler` remains unresolved.
- OQ-I: confirm the ADR-015 D-15.4/D-15.5 interpretation recorded in
  ADR-000: ISA-model equivalence and one-axis configurations without N;
  alternatively, record an ADR amendment.
- RV64 decode scheduling remains an owner decision; v0 is RV32 only.
- Protected historical verification: permission to repair the cluster/assembler
  suites was requested during cleanup and has not been granted. Keep the parser
  failure visible until the owner authorizes that migration. Local core-logic
  protection markers, including the multiplier, also remain in force.

Standing resolved decisions: OQ-D is single-hart, non-coherent, no DMA.
OQ-E waived the former CSR.scala protection for the ADR-019 migration.
OQ-G is resolved: uncacheable accesses run at the ROB head under HeadMemGrant;
cacheable PMA regions are fill/writeback-fault-free by platform contract.
Reconfirm that platform assumption when selecting the SoC memory map.
No architecture contradictions remain open in this handoff.

## Next steps

Resolve the protected historical sbt test migration after owner authorization;
do not remove tests or exclude their source directories to obtain a pass.

1. Investigate setup/DRV paths using the SRAM reports. Choose implementation
   changes through the spec-first workflow; a completed STA run is not closure.
2. Add placement, clock-tree/parasitic modeling, and characterized SRAM views
   before drawing physical timing or die-area conclusions.
3. Broaden ISA-model differential testing and vary TileLink response schedules.
   Keep spec-test/assertion allowlist debt visible in the generated reports.
4. Define reproducible benchmark workloads and an in-order comparison before
   claiming IPC, speedup, or practical performance.

## Gotchas

- Both build paths use Scala 2.13.12 and Chisel 6.2.0 with the matching plugin
  and the sibling `../spec-framework` checkout (`SPEC_FRAMEWORK_HOME` override).
  Earlier Chisel 3 instructions are historical. See
  [framework setup](../docs/tooling/spec-framework.md).
- The validated Apple Silicon tuple uses JDK 17, firtool 1.62.0 through Rosetta,
  native Verilator 5.020, and MacOSX26.5.sdk. Verilator 5.052 produced duplicate
  Ready protocol messages; MacOSX27.0.sdk failed to link in the recorded setup.
  Pinned Espresso is also required for reproducible decode minimization; its
  absence triggers a fallback and a multiline PATH diagnostic. Icarus provides
  the four-state SRAM comparison. The setup script provisions both.
  Resolved machine paths belong in ignored `verif/.toolchain.env`.
- Historical Coursier downloads failed with GitHub 403/proxy restrictions.
  The archive retains the old `/tmp`/Maven workaround. Reprovision missing
  tools with the platform setup script instead of assuming scratch paths persist.
- `sbt scalafmtCheckAll test` remains a required pre-commit attempt. Historical
  runs stopped at formatting/parser or dependency-download failures before
  the chained tests; active L1 results do not establish a full `sbt test` pass.
  Owner-protected cluster/assembler tests target older APIs; report failures
  without changing protected infrastructure or weakening the verification gate.
- svsim runs its test inside the testbench initial block; unreset registers
  read zero in the recorded L1 setup because RANDOMIZE_REG_INIT runs later.
  Those tests do not establish X-propagation correctness.
- A simulation firtool failure may surface only as FirtoolNonZeroExitCode.
  Emit CHIRRTL and invoke firtool directly for diagnostics. Rebuild classes
  after restoring a mutant before trusting results.
- `.scn` accepts `jalr x0, xN, 0`, not `0(xN)`, and decimal load/store offsets.
  Unwritten harness memory reads 0x13; page-table memory needs explicit zeros.
- SRAM outputs invalidate after rising edges; reads execute on falling edges.
  Test through a rising-edge consumer or held response, not the raw output
  before the falling-edge read. The functional view uses one dual-edge process
  per port with NBA invalidation to avoid multiple-driver/capture races.
- SRAM views are pinned by `verif/sta/sram/manifest.json` under
  `../.uda-toolchain/sta/sram/`. Run `bash verif/bin/setup-sram.sh` to provision;
  STA only verifies installed views. sv2v 0.0.13 lowers packed arrays for STA.
- The spec-check pre-commit hook checks the working tree, not only the index.
  Validate the actual staged content before making a partial commit.
