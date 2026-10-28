"""复用EX00解析器核本卡归档XML；仅文件分析，不运行产品或修改基线。"""
import argparse
import csv
import hashlib
import importlib.util
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('run', type=Path)
args = parser.parse_args()
base = Path(__file__).resolve().parent
repo = base.parents[2]
run = args.run.resolve()
if not run.is_relative_to(base / 'runs'):
    raise ValueError('仅允许本卡runs目录')
helper = repo / 'docs/evidence/2026-10-28-EX-00/summarize-baseline.py'
spec = importlib.util.spec_from_file_location('ex00_parser', helper)
ex00 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ex00)
record = json.loads((run / 'run.json').read_text(encoding='utf-8-sig'))
methods, issues, manifest = [], [], []
started = datetime.fromisoformat(record['startedAt']).timestamp()
for file in sorted((run / 'xml').glob('TEST-*.xml')):
    relative = file.relative_to(run).as_posix()
    if file.stat().st_mtime < started:
        issues.append({'issue': 'STALE_XML', 'xml': relative})
    rows, problems = ex00.read_cases(file, 'engine-runtime', 'AP', relative)
    for row in rows:
        row['attempt'] = run.name
    methods.extend(rows)
    issues.extend(problems)
    manifest.append(dict(path=relative, bytes=file.stat().st_size,
                         sha256=hashlib.sha256(file.read_bytes()).hexdigest()))
expected = set(ex00.source_classes(repo, 'engine-runtime'))
if record['selector']:
    selected = {name.split('#')[0] for name in record['selector'].split(',')}
    expected = {name for name in expected if name.rsplit('.', 1)[-1] in selected}
reported = {row['classname'].split('$', 1)[0] for row in methods}
missing_classes, unexpected_classes = sorted(expected - reported), sorted(reported - expected)
class_errors = [row for row in methods if row['status'] == 'error'
                and row['method'] in ('', 'initializationError')]
class_events = [row for row in methods if row['status'] in ('failure', 'error')
                and row['method'] in ('', 'initializationError')]
unexecuted = []
for classname in sorted({row['classname'] for row in class_events}):
    source = repo / 'engine-runtime/src/test/java' / (classname.replace('.', '/') + '.java')
    declarations = re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\s*\(', source.read_text(encoding='utf-8'))
    unexecuted.append(dict(classname=classname, source_methods=declarations))
summary = dict(cases=len(methods), counts=dict(Counter(row['status'] for row in methods)),
               expected_classes=len(expected), reported_classes=len(reported),
               missing_classes=missing_classes, unexpected_classes=unexpected_classes,
               class_level_errors=class_errors, class_level_events=class_events,
               known_unexecuted_source_methods=unexecuted,
               named_case_records=sum(bool(row['method']) for row in methods),
               issues=issues, native_exit_code=record['exitCode'],
               parser_sha256=hashlib.sha256(helper.read_bytes()).hexdigest(),
               raw_sha256=hashlib.sha256((run / 'raw.log').read_bytes()).hexdigest())
# 报告收齐、源方法全部执行、相对基线无新增是不同结论；类级FAIL和ERROR都不能漏列。
reports_collected = bool(methods) and not (
    missing_classes or unexpected_classes or issues)
complete = reports_collected and record['exitCode'] == 0 and not class_events
summary['reports_collected'] = reports_collected
summary['collection_complete'] = complete
summary['all_source_methods_executed'] = reports_collected and not class_events and not any(
    row['status'] == 'skipped' for row in methods)
summary['tests_all_passed'] = complete and all(row['status'] == 'passed' for row in methods)
destination = run / 'method-summary'
destination.mkdir(exist_ok=True)
ex00.write_csv(destination / 'methods.csv', ex00.METHOD_FIELDS, methods)
ex00.write_csv(destination / 'nonpass.csv', ex00.METHOD_FIELDS,
               [row for row in methods if row['status'] != 'passed'])
(run / 'xml-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
if not record['selector']:
    baseline = repo / 'docs/evidence/2026-10-28-EX-00/baseline/20261028-184530-895/method-summary/methods.csv'
    with baseline.open(encoding='utf-8-sig', newline='') as stream:
        before = [row for row in csv.DictReader(stream) if row['module'] == 'engine-runtime']
    identity = lambda row: (row['classname'], row['method'])
    nonpass = lambda rows: Counter((*identity(row), row['status']) for row in rows if row['status'] != 'passed')
    before_ids, after_ids = Counter(map(identity, before)), Counter(map(identity, methods))
    missing = before_ids - after_ids
    added = after_ids - before_ids
    new_nonpass = nonpass(methods) - nonpass(before)
    before_class_errors = [row for row in before if row['status'] == 'error'
                           and row['method'] in ('', 'initializationError')]
    new_class_errors = Counter(map(identity, class_errors)) - Counter(map(identity, before_class_errors))
    before_class_events = [row for row in before if row['status'] in ('failure', 'error')
                           and row['method'] in ('', 'initializationError')]
    event_identity = lambda row: (*identity(row), row['status'])
    new_class_events = Counter(map(event_identity, class_events)) - Counter(map(event_identity, before_class_events))
    expected_new = Counter({('cn.agentplatform.engine.infra.OntologyContextPacksTest',
                             'springInjectsProductionConstructorAndUsesConfiguredSecretReference(Path)'): 1})
    difference = dict(baseline=str(baseline), baseline_cases=len(before), after_cases=len(methods),
                      missing_methods=[dict(classname=key[0], method=key[1], count=count) for key, count in missing.items()],
                      new_methods=[dict(classname=key[0], method=key[1], count=count) for key, count in added.items()],
                      new_nonpass=[dict(classname=key[0], method=key[1], status=key[2], count=count) for key, count in new_nonpass.items()],
                      retained_nonpass_count=sum((nonpass(methods) & nonpass(before)).values()),
                      removed_nonpass_count=sum((nonpass(before) - nonpass(methods)).values()),
                      retained_class_level_errors=len(class_errors) - sum(new_class_errors.values()),
                      new_class_level_errors=[dict(classname=key[0], method=key[1], count=count) for key, count in new_class_errors.items()],
                      retained_class_level_events=len(class_events) - sum(new_class_events.values()),
                      new_class_level_events=[dict(classname=key[0], method=key[1], status=key[2], count=count) for key, count in new_class_events.items()],
                      known_unexecuted_source_test_count=sum(len(row['source_methods']) for row in unexecuted),
                      only_expected_new_method=(added == expected_new),
                      module_comparison_passed=reports_collected and record['exitCode'] == 0 and not missing
                      and not new_nonpass and not new_class_events and added == expected_new,
                      meaning='仅方法差异门；存量红保留，不表示全模块测试通过')
    (destination / 'comparison.json').write_text(json.dumps(difference, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    summary['module_comparison_passed'] = difference['module_comparison_passed']
(destination / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: summary[key] for key in ('cases', 'counts', 'collection_complete', 'tests_all_passed')}, ensure_ascii=True))
