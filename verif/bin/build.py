#!/usr/bin/env python3
"""Clean, staged compilation; macro definitions precede all macro consumers."""
import os
from pathlib import Path
import shutil
import subprocess

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "verif/out"
FRAMEWORK = Path(os.environ.get("SPEC_FRAMEWORK_HOME", REPO.parent / "spec-framework")).resolve()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / ".ready").unlink(missing_ok=True)
    for module in ("spec-core", "spec-macros"):
        if not (FRAMEWORK / module / "src/main/scala").is_dir():
            raise SystemExit(f"Missing {FRAMEWORK / module}; clone spec-framework beside uda-core "
                             "or set SPEC_FRAMEWORK_HOME.")
    classes = OUT / "classes"
    meta = OUT / "spec-meta"
    report = OUT / "spec-report"
    # Empty outputs remove stale UUID metadata and prevent running old RTL after failure.
    for path in (classes, meta, report):
        if path.exists():
            shutil.rmtree(path)
        path.mkdir()
    cp = os.environ["VERIF_CHISEL_CP"]
    compiler = os.environ["VERIF_SCALAC"]
    log = OUT / "build.log"

    def compile_stage(name, sources, chisel=False):
        print(f"[build] {name}: {len(sources)} sources", flush=True)
        cmd = ["java", "-Xss16m", f"-Dspec.meta.dir={meta}", "-cp", compiler + ":" + cp,
               "scala.tools.nsc.Main", "-Ymacro-annotations", "-d", str(classes),
               "-classpath", cp + ":" + str(classes)]
        if chisel:
            cmd.append("-Xplugin:" + os.environ["VERIF_PLUGIN"])
        result = subprocess.run(cmd + [str(p) for p in sources], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)
        with log.open("a") as fh:
            fh.write(f"\n[build] {name}\n" + result.stdout)
        if result.returncode:
            print(result.stdout)
            raise SystemExit(result.returncode)

    log.write_text("")
    for module in ("spec-core", "spec-macros"):
        compile_stage(module, sorted((FRAMEWORK / module / "src/main/scala").rglob("*.scala")))
    sources = sorted((REPO / "src/main/scala").rglob("*.scala"))
    specs = [p for p in sources if p.name.endswith("Specs.scala")]
    compile_stage("UDA specs", specs)
    compile_stage("UDA RTL + verification", [p for p in sources if p not in specs] +
                  sorted((REPO / "verif/src").rglob("*.scala")) +
                  sorted((REPO / "verif/suites").rglob("*.scala")), chisel=True)
    subprocess.run(["java", "-cp", cp + ":" + str(classes), "framework.spec.SpecCheck",
                    str(meta), str(report), "--top=CoreTop"], check=True)
    subprocess.run(["python3", str(REPO / "tools/spec-artifacts-check.py"), str(meta), str(report)],
                   check=True)
    (OUT / ".ready").touch()
    print(f"[build] done; compiler log: {log}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode)
