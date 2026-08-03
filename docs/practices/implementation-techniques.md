# Implementation techniques

These tips translate the UDA rules into day-to-day RTL work. Read alongside the
specs for the vertices you are touching.

## Interfaces
- Generate `valid` without referencing downstream `ready`.
- Add assertions or tests that catch combinational ready loops.
- Document each bundle in the spec so reviewers know how it is meant to behave.

## Choosing a timing cut
| Option | When to use it |
| --- | --- |
| Relay station | Default one-cycle staging element for shared clock domains. |
| Skid buffer | Break a long ready chain while keeping zero empty latency. |
| Full register slice | When you need clean registered paths in both directions. |
| FIFO | When bursts or mismatched rates must be absorbed. |

Record added latency in the relevant spec and note any follow-up work required to
remove the cut later.

## Token and epoch hygiene
- Standard tokens carry payload data plus `{epoch, tag}` metadata. Keep the
  metadata intact whenever you forward or transform a token.
- Emit new epochs only through the dedicated splitter logic. Filters must drop
  stale epochs locally instead of requesting a global flush.
- When you introduce a new dependency tag or replay rule, describe it in the spec
  and add a debug hook so regressions can expose it.

## Backpressure
- Credit counters should match the real buffer depth. Spend on send, repay on
  consume.
- For merges, make it obvious which input wins when both are ready. Document the
  arbitration policy.
- Break any feedback loop with a relay or register so the design stays deadlock
  free.

## Review checklist
1. Specs updated before RTL.
2. Latency or buffering changes noted in the spec with clear follow-up actions
   when work remains.
3. Tests cover epoch transitions, credit depletion, and replay paths touched by
   the change.

### Example: Credit counter on a load queue
- **Setup**: `LoadQueue` accepts up to four in-flight requests while `DCache` can
  apply backpressure for two cycles.
- **Counters**:
  - Initialize `credits := 4` at reset.
  - Decrement on each fire into the queue.
  - Increment when the memory system consumes an entry.
- **Backpressure rule**: Gate `io.enq.ready` with `(credits > 0)`; expose the
  remaining credit count in a debug register so tests can assert it never goes
  negative.
- **Spec callout**: Document the credit limit and the observable debug register
  so verification knows how to monitor saturation.
