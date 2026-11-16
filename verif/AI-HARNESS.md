# AI harness - design for an LLM as the primary user

`verif/` is the engine; this layer (`verif.ai`) is the **interface designed for an AI agent**,
not a human. The difference is not cosmetic - an LLM has different constraints than a person,
and the affordances follow from them. The methodology originated in the historical
KLASE32 harness; this document describes UDACore (see README.md for command status).

## What an AI needs that a human doesn't (and the design response)

| AI constraint | Design response |
|---|---|
| **Recompile is a 3-5 min tax per idea.** An LLM iterates in tight loops; a Scala edit+compile cycle per test destroys throughput. | **No-recompile loop.** The AI writes a declarative **`.scn`** text scenario; a pre-compiled generic runner (`verif.ai.RunSpec`) assembles + runs it. Only the (unavoidable) Verilator run remains. |
| **Context is scarce; raw logs overflow it.** Verilator/firtool spew thousands of lines. | **Structured JSON, dense.** `--json` emits `{cycles,done,derailed,checks:[{label,got,exp,status}],diagnosis}`. One run = a few hundred tokens, fully parseable. |
| **Reasons over numbers, not pixels.** (The waveform problem.) | **Expected beside observed, always.** Every check carries `got` and `exp`; traces carry `mem[pc]` next to `instRaw` with a `desync` flag. The AI compares fields, never reads a wave. |
| **No memory across context windows.** A fresh AI doesn't know the API. | **Self-describing.** `verif.ai.Describe` emits a JSON capability card: ops, reference models, tappable signals, the `.scn` grammar - plus, on this line, the harness readiness state. The AI bootstraps from one call. |
| **Over-claims plausible bugs.** | **Discipline as a guardrail, not a suggestion.** `verif.ai.Gate` auto-runs the latency-invariant + differential gates and returns a hard verdict: `CONFIRMED` / `HARNESS-SUSPECT` / `INCONCLUSIVE`, with evidence. The AI cannot skip the gates. |
| **Failures are opaque** (`done=false` tells the AI nothing). | **Auto-diagnosis.** On derail/NOSTORE/hang the harness classifies the cause: illegal/zero word in the image (assembler gap), trap-to-vector, or timeout - and points at the pc. |
| **Determinism required** to reason about a result. | Tiny fixed programs, fixed driver, deterministic image hash per run (`program_hash`) so a result is reproducible and referenceable. |

JSON commands write one result to stdout and preserve elaboration diagnostics
on stderr. A failed program check exits 1. No substring filter is applied to
results or process errors.

## The components (`verif.ai`)

- **`Spec`** - parser for the `.scn` declarative scenario grammar (below). Text -> assembly +
  expected checks. No Scala, no compile.
- **`RunSpec`** - the no-recompile entrypoint. Reads a `.scn` (file or stdin), runs it on the
  real core via `CoreHarness`, emits JSON (`--json`) or a terse human line. Includes
  auto-diagnosis. CoreHarness drives both TileLink links and captures the retire stream.
- **`Gate`** - takes a scenario (+ optional control variant) and runs the 3-gate filter
  automatically; returns a verdict the AI is meant to trust over its own intuition.
- **`Trace`** - per-cycle observations of all valid fetch lanes, architectural retire
  tokens, RecoveryEvent, divider busy, and ALU publication from a simulation-only wrapper.
- **`Describe`** - capability introspection as JSON.
- **`Diagnose`** - failure classifier shared by the above.
- **`Json`** - dependency-free JSON emitter.

## The `.scn` grammar (what the AI authors)

```
# comment lines start with '#'
@name   add-and-sub        # scenario name (optional)
@maxcycles 300             # optional; default 300
@ilat 2                    # optional imem latency; default 2
@dlat 2                    # optional dmem latency
@irq t 40 200              # optional: pulse an interrupt line high during cycles [40, 40+200)
                           # kind = t(imer)/e(xternal)/s(oftware); dur defaults to 10

li x1 0x12345678           # load a 32-bit constant (lui+addi), signed ok (e.g. -1)
li x2 12
add x3, x1, x2             # any line that isn't a directive is passed to the assembler verbatim
check x3 == 0x1234568a     # store x3 and assert it equals the value (auto result slot)
sub x4, x1, x2
check x4 == 0x1234566c

expect done                # optional: assert the run completed (store to DONE seen)
# expect derail            # or assert it derailed (for bug-witness scenarios)
```

Reserved register: **x31** holds the result pointer (set by the runner); don't write x31 in your
asm. `check <reg> == <val>` auto-assigns the next result slot. Values are hex (`0x..`) or signed
decimal. Same-line labels (`tk: addi ...`) work (the assembler resolves them).

## Typical AI loops

```bash
# 1) one-shot functional check (no recompile)
verif/bin/scn.sh run my.scn --json

# 2) is my suspicious result a real bug? (the AI must not decide this itself)
verif/bin/scn.sh gate bug.scn control.scn --json
#   -> {"verdict":"HARNESS-SUSPECT","reason":"result flips across iLat {1:119,2:255}", ...}

# 3) sweep many scenarios on ONE compiled DUT (persistent simulator: compile once, reset between)
verif/bin/scn.sh batch a.scn b.scn c.scn --json

# 4) fresh context: what can I do?
verif/bin/scn.sh describe

# 5) inspect every fetch lane, retire token and recovery event before the clock edge
verif/bin/scn.sh trace my.scn --cycles=200 --json
```

`scn.sh batch` (`verif.ai.RunBatch` -> `CoreHarness.runBatch`) elaborates + Verilates the core
**once** and resets between scenarios, instead of recompiling per `.scn`. The dominant per-run
cost (FIRRTL -> SV -> Verilator) is paid once, so an N-scenario sweep is ~1 compile, not N.
Scenarios share one config and are isolated by reset; the register file is not reset, so
programs must be self-contained.

## Trace semantics

Cycle 0 is the first driven cycle after reset. Samples are taken before each rising edge;
the runner emits exactly `--cycles=N` rows, including cycles after the completion store.
`@irq` pulses use the same cycle numbering as scenario runs.

The default JSON includes `fetch` (all valid lanes, offered to decode), `fetchReady`,
`retire` (one architectural observation or null), `recovery` (RecoveryEvent or null),
`reqAddr`/`reqValid` (instruction TileLink A offer), `divBusy`, and `aluWB` (ALU result
handshake). A held fetch packet can appear in several rows; count a transfer only when
`fetchReady` is true. `flush` is a legacy alias for RecoveryEvent.valid, not a new kill wire.
`jump` observes a taken branch-result handshake; `xcpt` observes a trap retire token;
`rd` is the current retiring architectural destination, or zero when no write occurs.

`expInst` compares fetched instructions with mapped backing-memory bytes only for
Bare/M-mode fetches without faults. Each lane has `compareValid`; otherwise `expInst` is
null. Sv32 virtual PCs cannot be used as physical-memory indexes. `desync` covers every
comparable lane and is a diagnostic: self-modifying code can legally differ from backing
memory before FENCE.I. Pipeline events in the same row need not refer to the same instruction.

The AI writes scenarios as data, reads results as JSON, and is held to the verification
discipline by the harness itself - turning the methodology into guardrails rather than advice.
