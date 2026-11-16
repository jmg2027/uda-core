#!/usr/bin/env python3
"""Compare the current inline SRAM model with checksum-pinned OpenRAM in Icarus."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def functional_model(source, macro):
    matches = re.findall(
        r'setInline\(\s*"sky130_cache_sram_functional\.sv"\s*,\s*"""(.*?)"""\s*\)',
        source,
        re.DOTALL,
    )
    if len(matches) != 1:
        raise ValueError("Expected one literal SRAM setInline model in CacheLineSram.scala")
    model, count = re.subn(
        r"\bmodule\s+" + re.escape(macro) + r"\b",
        "module uda_sram_cycle_model",
        matches[0],
    )
    if count != 1:
        raise ValueError("Expected one SRAM module declaration in the inline model")
    return "`timescale 1ns/1ps\n" + model.lstrip("\n")


def vendor_fixture(source):
    # Icarus needs this array declared before its first diagnostic reference.
    pattern = r"(?m)^[ \t]*reg[ \t]+\[DATA_WIDTH-1:0\][ \t]+mem[ \t]+\[0:RAM_DEPTH-1\];[ \t]*$"
    declarations = re.findall(pattern, source)
    if len(declarations) != 1 or "`timescale" in source:
        raise ValueError("Pinned vendor declaration layout changed; review fixture adaptation")
    declaration = declarations[0].strip()
    adapted = re.sub(pattern, "", source, count=1)
    adapted, count = re.subn(
        r"(?m)^  reg  csb0_reg;",
        "  " + declaration + "\n\n  reg  csb0_reg;",
        adapted,
    )
    if count != 1:
        raise ValueError("Cannot locate vendor module declaration insertion point")
    # Retain DELAY=3, T_HOLD=1, all procedural code, and all assignments.
    return "`timescale 1ns/1ps\n" + adapted


def run_logged(command, log):
    with log.open("w") as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        tail = "\n".join(log.read_text().splitlines()[-8:])
        raise RuntimeError(f"Command exited {result.returncode}; see {log}\n{tail}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", type=Path, help="Generated files (default: verif/out/sram-model)")
    args = parser.parse_args()
    verif = Path(__file__).resolve().parents[1]
    repo = verif.parent
    output = (args.out_dir or verif / "out/sram-model").resolve()
    output.mkdir(parents=True, exist_ok=True)
    summary_path = output / "summary.json"
    summary = {"status": "running"}

    def save():
        summary_path.write_text(json.dumps(summary, indent=2) + "\n")

    save()
    try:
        manifest_path = verif / "sta/sram/manifest.json"
        manifest = json.loads(manifest_path.read_text())
        macro = manifest["macro"]
        sta = Path(os.environ.get("VERIF_STA_HOME", str(repo.parent / ".uda-toolchain/sta")))
        sram = Path(os.environ.get("VERIF_SRAM_HOME", str(sta / "sram")))
        vendor_path = sram / macro / (macro + ".v")
        if not vendor_path.is_file():
            raise ValueError(f"Missing {vendor_path}; run bash verif/bin/setup-sram.sh")
        vendor_bytes = vendor_path.read_bytes()
        expected_sha = manifest["files"][vendor_path.name]["sha256"]
        if sha256(vendor_bytes) != expected_sha:
            raise ValueError(f"Vendor checksum mismatch: {vendor_path}")
        source_path = repo / "src/main/scala/udacore/core/design/modules/CacheLineSram.scala"
        source_bytes = source_path.read_bytes()
        fixture_path = verif / "tests/sram/SramModelCheck.sv"
        model_path = output / "cycle_model.sv"
        vendor_fixture_path = output / "vendor_ordered.v"
        model_path.write_text(functional_model(source_bytes.decode("ascii"), macro))
        vendor_fixture_path.write_text(vendor_fixture(vendor_bytes.decode("ascii")))
        iverilog = shutil.which("iverilog")
        vvp = shutil.which("vvp")
        if not iverilog or not vvp:
            raise ValueError("Icarus tools iverilog and vvp must be available on PATH")
        summary.update({
            "vendor_repository": manifest["repository"],
            "vendor_commit": manifest["commit"],
            "vendor_source": str(vendor_path),
            "vendor_sha256": expected_sha,
            "manifest_sha256": sha256(manifest_path.read_bytes()),
            "functional_source": str(source_path),
            "functional_source_sha256": sha256(source_bytes),
            "extracted_model_sha256": sha256(model_path.read_bytes()),
            "adapted_vendor_sha256": sha256(vendor_fixture_path.read_bytes()),
            "fixture_sha256": sha256(fixture_path.read_bytes()),
            "vendor_adaptations": ["timescale 1ns/1ps", "move memory declaration before first reference"],
            "vendor_delay": 3,
            "vendor_t_hold": 1,
            "iverilog": iverilog,
            "vvp": vvp,
        })
        compile_command = [
            iverilog, "-g2012", "-s", "SramModelCheck", "-o", str(output / "check.vvp"),
            str(fixture_path), str(vendor_fixture_path), str(model_path),
        ]
        run_command = [vvp, str(output / "check.vvp")]
        summary.update({"compile_command": compile_command, "run_command": run_command})
        save()
        run_logged(compile_command, output / "iverilog-build.log")
        run_logged(run_command, output / "result.log")
        result = (output / "result.log").read_text()
        matches = re.findall(r"SRAM_MODEL_CHECK_PASS cycles=(\d+) read_checks=(\d+)", result)
        if matches != [("9217", "26112")]:
            raise ValueError("Expected complete 9217-cycle / 26112-read-check result marker")
        summary.update({"status": "pass", "cycles": 9217, "read_checks": 26112})
        save()
        print(result.strip())
        print(f"[sram-model] verified vendor {manifest['commit']}; summary: {summary_path}")
    except (OSError, ValueError, RuntimeError) as error:
        summary.update({"status": "fail", "error": str(error)})
        save()
        print(f"[sram-model] {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
