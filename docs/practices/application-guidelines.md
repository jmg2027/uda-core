# Application guidelines

Use these steps when migrating a legacy design into the UDACore UDA flow or when
breaking new ground within the project.

## 1. Map the pipeline
- List the responsibilities of the existing stages.
- Sketch the equivalent UDA vertices (fetch, decode, execute, memory, commit,
  etc.). Each vertex should expose a ready/valid interface and describe its
  latency in the spec.

## 2. Replace flush logic with epochs
- Emit new epochs through the splitter when control flow changes.
- Drop stale work locally by comparing token epochs with the global value.
- Document every epoch producer and consumer in the relevant specs.

## 3. Partition resources
- Separate functional units (ALU, multiply, divide, branch) so each owns its own
  scheduling rules.
- Keep the renamer, reservation stations, and vGPR aligned; changes to one often
  require updates to the others.

## 4. Record timing cuts
- Start with safe staging (relay stations on long chains), then remove them only
  after timing reports prove they are unnecessary.
- Note every added or removed cut in the spec so the debt is visible.

## 5. Tune and verify
- Measure throughput at merge points to spot congestion early.
- Exercise tests that cover epoch flips, dependency replay, and store buffer
  fairness after each major change.
- Keep transient notes-migration plans, risk logs, open questions-in a working
  log so this document can stay short and timeless.

Update these guidelines whenever you discover a new repeatable tactic. If the
approach cannot be explained in a few bullet points, write a dedicated note and
cross-link it here.

### Example: Folding a legacy 5-stage pipe into UDA
- **Original design**: IF, ID, EX, MEM, WB with a shared flush line.
- **UDA sketch**:
  - Map IF to `FetchVertex` with a 2-entry queue and explicit epoch compare.
  - Combine ID and EX into `DecodeExecuteVertex` while keeping the issue
    scoreboard local.
  - Split MEM into `MemoryAccessVertex` and `StoreBufferVertex` to expose the
    existing bypass rules.
- **Epoch plan**: Redirect logic in `DecodeExecuteVertex` increments the global
  epoch. Each downstream vertex drops tokens when `{token.epoch != global}`.
- **Ready/valid check**: Insert a skid buffer between decode and memory to break
  the old flush-driven feedback path.
- **Spec updates**: Document the new epoch producer, the queue depths, and the
  arbitration policy inside each vertex contract before editing RTL.
