#!/usr/bin/env python3
"""SEM-14 交付自检：manifest 结构、引用文件与摘要、文档交叉引用、脱敏与 BLOCKED 登记。

用法：python docs/evidence/semantic-upgrade-20261103/SEM-14/verify-sem14.py
退出码：0 全通过；1 有失败项。
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve()
AP = HERE.parents[4]
OR = AP.parent / "ontorepo"
MANIFEST = AP / "docs/integration/semantic-runtime-delivery.json"
DOC = AP / "docs/integration/semantic-runtime.md"

FAILS: list[str] = []
PASSES: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        PASSES.append(name)
        print("PASS " + name + ((" — " + detail) if detail else ""))
    else:
        FAILS.append(name + ((" — " + detail) if detail else ""))
        print("FAIL " + name + ((" — " + detail) if detail else ""))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    check("manifest 文件存在", MANIFEST.is_file())
    check("交付文档存在", DOC.is_file())
    if not (MANIFEST.is_file() and DOC.is_file()):
        return report()

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    doc = DOC.read_text(encoding="utf-8")

    for key in (
        "manifestVersion", "deliveryId", "generatedAt", "status", "contracts",
        "profiles", "components", "capabilities", "supportedResources",
        "consistency", "blocked", "sanitization",
    ):
        check("manifest 顶层键 " + key, key in manifest)

    check("deliveryId", manifest.get("deliveryId") == "semantic-runtime@0.1")
    check("status 明确为受阻态", manifest.get("status") == "DOCUMENTED_BLOCKED")

    # 契约/摘要
    contracts = manifest["contracts"]
    ap_md = AP / contracts["ap"]["path"]
    ap_schema = AP / contracts["schema"]["path"]
    or_md = OR / contracts["orConsumption"]["path"]
    or_openapi = OR / contracts["orOpenapi"]["path"]
    for label, path, digest in (
        ("AP 契约", ap_md, contracts["ap"]["sha256"]),
        ("记录 Schema", ap_schema, contracts["schema"]["sha256"]),
        ("OR 消费口径", or_md, contracts["orConsumption"]["sha256"]),
        ("OR openapi", or_openapi, contracts["orOpenapi"]["sha256"]),
    ):
        exists = path.is_file()
        check(label + " 存在", exists, str(path))
        if exists:
            check(label + " 摘要一致", sha256(path) == digest, sha256(path))

    # profile
    profile_ids = [p.get("id") for p in manifest.get("profiles", [])]
    for expected in ("read-only@1.0", "condition@1.0", "decision@1.0", "goal@1.0"):
        check("profile " + expected, expected in profile_ids)

    # 两类资源与同标准复用
    resources = manifest.get("supportedResources", [])
    check("受支持资源恰为两类", len(resources) == 2)
    for item in resources:
        check("资源 " + str(item.get("id")) + " 无产品分支", item.get("codeBranchPerProduct") is False)
        check("资源 " + str(item.get("id")) + " 交接仍受阻", item.get("handoverStatus") == "BLOCKED")
        check("资源 " + str(item.get("id")) + " 已列仅配置项", bool(item.get("configOnly")))

    # 组件提交存在性（需要 git；失败不掩盖其它检查）
    for comp in manifest.get("components", []):
        repo = AP if comp["repo"] == "agent-platform" else OR
        try:
            proc = subprocess.run(
                ["git", "-C", str(repo), "cat-file", "-e", comp["commit"] + "^{commit}"],
                capture_output=True, text=True,
            )
            ok = proc.returncode == 0
            detail = "" if ok else (proc.stderr or "").strip()
        except OSError as exc:  # pragma: no cover - 环境缺 git 时如实记录
            ok, detail = False, str(exc)
        check("提交存在 " + comp["repo"] + "@" + comp["commit"], ok, detail)

    # 文档交叉引用
    for cap in manifest.get("capabilities", []):
        path = cap["path"]
        if path.startswith("/"):
            check("文档列出端点 " + path, path in doc)
    for blocked in manifest.get("blocked", []):
        check("文档登记 BLOCKED " + blocked["id"], blocked["id"] in doc)
    check("文档声明不冒充交接完成", "BLOCKED" in doc and "不表示生产环境已完成交接" in doc)
    check("文档含一致性核对表", "交付一致性核对表" in doc)
    check("文档含回退章节", "## 11. 回退" in doc)

    # 一致性表与 manifest 对齐
    states = {item.get("status") for item in manifest.get("consistency", [])}
    check("一致性含 PASS/PARTIAL/PENDING", {"PASS", "PARTIAL", "PENDING"} <= states)
    partial = [i for i in manifest.get("consistency", []) if i.get("status") != "PASS"]
    check("非 PASS 项均有 detail", all(i.get("detail") for i in partial))

    # 脱敏扫描（只匹配值形态，不误伤键名与契约术语）
    combined = doc + "\n" + json.dumps(manifest, ensure_ascii=False)
    forbidden = [
        (r"(?i)password\s*[:=]\s*\S+", "出现密码赋值"),
        (r"(?i)secret\s*[:=]\s*[A-Za-z0-9+/=]{8,}", "出现密钥赋值"),
        (r"postgresql://[^\s\"]+", "出现连接串"),
        (r"\b(sk|ak)-[A-Za-z0-9]{8,}", "出现疑似密钥"),
        (r"\bdev_(or|ap)_[a-z]+\b", "出现内部账号"),
        (r"\b127\.0\.0\.1\b", "出现回环地址"),
        (r"\b(25|35)4[0-9]{2}\b", "出现端口"),
    ]
    for pattern, label in forbidden:
        hit = re.search(pattern, combined)
        check("脱敏：" + label, hit is None, hit.group(0) if hit else "")

    check("manifest 声明不含密钥/账号/连接串",
          manifest["sanitization"].get("containsSecrets") is False
          and manifest["sanitization"].get("containsAccountNames") is False
          and manifest["sanitization"].get("containsConnectionStrings") is False)

    return report()


def report() -> int:
    print(f"\n通过 {len(PASSES)} 项，失败 {len(FAILS)} 项。")
    if FAILS:
        for item in FAILS:
            print("  - " + item)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
