"""Task2 review-fix source freeze, actual patch reconstruction, output/evidence byte verification."""
from pathlib import Path
import hashlib,json,shutil,subprocess,difflib,sys,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(n,o):(OUT/n).write_text(json.dumps(o,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
def common(base):
 return {p.relative_to(base).as_posix():sha(p) for p in sorted((base/'admin-common/src').rglob('*')) if p.is_file()}|{'admin-common/pom.xml':sha(base/'admin-common/pom.xml')}
def prior():
 old=json.loads((OUT/'prior-evidence-before.json').read_text('utf8'))
 now={p:sha(ROOT/p) for p in old}
 assert old==now,'Task1 evidence changed'
 save('prior-evidence-after.json',now)
if sys.argv[1]=='freeze':
 source=common(ROOT);save('source-before.json',source)
 assert common(OUT/'baseline')==json.loads((OUT/'baseline-sha256.json').read_text('utf8'))
 shutil.copytree(ROOT/'admin-common/src',OUT/'frozen/admin-common/src')
 shutil.copy2(ROOT/'admin-common/pom.xml',OUT/'frozen/admin-common/pom.xml')
 baseline=common(OUT/'baseline')
 changes=[dict(path=p,kind='added' if p not in baseline else 'deleted' if p not in source else 'modified',baselineSha256=baseline.get(p),frozenSha256=source.get(p)) for p in sorted(source.keys()|baseline.keys()) if source.get(p)!=baseline.get(p)]
 save('changes.json',changes)
 patch=[]
 for c in changes:
  name=c['path'];a=OUT/'baseline'/name;b=OUT/'frozen'/name
  patch.append(f'diff --git a/{name} b/{name}\n')
  if not a.exists():patch.append('new file mode 100644\n')
  patch.extend(difflib.unified_diff(a.read_bytes().decode('utf8').splitlines(keepends=True) if a.exists() else [],b.read_bytes().decode('utf8').splitlines(keepends=True) if b.exists() else [],fromfile='a/'+name if a.exists() else '/dev/null',tofile='b/'+name if b.exists() else '/dev/null'))
 (OUT/'full.patch').write_bytes(''.join(patch).encode('utf8'));assert (OUT/'full.patch').stat().st_size>0
 shutil.copytree(OUT/'baseline/admin-common',OUT/'applied-bytes/admin-common')
 args=['git','-c','core.autocrlf=false','apply','--verbose','--whitespace=nowarn','--directory=docs/evidence/iam-stage-2-review-fixes/applied-bytes','docs/evidence/iam-stage-2-review-fixes/full.patch']
 result=subprocess.run(args,cwd=ROOT,capture_output=True)
 (OUT/'actual-apply.log').write_bytes(result.stdout+result.stderr)
 assert result.returncode==0,result.stderr
 assert common(OUT/'applied-bytes')==source==common(OUT/'frozen')
 save('patch-apply-verification.json',dict(command=args,exitCode=result.returncode,allCommonFiles=len(source),changedFiles=len(changes),allBytesEqual=True,patchSha256=sha(OUT/'full.patch')))
 prior();print('Frozen/actually applied',len(source),'common files;',len(changes),'changes; byte-for-byte equal')
elif sys.argv[1]=='verify':
 after=common(ROOT);save('source-after.json',after)
 assert after==json.loads((OUT/'source-before.json').read_text('utf8'))==common(OUT/'frozen')==common(OUT/'applied-bytes')
 reports=OUT/'full-surefire';reports.mkdir(exist_ok=True)
 summary=[]
 for p in sorted((ROOT/'admin-common/target/surefire-reports').glob('TEST-*.xml')):
  shutil.copy2(p,reports/p.name);r=ET.parse(p).getroot()
  summary.append({k:r.attrib[k] for k in ['name','tests','failures','errors','skipped','time']})
 save('full-test-summary.json',summary)
 target={p.relative_to(ROOT/'admin-common/target').as_posix():sha(p) for d in ['classes','test-classes'] for p in sorted((ROOT/'admin-common/target'/d).rglob('*')) if p.is_file()}
 save('target-sha256.json',target);prior()
 print('Source unchanged:',len(after),'files; target:',len(target),'files; tests:',sum(int(s['tests']) for s in summary))
elif sys.argv[1]=='manifest':
 prior();report=ROOT/'docs/evidence/iam-stage-2-review-fixes.md';assert report.exists()
 files=[p for p in sorted(OUT.rglob('*')) if p.is_file() and p!=OUT/'manifest.json']+[report]
 save('manifest.json',{p.relative_to(ROOT).as_posix():sha(p) for p in files})
 print('Evidence manifest:',len(files),'files; patch SHA256:',sha(OUT/'full.patch'))
else:raise ValueError(sys.argv[1])
