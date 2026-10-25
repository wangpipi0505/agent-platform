"""Freeze this task's complete common tree and prove the patch by actual isolated git apply."""
from pathlib import Path
import difflib, hashlib, json, os, shutil, subprocess, xml.etree.ElementTree as ET

root=Path.cwd()
out=root/'docs/evidence/iam-stage-3'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def write(name,value): (out/name).write_text(json.dumps(value,indent=2,ensure_ascii=False),encoding='utf-8')
before=json.loads((out/'source-before.json').read_text(encoding='utf-8'))
files=sorted([p for p in (root/'admin-common/src').rglob('*') if p.is_file()]+[root/'admin-common/pom.xml'])
after={p.relative_to(root).as_posix():sha(p) for p in files}
changes=[]
patch=[]
for name in sorted(set(before)|set(after)):
    original=(out/'baseline'/name).read_bytes() if name in before else b''
    final=(root/name).read_bytes() if name in after else b''
    if original==final: continue
    changes.append({'path':name,'status':'added' if name not in before else 'modified','before':before.get(name),'after':after.get(name)})
    patch.append(f'diff --git a/{name} b/{name}\n')
    if name not in before: patch.append('new file mode 100644\n')
    patch.extend(difflib.unified_diff(original.decode('utf-8').splitlines(keepends=True),final.decode('utf-8').splitlines(keepends=True),
        fromfile='a/'+name if name in before else '/dev/null',tofile='b/'+name,n=3))
(out/'full.patch').write_bytes(''.join(patch).encode('utf-8'))
for p in files:
    dest=out/'frozen'/p.relative_to(root);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)
write('source-after.json',after);write('changed-files.json',changes)
frozen={name:sha(out/'frozen'/name) for name in after}
assert frozen==after
applied=out/'applied-final'
if applied.exists(): raise RuntimeError('Do not overwrite an existing apply proof')
shutil.copytree(out/'baseline',applied)
env=dict(os.environ)
# Prevent Git walking up into the shared project repository; this is an independent byte proof.
env['GIT_CEILING_DIRECTORIES']=str(out)
logs=[]
for arguments in [['git','-c','core.autocrlf=false','apply','--check',str(out/'full.patch')],['git','-c','core.autocrlf=false','apply',str(out/'full.patch')]]:
    result=subprocess.run(arguments,cwd=applied,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace')
    logs.append({'command':arguments,'cwd':str(applied),'exit':result.returncode,'stdout':result.stdout,'stderr':result.stderr})
    if result.returncode: write('apply-log.json',logs);raise RuntimeError('git apply failed')
write('apply-log.json',logs)
applied_files={p.relative_to(applied).as_posix():sha(p) for p in applied.rglob('*') if p.is_file()}
assert applied_files==after, 'actual applied files differ'
assert all((applied/name).read_bytes()==(out/'frozen'/name).read_bytes()==(root/name).read_bytes() for name in after)
write('apply-log.json',logs)
protected=json.loads((out/'protected-before.json').read_text(encoding='utf-8'))
actual_protected={name:sha(root/name) for name in protected}
assert actual_protected==protected, 'protected source changed'
write('protected-after.json',actual_protected)
shutil.copytree(root/'admin-common/target',out/'frozen-target')
target={p.relative_to(out/'frozen-target').as_posix():sha(p) for p in (out/'frozen-target').rglob('*') if p.is_file()}
write('target-after.json',target)
suites=[]
for p in sorted((out/'frozen-full-xml').glob('TEST-*.xml')):
    e=ET.parse(p).getroot()
    suites.append({k:e.attrib[k] for k in ['name','tests','failures','errors','skipped','time']})
summary={k:sum(int(s[k]) for s in suites) for k in ['tests','failures','errors','skipped']}
summary['suites']=suites
assert summary['tests']==113 and summary['failures']==summary['errors']==summary['skipped']==0
write('full-test-summary.json',summary)
write('freeze-proof.json',{'baselineFiles':len(before),'frozenFiles':len(after),'changedFiles':len(changes),
    'protectedFiles':len(protected),'targetFiles':len(target),'patchSha256':sha(out/'full.patch'),
    'sourceManifestSha256':sha(out/'source-after.json'),'baselineEqualsTask2':before==json.loads((root/'docs/evidence/iam-stage-2-review-fixes/source-after.json').read_text(encoding='utf-8-sig')),
    'actualGitApplyEqualsFrozenByteForByte':True,'liveEqualsFrozenByteForByte':True,'protectedUnchanged':True})
print(json.dumps({'sourceFiles':len(after),'changed':len(changes),'patchSha256':sha(out/'full.patch'),'tests':summary['tests'],'actualApply':'BYTE_IDENTICAL'},ensure_ascii=False))
