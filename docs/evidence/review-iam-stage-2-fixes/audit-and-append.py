from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
FIX = ROOT / "docs/evidence/iam-stage-2-review-fixes"
REPORT = ROOT / "docs/evidence/review-iam-stage-2.md"
REPORT_KEY = REPORT.relative_to(ROOT).as_posix()

def sha(data):
    return hashlib.sha256(data).hexdigest()

def check(path, base, allowed=None):
    values = json.loads(path.read_text(encoding="utf-8"))
    mismatches = [name for name, expected in values.items() if sha((base / name).read_bytes()) != expected]
    assert set(mismatches) == set(allowed or []), (path.name, mismatches)
    return {"entries": len(values), "mismatches": mismatches}

assert sha((FIX / "full.patch").read_bytes()) == "df1aad38462d57a0e9ccbca5cc86f85445861b01372f2950486b79fb98c61abb"
before = {
    "source": check(FIX / "source-after.json", ROOT),
    "target": check(FIX / "target-sha256.json", ROOT / "admin-common/target"),
    "fix_manifest": check(FIX / "manifest.json", ROOT),
    "history": check(FIX / "prior-evidence-after.json", ROOT),
}
old = REPORT.read_bytes()
assert sha(old) == "bc88528e9d8126718492f500cce191f79c540677b68dc3d75e16d82c04b6b269"
with (OUT / "report-prefix-before.md").open("xb") as saved:
    saved.write(old)
addition = (OUT / "append.md").read_bytes()
with REPORT.open("ab") as destination:
    destination.write(addition)
new = REPORT.read_bytes()
assert new == old + addition
after = {
    "source": check(FIX / "source-after.json", ROOT),
    "target": check(FIX / "target-sha256.json", ROOT / "admin-common/target"),
    "fix_manifest": check(FIX / "manifest.json", ROOT),
    "history": check(FIX / "prior-evidence-after.json", ROOT, [REPORT_KEY]),
}
result = {
    "before": before, "after": after,
    "original_report_bytes": len(old), "original_report_sha256": sha(old),
    "appended_report_sha256": sha(new), "original_prefix_preserved": new.startswith(old),
    "append_sha256": sha(addition),
    "commands_executed": ["python docs/evidence/review-iam-stage-2-fixes/audit-and-append.py"],
    "test_commands_executed": [],
}
with (OUT / "audit.json").open("x", encoding="utf-8") as audit:
    json.dump(result, audit, ensure_ascii=False, indent=2)
print(json.dumps(result, ensure_ascii=False, indent=2))
