"""Freeze all common source/POM; verify an actual git-apply and byte-for-byte reconstruction."""
from pathlib import Path
import sys,hashlib,json,shutil,subprocess,difflib
ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def manifest(base):
    return {p.relative_to(base).as_posix():sha(p) for p in sorted((base/'admin-common/src').rglob('*')) if p.is_file()} | {'admin-common/pom.xml':sha(base/'admin-common/pom.xml')}
def save(name,obj): (OUT/name).write_text(json.dumps(obj,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
if sys.argv[1]=='freeze':
    before=manifest(ROOT)
    if (OUT/'source-before.json').exists(): assert before==json.loads((OUT/'source-before.json').read_text())
    else: save('source-before.json',before)
    shutil.copytree(ROOT/'admin-common/src',OUT/'frozen/admin-common/src',dirs_exist_ok=True)
    shutil.copy2(ROOT/'admin-common/pom.xml',OUT/'frozen/admin-common/pom.xml')
    assert before==manifest(OUT/'frozen')
    baseline=manifest(OUT/'baseline')
    changes=[dict(path=p,kind='added' if p not in baseline else 'modified' if p in before else 'deleted',baselineSha256=baseline.get(p),frozenSha256=before.get(p)) for p in sorted(before.keys()|baseline.keys()) if baseline.get(p)!=before.get(p)]
    save('changes.json',changes)
    # Preserve actual CR/LF bytes: repository .gitattributes would normalize CRLF
    # during git diff --no-index. This complete unified diff has no HEAD dependency.
    patch=[]
    for change in changes:
        name=change['path'];a=OUT/'baseline'/name;b=OUT/'frozen'/name
        patch.append(f'diff --git a/{name} b/{name}\n')
        if not a.exists(): patch.append('new file mode 100644\n')
        patch.extend(difflib.unified_diff(a.read_bytes().decode('utf-8').splitlines(keepends=True) if a.exists() else [],b.read_bytes().decode('utf-8').splitlines(keepends=True) if b.exists() else [],fromfile='a/'+name if a.exists() else '/dev/null',tofile='b/'+name if b.exists() else '/dev/null'))
    (OUT/'full.patch').write_bytes(''.join(patch).encode('utf-8'))
    shutil.copytree(OUT/'baseline/admin-common',OUT/'applied-bytes/admin-common')
    applied=subprocess.run(['git','-c','core.autocrlf=false','apply','--verbose','--whitespace=nowarn','--directory=docs/evidence/iam-stage-1-ownership-fix/applied-bytes','docs/evidence/iam-stage-1-ownership-fix/full.patch'],cwd=ROOT,capture_output=True)
    (OUT/'actual-apply.log').write_bytes(applied.stdout+applied.stderr)
    assert applied.returncode==0,applied.stderr
    assert manifest(OUT/'applied-bytes')==before,'actual applied source differs from frozen source'
    save('patch-apply-verification.json',dict(command='git -c core.autocrlf=false apply --verbose --whitespace=nowarn --directory=docs/evidence/iam-stage-1-ownership-fix/applied-bytes docs/evidence/iam-stage-1-ownership-fix/full.patch',exitCode=applied.returncode,allCommonFiles=len(before),changedFiles=len(changes),allBytesEqual=True,patchSha256=sha(OUT/'full.patch')))
    print('Frozen and actually applied:',len(before),'common files;',len(changes),'changes; every byte equal')
elif sys.argv[1]=='verify':
    after=manifest(ROOT);save('source-after.json',after)
    before=json.loads((OUT/'source-before.json').read_text())
    assert after==before==manifest(OUT/'frozen')==manifest(OUT/'applied-bytes')
    copied=OUT/'full-surefire';copied.mkdir(exist_ok=True)
    for p in (ROOT/'admin-common/target/surefire-reports').glob('TEST-*.xml'): shutil.copy2(p,copied/p.name)
    target={p.relative_to(ROOT/'admin-common/target').as_posix():sha(p) for part in ['classes','test-classes'] for p in sorted((ROOT/'admin-common/target'/part).rglob('*')) if p.is_file()}
    save('target-sha256.json',target)
    inputs=[ROOT/'pom.xml',ROOT/'AGENTS.md',ROOT/'docs/evidence/iam-stage-1-implementation-brief.md',ROOT/'contracts/README.md']
    inputs += [p for module in ['platform-gateway','app-management','engine-runtime','skill-registry','model-gateway','monitor'] for p in sorted((ROOT/module/'src/main/java').rglob('*.java'))]
    save('read-only-input-sha256.json',{p.relative_to(ROOT).as_posix():sha(p) for p in inputs})
    print('Unchanged before/after:',len(after),'files; target entries',len(target))
else: raise ValueError(sys.argv[1])
