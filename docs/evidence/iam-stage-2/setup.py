from pathlib import Path
import hashlib,json,shutil,urllib.request,zipfile,io
ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(n,o):(OUT/n).write_text(json.dumps(o,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
files=[ROOT/'admin-common/pom.xml']+[p for p in (ROOT/'admin-common/src').rglob('*') if p.is_file()]
assert not (OUT/'baseline').exists()
for p in files:
 q=OUT/'baseline'/p.relative_to(ROOT);q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q)
baseline={p.relative_to(ROOT).as_posix():sha(p) for p in sorted(files)}
assert baseline==json.loads((ROOT/'docs/evidence/iam-stage-1-ownership-fix/source-after.json').read_text('utf8'))
save('baseline-sha256.json',baseline)
prior=[ROOT/'docs/evidence/iam-stage-1.md',ROOT/'docs/evidence/iam-stage-1-ownership-fix.md',ROOT/'docs/evidence/review-iam-stage-1.md']
for d in ['iam-stage-1','iam-stage-1-ownership-fix','review-iam-stage-1']:
 prior.extend(p for p in (ROOT/'docs/evidence'/d).rglob('*') if p.is_file())
save('prior-evidence-before.json',{p.relative_to(ROOT).as_posix():sha(p) for p in sorted(set(prior)) if p.is_file()})
sources=[('https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-jose/6.5.0/spring-security-oauth2-jose-6.5.0-sources.jar',['org/springframework/security/oauth2/jwt/NimbusJwtDecoder.java','org/springframework/security/oauth2/jwt/JwtTimestampValidator.java','org/springframework/security/oauth2/jwt/MappedJwtClaimSetConverter.java']),('https://repo.maven.apache.org/maven2/com/nimbusds/nimbus-jose-jwt/9.37.3/nimbus-jose-jwt-9.37.3-sources.jar',['com/nimbusds/jwt/proc/DefaultJWTProcessor.java','com/nimbusds/jose/proc/JWSVerificationKeySelector.java','com/nimbusds/jose/jwk/JWKSet.java'])]
record={}
for url,names in sources:
 data=urllib.request.urlopen(url,timeout=30).read();p=OUT/'upstream'/url.rsplit('/',1)[1];p.parent.mkdir(exist_ok=True);p.write_bytes(data)
 record[url]=sha(p)
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  for name in names:
   q=OUT/'upstream'/name;q.parent.mkdir(parents=True,exist_ok=True);q.write_bytes(z.read(name))
save('upstream-sha256.json',record)
print('Baseline matches Task1:',len(baseline),'files; downloaded pinned source jars')
