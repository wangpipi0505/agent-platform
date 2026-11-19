import json, base64, os, subprocess, sys, time, uuid
KEY = "/home/agent-platform/secrets/authority-rsa.pem"
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
now = int(time.time())
claims = {
  "iss": "http://127.0.0.1:18086", "aud": ["agent-platform-portal"], "client_id": "gateway",
  "token_use": "user", "tenant_id": "demo", "sub": "r30-eval-seeder",
  "roles": ["ADMIN"], "attributes": {"region": ["A"], "dataLabels": ["public"]},
  "scope": "platform.access", "iat": now, "nbf": now, "exp": now + 900,
  "jti": "tt-" + uuid.uuid4().hex, "origin_authentication_id": "oa1:" + uuid.uuid4().hex,
}
header = {"alg": "RS256", "typ": "at+jwt", "kid": "2026-10"}
signing = b64(json.dumps(header, separators=(",", ":")).encode()) + "." + b64(json.dumps(claims, separators=(",", ":")).encode())
open("/tmp/r30-signing.bin", "wb").write(signing.encode())
subprocess.run(["openssl", "dgst", "-sha256", "-sign", KEY, "-out", "/tmp/r30-sig.bin", "/tmp/r30-signing.bin"], check=True)
sig = open("/tmp/r30-sig.bin", "rb").read()
os.remove("/tmp/r30-signing.bin"); os.remove("/tmp/r30-sig.bin")
print(signing + "." + b64(sig))
