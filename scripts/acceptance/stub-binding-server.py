#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""J1/J2 执行器自测桩（只用于验证脚本逻辑，不是本体实现，也不进生产路径）。

模拟 `/onto/internal/release-bindings` 的四种响应：
  - 只读 key 写      -> 403 NO_PERMISSION（对应 O-12 的 release.bind 缺失）
  - binder key 首次写 -> 200 changed=true
  - binder key 重复写 -> 200 changed=false，同一个 bindingId
  - 查询不存在        -> 404 BINDING_NOT_FOUND
用法：python scripts/acceptance/stub-binding-server.py 18199
"""
import json
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

READONLY_KEY = "readonly-key"
BINDER_KEY = "binder-key"
BINDINGS = {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # 静音：自测不产出噪声日志
        return

    def _send(self, status, body):
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _key(self):
        return self.headers.get("X-API-Key", "")

    def do_POST(self):
        if urlparse(self.path).path != "/onto/internal/release-bindings":
            return self._send(404, {"code": "NOT_FOUND", "message": "unknown path", "data": None})
        length = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return self._send(400, {"code": "INVALID_INPUT", "message": "bad json", "data": None})
        for field in ("spaceId", "platformTenant", "appCode", "environment", "releaseId"):
            if not body.get(field):
                return self._send(
                    400, {"code": "INVALID_INPUT", "message": "missing " + field, "data": None}
                )
        key = self._key()
        if key == READONLY_KEY:
            return self._send(
                403, {"code": "NO_PERMISSION", "message": "只读服务账号不能写绑定", "data": None}
            )
        if key != BINDER_KEY:
            return self._send(
                401, {"code": "SERVICE_CREDENTIAL_REQUIRED", "message": "缺少服务凭据", "data": None}
            )
        slot = (body["platformTenant"], body["appCode"], body["environment"])
        existing = BINDINGS.get(slot)
        changed = existing is None or existing["releaseId"] != body["releaseId"]
        if changed:
            existing = {"bindingId": "bind-" + uuid.uuid4().hex[:12], "releaseId": body["releaseId"]}
            BINDINGS[slot] = existing
        return self._send(
            200,
            {
                "code": "OK",
                "data": {
                    "bindingId": existing["bindingId"],
                    "releaseId": existing["releaseId"],
                    "changed": changed,
                },
            },
        )

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path != "/onto/internal/release-bindings":
            return self._send(404, {"code": "NOT_FOUND", "message": "unknown path", "data": None})
        if self._key() not in (READONLY_KEY, BINDER_KEY):
            return self._send(
                401, {"code": "SERVICE_CREDENTIAL_REQUIRED", "message": "缺少服务凭据", "data": None}
            )
        query = parse_qs(parsed.query)
        slot = (
            (query.get("platformTenant") or [""])[0],
            (query.get("appCode") or [""])[0],
            (query.get("environment") or [""])[0],
        )
        existing = BINDINGS.get(slot)
        if existing is None:
            return self._send(404, {"code": "BINDING_NOT_FOUND", "message": "无绑定", "data": None})
        return self._send(
            200,
            {
                "code": "OK",
                "data": {"bindingId": existing["bindingId"], "releaseId": existing["releaseId"]},
            },
        )


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18199
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("stub listening on http://127.0.0.1:%d" % port, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
