"""Run once after the final test process has exited; never invokes Maven or changes sources."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = ROOT / "docs/evidence/iam-stage-4-d1"
assert ROOT.name == "agent-platform"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dump(name, value):
    (EVIDENCE / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def manifest(paths, relative_to=ROOT):
    return [{"path": p.relative_to(relative_to).as_posix(), "bytes": p.stat().st_size,
             "sha256": digest(p)} for p in sorted(paths)]


before = json.loads((EVIDENCE / "source-before.json").read_text(encoding="utf-8"))
for item in before:
    assert digest(EVIDENCE / "baseline" / item["path"]) == item["sha256"]

protected = json.loads((EVIDENCE / "protected-before.json").read_text(encoding="utf-8"))
protected_changes = [p["path"] for p in protected if not (ROOT / p["path"]).is_file()
                     or digest(ROOT / p["path"]) != p["sha256"]]
dump("protected-after-check.json", {"count": len(protected), "changes": protected_changes})
assert not protected_changes, protected_changes

source = [ROOT / "admin-common/pom.xml",
          *[p for p in (ROOT / "admin-common/src").rglob("*") if p.is_file()]]
for p in source:
    target = EVIDENCE / "frozen" / p.relative_to(ROOT)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(p, target)
dump("source-after.json", manifest(source))

target_files = [p for p in (ROOT / "admin-common/target").rglob("*") if p.is_file()]
dump("target-frozen.json", manifest(target_files))
(EVIDENCE / "final-xml").mkdir()
tests = []
for p in sorted((ROOT / "admin-common/target/surefire-reports").glob("TEST-*.xml")):
    shutil.copyfile(p, EVIDENCE / "final-xml" / p.name)
    xml = ET.parse(p).getroot()
    tests.append({"class": xml.get("name"), "tests": int(xml.get("tests")),
                  "failures": int(xml.get("failures")), "errors": int(xml.get("errors")),
                  "skipped": int(xml.get("skipped")),
                  "cases": [node.get("name") for node in xml.findall("testcase")]})
dump("final-test-summary.json", {"suites": tests, "totals": {
    k: sum(row[k] for row in tests) for k in ("tests", "failures", "errors", "skipped")}})
assert sum(row["failures"] + row["errors"] for row in tests) == 0

commands = []


def command(args, cwd, env=None, accepted=(0,)):
    result = subprocess.run(args, cwd=cwd, env=env, capture_output=True)
    commands.append({"command": args, "cwd": str(cwd), "exitCode": result.returncode,
                     "stdout": result.stdout.decode("utf-8", errors="replace"),
                     "stderr": result.stderr.decode("utf-8", errors="replace")})
    assert result.returncode in accepted, commands[-1]
    return result


command(["git", "rev-parse", "--verify", "HEAD"], ROOT, accepted=(128,))
command(["git", "remote", "-v"], ROOT)
diff = command(["git", "-c", "core.autocrlf=false", "diff", "--no-index", "--binary",
                "--no-ext-diff", "--no-renames", "--src-prefix=a/", "--dst-prefix=b/",
                "baseline", "frozen"], EVIDENCE, accepted=(1,))
patch = diff.stdout
for old, new in [(b"a/baseline/", b"a/"), (b"b/frozen/", b"b/"),
                 (b"a/frozen/", b"a/"), (b"b/baseline/", b"b/")]:
    patch = patch.replace(old, new)
assert patch.startswith(b"diff --git ") and len(patch) > 0
(EVIDENCE / "full.patch").write_bytes(patch)
# The diff is the artifact itself; avoid storing a second full copy in command JSON.
commands[-1]["stdout"] = f"Saved {len(patch)} patch bytes to full.patch after path-prefix normalization."

replay = EVIDENCE / "replay"
shutil.copytree(EVIDENCE / "baseline", replay)
env = os.environ.copy()
# Prevent discovering the parent unborn repository. git apply supports standalone directories;
# no synthetic HEAD, index, repository, commit, or remote is created.
env["GIT_CEILING_DIRECTORIES"] = str(EVIDENCE)
command(["git", "-c", "core.autocrlf=false", "apply", "--check", str(EVIDENCE / "full.patch")],
        replay, env)
command(["git", "-c", "core.autocrlf=false", "apply", str(EVIDENCE / "full.patch")],
        replay, env)
replayed = [p for p in replay.rglob("*") if p.is_file()]
expected = manifest(source)
actual = manifest(replayed, replay)
assert expected == actual, "git apply output differs from frozen source bytes"
dump("replay-byte-check.json", {"equal": True, "files": len(actual),
                              "patchBytes": len(patch), "patchSha256": digest(EVIDENCE / "full.patch"),
                              "sourceManifestSha256": digest(EVIDENCE / "source-after.json"),
                              "baselineManifestSha256": digest(EVIDENCE / "source-before.json")})
dump("freeze-commands.json", commands)
dump("freeze-summary.json", {
    "baselineFiles": len(before), "frozenFiles": len(source), "targetFiles": len(target_files),
    "protectedFiles": len(protected), "protectedUnchanged": not protected_changes,
    "patchSha256": digest(EVIDENCE / "full.patch"),
    "replayVerified": expected == actual,
    "testTotals": {k: sum(row[k] for row in tests)
                   for k in ("tests", "failures", "errors", "skipped")},
})
print((EVIDENCE / "freeze-summary.json").read_text(encoding="utf-8"))
