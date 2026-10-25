from pathlib import Path
import json,hashlib
r=Path.cwd();o=r/'docs/evidence/iam-stage-3'
def h(p): return hashlib.sha256(p.read_bytes()).hexdigest()
a=json.loads((o/'source-after.json').read_text());assert all(h(r/n)==v and h(o/'frozen'/n)==v and h(o/'applied-final'/n)==v for n,v in a.items())
t=json.loads((o/'target-after.json').read_text());assert all(h(r/'admin-common/target'/n)==v and h(o/'frozen-target'/n)==v for n,v in t.items())
p=json.loads((o/'protected-before.json').read_text());assert all(h(r/n)==v for n,v in p.items())
paths=sorted([x for x in o.rglob('*') if x.is_file() and x.name!='evidence-manifest.json']+[r/'docs/evidence/iam-stage-3.md'])
manifest={x.relative_to(r).as_posix():h(x) for x in paths}
(o/'evidence-manifest.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False),encoding='utf8')
print(json.dumps({'source_files_verified':len(a),'target_files_verified':len(t),'protected_files_verified':len(p),'evidence_files':len(manifest),'report_sha256':h(r/'docs/evidence/iam-stage-3.md'),'manifest_sha256':h(o/'evidence-manifest.json'),'patch_sha256':h(o/'full.patch')},indent=2))
