# Spec framework integration

UDACore consumes the real `spec-core` and `spec-macros` sources from a sibling
`spec-framework` checkout. There are no local no-op annotations or DSL builders.
The integration uses framework revision
`2086f078147bf88f721534f7226f551590b07a82` plus the accompanying fixes for
`SpecIndex` file-handle cleanup and `LocalSpec` parameter/companion expansion.

## Checkout and toolchain

Use this layout, or set `SPEC_FRAMEWORK_HOME` to an absolute checkout path:

```text
work/
  spec-framework/
  uda-core/
```

The consumer pins Scala 2.13.12, Chisel 6.2.0 and Chisel's uPickle 3.1.0.
Both build paths compile the framework sources against that dependency set.
No local Ivy publication or sbt compiler plugin for spec-framework is required;
`spec-plugin` is an sbt AutoPlugin, not a Scala compiler plugin.

For the verification build, use the platform-specific provisioner:

```bash
bash verif/bin/setup.sh        # Linux
bash verif/bin/setup-macos.sh  # Apple Silicon macOS
```

The Linux setup path is unchanged. The Apple Silicon path pins the validated
JDK 17 / Scala 2.13.12 / Chisel 6.2.0 / firtool 1.62.0 / Verilator 5.020 tuple.
`firtool` is the upstream macOS x86_64 release and is invoked through an explicit
Rosetta wrapper; Verilator 5.020 is built as a native arm64 executable. The script
finds or installs the RISC-V GCC/binutils tools and exposes the historical
`riscv64-unknown-elf-*` command names expected by the verification harness.

The macOS setup deliberately selects `MacOSX26.5.sdk`. The default
`MacOSX27.0.sdk` failed to link with this validated stack, and Homebrew Verilator
5.052 produced duplicate Ready protocol messages with Chisel 6.2.0, so neither is
used as an automatic substitute. Rosetta 2, Homebrew, and an installed
`MacOSX26.5.sdk` are prerequisites; `VERIF_MACOS_SDKROOT` may point to that exact SDK
when it is installed outside the normal Xcode/CommandLineTools locations.

Resolved paths are written to ignored `verif/.toolchain.env`. Downloads,
Scala/Chisel dependencies, the native Verilator build, the Rosetta firtool wrapper,
and ccache live under `~/Library/Caches/uda-core-verif` by default (override with
`VERIF_CACHE_ROOT`). Nothing required for reconstruction is kept under `/tmp`, so
re-running `setup-macos.sh` repairs the environment after `/tmp` cleanup without
manual path recovery.

## Build and export

```bash
bash verif/bin/build.sh
python3 tools/spec-check.py
bash verif/bin/test-spec-framework.sh
verif/bin/run.sh verif.spectest.RunSpecTests
```

The build compiles framework core, framework macros, UDA specs (`*Specs.scala`),
then UDA RTL and verification suites in separate compiler invocations. Specs must
remain independent of RTL so annotations can resolve already emitted spec IDs.
Compiler failures propagate to the caller. `run.sh` requires a successful build.

Outputs live in `verif/out/`:

- `classes/`: framework, spec, RTL and verification classes.
- `spec-meta/`: compile-time `.spec` and `.tag` artifacts.
- `spec-report/SpecIndex.json`: spec graph with resolved relationships.
- `spec-report/TagIndex.json`: source locations bound by `@LocalSpec`.
- `spec-report/SPEC.md`: generated specification and coverage report.
- `build.log`: full compiler diagnostics.

Every verification build removes old classes and metadata before compiling.
The artifact gate rejects empty indexes, duplicate spec IDs, malformed artifacts,
unknown tag IDs and unresolved relationships. The existing Python spec checker
continues enforcing the UDA-specific ADR-015/018 rules and their explicit allowlists.

For sbt users, the same source dependency stages are configured in `build.sbt`:

```bash
sbt compile
sbt specCheck
```

`specCheck` performs an aggregated clean and exports to `target/spec-report/`.
Use it for a complete metadata refresh after any edit or deletion. Raw macro files
have unique names, so repeated incremental compilation alone is not an authoritative
snapshot. `exportSpecIndex` exports the current compilation and rejects duplicates.
The historical `src/test` cluster tests target older architecture; the active OoO
verification suites are under `verif/suites`.

## Authoring and interpreting results

Use `framework.spec.Spec._`, `framework.macros.SpecEmit.spec`, and
`framework.macros.LocalSpec`. Wrap every spec definition in `spec { ... }` and use
spec values in `.is`, `.has` and `.uses` so the compiler checks references.
Spec IDs are global. Previously colliding IDs now use `SpecObject.specVal`;
keep new IDs unique across the checkout. Scala val names and test bindings are
unchanged by this disambiguation.
Supply both arguments to `.code("scala", content)`; the single-argument overload
currently fails during reflective macro evaluation.

The framework reports unfinished implementation and assertion coverage separately
from broken graph references. Existing design shells and `@LocalSpec` property
annotations do not establish formal enforcement: the latter emit `kind = impl`.
A real assertion binding uses `assert(Formal.assertProperty(prop) { condition })`.
Consequently the integration uses the default graph-integrity gate, not `--strict`.
Generated `.sva`/`.sby` files are report scaffolding, not evidence of a completed
formal proof. Existing L1 test results remain in the verification runner; they are
not automatically imported into the framework's `VerifIndex.json`.
