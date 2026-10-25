"""Own-scope byte manifests and a real replayable no-HEAD patch; no runtime operations."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
root = evidence.parents[2]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources(directory):
    return sorted([directory / "skill-registry/pom.xml", *
        (directory / "skill-registry/src").rglob("*")], key=lambda p: p.as_posix())


def manifest(directory):
    return {p.relative_to(directory).as_posix(): digest(p)
            for p in sources(directory) if p.is_file()}


def save(name, value):
    (evidence / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if sys.argv[1] == "before":
    before = manifest(root)
    save("final-source-before.json", before)
    target = evidence / "frozen/skill-registry"
    if not target.exists():
        target.mkdir(parents=True, exist_ok=False)
        shutil.copytree(root / "skill-registry/src", target / "src")
        shutil.copy2(root / "skill-registry/pom.xml", target / "pom.xml")
    assert manifest(evidence / "frozen") == before
    baseline = manifest(evidence / "baseline")
    save("changes.json", [{"path": p, "status": "added" if p not in baseline else
            "deleted" if p not in before else "modified", "beforeSha256": baseline.get(p),
            "afterSha256": before.get(p)} for p in sorted(baseline.keys() | before.keys())
            if baseline.get(p) != before.get(p)])
    diff = subprocess.run(["git", "-c", "core.autocrlf=false", "diff", "--no-index", "--binary", "--no-renames", "--unified=100000",
            "--", "baseline/skill-registry", "frozen/skill-registry"], cwd=evidence, capture_output=True)
    assert diff.returncode == 1 and diff.stdout, diff.stderr
    patch = diff.stdout
    for side in (b"a/", b"b/"):
        for directory in (b"baseline/", b"frozen/"):
            patch = patch.replace(side + directory, side)
    patch_path = evidence / "skill-expected-attempt.patch"
    patch_path.write_bytes(patch)
    replay = evidence / "patch-replay"
    shutil.copytree(evidence / "baseline", replay)
    checks = []
    environment = {**os.environ, "GIT_CEILING_DIRECTORIES": str(evidence)}
    for args in (["git", "-c", "core.autocrlf=false", "apply", "--check", str(patch_path)],
                 ["git", "-c", "core.autocrlf=false", "apply", str(patch_path)]):
        done = subprocess.run(args, cwd=replay, env=environment, capture_output=True, text=True)
        checks.append({"command": args, "exitCode": done.returncode, "stdout": done.stdout, "stderr": done.stderr})
        assert done.returncode == 0, checks
    assert manifest(replay) == before
    save("patch-replay-verification.json", {"patchSha256": digest(patch_path), "checks": checks,
            "replayedFiles": len(before), "byteIdenticalToFrozen": True})
    print("before: frozen", len(before), "files; patch replay byte-identical")
elif sys.argv[1] == "after":
    before = json.loads((evidence / "final-source-before.json").read_text(encoding="utf-8"))
    after = manifest(root)
    save("final-source-after.json", after)
    assert before == after == manifest(evidence / "frozen"), "Source changed during final full run"
    entries = json.loads((evidence / "protected-before.json").read_text(encoding="utf-8-sig"))
    changed = [{"path": e["path"], "beforeSha256": e["sha256"],
            "afterSha256": digest(root / e["path"]) if (root / e["path"]).exists() else None}
            for e in entries if not (root / e["path"]).exists() or digest(root / e["path"]) != e["sha256"]]
    save("protected-verification.json", {"checked": len(entries), "unchanged": len(entries) - len(changed), "changed": changed})
    reports = evidence / "final-surefire"
    reports.mkdir(exist_ok=False)
    suites = []
    for p in sorted((root / "skill-registry/target/surefire-reports").glob("TEST-*.xml")):
        shutil.copy2(p, reports / p.name)
        a = ET.parse(p).getroot().attrib
        suites.append({k: a[k] for k in ("name", "tests", "failures", "errors", "skipped", "time")})
    save("final-test-summary.json", {"totals": {k: sum(int(s[k]) for s in suites)
            for k in ("tests", "failures", "errors", "skipped")}, "suites": suites})
    print("after: source unchanged; protected differences", len(changed))
else:
    raise SystemExit("Use before or after")
