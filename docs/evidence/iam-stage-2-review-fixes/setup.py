from pathlib import Path
import hashlib,json,shutil
ROOT=Path(__file__).resolve().parents[3];OUT=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(n,o):(OUT/n).write_text(json.dumps(o,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
files=[ROOT/'admin-common/pom.xml']+[p for p in (ROOT/'admin-common/src').rglob('*') if p.is_file()]
assert not (OUT/'baseline').exists()
current={p.relative_to(ROOT).as_posix():sha(p) for p in sorted(files)}
assert current==json.loads((ROOT/'docs/evidence/iam-stage-2/source-after.json').read_text('utf8'))
for p in files:
 q=OUT/'baseline'/p.relative_to(ROOT);q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q)
save('baseline-sha256.json',current)
target=json.loads((ROOT/'docs/evidence/iam-stage-2/target-sha256.json').read_text('utf8'))
assert all(sha(ROOT/'admin-common/target'/p)==v for p,v in target.items())
save('baseline-target-sha256.json',target)
prior=[]
for p in (ROOT/'docs/evidence').iterdir():
 if p==OUT:continue
 if 'iam-stage-1' in p.name or 'iam-stage-2' in p.name:
  prior.extend(q for q in p.rglob('*') if q.is_file()) if p.is_dir() else prior.append(p)
save('prior-evidence-before.json',{p.relative_to(ROOT).as_posix():sha(p) for p in sorted(prior)})
source=(ROOT/'docs/evidence/iam-stage-2/freeze.py').read_text('utf8').replace('Task2 source','Task2 review-fix source').replace('iam-stage-2','iam-stage-2-review-fixes')
(OUT/'freeze.py').write_text(source,encoding='utf8')
print('Baseline matches Task2:',len(current),'source files;',len(target),'target outputs; prior',len(prior),'files')
