# Divide-and-conquer workflow guide

**Version:** 2025-09-15 - **Owner:** Delivery operations team

Use this guide to organise complex initiatives with a divide-and-conquer
approach. Keep project trackers up to date so this reference remains focused on
process rather than status minutiae.

## 1. Purpose and applicability
- Restore clarity and momentum when a project becomes too large to tackle in a
  single pass.
- Applies to any technical or operational effort where work can be partitioned
  into partially independent components that integrate into a cohesive whole.

## 2. Preparation checklist
1. **Reconfirm objectives** – capture the desired outcomes, constraints, and
   completion definition before breaking down the work.
2. **Audit references** – read through the governing guidelines, style manuals,
   and existing specifications so each sub-task respects upstream expectations.
3. **Establish ownership** – assign accountable leads for orchestration, and
   identify subject matter contacts for each anticipated workstream.

## 3. Iterative workflow
1. **Decompose deliberately** – map the system into well-bounded components.
   Prefer boundaries that minimise cross-team dependencies and allow parallel
   execution.
2. **Plan integration points** – define how the components reconnect, including
   interface contracts, data flows, and milestones for alignment reviews.
3. **Execute focused sprints** – complete one component at a time (or in small
   parallel batches). Keep local design notes and test artefacts scoped to the
   component under construction.
4. **Integrate continuously** – fold finished components back into the shared
   baseline quickly. Address conflicts or contract gaps immediately rather than
   deferring them to the end.
5. **Review and iterate** – after each integration round, validate outcomes
   against the original objectives and refine the remaining backlog based on the
   latest findings.

## 4. Tracking and communication
- Maintain shared dashboards or checklists that capture ownership, progress,
  integration risks, and follow-up actions.
- Close each working session by updating the tracker and documenting decisions
  that affect downstream tasks.
- Surface blockers promptly and route them to the accountable owner so the
  workflow keeps moving.

## 5. Quality and validation
- Align verification activities with each component’s definition of done.
- Automate repeatable checks (formatters, unit tests, simulations, or process
  audits) and run them prior to declaring a component complete.
- Record validation artefacts and attach them to the component’s tracker entry
  for easy reference during integration reviews.

## 6. Reporting artefacts
- Summarise current status, integration health, and open risks in the shared
  tracker rather than embedding them in this document.
- Archive final retrospectives and lessons learned so future campaigns can reuse
  effective patterns and avoid pitfalls encountered here.
