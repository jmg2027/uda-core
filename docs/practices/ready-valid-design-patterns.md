# Ready/valid design patterns

Use this catalog to choose the right timing cut for a `DecoupledIO` channel.
Each pattern lists when to use it and what to watch for.

| Pattern | Forward path | Backward path | Use when | Watch for |
| --- | --- | --- | --- | --- |
| Pass-through | Combinational | Combinational | Tiny glue that cannot form a loop | Long combinational paths |
| Slice (1 entry) | Register with bypass | Combinational | Zero empty latency, short ready chain | Ready still combinational |
| Skid buffer (2 entries) | Optional bypass | Registered | Need to break the ready chain without extra latency | Minimum depth of two |
| Full register slice | Registered | Registered | Default choice when timing is tight | Adds one cycle when empty |
| FIFO (N entries) | Registered | Registered | Burst absorption or rate matching | Area and extra latency |

### Example: Adding a register slice between issue and execute
- **Context**: `issue_to_execute` channel fails timing because the ready fan-out
  crosses three functional units.
- **Pattern choice**: Insert a full register slice with registered ready and
  valid paths.
- **Implementation notes**:
  - Stage the payload and `epoch` tag together so replay logic still works.
  - Drive the downstream `ready` through a register so the arbitration block no
    longer sits on the critical path.
- **Verification hook**: Add a coverage counter that compares the number of
  fires at the slice input and output to confirm nothing is dropped.

## Functional templates
- **Arbiter (N->1):** prefer round-robin unless a fixed priority is documented.
  Stage the output with a registered ready path to keep timing clean.
- **Demux (1->N):** align the select signal with the payload and stage the branch
  outputs if they fan out widely.
- **Broadcast:** either AND all `ready` signals or collect acknowledgements with
  a counter. Document which approach the spec expects.
- **Join:** buffer each input so slow producers do not block faster ones.

## Verification reminders
- When `valid` is high and `ready` is low, hold the payload steady.
- Count fires at input and output to ensure nothing is dropped or duplicated.
- Run lint or assertions to confirm there are no combinational ready loops.

Update this file when a new reusable pattern lands so the cheat sheet stays in
step with the code base.
