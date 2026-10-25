from pathlib import Path
import json,hashlib
r=Path.cwd();o=r/'docs/evidence/iam-stage-3-review-fixes';changed=json.loads((o/'changed-files.json').read_text(encoding='utf8'));proof=[]
for n in changed:
 p=r/n;b=(o/'baseline'/n).read_bytes();a=p.read_bytes();normalized=a.replace(b'\r\n',b'\n');oldstyle='CRLF' if b.count(b'\r\n')==b.count(b'\n') else 'LF'
 assert b.count(b'\r\n') in (0,b.count(b'\n'))
 final=normalized.replace(b'\n',b'\r\n') if oldstyle=='CRLF' else normalized
 assert final.replace(b'\r\n',b'\n')==a.replace(b'\r\n',b'\n');p.write_bytes(final)
 proof.append({'path':n,'baseline_style':oldstyle,'tested_sha256':hashlib.sha256(a).hexdigest(),'final_sha256':hashlib.sha256(final).hexdigest(),'normalized_text_identical':True})
(o/'line-ending-proof.json').write_text(json.dumps(proof,indent=2),encoding='utf8')
for name in ['source-after.json','full.patch','applied-source.json','frozen-source.json','apply-proof.json','target-after.json','frozen-target.json','freeze-summary.json']:
 (o/('pre-eol-'+name)).write_bytes((o/name).read_bytes())
p=o/'freeze.py';s=p.read_text(encoding='utf8').replace("o/'frozen'","o/'frozen-final'").replace("o/'applied'","o/'applied-final'").replace("o/'frozen-target'","o/'frozen-target-final'");(o/'freeze-final.py').write_text(s,encoding='utf8')
print(json.dumps(proof,indent=2))
