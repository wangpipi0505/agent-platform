#!/usr/bin/env python3
"""PR-B1 票据铸造（M5-3 harness mint 同构；密钥只从 env 读取，不入参数/日志）。

用法：
  export AGENT_JWT_SECRET=$(docker exec e1-monitor printenv AGENT_JWT_SECRET)
  python3 mint-prb1-tokens.py <outdir>
产出 <outdir>/.user-token（demo ADMIN 用户票）与 <outdir>/.service-token（dev-hmac
ServiceTokens.issue 同构服务票，aud=agent-platform-internal，roles=ADMIN+SERVICE）。
"""
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid


def b64(v):
    return base64.urlsafe_b64encode(v).rstrip(b"=").decode()


def sign(secret, claims):
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64(json.dumps(claims, separators=(",", ":"), ensure_ascii=False).encode())
    signing = (header + "." + payload).encode()
    mac = hmac.new(secret.encode(), signing, hashlib.sha256).digest()
    return header + "." + payload + "." + b64(mac)


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else "/home/agent-platform/evidence/PR-B1"
    secret = os.environ.get("AGENT_JWT_SECRET", "")
    if len(secret) < 32:
        sys.exit("AGENT_JWT_SECRET 未设置或过短")
    now = int(time.time())
    os.makedirs(outdir, exist_ok=True)
    user = sign(secret, {
        "iss": "agent-platform-v12", "aud": "agent-platform", "token_use": "user",
        "sub": "prb1-admin", "tenant_id": "demo", "roles": ["ADMIN"],
        "attributes": {"region": "E2", "dataLabels": ["E2_REAL"]},
        "iat": now, "exp": now + 900, "jti": str(uuid.uuid4()),
    })
    service = sign(secret, {
        "iss": "agent-platform-v12", "aud": "agent-platform-internal", "token_use": "service",
        "sub": "prb1-admin", "tenant_id": "demo", "roles": ["ADMIN", "SERVICE"],
        "attributes": {}, "iat": now, "exp": now + 120, "jti": str(uuid.uuid4()),
    })
    for name, token in ((".user-token", user), (".service-token", service)):
        path = os.path.join(outdir, name)
        with open(path, "w") as f:
            f.write(token)
        os.chmod(path, 0o600)
    print("tokens written to " + outdir + "/.user-token .service-token")


if __name__ == "__main__":
    main()
