---
name: verif
description: Use when verifying the UDACore RISC-V core - running instruction/program tests on the REAL core (Verilator), checking whether a suspected RTL bug is real, or root-causing a control-flow / data desync. Provides an AI-native harness: declarative `.scn` scenarios (no recompile), JSON results with auto-diagnosis, an anti-over-claim Gate (latency + differential), and per-cycle signal Trace. Trigger on "test this instruction on the core", "is this a real bug?", "run a scenario", "why does this program derail/hang", "verify the decoder/ALU/commit/CSR". Note: while CoreTop is a spec shell, DUT runs answer harness-not-ready (exit 3) - that answer is itself the correct status report.
---

# UDACore verification - AI harness

The `verif/` framework runs programs on the **real UDACore core** and self-checks results. You
author small declarative `.scn` text scenarios and read structured JSON back. Full design:
`verif/AI-HARNESS.md`; port status and what-works-today: `verif/README.md`.

## 0. Readiness model (this line is spec-first; check state before promising results)
```bash
verif/bin/scn.sh describe     # JSON capability card, includes a "status" block
```
- `status.dut_binding: pending CoreTop RTL` means run/batch/gate/trace/serve answer
  `{"error":"harness-not-ready",...}` with exit 3. That is the DESIGNED answer while the
  vertices are spec shells - report it as status, do not treat it as a harness failure and
  do not try to "fix" it by stubbing RTL (spec-first forbids stubs).
- The DUT binding activates with the CommitUnit RTL + ADR-010 retire stream. The binding
  contract lives in `verif/src/scala/verif/harness/CoreHarness.scala` (doc comment): serve
  TileLink A->D on instBus/dataBus from MemModel, Put-to-DONE sentinel, reset between runs.
- If `scn.sh` errors with missing classes instead: `bash verif/bin/setup.sh` (once per
  container, idempotent) or `bash verif/bin/build.sh` (compile only).

## 1. The loop - author `.scn`, run, read JSON
```
@name my-test          # optional; @maxcycles/@ilat/@dlat/@irq too
li x1 0x12345678       # load a 32-bit constant (signed ok)
li x2 12
add x3, x1, x2         # any non-directive line goes to the assembler verbatim (labels ok)
check x3 == 0x12345684 # store x3 to a result slot and assert it equals the value
expect done            # optional: assert the run completed (or: expect derail)
```
Reserved: **x31** holds the result pointer - do not write x31 in asm.
```bash
verif/bin/scn.sh run my.scn --json            # --config=default|minimal selects CoreParams
```
The `diagnosis` field says WHY on failure (zero word = assembler gap; trap-to-vector;
timeout - raise `@maxcycles` to disambiguate hang from slowness). Seed scenarios:
`verif/scn/*.scn`.

For a repeated author-run-fix loop use the daemon (one resident compiled DUT, seconds per run):
```bash
verif/bin/scn.sh serve start                  # one daemon per --config
verif/bin/scn.sh run my.scn --daemon
verif/bin/scn.sh serve stop                   # restart after an RTL edit + build.sh
```

## 2. Found something suspicious? You do NOT decide if it is a bug - the Gate does
Write a **control** variant that changes only the suspected variable, then:
```bash
verif/bin/scn.sh gate suspect.scn control.scn --json
# verdict: CONFIRMED | HARNESS-SUSPECT | INCONCLUSIVE
```
- `HARNESS-SUSPECT`: flips across memory latency/skew - timing-sensitive, NOT a confirmed
  RTL bug. Do not report it as one.
- `INCONCLUSIVE`: latency-invariant but no asymmetry vs control (or no control given).
- Only `CONFIRMED` may be promoted to a finding.

## 3. Root-cause: per-cycle signals as text
```bash
verif/bin/scn.sh trace my.scn --taps=pc,instRaw,expInst,desync --cycles=60 --json
```
`desync=true` rows mark instRaw != mem[pc] - the classic fetch/data desync signature.
(Probe taps bind to rebuild vertex signals together with the DUT binding.)

## 4. Batteries
```bash
verif/bin/scn.sh batch a.scn b.scn --json     # one compile, reset between scenarios
verif/bin/run.sh verif.suites.SmokeSuite      # first suite once the binding lands
```

## Rules
- Never claim a bug from a single run; the Gate verdict is the arbiter.
- Programs must fit below RESULT_BASE (Diagnose flags "program overrun").
- After any RTL edit: `bash verif/bin/build.sh`, restart any daemon, rerun.
