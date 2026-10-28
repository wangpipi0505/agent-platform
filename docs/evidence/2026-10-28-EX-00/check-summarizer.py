"""离线合成报告检查；不访问PG、运行配置或产品测试。"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("baseline_summary", HERE / "summarize-baseline.py")
SUMMARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUMMARY)


def main():
    scratch = Path(tempfile.mkdtemp(prefix="ex00-summary-check-"))
    checks = []

    def fixture(name):
        repo = scratch / name
        repo.mkdir()
        (repo / "pom.xml").write_text("<project><modules><module>module</module></modules></project>")
        source = repo / "module/src/test/java/demo"
        source.mkdir(parents=True)
        (source / "CaseTest.java").write_text("package demo; class CaseTest {}")
        run = repo / "docs/evidence/2026-10-28-EX-00/baseline/offline"
        run.mkdir(parents=True)
        return repo, source, run

    stamp = datetime.fromtimestamp(time.time() - 10, timezone.utc).isoformat()
    command = dict(startedAt=stamp, exitCode=0, status="COLLECTED")
    repo, source, run = fixture("ap")
    xml_dir = run / "module-xml"
    xml_dir.mkdir()
    xml = xml_dir / "TEST-demo.CaseTest.xml"
    metadata = run / "module-run.json"
    metadata.write_text(json.dumps(command))
    body = ('<testsuites xmlns="urn:fixture"><testsuite name="outer"><testsuite name="nested">'
            '<testcase classname="demo.CaseTest$Inner" name="parameter[1]" displayName="显示一"/>'
            '<testcase classname="demo.CaseTest" name="bad"><failure message="DO_NOT_EXPORT_SECRET"/></testcase>'
            '<testcase classname="demo.CaseTest" name="skip"><skipped/></testcase>'
            '<testcase classname="demo.CaseTest" name="error"><error>stack</error></testcase>'
            '</testsuite></testsuite></testsuites>')
    xml.write_text(body, encoding="utf-8")
    summary, methods, coverage = SUMMARY.summarize(repo, run, "AP")
    assert summary["counts"] == dict(passed=1, failure=1, skipped=1, error=1)
    assert summary["collection_complete"] and not summary["tests_all_passed"]
    assert methods[0]["method"] == "parameter[1]" and methods[0]["display_name"] == "显示一"
    assert methods[0]["suite_path"] == "outer > nested"
    assert "DO_NOT_EXPORT_SECRET" not in json.dumps((summary, methods, coverage))
    checks.append("mixed_status_names_and_safe_projection")
    os.utime(xml, (time.time() - 100, time.time() - 100))
    summary, _, _ = SUMMARY.summarize(repo, run, "AP")
    assert summary["cases"] == 0 and not summary["collection_complete"]
    checks.append("stale_xml")
    xml.write_text(body, encoding="utf-8")
    metadata.unlink()
    summary, _, _ = SUMMARY.summarize(repo, run, "AP")
    assert not summary["collection_complete"] and summary["not_reported"] == 1
    checks.append("missing_command")
    metadata.write_text(json.dumps(command))
    (source / "AbsentTest.java").write_text("package demo; class AbsentTest {}")
    summary, _, _ = SUMMARY.summarize(repo, run, "AP")
    assert summary["not_reported"] == 1 and not summary["collection_complete"]
    checks.append("source_class_not_reported")
    (source / "AbsentTest.java").unlink()
    xml.write_text("<broken", encoding="utf-8")
    summary, _, _ = SUMMARY.summarize(repo, run, "AP")
    assert any(row["issue"] == "INVALID_XML" for row in summary["issues"])
    checks.append("invalid_xml")
    xml.write_text(body, encoding="utf-8")
    metadata.write_text(json.dumps(dict(command, exitCode=7, status="COMMAND_FAILED")))
    summary, _, _ = SUMMARY.summarize(repo, run, "AP")
    assert summary["cases"] == 4 and not summary["collection_complete"]
    checks.append("nonzero_command_with_xml")
    metadata.write_text(json.dumps(command))
    cli = [sys.executable, str(HERE / "summarize-baseline.py"), "--repo", str(repo), "--run", str(run), "--kind", "AP"]
    assert subprocess.run(cli, capture_output=True).returncode == 0
    frozen = (run / "method-summary/summary.json").read_bytes()
    assert subprocess.run(cli, capture_output=True).returncode != 0
    assert (run / "method-summary/summary.json").read_bytes() == frozen
    checks.append("refuse_summary_overwrite")

    repo, source, run = fixture("or")
    (source / "JdbcOutboxStoreTest.java").write_text("package demo; class JdbcOutboxStoreTest {}")
    for database, classname in (("BASE", "CaseTest"), ("P4", "JdbcOutboxStoreTest")):
        group = run / f"module-{database}"
        (group / "xml").mkdir(parents=True)
        (group / "run.json").write_text(json.dumps(dict(command, database=database, classes=[classname])))
        (group / "xml/TEST-demo.xml").write_text(f'<testsuite tests="1" errors="0"><testcase classname="demo.{classname}" name="one"/></testsuite>')
    summary, _, _ = SUMMARY.summarize(repo, run, "OR")
    assert summary["collection_complete"] and summary["tests_all_passed"] and summary["cases"] == 2
    checks.append("or_split")
    base = run / "module-BASE/xml/TEST-demo.xml"
    saved = base.read_text()
    base.write_text('<testsuite tests="2"><testcase classname="demo.CaseTest" name="one"/><testcase classname="demo.JdbcOutboxStoreTest" name="extra"/></testsuite>')
    summary, _, _ = SUMMARY.summarize(repo, run, "OR")
    assert not summary["collection_complete"] and any(row["issue"] == "UNEXPECTED_OR_WRONG_GROUP_CLASS" for row in summary["issues"])
    checks.append("wrong_group_actual_class")
    base.write_text(saved.replace('errors="0"', 'errors="1"'))
    summary, _, _ = SUMMARY.summarize(repo, run, "OR")
    assert not summary["tests_all_passed"] and any(row["issue"] == "XML_SUITE_COUNT_MISMATCH" for row in summary["issues"])
    checks.append("suite_count_mismatch")
    base.write_text(saved.replace('</testsuite>', '<error>DO_NOT_EXPORT_SECRET</error></testsuite>'))
    summary, _, _ = SUMMARY.summarize(repo, run, "OR")
    assert not summary["tests_all_passed"] and any(row["issue"] == "XML_SUITE_LEVEL_ERROR" for row in summary["issues"])
    checks.append("suite_level_error")
    base.write_text(saved)
    record = run / "module-BASE/run.json"
    record.write_text(json.dumps(dict(command, database="P4", classes=["CaseTest"])))
    summary, _, _ = SUMMARY.summarize(repo, run, "OR")
    assert not summary["collection_complete"] and any(row["issue"] == "COMMAND_GROUP_OR_CLASSES_MISMATCH" for row in summary["issues"])
    checks.append("command_group_mismatch")
    record.write_text(json.dumps(dict(command, database="BASE", classes=["CaseTest"])))
    (repo / "pom.xml").write_text('<project><modules><module>module</module><module>empty</module></modules></project>')
    (run / "empty-no-tests.txt").write_text("No source tests; not passed")
    summary, _, _ = SUMMARY.summarize(repo, run, "OR")
    assert summary["collection_complete"] and summary["tested_groups_all_passed"]
    assert not summary["tests_all_passed"] and summary["no_source_modules"] == ["empty"]
    assert summary["groups"][-1]["tests_all_passed"] is None
    checks.append("no_source_module_is_not_a_pass")
    (run / "empty-no-tests.txt").unlink()
    summary, _, _ = SUMMARY.summarize(repo, run, "OR")
    assert not summary["collection_complete"]
    checks.append("missing_no_source_record")
    summary, _, coverage = SUMMARY.summarize(repo, run, "OR", module_scope=["module"])
    assert summary["scope"] == "SELECTED_MODULES" and summary["modules"] == ["module"]
    assert summary["reactor_modules"] == ["module", "empty"]
    assert summary["collection_complete"] and summary["tests_all_passed"]
    assert all(row["module"] == "module" for row in coverage)
    checks.append("selected_modules_do_not_claim_full_reactor")
    for selected in (["unknown"], ["module", "module"], []):
        try:
            SUMMARY.summarize(repo, run, "OR", module_scope=selected)
        except ValueError:
            pass
        else:
            raise AssertionError("非法/重复/空模块选择不得放行")
    checks.append("invalid_module_selection_rejected")

    repo, source, run = fixture("supplement")
    for classname in ("LaterTest", "BlockedTest"):
        (source / (classname + ".java")).write_text("package demo; class " + classname + " {}")
    (run / "HEAD.txt").write_text("a" * 40)
    (run / "module-run.json").write_text(json.dumps(dict(command, exitCode=1, status="COMMAND_FAILED")))
    (run / "module-xml").mkdir()
    (run / "module-xml/TEST-demo.CaseTest.xml").write_text('<testsuite tests="1"><testcase classname="demo.CaseTest" name="one"/></testsuite>')
    supplement = run.parent / "supplement"
    supplement.mkdir()
    (supplement / "HEAD.txt").write_text("a" * 40)
    (supplement / "module-run.json").write_text(json.dumps(command))
    (supplement / "module-xml").mkdir()
    extra_xml = supplement / "module-xml/TEST-demo.LaterTest.xml"
    extra_xml.write_text('<testsuite tests="1"><testcase classname="demo.LaterTest" name="two"/></testsuite>')
    summary, methods, coverage = SUMMARY.summarize(repo, run, "AP", (supplement,))
    assert summary["cases"] == 2 and summary["not_reported"] == 1
    assert not summary["collection_complete"] and not summary["tests_all_passed"]
    assert summary["groups"][0]["command_status"] == "COMMAND_FAILED"
    assert {row["attempt"] for row in methods} == {"offline", "supplement"}
    checks.append("supplement_preserves_failed_command_and_missing_class")
    duplicate = supplement / "module-xml/TEST-demo.CaseTest.xml"
    duplicate.write_text('<testsuite tests="1"><testcase classname="demo.CaseTest" name="again"/></testsuite>')
    summary, _, _ = SUMMARY.summarize(repo, run, "AP", (supplement,))
    assert any(row["issue"] == "DUPLICATE_CLASS_ACROSS_ATTEMPTS" for row in summary["issues"])
    checks.append("duplicate_class_across_attempts")
    duplicate.unlink()
    (supplement / "HEAD.txt").write_text("b" * 40)
    try:
        SUMMARY.summarize(repo, run, "AP", (supplement,))
    except ValueError:
        pass
    else:
        raise AssertionError("不同源码HEAD的补采不得合并")
    checks.append("different_source_head_rejected")
    (supplement / "HEAD.txt").write_text("a" * 40)
    os.utime(extra_xml, (time.time() - 100, time.time() - 100))
    summary, _, _ = SUMMARY.summarize(repo, run, "AP", (supplement,))
    assert summary["cases"] == 1 and summary["not_reported"] == 2
    checks.append("supplement_uses_own_freshness_record")
    repo, source, run = fixture("terminated")
    (run / "module-run.json").write_text(json.dumps(dict(command, testExecutionStatus="INCOMPLETE_FORK_TERMINATED", testProcessExitCode=-1)))
    (run / "module-xml").mkdir()
    xml = run / "module-xml/TEST-demo.CaseTest.xml"
    xml.write_text('<testsuite tests="1"><testcase classname="demo.CaseTest" name="one"/></testsuite>')
    summary, _, _ = SUMMARY.summarize(repo, run, "AP")
    assert summary["cases"] == 1 and not summary["collection_complete"] and not summary["tests_all_passed"]
    assert summary["groups"][0]["commands"][0]["testExecutionStatus"] == "INCOMPLETE_FORK_TERMINATED"
    checks.append("forced_fork_termination_not_washed_by_exit_zero")
    (run / "module-run.json").write_text(json.dumps(command))
    xml.write_text('<testsuite tests="1" errors="1"><testcase classname="demo.CaseTest" name=""><error/></testcase></testsuite>')
    summary, _, coverage = SUMMARY.summarize(repo, run, "AP")
    assert summary["class_level_error_events"] == 1 and summary["class_level_error_classes"] == ["demo.CaseTest"]
    assert coverage[0]["status"] == "CLASS_LEVEL_ERROR" and not summary["tests_all_passed"]
    checks.append("class_level_error_not_reported_as_executed_methods")
    result = dict(passed=len(checks), checks=checks, product_tests_executed=False)
    (HERE / "summarizer-checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
