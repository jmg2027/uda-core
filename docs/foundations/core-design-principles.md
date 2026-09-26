# Core design principles

These five principles describe the habits that keep UDACore coherent. Use them
as a checklist during reviews; deeper background sits in the linked references.

## 1. Unified dataflow mindset
- **Rule:** Model the core as tokens moving through a directed graph of vertices.
- **Why:** A single mental model avoids hidden control paths and makes it easier
  to reason about timing and functionality together.
- **See also:** [`unified-design-principles.md`](unified-design-principles.md).

## 2. Control is data
- **Rule:** Redirects, traps, and resource grants live on token fields. Do not add
  side-band "flush" wiring.
- **Why:** Epoch comparisons give a simple, local proof of correctness.
- **See also:** [ADR-019](../../document/adr/ADR-019-conventional-ooo-root-architecture.md) for the selective-recovery model that replaced the epoch token model.

## 3. Latency-insensitive links
- **Rule:** Default to `DecoupledIO` edges. Document any exception before you wire
  it.
- **Why:** When every link can stall, you can retime or pipeline without breaking
  neighbours.
- **See also:** [`../practices/decoupled-io-guide.md`](../practices/decoupled-io-guide.md).

## 4. Deliberate cycle breaking
- **Rule:** Remove strongly connected components. Add relay stations or queues
  only when you can justify them, and record the exit criteria.
- **Why:** Timing closure stays predictable and the debt is visible.
- **See also:** [`../practices/implementation-techniques.md`](../practices/implementation-techniques.md).

## 5. Specs lead, docs follow
- **Rule:** Specifications describe the graph first. RTL and documentation echo
  the spec without inventing new terminology.
- **Why:** A single source of truth keeps the project verifiable.
- **See also:** [`../process/spec-usage-guideline.md`](../process/spec-usage-guideline.md).

When you introduce a feature, note which principle it touches and cross-link the
supporting spec. If you cannot point to an updated spec entry, the work is not
ready.
