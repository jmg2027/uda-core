#!/usr/bin/env python3
"""
spec-check.py - Interim source-reflection spec checker for UDACore.

Implements ADR-015 D-15.1 / D-15.2 (interim, static-analysis form). This is a
standalone Python3 pre-plugin gate that parses src/main/scala/**/*Specs.scala and
the @LocalSpec annotation sites by source reflection (regex, AST-lite). It runs
against the no-op DSL stub today and is meant to be superseded by the compiler
plugin's spec.meta metadata later (same check names).

It reports findings as WARNING or ERROR with file:line locations and exits
nonzero when any ERROR is present (warnings never fail the build).

Checks
------
1. graph-consistency (ERROR/WARN): for each rawTop CONTRACT that carries a
   .draw("mermaid", ...), every boundary I/O endpoint label in the graph must
   reconcile with an interface declared in that CONTRACT's .has(...).
2. property-to-assertion (ERROR): every PROPERTY val name must be referenced by
   some site under src/main/scala OUTSIDE a /spec/ path (i.e. bound by an
   @LocalSpec design assert), OR be listed in tools/spec-check-allow.txt.
3. localspec-coverage (WARN): every concrete design-file class extending a
   *Module should carry an @LocalSpec annotation.
4. rawtop-wiring-only (ERROR): a design file bound to a rawTop CONTRACT must
   contain no when(/switch(/RegInit( - rawTops are :<>= wiring only.
5. reg-queue-recovery (WARN): a design file that instantiates Queue( or
   RegEnable( should state its recovery stance somewhere in the same file: a
   RecoveryEvent/recovery reference, a transaction generation tag, or a
   recovery-exempt classification (ADR-019 D-19.10 re-base of the ADR-005
   eager-filter discipline; the old "mentions epoch" test is superseded).

Heuristics (documented, approximate matching is acceptable per ADR-015 D-15.2)
-----------------------------------------------------------------------------
* A "rawTop CONTRACT" is any `val X = spec { CONTRACT(...) ... .is(rawTop) ... }`
  block. A spec block runs from its `val NAME = spec {` head to its terminating
  `.build()`; the block's category is the first category keyword after `spec {`.
* Graph boundary endpoints are the mermaid text nodes declared as
  `id@{shape: text, label: LABEL}` (the inputs_group / outputs_group ports).
  Internal module nodes and inter-module edge labels denote child vertices /
  bundles, NOT top interfaces, and are intentionally not reconciled here.
* Interface identity: from each `intf*` val named in `.has(...)`, and from each
  boundary LABEL, we derive a comparison key by lowercasing, stripping all
  non-alphanumerics, dropping a leading "intf", and dropping at most one trailing
  "in"/"out". A boundary label whose key matches no interface key is an ERROR
  (drawn but undeclared); an interface whose key matches no boundary label is a
  WARNING (declared but undrawn).
* "design file" = a .scala file whose path does not contain "/spec/".
* rawTop design file = a non-spec .scala file that annotates a class with
  @LocalSpec(<contractVal>) where <contractVal> is a rawTop CONTRACT val.

ASCII only. No external dependencies.
"""

import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC_ROOT = os.path.join(REPO_ROOT, "src", "main", "scala")
ALLOW_FILE = os.path.join(REPO_ROOT, "tools", "spec-check-allow.txt")
TEST_ALLOW_FILE = os.path.join(REPO_ROOT, "tools", "spec-test-allow.txt")
VERIF_ROOT = os.path.join(REPO_ROOT, "verif")

CATEGORIES = (
    "CONTRACT",
    "FUNCTION",
    "PROPERTY",
    "COVERAGE",
    "INTERFACE",
    "PARAMETER",
    "BUNDLE",
    "RAW",
)

# val NAME = spec { CATEGORY( ... )  -- captures the val name and its category.
SPEC_HEAD_RE = re.compile(
    r"val\s+(\w+)\s*=\s*spec\s*\{\s*(" + "|".join(CATEGORIES) + r")\s*\(",
    re.MULTILINE,
)
PROP_VAL_RE = re.compile(r"val\s+(\w+)\s*=\s*spec\s*\{\s*PROPERTY\s*\(", re.MULTILINE)
FUNCPROP_VAL_RE = re.compile(
    r"val\s+(\w+)\s*=\s*spec\s*\{\s*(?:PROPERTY|FUNCTION)\s*\(", re.MULTILINE)
LABEL_RE = re.compile(r"shape:\s*text\s*,\s*label:\s*([A-Za-z0-9_]+)")
HAS_RE = re.compile(r"\.has\s*\(([^)]*)\)", re.DOTALL)
DRAW_MERMAID_RE = re.compile(
    r'\.draw\s*\(\s*"mermaid"\s*,\s*(""".*?"""|".*?")',
    re.DOTALL,
)
LOCALSPEC_CLASS_RE = re.compile(r"@LocalSpec\s*\(\s*(\w+)")
CLASS_MODULE_RE = re.compile(
    r"^\s*(abstract\s+)?(?:final\s+)?class\s+(\w+)[^\n]*\bextends\b[^\n]*?\w*Module\b",
    re.MULTILINE,
)

FORBIDDEN_RAWTOP = ("when(", "switch(", "RegInit(")


class Finding:
    def __init__(self, check, severity, path, line, message):
        self.check = check
        self.severity = severity  # "ERROR" or "WARNING"
        self.path = path
        self.line = line
        self.message = message

    def render(self):
        rel = os.path.relpath(self.path, REPO_ROOT)
        return "{sev:7s} [{chk}] {loc}: {msg}".format(
            sev=self.severity,
            chk=self.check,
            loc="{}:{}".format(rel, self.line),
            msg=self.message,
        )


def walk_scala():
    for root, _dirs, files in os.walk(SRC_ROOT):
        for name in files:
            if name.endswith(".scala"):
                yield os.path.join(root, name)


def is_spec_path(path):
    norm = path.replace(os.sep, "/")
    return "/spec/" in norm


def line_of(text, index):
    return text.count("\n", 0, index) + 1


def norm_key(s):
    n = re.sub(r"[^a-z0-9]", "", s.lower())
    if n.startswith("intf"):
        n = n[4:]
    if n.endswith("out"):
        n = n[:-3]
    elif n.endswith("in"):
        n = n[:-2]
    return n


def extract_blocks(text):
    """Yield (name, category, block_text, head_line) for each spec val.

    A block runs from the `val NAME = spec {` head to the next `.build()`.
    """
    blocks = []
    for m in SPEC_HEAD_RE.finditer(text):
        name = m.group(1)
        category = m.group(2)
        start = m.start()
        end_m = text.find(".build()", m.end())
        end = end_m + len(".build()") if end_m != -1 else len(text)
        blocks.append((name, category, text[start:end], line_of(text, start)))
    return blocks


def parse_specs():
    """Return dict of spec metadata gathered across all *Specs.scala files."""
    rawtop_contracts = {}  # val name -> (path, line)
    prop_decls = []  # (name, path, line)
    contract_blocks = []  # (name, path, block_text)

    for path in walk_scala():
        if not path.endswith("Specs.scala"):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()

        for name, category, block, head_line in extract_blocks(text):
            if category == "CONTRACT":
                contract_blocks.append((name, path, block))
                if ".is(rawTop)" in block.replace(" ", "") or re.search(
                    r"\.is\([^)]*\brawTop\b", block
                ):
                    rawtop_contracts[name] = (path, head_line)

        for m in PROP_VAL_RE.finditer(text):
            prop_decls.append((m.group(1), path, line_of(text, m.start())))

    return rawtop_contracts, prop_decls, contract_blocks


def check_graph_consistency(rawtop_contracts, contract_blocks, findings):
    warn = err = 0
    rawtop_names = set(rawtop_contracts.keys())
    for name, path, block in contract_blocks:
        if name not in rawtop_names:
            continue
        draw_m = DRAW_MERMAID_RE.search(block)
        if not draw_m:
            continue
        graph = draw_m.group(1)

        # Interface keys declared in .has(...)
        iface_keys = {}
        has_m = HAS_RE.search(block)
        if has_m:
            for tok in re.split(r"[,\s]+", has_m.group(1)):
                tok = tok.strip()
                if tok.startswith("intf"):
                    iface_keys[norm_key(tok)] = tok
        iface_key_set = set(iface_keys.keys())

        # Boundary labels drawn in the mermaid graph.
        labels = LABEL_RE.findall(graph)
        drawn_keys = set()
        for lbl in labels:
            k = norm_key(lbl)
            drawn_keys.add(k)
            if k not in iface_key_set:
                # Locate label line within the file for reporting.
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    ftext = fh.read()
                idx = ftext.find("label: " + lbl)
                ln = line_of(ftext, idx) if idx != -1 else 0
                findings.append(
                    Finding(
                        "graph-consistency",
                        "ERROR",
                        path,
                        ln,
                        "mermaid endpoint label '{}' in {} has no matching "
                        "interface in .has(...)".format(lbl, name),
                    )
                )
                err += 1

        # Interfaces declared but never drawn as a boundary endpoint.
        for k, tok in sorted(iface_keys.items()):
            if k not in drawn_keys:
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    ftext = fh.read()
                idx = ftext.find(tok)
                ln = line_of(ftext, idx) if idx != -1 else 0
                findings.append(
                    Finding(
                        "graph-consistency",
                        "WARNING",
                        path,
                        ln,
                        "interface '{}' declared in {}.has(...) is not drawn as "
                        "a mermaid boundary endpoint".format(tok, name),
                    )
                )
                warn += 1
    return warn, err


def load_allowlist():
    allow = set()
    if os.path.exists(ALLOW_FILE):
        with open(ALLOW_FILE, "r", encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                s = raw.strip()
                if s and not s.startswith("#"):
                    allow.add(s)
    return allow


def collect_nonspec_text():
    """Concatenate all non-spec design .scala sources for token search."""
    chunks = []
    for path in walk_scala():
        if is_spec_path(path):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            chunks.append(fh.read())
    return "\n".join(chunks)


def check_property_to_assertion(prop_decls, findings):
    allow = load_allowlist()
    design_text = collect_nonspec_text()
    err = 0
    for name, path, line in prop_decls:
        bound = re.search(r"\b" + re.escape(name) + r"\b", design_text) is not None
        if bound:
            continue
        if name in allow:
            continue
        findings.append(
            Finding(
                "property-to-assertion",
                "ERROR",
                path,
                line,
                "PROPERTY '{}' is neither paired with an @LocalSpec design "
                "assert nor listed in tools/spec-check-allow.txt".format(name),
            )
        )
        err += 1
    return err


def unpaired_properties(prop_decls):
    design_text = collect_nonspec_text()
    out = []
    for name, _path, _line in prop_decls:
        if re.search(r"\b" + re.escape(name) + r"\b", design_text) is None:
            out.append(name)
    return sorted(set(out))


def check_localspec_coverage(findings):
    warn = 0
    for path in walk_scala():
        if is_spec_path(path):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        has_localspec = "@LocalSpec" in text
        for m in CLASS_MODULE_RE.finditer(text):
            if m.group(1):  # abstract base class - skip
                continue
            if not has_localspec:
                findings.append(
                    Finding(
                        "localspec-coverage",
                        "WARNING",
                        path,
                        line_of(text, m.start()),
                        "class '{}' extends a *Module but the file carries no "
                        "@LocalSpec annotation".format(m.group(2)),
                    )
                )
                warn += 1
    return warn


def check_rawtop_wiring(rawtop_contracts, findings):
    err = 0
    rawtop_names = set(rawtop_contracts.keys())
    for path in walk_scala():
        if is_spec_path(path):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        bound = set(LOCALSPEC_CLASS_RE.findall(text))
        if not (bound & rawtop_names):
            continue
        for token in FORBIDDEN_RAWTOP:
            idx = text.find(token)
            if idx != -1:
                findings.append(
                    Finding(
                        "rawtop-wiring-only",
                        "ERROR",
                        path,
                        line_of(text, idx),
                        "rawTop design file contains '{}' - rawTops must be "
                        ":<>= wiring only, no behavioral logic".format(token),
                    )
                )
                err += 1
    return err


REG_QUEUE_RE = re.compile(r"(?<![A-Za-z0-9_])(Queue|RegEnable)\(")
RECOVERY_STANCE_RE = re.compile(r"recover|generation|recovery-exempt", re.IGNORECASE)


def check_reg_queue_recovery(findings):
    warn = 0
    for path in walk_scala():
        if is_spec_path(path):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        if RECOVERY_STANCE_RE.search(text):
            continue
        m = REG_QUEUE_RE.search(text)
        if m:
            findings.append(
                Finding(
                    "reg-queue-recovery",
                    "WARNING",
                    path,
                    line_of(text, m.start()),
                    "file instantiates '{}(' but states no recovery stance - confirm "
                    "RecoveryEvent handling, a transaction generation tag, or a "
                    "recovery-exempt classification (ADR-019)".format(m.group(1)),
                )
            )
            warn += 1
    return warn


def load_test_allowlist():
    allow = set()
    if os.path.exists(TEST_ALLOW_FILE):
        with open(TEST_ALLOW_FILE, "r", encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                t = raw.strip()
                if t and not t.startswith("#"):
                    allow.add(t)
    return allow


def collect_test_bindings():
    """Names bound by test artifacts (ADR-018 D-18.1): quoted spec-val strings in
    verif/**/*.scala (SpecTest verifies=Seq("...")) and `@verifies a b c` lines in
    verif/**/*.scn."""
    bound = set()
    quoted = re.compile(r'"((?:func|prop)\w+)"')
    verline = re.compile(r"^\s*@verifies\s+(.+)$", re.MULTILINE)
    for root, _dirs, files in os.walk(VERIF_ROOT):
        if os.sep + "out" in root:
            continue
        for fn in files:
            path = os.path.join(root, fn)
            if fn.endswith(".scala"):
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    bound.update(quoted.findall(fh.read()))
            elif fn.endswith(".scn"):
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    for m in verline.finditer(fh.read()):
                        bound.update(t for t in m.group(1).split() if t)
    return bound


def funcprop_decls():
    out = []
    for path in walk_scala():
        if not is_spec_path(path):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        for m in FUNCPROP_VAL_RE.finditer(text):
            out.append((m.group(1), path, line_of(text, m.start())))
    return out


def check_spec_test_coverage(findings):
    """ADR-018 D-18.5 (check 6): every FUNCTION/PROPERTY val is bound to a test
    artifact by name, or listed in tools/spec-test-allow.txt."""
    allow = load_test_allowlist()
    bound = collect_test_bindings()
    err = 0
    for name, path, line in funcprop_decls():
        if name in bound or name in allow:
            continue
        findings.append(
            Finding(
                "spec-test-coverage",
                "ERROR",
                path,
                line,
                "FUNCTION/PROPERTY '{}' has no test binding (SpecTest verifies / .scn "
                "@verifies) and is not listed in tools/spec-test-allow.txt (ADR-018)".format(name),
            )
        )
        err += 1
    return err


def untested_funcprops():
    bound = collect_test_bindings()
    return sorted(set(n for n, _p, _l in funcprop_decls() if n not in bound))


def main():
    update_allow = "--update-allow" in sys.argv[1:]
    update_test_allow = "--update-test-allow" in sys.argv[1:]

    rawtop_contracts, prop_decls, contract_blocks = parse_specs()

    if update_allow:
        names = unpaired_properties(prop_decls)
        with open(ALLOW_FILE, "w", encoding="utf-8") as fh:
            fh.write(
                "# spec-check-allow.txt - PROPERTY vals not yet paired with an\n"
                "# @LocalSpec design assert (ADR-015 D-15.2 check 2). Each name\n"
                "# here is a KNOWN interim gap that keeps the check green while\n"
                "# locking the door: no NEW unpaired PROPERTY may appear without\n"
                "# either a paired assert or an explicit line added here.\n"
                "# One val name per line. Remove a name once its design assert lands.\n"
            )
            for n in names:
                fh.write(n + "\n")
        print("wrote {} allowlisted PROPERTYs to {}".format(len(names), ALLOW_FILE))
        return 0

    if update_test_allow:
        names = untested_funcprops()
        with open(TEST_ALLOW_FILE, "w", encoding="utf-8") as fh:
            fh.write(
                "# spec-test-allow.txt - FUNCTION/PROPERTY vals not yet bound to a test\n"
                "# artifact (ADR-018 D-18.1: SpecTest verifies=Seq(...) or .scn @verifies).\n"
                "# Each name is a KNOWN interim gap that keeps check 6 green while locking\n"
                "# the door: no NEW unbound FUNCTION/PROPERTY may appear without either a\n"
                "# test binding or an explicit line here. One val name per line; remove a\n"
                "# name when its test lands (the red test comes BEFORE the implementation,\n"
                "# ADR-018 D-18.3).\n"
            )
            for n in names:
                fh.write(n + "\n")
        print("wrote {} allowlisted FUNCTION/PROPERTYs to {}".format(len(names), TEST_ALLOW_FILE))
        return 0

    findings = []
    counts = {}

    g_warn, g_err = check_graph_consistency(rawtop_contracts, contract_blocks, findings)
    counts["graph-consistency"] = (g_warn, g_err)

    p_err = check_property_to_assertion(prop_decls, findings)
    counts["property-to-assertion"] = (0, p_err)

    l_warn = check_localspec_coverage(findings)
    counts["localspec-coverage"] = (l_warn, 0)

    r_err = check_rawtop_wiring(rawtop_contracts, findings)
    counts["rawtop-wiring-only"] = (0, r_err)

    q_warn = check_reg_queue_recovery(findings)
    counts["reg-queue-recovery"] = (q_warn, 0)

    t_err = check_spec_test_coverage(findings)
    counts["spec-test-coverage"] = (0, t_err)

    errors = [f for f in findings if f.severity == "ERROR"]
    warnings = [f for f in findings if f.severity == "WARNING"]

    for f in errors:
        print(f.render())
    for f in warnings:
        print(f.render())

    print("")
    print("spec-check summary (rawTops: {}, PROPERTYs: {})".format(
        len(rawtop_contracts), len(prop_decls)))
    for chk in (
        "graph-consistency",
        "property-to-assertion",
        "localspec-coverage",
        "rawtop-wiring-only",
        "reg-queue-recovery",
        "spec-test-coverage",
    ):
        w, e = counts[chk]
        print("  {chk:24s} errors={e} warnings={w}".format(chk=chk, e=e, w=w))
    print("  {chk:24s} errors={e} warnings={w}".format(
        chk="TOTAL", e=len(errors), w=len(warnings)))

    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
