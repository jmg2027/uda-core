## UDACore onboarding guide (for Chisel experts)

This guide is for experienced Chisel or Rocket Chip developers joining the UDACore project. UDACore follows a design philosophy different from Rocket Chip, so read this before diving into the code.

### Terminology Baseline
- **Vertex** - A graph node called out in the raw-top diagrams maintained in `records/architecture/raw-top-architecture-review.md`. Each one has a dedicated spec entry and is the boundary where ready/valid edges begin or end. Modules that live entirely inside a vertex's box stay internal and are not renamed as vertices.
- **Edge** - The explicit communication path between vertices. Edges are handshake channels unless otherwise noted.
- **Raw top (domain)** - A vertex tagged with `raw top` that instantiates child vertices and wires edges among them without embedding behavioural logic.

> **Implementation note:** Chisel code still declares `class ... extends Module`. Only the classes that match a diagram node are treated as vertices in discussions; the rest are internal helpers that stay local to their parent vertex.

### 1. Project goals and status

* **Core objective**: Maintain a single code base whose behaviour can be tuned through configuration. Chisel elaboration should produce the PPA point that matches each configuration.
* **Current status**: `2-stage in-order core`. The short pipeline keeps the fundamental behaviour easy to grasp.

### 2. Key design principles you must know

UDACore differs from traditional CPU pipeline control. Learn the three concepts below to cover most of the code.

#### 1) No flush signal, only epoch and redirect

There is no global `flush` signal to clear the pipeline.

* **Epoch**: A global ID representing the current branch of program flow. Every valid pipeline token carries its epoch value.
* **Redirect**: Drops the wrong branch and switches to a new one. When a branch misprediction or trap occurs, the `RedirectUnit` issues a new epoch value and PC.
* **Operation**:
    1. When a `redirect` happens, `GlobalEpochCtrl` increments the global epoch counter.
    2. Each pipeline vertex compares the epoch tag on its data against the new global epoch value.
    3. If the data's epoch differs from the global epoch, the data becomes invalid and is discarded.

> **Debug tip**: If the pipeline looks empty in a waveform, watch `redirect_fire` and changes in `global_epoch`. Compare each pipeline register's `epoch` field with `global_epoch` instead of searching for a `flush` signal.

#### 2) Everything is a dataflow

UDACore follows the Unified Dataflow Architecture. Instead of complex finite state machines, behaviour is expressed as tokens moving between producers and consumers.

* All vertex-to-vertex edges use `Chisel.DecoupledIO`.
* The `valid`/`ready` handshake is the key to understanding system behaviour.

#### 3) Frontend and backend stay separated

* **Frontend**: Generates the `PC` and fetches instructions from instruction memory.
* **Backend**: Executes instructions and tells the frontend when a redirect is required.

---

### 3. Exploring the codebase

Review the code in the following order.

1. **`/src/main/scala/udacore/backend/spec/` (and `frontend/spec/`)** - start here.
    * These directories hold design specifications (`...Specs.scala`) for every vertex. Read each vertex's spec before the Scala code to learn the `CONTRACT` (vertex role) and `PROPERTY` (required guarantees). The spec is the single source of truth.
2. **`/src/main/scala/udacore/backend/design/` (and `frontend/design/`)** - actual Chisel implementations.
    * Once the spec is clear, the implementation layout is much easier to follow.
3. **Domain `shared/` and `api/` folders** - each raw top owns `design/shared/` for intra-domain bundles and parameters and `design/api/` for the subset exported upward. Mirror the layout under `spec/` before wiring RTL. Use `src/main/scala/udacore/common/` only for cross-domain utilities that are already promoted there.

---

### 4. Quick debugging guide

Focus on the following signals when running tests and inspecting waveforms.

* **Top level**:
    * `io_redirect_fire`: backend requests a path change from the frontend.
    * `io_redirect_target`: new PC address.
* **Global signals**:
    * `GlobalEpochCtrl_epoch`: current global epoch. A change here marks a major pipeline event.
* **Inter-stage interfaces**:
    * `if_to_id_valid`, `if_to_id_ready`, `if_to_id_bits_*`
    * `id_to_ex_valid`, `id_to_ex_ready`, `id_to_ex_bits_*`
    * Compare each interface's `epoch` field with `GlobalEpochCtrl`'s epoch to spot stale tokens.

### Quick start checklist for new developers

1. Read this `ONBOARDING.md` to understand `epoch` and `redirect`.
2. When modifying or analysing a vertex, locate its `...Specs.scala` file in `spec/` and read the `CONTRACT`.
3. Study the implementation in the `design` directory.
4. After running tests and viewing waveforms, track pipeline flow through `redirect` and `epoch` changes instead of looking for `flush` signals.

Use this guide as the starting point for work on the UDACore codebase.
