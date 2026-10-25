from pathlib import Path
import hashlib,json,shutil
root=Path.cwd(); out=root/'docs/evidence/iam-stage-3'
files=sorted([p for p in (root/'admin-common/src').rglob('*') if p.is_file()]+[root/'admin-common/pom.xml'])
manifest={p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
prior=json.loads((root/'docs/evidence/iam-stage-2-review-fixes/source-after.json').read_text(encoding='utf-8-sig'))
assert manifest==prior, 'BASELINE_DIFFERS'
for p in files:
 d=out/'baseline'/p.relative_to(root);d.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,d)
(out/'source-before.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
protected=[]
for mod in ['platform-gateway','app-management','engine-runtime','skill-registry','model-gateway','monitor','web','contracts']:
 protected.extend(p for p in (root/mod).rglob('*') if p.is_file() and not any(x in p.parts for x in ['target','node_modules','dist','.git']))
protected += [root/'pom.xml',root/'admin-common/src/main/java/cn/agentplatform/common/SecurityConfiguration.java']
(out/'protected-before.json').write_text(json.dumps({p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(protected)},indent=2),encoding='utf-8')
print('Verified common baseline',len(files),'protected files',len(protected))
