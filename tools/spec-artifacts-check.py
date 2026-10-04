#!/usr/bin/env python3
"""Reject missing, duplicate or malformed metadata before accepting a build."""
import json
from pathlib import Path
import sys


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check(meta, report):
    raw_specs = []
    for path in meta.glob("*.spec"):
        text = path.read_text()
        raw_specs.append(json.loads(text[text.index("{"):]))
    raw_tags = [json.loads(p.read_text()) for p in meta.glob("*.tag")]
    specs = json.loads((report / "SpecIndex.json").read_text())
    tags = json.loads((report / "TagIndex.json").read_text())
    require(specs and tags, "SpecIndex and TagIndex must both be nonempty")
    ids = [s["id"] for s in raw_specs]
    require(len(ids) == len(set(ids)), "Duplicate spec IDs would be silently merged")
    require(len(specs) == len(raw_specs), "SpecCheck dropped spec artifacts")
    require(len(tags) == len(raw_tags), "SpecCheck dropped tag artifacts")
    known = set(ids)
    for spec in specs:
        require(spec["category"] and spec["scalaDeclarationPath"], spec)
        for relation in ("is", "has", "uses"):
            require(set(spec.get(relation, [])) <= known, (spec["id"], relation))
    for tag in tags:
        require(tag["id"] in known, f"Unknown tag spec: {tag['id']}")
        require(tag["scalaDeclarationPath"] and tag["srcFile"], tag)
    print(f"[spec-artifacts] {len(specs)} specs, {len(tags)} tags; all references resolve")


if __name__ == "__main__":
    check(Path(sys.argv[1]), Path(sys.argv[2]))
