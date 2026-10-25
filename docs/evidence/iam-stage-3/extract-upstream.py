from pathlib import Path
from zipfile import ZipFile
import hashlib,json
out=Path('docs/evidence/iam-stage-3/upstream');out.mkdir(exist_ok=True)
entries={
 'docs/evidence/iam-stage-2/upstream/nimbus-jose-jwt-9.37.3-sources.jar':['com/nimbusds/jose/crypto/RSASSAVerifier.java','com/nimbusds/jose/JWSObject.java'],
 'docs/evidence/iam-stage-2/upstream/spring-security-oauth2-jose-6.5.0-sources.jar':['org/springframework/security/oauth2/jwt/NimbusJwtDecoder.java']}
manifest={}
for jar,names in entries.items():
 with ZipFile(jar) as z:
  for name in names:
   b=z.read(name);p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b);manifest[name]=hashlib.sha256(b).hexdigest()
(out/'sha256.json').write_text(json.dumps(manifest,indent=2),encoding='utf8')
