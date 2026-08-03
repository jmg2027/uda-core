---
name: handoff
description: Use when wrapping up a work session or pausing a multi-session task on UDACore - produce a structured handoff (goal, done, in-flight/uncommitted, key decisions and why, validation status, open owner questions, next steps, gotchas). Trigger on "write a handoff", "/handoff", "summarize where we are for the next session", "pause and record state". This environment is ephemeral, so durable state lives in git (document/HANDOFF.md).
---

# UDACore session handoff

The container is ephemeral and context summarizes between windows. git is the durable store;
commit messages already say "what + why" (in Korean, per the language policy). This skill
captures what git history alone does not: uncommitted state, decision rationale, owner-gated
questions, and what is next.

## Output modes
- Default: emit the handoff to chat.
- `--write`: also rewrite `document/HANDOFF.md` (English ASCII) and commit + push.

## Steps
1. Gather state:
   ```bash
   git branch --show-current && git log --oneline -12 && git status --short && git diff --stat
   python3 tools/spec-check.py 2>&1 | tail -3
   bash verif/bin/build.sh 2>&1 | tail -1     # only if sources changed this session
   ```
2. Fill the template. Tight prose, reference commit hashes, no diffs. Carry forward the
   standing sections from the previous HANDOFF.md: **Open questions needing the OWNER**
   (OQ-C/OQ-E/RV64...) and **Gotchas** (toolchain, proxy, chisel 3/6 duality) - update,
   never silently drop.
3. If `--write`: replace HANDOFF.md's per-session sections, keep/refresh the standing ones,
   then commit (Korean message, prefixed with the Korean word for handoff followed by a
   colon, matching the existing history) and push to the current branch.

## Template
```
# Handoff: <topic> (<date>)

Branch / supersedes note.

## Goal            - the project identity + this session's aim (1-3 lines)
## Done this session - numbered, one commit(-group) per item, what + why
## Validation status - build gate, spec-check, verif/STA results actually run
## Open questions needing the OWNER - carried + new
## Next steps (in order) - concrete, with the file/ADR each step lands in
## Gotchas         - environment traps the next session must know
```

## Rules
- Never claim validation that was not run this session; "Nothing SIMULATED against
  CoreTop" style honesty is the norm here.
- Decisions made this session must point at their ADR (or be flagged as needing one).
- HANDOFF.md is English ASCII (the pre-commit hook enforces it).
