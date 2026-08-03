# DecoupledIO guide

UDACore relies on `DecoupledIO` links for almost every vertex-to-vertex
connection. Use these rules whenever you add or review an interface.

## 1. Handshake ground rules
- Producers own `valid`; consumers own `ready`.
- A producer keeps `valid` high until the transfer fires. Do not gate `valid` on
  downstream `ready`.
- Consumers may drop `ready` at any time. If the interface is always ready,
  document the invariant that makes it safe.
- The transfer fires when both `valid` and `ready` are true.

## 2. Bundle naming
- Producers expose `somethingOut`; consumers expose `somethingIn`.
- The bundle type stays direction neutral so it can be shared between ends.
- Avoid legacy terms like "flush"-stick with epoch and redirect wording.

## 3. Removing backpressure
Only tie `ready` high when all of the following hold:
1. The consumer can accept a value every cycle.
2. The producer can never outrun the consumer.
3. The spec explicitly justifies why the link cannot stall.

When any of these assumptions changes, revert to a full handshake. It is easier
to maintain backpressure than to debug silent throughput degradation later.
