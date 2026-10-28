"""Scan this EX-00 run without printing configured secret values.
Raw logs and XML are local-only evidence; only the explicitly listed candidates
are eligible for a later commit. Run after all collectors have closed their logs.
"""
import json
import re
from pathlib import Path

AP = Path(r"G:\207\项目\算法重组工具\代码\agent-platform")
OR = Path(r"G:\207\项目\算法重组工具\代码\ontorepo")
RUN_ID = "20261028-164553-272"
secret_values = {}
for line in (AP / ".env").read_text(encoding="utf-8-sig").splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        if re.search(r"PASSWORD|SECRET|TOKEN|(?:^|_)KEY(?:$|_)", key, re.I) and len(value) >= 8:
            secret_values["AP:" + key] = value
for key, value in json.loads((OR / ".runtime/local-env.json").read_text(encoding="utf-8-sig")).items():
    if re.search(r"PASSWORD|SECRET|TOKEN|(?:^|_)KEY(?:$|_)", key, re.I) and len(str(value)) >= 8:
        secret_values["OR:" + key] = str(value)
jwt_pattern = re.compile(r"\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
for root in (AP, OR):
    evidence = root / "docs/evidence/2026-10-28-EX-00"
    run = evidence / "baseline" / RUN_ID
    findings = []
    examined = 0
    for path in sorted(evidence.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in {".txt", ".json", ".csv", ".xml", ".md", ".ps1", ".py"}:
            continue
        if path.name == "sensitive-scan.json":
            continue
        examined += 1
        data = path.read_text(encoding="utf-8-sig", errors="replace")
        for key, value in secret_values.items():
            count = data.count(value)
            if count:
                findings.append({"path": path.relative_to(evidence).as_posix(), "kind": "configured-secret", "key": key, "matches": count})
        count = len(jwt_pattern.findall(data))
        if count:
            findings.append({"path": path.relative_to(evidence).as_posix(), "kind": "jwt-pattern", "matches": count})
    result = {"runId": RUN_ID, "filesExamined": examined, "findings": findings,
              "rawLogsAndXmlEligibleForCommit": False,
              "scope": "Exact current configured secret values plus JWT patterns. This scan does not prove absence of every possible sensitive value."}
    (run / "sensitive-scan.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(root.name, "files", examined, "findings", len(findings))
