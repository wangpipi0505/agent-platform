from pathlib import Path
import json,hashlib
r=Path.cwd();o=r/'docs/evidence/iam-stage-3-review-fixes'
def h(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(n):return json.loads((o/n).read_text(encoding='utf8'))
source=load('source-after.json');assert all(h(r/n)==s for n,s in source.items());assert all((r/n).read_bytes()==(o/'frozen-final'/n).read_bytes()==(o/'applied-final'/n).read_bytes() for n in source)
target=load('target-after.json');assert all(h(r/'admin-common/target'/n)==s for n,s in target.items());assert all((r/'admin-common/target'/n).read_bytes()==(o/'frozen-target-final'/n).read_bytes() for n in target)
for file in ['protected-before.json','original-evidence-before.json']:assert all(h(r/n)==s for n,s in load(file).items())
for item in load('line-ending-proof.json'):
 n=item['path'];assert h(o/'frozen'/n)==item['tested_sha256'];assert h(r/n)==item['final_sha256'];assert (o/'frozen'/n).read_bytes().replace(b'\r\n',b'\n')==(r/n).read_bytes().replace(b'\r\n',b'\n')
oldtarget=load('target-before.json');jars={n:s for n,s in oldtarget.items() if n.endswith('.jar')};assert all(target[n]==s for n,s in jars.items())
assert (r/'docs/evidence/review-iam-stage-3.md').read_bytes()==(o/'review-original-prefix.md').read_bytes()
summary={'source_frozen_applied_live_bytes_equal':len(source),'target_frozen_live_bytes_equal':len(target),'protected_files_unchanged':len(load('protected-before.json')),'original_evidence_unchanged':len(load('original-evidence-before.json')),'tested_source_normalized_bytes_equal':len(load('line-ending-proof.json')),'inherited_jar_unchanged':jars,'report_sha256':h(r/'docs/evidence/iam-stage-3-review-fixes.md')}
(o/'final-audit.json').write_text(json.dumps(summary,indent=2,ensure_ascii=False),encoding='utf8')
files=[p for p in o.rglob('*') if p.is_file() and p.name not in ['evidence-manifest.json','final-hashes.json']]+[r/'docs/evidence/iam-stage-3-review-fixes.md']
manifest={p.relative_to(r).as_posix():h(p) for p in sorted(files)};(o/'evidence-manifest.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False),encoding='utf8')
hashes={n:h(o/n) for n in ['full.patch','source-after.json','target-after.json','evidence-manifest.json','final-audit.json']};hashes['report']=h(r/'docs/evidence/iam-stage-3-review-fixes.md');hashes['manifest_entries']=len(manifest);(o/'final-hashes.json').write_text(json.dumps(hashes,indent=2),encoding='utf8');print(json.dumps(summary,indent=2));print(json.dumps(hashes,indent=2))
