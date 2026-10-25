from pathlib import Path
import json,hashlib,shutil,difflib,subprocess,os,xml.etree.ElementTree as ET
r=Path.cwd();parent=r/'docs/evidence/iam-stage-3-review-fixes';o=parent/'i3'
def h(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text(encoding='utf8'))
def save(n,v):(o/n).write_text(json.dumps(v,indent=2,ensure_ascii=False),encoding='utf8')
initial=load(parent/'source-before.json');before=load(o/'source-before.json');after={n:h(r/n) for n in initial}
assert set(after)==set(before)
changed=[n for n in initial if initial[n]!=after[n]];i3changed=[n for n in before if before[n]!=after[n]]
assert len(changed)==8 and len(i3changed)==3
for n in after:
 p=o/'frozen'/n;p.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(r/n,p)
save('source-after.json',after);save('combined-source-before.json',initial);save('changed-files.json',{'combined':changed,'i3_only':i3changed})
def patch(base,names):
 return ''.join(''.join(difflib.unified_diff((base/n).read_bytes().decode('utf8').splitlines(keepends=True),(r/n).read_bytes().decode('utf8').splitlines(keepends=True),fromfile='a/'+n,tofile='b/'+n)) for n in names).encode('utf8')
(o/'full.patch').write_bytes(patch(parent/'baseline',changed));(o/'i3-only.patch').write_bytes(patch(o/'baseline',i3changed))
proof=[];env=dict(os.environ);env['GIT_CEILING_DIRECTORIES']=str(o)
for base,dest,patchname in [(parent/'baseline',o/'applied','full.patch'),(o/'baseline',o/'i3-applied','i3-only.patch')]:
 shutil.copytree(base,dest)
 for check in [True,False]:
  args=['git','-c','core.autocrlf=false','apply']+(['--check'] if check else [])+[str(o/patchname)]
  result=subprocess.run(args,cwd=dest,env=env,capture_output=True);proof.append({'argv':args,'cwd':str(dest),'exit':result.returncode,'stdout':result.stdout.decode('utf8',errors='replace'),'stderr':result.stderr.decode('utf8',errors='replace')});assert result.returncode==0,proof
 applied={p.relative_to(dest).as_posix():h(p) for p in dest.rglob('*') if p.is_file()};assert applied==after
 assert all((dest/n).read_bytes()==(o/'frozen'/n).read_bytes()==(r/n).read_bytes() for n in after)
save('apply-proof.json',{'commands':proof,'source_files':len(after),'combined_and_i3_apply_frozen_live_bytes_equal':True})
candidate=load(o/'candidate-protected.json');assert all(h(r/n)==s for n,s in candidate.items())
protected=load(parent/'protected-before.json');assert all(h(r/n)==s for n,s in protected.items())
original=load(parent/'original-evidence-before.json');assert all(h(r/n)==s for n,s in original.items())
save('protected-after.json',protected);save('original-evidence-after.json',original)
t=r/'admin-common/target';shutil.copytree(t,o/'frozen-target');target={p.relative_to(t).as_posix():h(p) for p in t.rglob('*') if p.is_file()};assert all((t/n).read_bytes()==(o/'frozen-target'/n).read_bytes() for n in target);save('target-after.json',target)
audit={}
for folder in ['red-xml','green-named-xml','final-common-xml']:
 suites=[]
 for p in sorted((o/folder).glob('TEST-*.xml')):
  e=ET.parse(p).getroot();suites.append({'name':e.attrib['name'],'sha256':h(p),**{k:int(e.attrib[k]) for k in ['tests','failures','errors','skipped']},'methods':[t.attrib['name'] for t in e.findall('testcase')]})
 audit[folder]={'suites':suites,'total':{k:sum(s[k] for s in suites) for k in ['tests','failures','errors','skipped']}}
assert audit['red-xml']['total']=={'tests':2,'failures':1,'errors':0,'skipped':0}
assert audit['green-named-xml']['total']=={'tests':41,'failures':0,'errors':0,'skipped':0}
assert audit['final-common-xml']['total']=={'tests':123,'failures':0,'errors':0,'skipped':0}
save('xml-audit.json',audit)
save('freeze-summary.json',{'source_files':len(after),'combined_changed_files':len(changed),'i3_changed_files':len(i3changed),'target_files':len(target),'original_evidence_unchanged':len(original),'protected_unchanged':len(protected),'i1_i2_candidate_evidence_unchanged':len(candidate),'tests':audit['final-common-xml']['total'],'source_sha256':h(o/'source-after.json'),'patch_sha256':h(o/'full.patch'),'i3_patch_sha256':h(o/'i3-only.patch'),'target_sha256':h(o/'target-after.json')})
print((o/'freeze-summary.json').read_text(encoding='utf8'))
