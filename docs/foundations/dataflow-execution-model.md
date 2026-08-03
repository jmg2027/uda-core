# Dataflow execution model

This note explains how UDACore handles control and data flow during execution.
Use it when you need a refresher on how epochs, renaming, and buffering fit
together.

## 1. Epochs drive control
- Redirects, traps, and similar events advance the global epoch.
- Every in-flight token carries an epoch tag. If the tag does not match the
  global value, the token is dropped locally without a flush signal.
- Buffers check `token.epoch === globalEpoch` before they act. Keep this guard in
  mind when you add queues or replay paths.

## 2. Rename, reservation, and publish loop
UDACore uses Unified Physical Register File (PRF) architecture:

1. **Rename unit**
   - Maintains map table (architectural → physical register mapping) and free list
   - Assigns physical register to each destination and tracks source dependencies
   - Emits uops with physical register tags (prs1, prs2, prd)
2. **Reservation stations**
   - Hold uops until operands arrive (via wakeup broadcast) and FU is free
   - Mask grants by epoch so wrong-path work never consumes slots
3. **PublishMux (Result Bus)**
   - Collects results from all functional units
   - Writes to physical register file
   - Broadcasts wakeup (prd, valid) to reservation stations
   - Forwards result to commit unit

## 3. Unified PRF architecture
Physical register file holds both architectural and speculative state:

- Total entries: 33 (architectural baseline) + N (speculative)
- On commit: update map table only, no data writeback
- Map table approach scales from N=1 (in-order) to N=32+ (wide OoO)
- Commit frees old physical register to free list

## 4. Supporting structures
- **Commit unit:** maintains program order, updates map table on retirement, frees old physical registers
- **Physical register file:** unified storage for all register state

## 5. Applying the model
When you change the backend:
- Update the relevant specs so the epoch guards, rename rules, and buffer depths
  remain consistent.
- Trace any new bypass around the epoch or rename loop and explain why it is
  safe.
- Keep specs updated with any changes so reviewers can understand the current
  implementation state.
