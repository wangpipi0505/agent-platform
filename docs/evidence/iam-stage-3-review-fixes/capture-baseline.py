from pathlib import Path
import json,hashlib,shutil
r=Path.cwd();o=r/'docs/evidence/iam-stage-3-review-fixes'
def h(p): return hashlib.sha256(p.read_bytes()).hexdigest()
a=json.loads((r/'docs/evidence/iam-stage-3/source-after.json').read_text());assert all(h(r/n)==s for n,s in a.items())
for n in a:
 p=o/'baseline'/n;p.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(r/n,p)
(o/'source-before.json').write_text(json.dumps(a,indent=2),encoding='utf8')
p=json.loads((r/'docs/evidence/iam-stage-3/protected-after.json').read_text());assert all(h(r/n)==s for n,s in p.items());(o/'protected-before.json').write_text(json.dumps(p,indent=2),encoding='utf8')
files=[r/'docs/evidence/iam-stage-3.md',r/'docs/evidence/review-iam-stage-3.md']
for d in ['docs/evidence/iam-stage-3','docs/evidence/review-iam-stage-3']: files += [p for p in (r/d).rglob('*') if p.is_file()]
old={p.relative_to(r).as_posix():h(p) for p in files};(o/'original-evidence-before.json').write_text(json.dumps(old,indent=2,ensure_ascii=False),encoding='utf8')
shutil.copyfile(r/'docs/evidence/review-iam-stage-3.md',o/'review-original-prefix.md')
(o/'target-before.json').write_text(json.dumps({p.relative_to(r/'admin-common/target').as_posix():h(p) for p in (r/'admin-common/target').rglob('*') if p.is_file()},indent=2),encoding='utf8')
print('baseline',len(a),'protected',len(p),'original evidence',len(old))
