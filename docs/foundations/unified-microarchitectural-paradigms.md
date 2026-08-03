# Unified microarchitectural paradigms

UDACore groups its microarchitectural policies into four recurring themes. These
notes explain what each theme covers and how to keep the documentation, specs,
and RTL aligned. Use them as a quick refresher before design or review work.

## 1. Execution-context algebra (ECA)
*Purpose:* describe how the machine spawns speculative work, validates it, and
commits architectural state.

* Key invariants
  - Every instruction carries an epoch tag. Redirects bump the global epoch and
    anything that no longer matches is discarded.
  - Checkpoints exist only so that a resolved context can commit or roll back as
    a unit; do not add "partial" flush knobs.
  - Commit happens in program order so traps and interrupts stay precise.
* Typical artefacts to keep current
  - `BackendTopSpecs.scala` sections that define epoch split/filter behaviour.
  - Documentation that references a flush signal. Replace it with epoch
    terminology and point readers to the redirect flow instead.
* Review questions
  - Does a new feature introduce a context that never resolves? If so, the
    pipeline can stall forever.
  - Are we leaking checkpoint storage by spawning more contexts than we retire?

## 2. Resource-availability algebra (RAA)
*Purpose:* capture the rules that decide when work may claim a shared resource
such as a functional unit, issue slot, or queue entry.

* Key invariants
  - A resource is allocated through a request/grant/release handshake; a vertex
    never assumes ownership without an explicit grant.
  - Arbitration policy (round robin, priority, age based) lives beside the
    resource declaration so designers can audit starvation risks.
* Typical artefacts to keep current
  - Reservation-station specs that define the request matrix.
  - Dispatcher and issue-queue docs that explain how many grants may be active
    per cycle and what happens when no grant fires.
* Review questions
  - Are grants masked by epoch so wrong-path work cannot consume capacity?
  - Do we free a resource in all error paths, including replay and cancel logic?

## 3. Memory-dependence graph (MDG)
*Purpose:* make memory ordering rules explicit instead of burying them in ad-hoc
forwarding logic.

* Key invariants
  - Loads and stores advertise their address metadata up front so potential
    conflicts can be tracked.
  - Replays are selective: only the dependent operations retry, not the whole
    pipeline.
* Typical artefacts to keep current
  - LSU specs that describe dependency tokens and commit guards.
  - Store buffer documentation that explains age ordering, bypass paths, and the
    kill-on-epoch mismatch rule.
* Review questions
  - Does new logic introduce a hidden bypass around the dependency table?
  - Are fences and AMO operations listed explicitly with their ordering effects?

## 4. Flow-control lattice (FCL)
*Purpose:* explain how backpressure moves through the design so buffers never
overflow and the pipeline never deadlocks.

* Key invariants
  - Every `DecoupledIO` link honours ready/valid without combinational loops.
  - Credit counts or depth assumptions are written down in specs so that timing
    fixes do not change behaviour silently.
* Typical artefacts to keep current
  - Relay-station placements and their removal criteria.
  - Assertions that guard against ready feedback or credit underflow.
* Review questions
  - If a queue fills, which upstream vertex pauses first? The answer should be
    obvious from the spec.
  - Do multi-issue paths describe how credits split across lanes?

---

Each paradigm describes one dimension of the same design. Keep them aligned:
redirect policy (ECA) must match resource grants (RAA), memory replay (MDG), and
backpressure handling (FCL). When editing documentation, make sure every new
feature explains which paradigm it touches and where the authoritative spec
lives.
