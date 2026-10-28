"""仅汇总本轮归档的 Surefire XML，不执行测试、不读取运行凭据。"""
import argparse
import csv
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path
import xml.etree.ElementTree as ET

P4_CLASSES = {
    "JdbcOutboxStoreTest", "OutboxRelayTest", "OutboxLeaseWaitAuditTest",
    "OutboxClockAuditTest", "ReleaseEventServiceTest", "TraceAuditServiceTest",
    "ReleaseRestoreTest",
}
METHOD_FIELDS = ["module", "database", "attempt", "classname", "method", "status", "display_name",
                 "suite_path", "case_index", "seconds", "xml"]


def source_classes(repo, module):
    result = []
    for file in sorted((repo / module / "src/test/java").rglob("*Test.java")):
        package = re.search(r"(?m)^\s*package\s+([\w.]+)\s*;", file.read_text(encoding="utf-8-sig"))
        if not package:
            raise ValueError(f"测试类缺少可识别package: {file.relative_to(repo)}")
        result.append(package[1] + "." + file.stem)
    return result


def read_cases(file, module, database, xml_path):
    """保留参数序号、嵌套类和suite路径；不复制失败正文、system-out或属性环境。"""
    rows = []

    def walk(element, ancestors):
        tag = element.tag.rsplit("}", 1)[-1]
        if tag == "testsuite":
            ancestors = ancestors + [element.get("name", "")]
        if tag == "testcase":
            children = {child.tag.rsplit("}", 1)[-1] for child in element}
            status = next((name for name in ("error", "failure", "skipped") if name in children), "passed")
            rows.append(dict(module=module, database=database,
                             classname=element.get("classname", ""), method=element.get("name", ""),
                             status=status, display_name=element.get("displayName", ""),
                             suite_path=" > ".join(ancestors), case_index=len(rows) + 1,
                             seconds=element.get("time", ""), xml=xml_path))
        for child in element:
            walk(child, ancestors)

    root = ET.parse(file).getroot()
    walk(root, [])
    issues = []
    for suite in root.iter():
        if suite.tag.rsplit("}", 1)[-1] != "testsuite":
            continue
        cases = [node for node in suite.iter() if node.tag.rsplit("}", 1)[-1] == "testcase"]
        actual = Counter()
        for case in cases:
            children = {node.tag.rsplit("}", 1)[-1] for node in case}
            actual[next((name for name in ("error", "failure", "skipped") if name in children), "passed")] += 1
        values = {"tests": len(cases), "errors": actual["error"],
                  "failures": actual["failure"], "skipped": actual["skipped"]}
        for field, observed in values.items():
            if suite.get(field) is not None:
                try:
                    consistent = int(suite.get(field)) == observed
                except ValueError:
                    consistent = False
                if not consistent:
                    issues.append(dict(module=module, database=database, issue="XML_SUITE_COUNT_MISMATCH",
                                       xml=xml_path, suite=suite.get("name", ""), field=field))
        if any(node.tag.rsplit("}", 1)[-1] in ("error", "failure") for node in suite):
            issues.append(dict(module=module, database=database, issue="XML_SUITE_LEVEL_ERROR", xml=xml_path))
    return rows, issues


def summarize(repo, run, kind, supplements=(), module_scope=None):
    if supplements:
        if kind != "AP":
            raise ValueError("当前补采合并仅用于AP已知坏类阻断后的未到达类")
        head_file = run / "HEAD.txt"
        if not head_file.is_file():
            raise ValueError("主调用缺少源码HEAD记录")
        head = head_file.read_text(encoding="utf-8-sig").strip()
        if not re.fullmatch(r"[0-9a-fA-F]{40}", head):
            raise ValueError("主调用源码HEAD无效")
        for attempt in supplements:
            attempt_head = attempt / "HEAD.txt"
            if not attempt_head.is_file() or attempt_head.read_text(encoding="utf-8-sig").strip() != head:
                raise ValueError("补采与主调用源码HEAD无法确认一致")
    pom = ET.parse(repo / "pom.xml")
    reactor_modules = [node.text for node in pom.findall("./{*}modules/{*}module")]
    if not reactor_modules:
        raise ValueError("reactor模块为空")
    if module_scope is not None and (not module_scope or len(set(module_scope)) != len(module_scope)
                                     or not set(module_scope).issubset(reactor_modules)):
        raise ValueError("所选模块为空、重复或不在reactor中")
    modules = [name for name in reactor_modules if module_scope is None or name in module_scope]
    groups, methods, coverage, issues, no_source_modules = [], [], [], [], []
    for module in modules:
        expected = source_classes(repo, module)
        databases = ("AP",) if kind == "AP" else ("BASE", "P4")
        for database in databases:
            selected = expected if database == "AP" else [name for name in expected
                        if (name.rsplit(".", 1)[-1] in P4_CLASSES) == (database == "P4")]
            if not selected:
                if database != "P4":
                    no_source_modules.append(module)
                    recorded = (run / f"{module}-no-tests.txt").is_file()
                    if not recorded:
                        issues.append(dict(module=module, database=database, issue="MISSING_NO_SOURCE_TESTS_RECORD"))
                    groups.append(dict(module=module, database=database, expected_classes=0,
                                       exit_code=None, command_status="NO_SOURCE_TESTS", fresh_xml=0,
                                       cases=0, counts={}, not_reported=[],
                                       collection_complete=recorded, tests_all_passed=None))
                continue
            records, fresh, group_rows, seen_classes = [], [], [], set()
            for attempt in (run, *supplements):
                folder = attempt if kind == "AP" else attempt / f"{module}-{database}"
                metadata = folder / (f"{module}-run.json" if kind == "AP" else "run.json")
                xml_dir = attempt / f"{module}-xml" if kind == "AP" else folder / "xml"
                if attempt != run and not metadata.is_file():
                    continue  # 补采只包含被阻断的模块，不伪造其余模块调用。
                record = {}
                if metadata.is_file():
                    record = json.loads(metadata.read_text(encoding="utf-8-sig"))
                else:
                    issues.append(dict(module=module, database=database, issue="MISSING_COMMAND_RECORD"))
                records.append(dict(attempt=attempt.name, exitCode=record.get("exitCode"), status=record.get("status", "UNEXECUTED"),
                                    testExecutionStatus=record.get("testExecutionStatus"),
                                    testProcessExitCode=record.get("testProcessExitCode")))
                if kind == "OR" and record:
                    if record.get("database") != database or set(record.get("classes", [])) != {name.rsplit(".", 1)[-1] for name in selected}:
                        issues.append(dict(module=module, database=database, issue="COMMAND_GROUP_OR_CLASSES_MISMATCH"))
                started = datetime.fromisoformat(record["startedAt"]).timestamp() if record.get("startedAt") else None
                attempt_rows = []
                for xml in sorted(xml_dir.glob("TEST-*.xml")):
                    xml_path = attempt.name + "/" + xml.relative_to(attempt).as_posix()
                    if started is None or xml.stat().st_mtime < started:
                        issues.append(dict(module=module, database=database, issue="STALE_OR_UNATTRIBUTED_XML", xml=xml_path))
                        continue
                    try:
                        rows, xml_issues = read_cases(xml, module, database, xml_path)
                    except ET.ParseError:
                        issues.append(dict(module=module, database=database, issue="INVALID_XML", xml=xml_path))
                        continue
                    for row in rows:
                        row["attempt"] = attempt.name
                    fresh.append(xml_path)
                    attempt_rows.extend(rows)
                    issues.extend(xml_issues)
                current_classes = {row["classname"].split("$", 1)[0] for row in attempt_rows}
                for duplicate in sorted(seen_classes & current_classes):
                    issues.append(dict(module=module, database=database, issue="DUPLICATE_CLASS_ACROSS_ATTEMPTS", classname=duplicate))
                seen_classes.update(current_classes)
                group_rows.extend(attempt_rows)
            reported = {row["classname"] for row in group_rows}
            for classname in sorted(reported):
                if not any(classname == name or classname.startswith(name + "$") for name in selected):
                    issues.append(dict(module=module, database=database, issue="UNEXPECTED_OR_WRONG_GROUP_CLASS", classname=classname))
            missing = []
            for classname in selected:
                found = any(name == classname or name.startswith(classname + "$") for name in reported)
                class_error = any(row["status"] == "error" and row["method"] in ("", "initializationError")
                                  and (row["classname"] == classname or row["classname"].startswith(classname + "$")) for row in group_rows)
                coverage.append(dict(module=module, database=database, classname=classname,
                                     status=("CLASS_LEVEL_ERROR" if class_error else "REPORTED") if found else "NOT_REPORTED"))
                if not found:
                    missing.append(classname)
            status_counts = Counter(row["status"] for row in group_rows)
            counts = {name: status_counts[name] for name in ("passed", "failure", "error", "skipped")}
            command_ok = all(record["exitCode"] == 0 and record["status"] == "COLLECTED"
                             and record["testExecutionStatus"] in (None, "COMPLETED")
                             and record["testProcessExitCode"] in (None, 0) for record in records)
            group_issues = [issue for issue in issues if issue["module"] == module and issue["database"] == database]
            collected = bool(group_rows) and not missing and not group_issues and command_ok
            groups.append(dict(module=module, database=database, expected_classes=len(selected),
                               exit_code=records[0]["exitCode"], command_status=records[0]["status"], commands=records,
                               fresh_xml=len(fresh), cases=len(group_rows), counts=counts,
                               not_reported=missing, collection_complete=collected,
                               tests_all_passed=collected and all(row["status"] == "passed" for row in group_rows)))
            methods.extend(group_rows)
    counts = Counter(row["status"] for row in methods)
    class_errors = [row for row in methods if row["status"] == "error" and row["method"] in ("", "initializationError")]
    summary = dict(repository=kind, run_id=run.name, supplement_runs=[item.name for item in supplements], modules=modules,
                   scope="SELECTED_MODULES" if module_scope is not None else "FULL_REACTOR",
                   reactor_modules=reactor_modules, source_pattern="*Test.java",
                   groups=groups, cases=len(methods),
                   counts={name: counts[name] for name in ("passed", "failure", "error", "skipped")},
                   error_classes=sorted({row["classname"] for row in methods if row["status"] == "error"}),
                   class_level_error_events=len(class_errors),
                   class_level_error_classes=sorted({row["classname"] for row in class_errors}),
                   not_reported=sum(row["status"] == "NOT_REPORTED" for row in coverage), issues=issues,
                   no_source_modules=no_source_modules,
                   collection_complete=bool(groups) and not issues and all(group["collection_complete"] for group in groups),
                   tested_groups_all_passed=bool(methods) and not issues and all(group["tests_all_passed"] for group in groups if group["expected_classes"]),
                   tests_all_passed=bool(groups) and not issues and not no_source_modules and all(group["tests_all_passed"] for group in groups),
                   display_name_note="仅保留XML显式displayName属性；缺失时留空，原case name保留，不反推展示名。",
                   scope_note="仅汇总本轮命令与XML。NOT_REPORTED须归因；无源测试模块单列不适用，采集完整不代表全部测试通过。")
    return summary, methods, coverage


def write_csv(file, fields, rows):
    with file.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--kind", choices=("AP", "OR"), required=True)
    parser.add_argument("--supplement", type=Path, action="append", default=[])
    parser.add_argument("--module", action="append", dest="module_scope")
    args = parser.parse_args()
    repo, run = args.repo.resolve(), args.run.resolve()
    supplements = tuple(path.resolve() for path in args.supplement)
    if len({run, *supplements}) != 1 + len(supplements):
        raise ValueError("主调用与补采目录不能重复")
    for path in (run, *supplements):
        if not path.is_dir() or not path.is_relative_to(repo / "docs/evidence/2026-10-28-EX-00/baseline"):
            raise ValueError("仅接受本仓EX-00本轮baseline目录")
    destination = run / "method-summary"
    if destination.exists():
        raise FileExistsError("已有汇总不可覆盖")
    summary, methods, coverage = summarize(repo, run, args.kind, supplements, args.module_scope)
    destination.mkdir()  # 已存在则拒绝覆盖，重采集必须新RunId。
    (destination / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_csv(destination / "methods.csv", METHOD_FIELDS, methods)
    write_csv(destination / "nonpass.csv", METHOD_FIELDS, [row for row in methods if row["status"] != "passed"])
    write_csv(destination / "coverage.csv", ["module", "database", "classname", "status"], coverage)
    print(json.dumps({key: summary[key] for key in ("repository", "scope", "modules", "cases", "counts", "not_reported", "collection_complete", "tests_all_passed")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
