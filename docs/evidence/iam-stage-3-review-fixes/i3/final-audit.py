from pathlib import Path
import json,hashlib
r=Path.cwd();parent=r/'docs/evidence/iam-stage-3-review-fixes';o=parent/'i3'
def h(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text(encoding='utf8'))
source=load(o/'source-after.json');actual={p.relative_to(r).as_posix():h(p) for p in [r/'admin-common/pom.xml']+[p for p in (r/'admin-common/src').rglob('*') if p.is_file()]};assert actual==source
assert all((r/n).read_bytes()==(o/'frozen'/n).read_bytes()==(o/'applied'/n).read_bytes()==(o/'i3-applied'/n).read_bytes() for n in source)
target=load(o/'target-after.json');t=r/'admin-common/target';assert target=={p.relative_to(t).as_posix():h(p) for p in t.rglob('*') if p.is_file()};assert all((t/n).read_bytes()==(o/'frozen-target'/n).read_bytes() for n in target)
for manifest in [o/'candidate-protected.json',parent/'protected-before.json',parent/'original-evidence-before.json']:
 assert all(h(r/n)==s for n,s in load(manifest).items())
oldreport=load(parent/'final-hashes.json')['report'];assert h(o/'i1-i2-candidate-report.md')==oldreport
oldtarget=load(parent/'target-before.json');jars={n:s for n,s in oldtarget.items() if n.endswith('.jar')};assert all(target[n]==s for n,s in jars.items())
assert (r/'docs/evidence/review-iam-stage-3.md').read_bytes()==(parent/'review-original-prefix.md').read_bytes()
summary={'source_frozen_combined_applied_i3_applied_live_bytes_equal':len(source),'target_frozen_live_bytes_equal':len(target),'protected_unchanged':len(load(parent/'protected-before.json')),'original_evidence_unchanged':len(load(parent/'original-evidence-before.json')),'i1_i2_candidate_artifacts_unchanged':len(load(o/'candidate-protected.json')),'candidate_report_original_sha256':oldreport,'final_report_sha256':h(r/'docs/evidence/iam-stage-3-review-fixes.md'),'reviewer_addendum_sha256':h(r/'docs/evidence/review-iam-stage-3-approver-addendum.md'),'inherited_jar_unchanged':jars}
(o/'final-audit.json').write_text(json.dumps(summary,indent=2),encoding='utf8')
files=[p for p in o.rglob('*') if p.is_file() and p.name not in ['evidence-manifest.json','final-hashes.json']]+[r/'docs/evidence/iam-stage-3-review-fixes.md']
manifest={p.relative_to(r).as_posix():h(p) for p in sorted(files)};(o/'evidence-manifest.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False),encoding='utf8')
hashes={n:h(o/n) for n in ['full.patch','i3-only.patch','source-after.json','target-after.json','evidence-manifest.json','final-audit.json']};hashes['report']=summary['final_report_sha256'];hashes['manifest_entries']=len(manifest);(o/'final-hashes.json').write_text(json.dumps(hashes,indent=2),encoding='utf8');print(json.dumps(summary,indent=2));print(json.dumps(hashes,indent=2))
