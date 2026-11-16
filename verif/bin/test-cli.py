#!/usr/bin/env python3
"""Check JSON output, preserved diagnostics and CLI process exit codes."""
import json
from pathlib import Path
import subprocess


def main():
    repo = Path(__file__).resolve().parents[2]
    output = repo / "verif/out/cli-regression"
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text('{"status": "running"}\n')
    source = """@name espresso-Verilator
@maxcycles 1000
li x1 5
addi x2, x1, 7
check x2 == 12
expect done
"""
    good = output / "pass.scn"
    bad = output / "fail.scn"
    good.write_text(source)
    bad.write_text(source.replace("check x2 == 12", "check x2 == 13"))
    checks = []

    def run(label, args, expected=0, json_output=True):
        result = subprocess.run(args, cwd=repo, text=True, capture_output=True)
        (output / (label + ".stdout")).write_text(result.stdout)
        (output / (label + ".stderr")).write_text(result.stderr)
        if result.returncode != expected:
            raise AssertionError(f"{label}: exit {result.returncode}, expected {expected}; see {output}")
        data = json.loads(result.stdout) if json_output else result
        return data

    cli = str(repo / "verif/bin/scn.sh")
    single = run("run", [cli, "run", str(good), "--json"])
    assert single["name"] == "espresso-Verilator" and single["pass"] and single["done"]
    checks.append("run preserves JSON containing former filter words")
    failure = run("check-failure", [cli, "run", str(bad), "--json"], expected=1)
    assert not failure["pass"] and failure["done"]
    checks.append("failed architectural check returns exit 1 and valid JSON")
    batch = run("batch", [cli, "batch", str(good), str(good), "--json"])
    assert len(batch) == 2 and all(r["pass"] and r["done"] for r in batch)
    checks.append("batch returns one JSON array")
    trace = run("trace", [cli, "trace", str(good), "--cycles=200", "--json"])
    assert trace["name"] == "espresso-Verilator" and len(trace["rows"]) == 200
    checks.append("trace returns one complete JSON object")
    gate = run("gate", [cli, "gate", str(good), str(good), "--json"])
    assert gate["verdict"] == "INCONCLUSIVE" and not gate["differential"]["differ"]
    assert gate["latency_invariant"] and gate["harness_invariant"]
    checks.append("gate keeps identical controls inconclusive")
    card = run("describe", [cli, "describe"])
    assert card["framework"] == "UDACore verification harness"
    checks.append("describe returns one JSON capability card")
    runner = str(repo / "verif/bin/run.sh")
    missing = run("missing-main", [runner, "verif.NoSuchMainForExitCheck"], expected=1, json_output=False)
    assert "ClassNotFoundException" in missing.stderr
    checks.append("Java failure exit and diagnostic reach the caller")
    emitted = output / "espresso-product"
    run("emit", [runner, "verif.suites.EmitCore", "default", str(emitted)], json_output=False)
    assert (emitted / "CoreTop.sv").is_file()
    checks.append("successful emission exits 0 when its path contains espresso")
    summary = {"status": "pass", "pass": len(checks), "fail": 0, "checks": checks}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    for check in checks:
        print("[PASS] " + check)
    print(f"CLI regression: {len(checks)} PASS; logs in {output}")


if __name__ == "__main__":
    main()
