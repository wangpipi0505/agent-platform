from pathlib import Path
import json,hashlib,shutil,difflib,subprocess,os,xml.etree.ElementTree as ET
r=Path.cwd();o=r/'docs/evidence/iam-stage-3-review-fixes'
def h(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(n,v):(o/n).write_text(json.dumps(v,indent=2,ensure_ascii=False),encoding='utf8')
before=json.loads((o/'source-before.json').read_text(encoding='utf8'))
files=sorted([r/'admin-common/pom.xml']+[p for p in (r/'admin-common/src').rglob('*') if p.is_file()])
after={p.relative_to(r).as_posix():h(p) for p in files};assert set(before)==set(after)
changed=[n for n in before if before[n]!=after[n]]
for n in after:
 dst=o/'frozen-final'/n;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(r/n,dst)
save('source-after.json',after);save('changed-files.json',changed)
patch=''
for n in changed:
 old=(o/'baseline'/n).read_bytes().decode('utf8');new=(r/n).read_bytes().decode('utf8')
 patch+=''.join(difflib.unified_diff(old.splitlines(keepends=True),new.splitlines(keepends=True),fromfile='a/'+n,tofile='b/'+n))
(o/'full.patch').write_bytes(patch.encode('utf8'))
shutil.copytree(o/'baseline',o/'applied-final')
env=dict(os.environ);env['GIT_CEILING_DIRECTORIES']=str(o)
proof=[]
for args in [['git','-c','core.autocrlf=false','apply','--check',str(o/'full.patch')],['git','-c','core.autocrlf=false','apply',str(o/'full.patch')]]:
 result=subprocess.run(args,cwd=o/'applied-final',env=env,capture_output=True);proof.append({'argv':args,'exit':result.returncode,'stdout':result.stdout.decode('utf8',errors='replace'),'stderr':result.stderr.decode('utf8',errors='replace')});assert result.returncode==0,proof
applied={p.relative_to(o/'applied-final').as_posix():h(p) for p in (o/'applied-final').rglob('*') if p.is_file()}
frozen={p.relative_to(o/'frozen-final').as_posix():h(p) for p in (o/'frozen-final').rglob('*') if p.is_file()}
assert applied==frozen==after
assert all((o/'applied-final'/n).read_bytes()==(o/'frozen-final'/n).read_bytes()==(r/n).read_bytes() for n in after)
assert all(h(o/'baseline'/n)==s for n,s in before.items())
save('applied-source.json',applied);save('frozen-source.json',frozen)
save('apply-proof.json',{'commands':proof,'files':len(after),'baseline_sha_matches':True,'applied_frozen_live_bytes_equal':True,'patch_sha256':h(o/'full.patch')})
protected=json.loads((o/'protected-before.json').read_text(encoding='utf8'));assert all(h(r/n)==s for n,s in protected.items());save('protected-after.json',protected)
original=json.loads((o/'original-evidence-before.json').read_text(encoding='utf8'));assert all(h(r/n)==s for n,s in original.items());save('original-evidence-after.json',original)
assert (r/'docs/evidence/review-iam-stage-3.md').read_bytes().startswith((o/'review-original-prefix.md').read_bytes())
t=r/'admin-common/target';shutil.copytree(t,o/'frozen-target-final');target={p.relative_to(t).as_posix():h(p) for p in t.rglob('*') if p.is_file()};frozen_target={p.relative_to(o/'frozen-target-final').as_posix():h(p) for p in (o/'frozen-target-final').rglob('*') if p.is_file()};assert target==frozen_target
assert all((t/n).read_bytes()==(o/'frozen-target-final'/n).read_bytes() for n in target)
save('target-after.json',target);save('frozen-target.json',frozen_target)
suites=[ET.parse(p).getroot() for p in (o/'final-common-xml').glob('TEST-*.xml')];counts={k:sum(int(s.attrib[k]) for s in suites) for k in ['tests','failures','errors','skipped']};assert counts=={'tests':121,'failures':0,'errors':0,'skipped':0}
save('freeze-summary.json',{'source_files':len(after),'changed_files':len(changed),'target_files':len(target),'protected_files':len(protected),'original_evidence_files':len(original),'original_review_prefix_preserved':True,'tests':counts,'source_sha256':h(o/'source-after.json'),'patch_sha256':h(o/'full.patch'),'target_sha256':h(o/'target-after.json')})
print((o/'freeze-summary.json').read_text(encoding='utf8'));print('\n'.join(changed))
