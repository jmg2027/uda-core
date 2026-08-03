# Role-based interface parameterization

Apply these guidelines when sizing or tuning vertex interfaces.

## 1. Ready/valid contract
- Producers assert `valid` when data is ready and hold it until the transfer
  fires.
- Consumers assert `ready` when they can accept a value and capture data only on
  `valid && ready`.
- Keep the implementation split between data-path work and handshake logic so the
  responsibilities remain clear.

## 2. Per-port behaviour
Describe each output port with a simple behaviour tag:
- **Combinational:** zero-cycle path; backpressure flows straight through.
- **Latched:** single register stage for timing slack.
- **Skidded:** small buffer to absorb a one-cycle stall.

Choose the lightest option that meets timing, and note the choice in the spec so
later changes do not accidentally remove it.

## 3. Modularity
- Treat every vertex as a black box. It honours contracts on its ports and does
  not assume the latency or implementation details of its neighbours.
- When requirements change, adjust the per-port behaviour instead of inventing a
  new interface style.

## 4. PPA reassurance
Combinational behaviour results in simple wires. Latched or skidded modes become
small queues. Synthesis removes unused handshake logic, so adopting these
patterns does not penalise timing or area when the ports remain idle.
