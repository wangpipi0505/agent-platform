"""复用已冻结的EX00纯解析器，单独记录EX02定向或app完整模块；不运行产品。"""
import argparse
import csv
import hashlib
import importlib.util
import json
from collections import Counter
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
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
record = json.loads((run / 'app-management-run.json').read_text(encoding='utf-8-sig'))
destination = run / 'method-summary'
destination.mkdir()
if record['scope'] == 'MODULE':
    summary, methods, coverage = module.summarize(repo, run, 'AP', module_scope=['app-management'])
    module.write_csv(destination / 'coverage.csv', ['module', 'database', 'classname', 'status'], coverage)
else:
    methods, issues = [], []
    for file in sorted((run / 'app-management-xml').glob('TEST-*.xml')):
        rows, problems = module.read_cases(file, 'app-management', 'AP', file.relative_to(run).as_posix())
        for row in rows:
            row['attempt'] = run.name
        methods.extend(rows)
        issues.extend(problems)
    expected = {name.split('#')[0].rsplit('.', 1)[-1] for name in record['selector'].split(',')}
    actual = {row['classname'].split('$')[0].rsplit('.', 1)[-1] for row in methods}
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    summary = dict(scope='TARGETED', selector=record['selector'], cases=len(methods),
                   missing_selected_classes=missing, unexpected_classes=unexpected,
                   counts=dict(Counter(row['status'] for row in methods)), issues=issues,
                   tests_all_passed=record['exitCode'] == 0 and bool(methods) and not issues and not missing and not unexpected
                   and all(row['status'] == 'passed' for row in methods))
module.write_csv(destination / 'methods.csv', module.METHOD_FIELDS, methods)
module.write_csv(destination / 'nonpass.csv', module.METHOD_FIELDS, [row for row in methods if row['status'] != 'passed'])
summary['parser_sha256'] = hashlib.sha256(helper.read_bytes()).hexdigest()
summary['raw_sha256'] = hashlib.sha256((run / 'maven.raw.log').read_bytes()).hexdigest()
(destination / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
if record['scope'] == 'MODULE':
    baseline = repo / 'docs/evidence/2026-10-28-EX-00/baseline/20261028-184530-895/method-summary/methods.csv'
    with baseline.open(encoding='utf-8-sig', newline='') as stream:
        before = [row for row in csv.DictReader(stream) if row['module'] == 'app-management']
    identity = lambda row: (row['classname'], row['method'])
    nonpass = lambda rows: Counter((*identity(row), row['status']) for row in rows if row['status'] != 'passed')
    missing = Counter(map(identity, before)) - Counter(map(identity, methods))
    new_nonpass = nonpass(methods) - nonpass(before)
    difference = dict(baseline=str(baseline), baseline_cases=len(before), after_cases=len(methods),
                      missing_baseline_methods=[dict(classname=key[0], method=key[1], count=count) for key, count in missing.items()],
                      new_nonpass=[dict(classname=key[0], method=key[1], status=key[2], count=count) for key, count in new_nonpass.items()],
                      retained_nonpass_count=sum((nonpass(methods) & nonpass(before)).values()),
                      module_comparison_passed=summary['collection_complete'] and not missing and not new_nonpass,
                      meaning='仅本卡方法差异门；存量红保留，不表示全模块测试通过')
    (destination / 'comparison.json').write_text(json.dumps(difference, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: summary[key] for key in ['scope', 'cases', 'counts', 'tests_all_passed']}, ensure_ascii=True))
