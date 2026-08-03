# Handoff: UDACore identity + TileLink boundary + spec hardening + verified-IP port (2026-07-06)

Branch: `claude/verif-harness-synthesis-porting-ry6spw` (rebuild lineage; 16 commits ahead
of `origin/rebuild`, fast-forward). This file is the durable state for the next session;
the container is ephemeral. Sessions recorded here: the verif/synthesis port (commits
`1fb7ceb4`/`2eece6db`), the identity/boundary rework (`70fd8761`/`97226929`), the CSR
library / cache specs / philosophy review (`af4377c1`/`ec29f7c3`/`ac2ccf10`), ADR-017 +
harness (`ed744db0`/`a1c217ea`), ADR-018 Spec-TDD (`bd83c2e5`), and the verified-IP port
with the multiplier correction (`24dbb9b7`/`9a8b9d3e`/`20fc9fcb`).

## Goal

The rebuild line is a **personal research vehicle for parametric in-order-to-OoO scaling**
(unified PRF, window parameter `SpeculativeRegNum` = N), now fully decoupled from the
company line by owner directive: renamed **UDACore** (package `udacore`), XLEN-parametric,
standard full-TileLink memory boundary, with accommodation seams for caches, TLBs, and the
U/S/H privilege ladder. All of it recorded as **ADR-016** (read it; it amends ADR-003
D-3.14). Method unchanged: spec-first, contracts hardened before shell RTL.

## Done this session

1. `1fb7ceb4`/`2eece6db` - main's verif harness (scn/gate/trace/daemon, CachedSimulator,
 one-compile batching) and the yosys+sky130+OpenSTA synthesis/STA flow ported into
 `verif/`, sbt-free (pinned scalac + chisel 6.2.0 jars). DUT-facing commands answer
 `harness-not-ready` (exit 3) until CoreTop has RTL; the synthesis flow works today on
 the implemented functional units (EmitUnit / sta.sh unit / ppa-unit.sh).
2. `70fd8761` - rename klase32 -> udacore across 172 files (packages, dirs, docs, verif,
 `UDACoreElab`). Repo URL and references to the company line (KLASE32) kept.
3. `97226929` - ADR-016 implemented spec-first:
 - `common/tilelink/`: standard TileLink 1.8.x five-channel spec + bundles
   (TLLinkParams/TLBundle, hasBCE gates B/C/E; channel-priority PROPERTY).
 - CoreTop boundary: the four external memory req/resp interfaces replaced by two
   TileLink master links `instBus`/`dataBus`; internal ProgMem/DataMem edges kept, with
   CoreTop-owned InstBusAdapter/DataBusAdapter vertices specified as the translators
   (cache/TLB vertices later splice into the internal edges; boundary never changes).
 - CoreParams: XLEN in {32,64} (require), PrivilegeParams (U/S/H ladder with legality
   requires), Option[CacheParams] icache/dcache, Option[TlbParams] itlb/dtlb (TCM point =
   all None), single link-geometry derivation (instBusParams/dataBusParams; dcache flips
   the data link to TL-C).
 - MemorySubsystem: `memOpWidth == 32` fixed require relaxed to XLEN-following
   (ADR-003 D-3.14 amended); preset `rv32Tcm` -> `tcm(xLen)`.
 - CAPABILITY specs (PrivilegeModes / AddressTranslation / CacheHierarchy) on CoreTop;
   stub Spec DSL gained the missing CAPABILITY category.
 - verif: harness binding contract now specifies TileLink channel service (A->D Gets,
   Put-to-DONE sentinel, B/C/E tied off until coherent); STA SDC memory-boundary glob is
   `io_*Bus_*`.

4. `af4377c1`/`ec29f7c3`/`ac2ccf10` - follow-on owner directives:
 - main's CSR decorator library ported spec-first to `common/system/csr/`
   (Csr/Mirror/Shadow/Indirect/Counter/Custom + CsrAccess on the rebuild's
   CSRControl enum, no rocket Parameters). Mechanism only; ownership stays with
   TrapController (ADR-004). The protected backend `csr/CSR.scala` and the thin
   CSRTemplateBase are untouched - migration happens with the TrapController RTL.
 - Cache system vertex contracts written: `core/spec/modules/InstructionCacheSpecs.scala`
   (read-only, TL-UH fill, fence.i token, epoch-blind) and `DataCacheSpecs.scala`
   (TL-C coherent, committed-only-by-construction, probe liveness bound to the TL
   channel-priority property, function-transparency acceptance). New PROPERTYs are
   allowlisted per ADR-015 until their paired asserts land with RTL.
 - Full UDA-philosophy spec review (subagent, 12 findings): doctrine now enumerates
   the sanctioned rawNoDecoupled classes (epoch / async inputs / boot statics /
   commit-broadcast strobes / wakeup broadcast) and the FACT-vs-TRANSFER projection
   rule; external-IP kill ports documented as tied-inactive under the no-flush
   doctrine; BackendTop/MemorySubsystemTop contract descriptions written; ~40
   backend/memory interfaces got explicit .is(rawReadyValidIntf); owned members
   moved .uses -> .has; PRF epoch stance corrected to epoch-free-by-construction;
   Divider sub-core CONTRACTs demoted to RAW subcore. Accepted deviations: section
   ordering, doubled In/Out interface names, CoreTop's integration FUNCTION list.

5. `ed744db0`/`a1c217ea` - Feature-pattern import + agent harness:
 - ADR-017 (extension contribution): extensions are optional vertices + data
   contributions (decode rows absent-when-disabled -> illegal trap, the F-3 lesson as
   propDisabledExtensionTraps; CSR Map[Int,Csr] entries into the one readFromCsr map).
   Feature-style host-signal weaving, side-effect hardware construction, and `def`
   feature handles are forbidden (doctrine: DesignRuleSpecs.rawExtensionContribution).
 - Agent harness rebuilt for this branch: CLAUDE.md rewritten (authority order, current
   shell-vs-implemented state, scalac-gate commands, UDA one-screen rules), .claude
   session hook (toolchain + githooks + readiness line), .githooks/pre-commit
   (staged-ASCII with grandfathered-debt allowlist + spec-check gate), four skills
   (verif / spec-first / ppa / handoff).

6. `ADR-018 Spec-TDD` (this session) - every FUNCTION/PROPERTY spec val now carries a
 test obligation, mechanically enforced:
 - Test ladder L0-L3 (elaboration assert / vertex SpecTest / .scn scenario / config
   equivalence); red-before-green with PENDING as the sanctioned shell-era color;
   named adversarial test-review step; XFAIL/XPASS known-bug mechanism.
 - Infrastructure: `verif.spectest` L1 framework (+ reset-applying sim()), `.scn`
   `@verifies` directive (carried into run JSON), spec-check check 6
   `spec-test-coverage` + `tools/spec-test-allow.txt` (seeded 125, shrink-to-zero),
   runner `verif/bin/run.sh verif.spectest.RunSpecTests`.
 - First conforming tests are green: alu.compute, multiplier.csa16/csa32,
   divider.nrclz/restoring, csr.library.

7. `24dbb9b7`/`9a8b9d3e`/`20fc9fcb` - verified functional-unit IP port + multiplier
 correction (owner directive: origin/main's Multiplier/Divider are the VERIFIED RTL):
 - Divider: main's verified restoring + non-restoring cores replace the rebuild's
   refactored copies, fixing a real REGRESSION (F-10/F-11 remainder-width bug the rebuild
   reintroduced). `divider.nrclz` and `divider.restoring` PASS.
 - Multiplier: the actually-fixed core lives in main's `klase32_rvv_coprocessor/` subtree
   (not `functionalunit/`). Ported it - abstract `MulCore` control skeleton +
   `IterativeAdderCore` accumulator + `SliceMultiplier` - replacing the functionalunit
   port and a hand-patched `&&`. Root causes fixed: (a) `SliceMultiplier` exposes the FULL
   untruncated signed product `pFull` (was truncated to 2*width, losing the sign of a large
   unsigned slice product), and `alignedPartialProducts()` sign-extends it before a plain
   carry-propagate fold (no `MulTables`, now deleted); (b) `earlyOutBothHalfZero` requires
   BOTH upper halves zero, gated to segmentCount <= 2 (was `||`). Both configs pass -
   `multiplier.csa32` (production 32,1) and `multiplier.csa16` (16,1). **UDA-M1 resolved.**
 - Process correction carried: earlier over-claims (UDA-F1/F2 as owner-gated findings)
   violated the Gate discipline; both were rebuild regressions, fixed by the verified port.

## Verified-IP port + corrected findings (this session)

The owner directed that the two functional-unit IPs (Multiplier, Divider) in origin/main
are the VERIFIED RTL and must be brought into this branch. Done: main's verified Divider
(both cores) and Multiplier core replace the rebuild's refactored copies
(`external/{divider,multiplier}/design`), keeping the rebuild's top IO / params /
@LocalSpec surface. This CORRECTS my earlier over-claims (UDA-F1/F2), which violated my
own Gate discipline (a plausible failure is a QUESTION, not a finding):

- **UDA-F2 was a real rebuild REGRESSION, now FIXED**: the rebuild's refactored restoring
  divider reintroduced the exact remainder-width bug main's code documents fixing (F-10/F-11:
  the packed-P compare in dataWidth bits broke for divisors >= 2^(dataWidth-1)). With main's
  verified divider, `verif.spectest divider.nrclz` AND `divider.restoring` both PASS.
- **The multiplier was also a rebuild regression** (refactored to an UNSIGNED SliceMultiplier
  `io.p := io.a*io.b`, so MULH/MULHSU high words were wrong). The corrected core comes from
  main's `klase32_rvv_coprocessor` (below), and both configs now PASS.

## UDA-M1 (multiplier) - RESOLVED (rvv_coprocessor core)

The owner identified that the actually-fixed multiplier lives in the
`klase32_rvv_coprocessor/` subtree, not `functionalunit/`. That corrected core is now
ported into `external/multiplier/design/Multiplier.scala` (abstract `MulCore` control
skeleton + `IterativeAdderCore` accumulator + `SliceMultiplier`), replacing the
functionalunit-based port and the hand-patched `&&` early-out. Two root causes, both fixed
by the rvv lineage:
 - **Truncated slice product**: `SliceMultiplier` now exposes the FULL untruncated signed
   product `pFull` (`SInt((2*width+2).W)`). The old copy truncated to `2*width`, losing the
   true sign of a large unsigned slice product (0xFFFF*0xFFFF reads negative), so the
   accumulator sign-extended wrongly. `alignedPartialProducts()` sign-extends `pFull` into
   the `2*dataWidth` accumulator before a plain carry-propagate fold - no `MulTables`.
 - **Upper-half fast-out**: `earlyOutBothHalfZero` requires BOTH operand upper halves zero
   (`aHiZero && bHiZero`) and is gated to `segmentCount <= 2`; the old `||` was wrong at
   multi-slice configs.
`MulTableGen.scala` (the old hardcoded table lineage) is deleted - the rvv core does not
use it. Both configs are verified: `verif.spectest multiplier.csa32` (production single
cycle, 32,1) AND `multiplier.csa16` (multi-cycle, 16,1) pass the full MUL/MULH/MULHSU/MULHU
sweep. MultiplierUnit still selects (32,1) as the lowest-latency production point; choosing
a smaller multi-cycle multiplier is now a pure PPA choice (OQ-C, owner-gated), not a
correctness one.

## Process note

An earlier version of these tests over-claimed UDA-F1/F2 as owner-gated RTL findings without
the Gate rigor, and even briefly used a no-reset testbench that made the failure set
nondeterministic. Corrected: `spectest.sim()` always applies reset, the mul/div sampling loop
is copied verbatim from main's VERIFIED DividerEngineTest, and a failure is only surfaced as a
finding after confirming the RTL provenance (verified vs refactored) - the discipline the
verif skill mandates, now applied at L1.

## Validation status (this session)

- `bash verif/bin/build.sh`: whole tree compiles, 0 errors (pre-existing assembler
 warnings only).
- `python3 tools/spec-check.py`: 0 errors, 7 pre-existing warnings (localspec-coverage on
 protected CSR/Decoder files + one reg-queue-epoch note; none touch the multiplier).
- `verif/bin/run.sh verif.spectest.RunSpecTests`: **6/6 PASS** - alu.compute,
 multiplier.csa32, multiplier.csa16, divider.nrclz, divider.restoring, csr.library.
 No XFAIL/PENDING remaining on the implemented units.
- Synthesis emit re-checked on the ported core: `EmitUnit mul_csa16` (120 lines) and
 `mul_csa32` (73 lines) both elaborate + emit SystemVerilog cleanly (SegVec packed-array
 path OK under the relaxed lowering).
- `scn.sh describe` reflects the resolved multiplier state (empty known_findings);
 run/gate/trace/batch still answer harness-not-ready (exit 3) as designed.
- Nothing SIMULATED against CoreTop (still a spec shell by design).

## Decision on further main-branch ports (owner asked "what else from main")

Judgment recorded: nothing else pulls its weight right now. Main's TileLink is a bespoke
flattened TL-UL bundle (EtlIntf) - superseded by the standard TL spec written here; its
KlasQueue/klastools/utils are stubs or trivia; CSR indirection templates already exist in
this tree; CLIC/FPU/debug/trigger are company-core RTL that this line will respec against
its own ADRs. The valuable port (verif + synthesis instruments) is done. Revisit when the
CommitUnit RTL lands (then main's suite corpus becomes progressively portable, OQ-F).

## Open questions needing the OWNER (carried)

- **OQ-E (ADR-004)**: sign-off to edit `backend/design/modules/csr/CSR.scala`
 (`AGENT: DO NOT TOUCH`) so TrapController is the single trap-CSR writer.
- **OQ-C (ADR-008)**: N=1 PPA bar - +10% area envelope vs parity.
- RV64: parameter plumbing accepts xLen=64 but decode tables/assembler are RV32-only -
 scheduling the RV64 decode work is an owner call (tracked in ADR-016 D-16.2 /
 rawParametricISA note).

## Next steps (in order)

1. **New personal repo + spec-framework session** (carried): push this lineage to its own
 repo, swap the stub `framework/specs` + macros for the real spec-core/spec-macros/plugin,
 wire the ADR-015 machine checks (now including the ADR-016 additions: adapter
 channel-priority assert, TL-C permission legality, no-hardcoded-32 grep), retire
 `tools/spec-check.py`.
2. **Fill shell RTL against the hardened specs**: CommitUnit + RenameUnit + alloc FIFO
 (ADR-001/002) together with the CoreHarness DUT binding (TileLink A/D service per the
 CoreHarness doc contract) and the ADR-010 retire stream -> RS/Dispatch/PublishMux/PRF ->
 StoreBuffer + memory subsystem (ADR-003) -> **InstBusAdapter/DataBusAdapter** (first
 ADR-016 RTL; brings EmitCore/sta.sh core to life) -> frontend (SlotSlicer INV-S1..S5) ->
 TrapController/CsrController (after OQ-E; now carries the privilege-mode seam).
3. **Accommodation RTL when needed by experiments**: ICache/DCache vertices (TL-UH burst /
 TL-C), ITLB/DTLB + shared PTW, U/S modes in TrapController. Each is an ADR-016 splice,
 not a boundary change.
4. **N-sweep experiments** once CoreTop elaborates (unchanged; cache/coherence axes must
 stay separate from the N axis in config names, ADR-015 D-15.5).

## Gotchas

- No sbt in the remote env; `verif/bin/build.sh` is the compile gate (chisel 6.2.0 jars).
 The sbt default is chisel *3* (`chiselVer` defaults "3"); chisel-3-only constructs can
 lurk in code the 6.2 gate has not elaborated (Multiplier's Vec:=0.U was one).
- This proxy 403s github tarballs but relays `git clone` and raw.githubusercontent.com;
 setup-sta.sh encodes this (plus the CUDD autotools timestamp fix).
- Repo name stays `jmg2027/klase32` (hosting detail); the project is UDACore. The planned
 personal-repo split (next step 1) is the natural moment to rename the repository.
- rebuild-lineage rules: commit messages Korean, specs/docs English ASCII, no emoji, no
 TODO comments, spec-first per AGENTS.md; `src/test/cluster` and `assembler/` are
 protected infra (assembler kept - it has RVC support main's lacks; coverage delta in
 verif/README.md).
- `BootSequencer` default `bootCycles=2` still ignores `CorePrivateParams.bootCycles=8`
 (belongs with CoreTop integration).
- src/test cluster tests reference pre-rebuild APIs (KLASE32 top) and do not compile
 under sbt test; they were renamed textually with the tree but remain stale protected
 infra awaiting the owner's call.
