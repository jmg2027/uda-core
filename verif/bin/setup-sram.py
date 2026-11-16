#!/usr/bin/env python3
"""Fetch the pinned OpenRAM macro views and verify every source checksum."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    verif = Path(__file__).resolve().parents[1]
    manifest = json.loads((verif / "sta/sram/manifest.json").read_text())
    sta = Path(os.environ.get("VERIF_STA_HOME", "/tmp/verif-sta"))
    root = Path(os.environ.get("VERIF_SRAM_HOME", str(sta / "sram")))
    target = root / manifest["macro"]
    target.mkdir(parents=True, exist_ok=True)
    base = "https://raw.githubusercontent.com/VLSIDA/sky130_sram_macros/" + manifest["commit"]
    for name, info in manifest["files"].items():
        file = target / name
        valid = file.is_file() and hashlib.sha256(file.read_bytes()).hexdigest() == info["sha256"]
        if not valid:
            if args.verify_only:
                raise SystemExit(f"SRAM view missing or checksum mismatch: {file}")
            relative = name if name == "LICENSE" else manifest["macro"] + "/" + name
            data = urllib.request.urlopen(base + "/" + relative, timeout=60).read()
            if hashlib.sha256(data).hexdigest() != info["sha256"]:
                raise SystemExit(f"Downloaded SRAM checksum mismatch: {name}")
            temporary = file.with_name(file.name + ".tmp")
            temporary.write_bytes(data)
            temporary.replace(file)
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"[setup-sram] verified {manifest['macro']} at {manifest['commit']}: {target}")


if __name__ == "__main__":
    main()
