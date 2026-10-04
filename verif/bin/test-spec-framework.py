#!/usr/bin/env python3
"""Exercise real macros, reference failures and the large-consumer regressions."""
import importlib.util
import json
import os
from pathlib import Path
import resource
import subprocess
import tempfile

REPO = Path(__file__).resolve().parents[2]
CLASSES = REPO / "verif/out/classes"


def run(cmd, success=True, **kwargs):
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, **kwargs)
    if (result.returncode == 0) != success:
        raise AssertionError(result.stdout)
    return result.stdout


def main():
    if not (CLASSES / "framework/macros/LocalSpec.class").exists():
        raise SystemExit("Compile with verif/bin/build.sh first")
    with tempfile.TemporaryDirectory(prefix="uda-spec-test-") as temp:
        root = Path(temp)
        meta = root / "meta"
        classes = root / "classes"
        meta.mkdir()
        classes.mkdir()
        cp = os.environ["VERIF_CHISEL_CP"] + ":" + str(CLASSES) + ":" + str(classes)
        compiler = os.environ["VERIF_SCALAC"] + ":" + cp

        def compile_source(name, text, success=True):
            path = root / name
            path.write_text(text)
            return run(["java", f"-Dspec.meta.dir={meta}", "-cp", compiler,
                        "scala.tools.nsc.Main", "-Ymacro-annotations", "-classpath", cp,
                        "-d", str(classes), str(path)], success=success)

        compile_source("Specs.scala", '''
package integration
import framework.macros.SpecEmit.spec
import framework.spec.Spec._
object Specs {
  val module = spec { CONTRACT("Example").desc("Fixture").has(port).uses(width).build() }
  val port = spec { INTERFACE("Port").desc("Port fixture").build() }
  val width = spec { PARAMETER("Width").desc("Width fixture").build() }
}
''')
        compile_source("Design.scala", '''
package integration
import framework.macros.LocalSpec
@LocalSpec(Specs.module)
case class Design(@LocalSpec(Specs.width) width: Int) {
  @LocalSpec(Specs.port) val port = width
}
object Design { val preservedCompanion = 42 }
object Check extends App {
  assert(Design.preservedCompanion == 42)
  assert(Design(7).copy(width = 9).port == 9)
  for (_ <- 0 until 1000) {
    assert(framework.spec.SpecIndex.idFor("integration.Specs.width").contains("Width"))
  }
}
''')

        def limited_files():
            _, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
            resource.setrlimit(resource.RLIMIT_NOFILE, (min(128, hard), hard))

        run(["java", f"-Dspec.meta.dir={meta}", "-cp", cp, "integration.Check"],
            preexec_fn=limited_files)
        report = root / "report"
        run(["java", "-cp", cp, "framework.spec.SpecCheck", str(meta), str(report), "--strict"])
        specs = json.loads((report / "SpecIndex.json").read_text())
        tags = json.loads((report / "TagIndex.json").read_text())
        assert len(specs) == 3 and len(tags) == 3
        assert next(s for s in specs if s["id"] == "Example")["has"] == ["Port"]
        assert any(t["id"] == "Width" and t["scalaDeclarationPath"].endswith(".width") for t in tags)
        print("PASS: forward refs, constructor annotation, companion, bounded file handles")

        checker = ["python3", str(REPO / "tools/spec-artifacts-check.py"), str(meta), str(report)]
        run(checker)
        duplicate = meta / "duplicate.spec"
        duplicate.write_text(next(meta.glob("*.spec")).read_text())
        assert "Duplicate spec IDs" in run(checker, success=False)
        duplicate.unlink()
        malformed = meta / "malformed.tag"
        malformed.write_text("not JSON")
        run(checker, success=False)
        malformed.unlink()
        print("PASS: artifact gate rejects duplicate IDs and malformed metadata")

        output = compile_source("Bad.scala", '''
package integration
import framework.macros.SpecEmit.spec
import framework.spec.Spec._
object Bad { val bad = spec { CONTRACT("Bad").desc("bad").has(Specs.missing).build() } }
''', success=False)
        assert "missing" in output
        compile_source("Dangling.scala", '''
package integration
import framework.macros.SpecEmit.spec
import framework.spec.Spec._
object Dangling { val bad = spec { CONTRACT("Dangling").desc("bad").has("Absent").build() } }
''')
        output = run(["java", "-cp", cp, "framework.spec.SpecCheck", str(meta), str(report)],
                     success=False)
        assert "Absent" in output
        print("PASS: nonexistent value fails compilation; dangling string fails SpecCheck")

    # Exercise the actual build driver's failure path without touching repository RTL.
    spec = importlib.util.spec_from_file_location("uda_build", REPO / "verif/bin/build.py")
    build = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(build)
    with tempfile.TemporaryDirectory(prefix="uda-build-failure-") as temp:
        build.REPO = Path(temp)
        build.OUT = Path(temp) / "out"
        build.FRAMEWORK = Path(temp) / "framework"
        for module in ("spec-core", "spec-macros"):
            (build.FRAMEWORK / module / "src/main/scala").mkdir(parents=True)
        (build.FRAMEWORK / "spec-core/src/main/scala/Invalid.scala").write_text("invalid scala")
        build.OUT.mkdir()
        (build.OUT / ".ready").touch()
        try:
            build.main()
        except SystemExit as error:
            assert error.code != 0
        else:
            raise AssertionError("Build accepted a compiler error")
        assert not (build.OUT / ".ready").exists()
        print("PASS: build propagates compiler failure and clears readiness")


if __name__ == "__main__":
    main()
