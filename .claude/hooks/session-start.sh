#!/bin/bash
# SessionStart hook (UDACore): provision the verif toolchain (chisel/firtool/verilator/riscv-gcc),
# build the AI verification harness, and activate the repo git hooks. Fail-safe by design: always
# exits 0 so a network/build hiccup never blocks the session.
LOG=/tmp/verif-session-start.log
PROJ="${CLAUDE_PROJECT_DIR:-$(pwd)}"
{
  echo "=== udacore session-start $(date -u) ==="
  if [ -x "$PROJ/verif/bin/setup.sh" ]; then
    bash "$PROJ/verif/bin/setup.sh"
  else
    echo "verif/bin/setup.sh not found; skipping"
  fi
} >"$LOG" 2>&1 || true

# persist the resolved toolchain locations into the session shell
if [ -f "$PROJ/verif/.toolchain.env" ] && [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  cat "$PROJ/verif/.toolchain.env" >> "$CLAUDE_ENV_FILE"
fi

# activate the tracked git hooks (.githooks/pre-commit: staged-ASCII + spec-check gates)
git -C "$PROJ" config core.hooksPath .githooks 2>/dev/null || true

# surface a status line as SessionStart context (best-effort)
if [ -f "$PROJ/verif/out/.ready" ]; then
  echo "UDACore verif harness ready: verif/bin/scn.sh {describe|run|gate|trace}. CoreTop is a spec shell - DUT runs answer harness-not-ready (exit 3) until the CommitUnit RTL lands; EmitUnit/sta.sh/ppa-unit.sh work today. Spec-first: read document/adr/ADR-000-index.md before design changes."
else
  echo "UDACore: toolchain provisioned; run 'bash verif/bin/build.sh' if scn.sh reports missing classes (log: /tmp/verif-session-start.log). Spec-first: read document/adr/ADR-000-index.md before design changes."
fi
exit 0
