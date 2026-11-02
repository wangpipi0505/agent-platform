#!/usr/bin/env python3
"""J4 契约自检桩：模拟 OR 正式清单端点与 AP ontology manifest 导入端点。

只用于验证 joint-j4.ps1 / Invoke-JointAcceptance.ps1 的脚本层契约，
不产生真实验收证据；真实验收必须指向 18182/18080 的真实服务。

端点与真实形状：
  GET  /onto/releases/{releaseId}/manifest           -> {"code":"OK","data":{"manifests":[...]}}
  GET  /onto/releases/{releaseId}/context-pack       -> {"code":"OK","data":{conceptCards,termDictionary,...}}
  GET|POST /onto/internal/release-bindings           -> 绑定负例 403 / 首次 changed=true / 重复 changed=false
  POST /admin/skills/import-ontology-manifests        -> {"code":"OK","data":{"drafts":[...],"warnings":[...]}}
  POST /auth/dev-token                                -> {"code":"OK","data":{"accessToken":...,"user":{...}}}

清单字段与 ontorepo ContractGenerator.formalize 输出一致（snake_case，无 preview），
导入回执与 skill-registry OntologyManifestImports.importManifests 一致（drafts/warnings）。
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

STATE = {}
BINDINGS = {}


def envelope(code, message, data):
    return {"code": code, "message": message, "data": data}


def formal_manifest(tool_code, public_base, space_code, release_id, version, digest):
    return {
        "tool_code": tool_code,
        "name": "联合验收桩操作 " + tool_code,
        "description": "stub formal manifest for J4 contract self-check",
        "invocation_kind": "SYNC",
        "adapter_type": "HTTP",
        "category": "ontology_service",
        "endpoint": public_base + "/onto/r/" + space_code + "/releases/" + release_id + "/ops/" + tool_code,
        "http_method": "POST",
        "input_schema": {"type": "object", "properties": {"id": {"type": "string"}}},
        "output_schema": {"type": "object", "properties": {"ok": {"type": "boolean"}}},
        "guard_attrs": ["release.read"],
        "exec_attrs": {
            "readonly_flag": True,
            "side_effect": False,
            "idempotent": True,
            "cancellable": False,
            "timeout_ms": 15000,
            "max_rows": 1,
        },
        "risk_level": "LOW",
        "error_semantics": [{"code": "STUB_EMPTY", "httpStatus": 400, "message": "empty input"}],
        "return_spec": {"kind": "OBJECT", "ontologyMetaPath": "$.meta"},
        "ontology_meta": {
            "spaceCode": space_code,
            "releaseId": release_id,
            "version": version,
            "contentDigest": digest,
        },
    }


def draft_from_manifest(manifest, auth_ref, version_no=1):
    tool_code = manifest["tool_code"]
    meta = manifest["ontology_meta"]
    digest = "b" * 64
    definition = {
        "code": tool_code,
        "name": manifest.get("name", tool_code),
        "description": manifest.get("description", ""),
        "type": manifest.get("adapter_type", "HTTP"),
        "invocationKind": manifest.get("invocation_kind", "SYNC"),
        "httpMethod": manifest.get("http_method", "POST"),
        "endpoint": manifest["endpoint"],
        "authScheme": "API_KEY",
        "authRef": auth_ref,
        "importSource": {
            "kind": "ONTOLOGY",
            "toolCode": tool_code,
            "spaceCode": meta["spaceCode"],
            "releaseId": meta["releaseId"],
            "version": meta["version"],
            "contentDigest": meta["contentDigest"],
        },
    }
    return {
        "id": str(101 + version_no),
        "code": tool_code,
        "status": "DRAFT",
        "currentVersion": None,
        "warnings": [],
        "versions": [
            {
                "skillVersionId": str(900 + version_no),
                "code": tool_code,
                "versionNo": version_no,
                "status": "DRAFT",
                "sha256": digest,
                "semanticDigest": digest,
                "digestVersion": 1,
                "definition": definition,
            }
        ],
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "stub-joint-j4/1.0"

    def log_message(self, fmt, *args):  # 保持与其它桩一致的启动日志风格，屏蔽逐请求噪声
        return

    def _send(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json;charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized_read(self):
        return self.headers.get("X-API-Key") == STATE["read_key"]

    def _authorized_bearer(self):
        return self.headers.get("Authorization") == "Bearer " + STATE["bearer"]

    def do_GET(self):
        path = urlparse(self.path).path
        prefix = "/onto/releases/"
        suffix = "/manifest"
        if path.startswith(prefix) and path.endswith(suffix):
            release_id = path[len(prefix):-len(suffix)]
            if STATE["mode"] == "server-error":
                self._send(500, envelope("INTERNAL_ERROR", "stub server-error", None))
                return
            if not self._authorized_read():
                self._send(401, envelope("SERVICE_CREDENTIAL_REQUIRED", "missing or invalid X-API-Key", None))
                return
            if STATE["mode"] == "empty-manifest":
                self._send(200, envelope("OK", "success", {"manifests": []}))
                return
            manifests = [
                formal_manifest(
                    STATE["op_code"],
                    STATE["public_base"],
                    STATE["space_code"],
                    release_id if release_id else STATE["release_id"],
                    "v1",
                    STATE["digest"],
                )
            ]
            if STATE["mode"] == "preview-manifest":
                manifests[0]["ontology_meta"]["preview"] = True
            if STATE["mode"] == "bad-endpoint":
                manifests[0]["endpoint"] = STATE["public_base"] + "/onto/internal/candidates/c1/ops/" + STATE["op_code"]
            self._send(200, envelope("OK", "success", {"manifests": manifests}))
            return
        prefix = "/onto/releases/"
        suffix = "/context-pack"
        if path.startswith(prefix) and path.endswith(suffix):
            if not self._authorized_read():
                self._send(401, envelope("SERVICE_CREDENTIAL_REQUIRED", "missing or invalid X-API-Key", None))
                return
            # J3 只要求四个分区是数组；空数组是允许的，但要在证据里如实列出。
            self._send(200, envelope("OK", "success", {
                "conceptCards": [{"id": "c1", "term": "联合验收"}],
                "termDictionary": [],
                "relationSummaries": [],
                "exampleQa": [],
                "candidateSha256": "a" * 64,
            }))
            return
        if path == "/onto/internal/release-bindings":
            if not self._authorized_read():
                self._send(401, envelope("SERVICE_CREDENTIAL_REQUIRED", "missing or invalid X-API-Key", None))
                return
            query = parse_qs(urlparse(self.path).query)
            app_code = (query.get("appCode") or [""])[0]
            binding = BINDINGS.get(app_code)
            if not binding:
                self._send(404, envelope("BINDING_NOT_FOUND", "binding not created yet", None))
                return
            self._send(200, envelope("OK", "success", {
                "bindingId": binding["bindingId"],
                "releaseId": binding["releaseId"],
            }))
            return
        self._send(404, envelope("NOT_FOUND", "stub route not found", None))

    def do_POST(self):
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if path == "/auth/dev-token":
            self._send(200, envelope("OK", "success", {
                "accessToken": STATE["bearer"],
                "user": {"tenantId": "local-v12", "principalId": "developer-v12", "roles": ["ADMIN", "EDITOR"]},
            }))
            return
        if path == "/admin/skills/import-ontology-manifests":
            if STATE["mode"] == "server-error":
                self._send(500, envelope("INTERNAL_ERROR", "stub server-error", None))
                return
            if not self._authorized_bearer():
                self._send(401, envelope("UNAUTHENTICATED", "missing bearer", None))
                return
            try:
                body = json.loads(raw.decode("utf-8")) if raw else {}
            except ValueError:
                self._send(400, envelope("INVALID_JSON", "body is not json", None))
                return
            manifests = body.get("manifests")
            if not isinstance(manifests, list) or not manifests:
                self._send(400, envelope("INVALID_MANIFEST", "manifests must be a non-empty array", None))
                return
            if body.get("authScheme") != "API_KEY":
                self._send(400, envelope("SKILL_AUTH_SCHEME_CONFLICT", "only API_KEY is accepted", None))
                return
            if body.get("authRef") != STATE["auth_ref"]:
                self._send(400, envelope("INVALID_ARGUMENT", "authRef无效或尚未配置，请选择当前租户已登记的secret_ref", None))
                return
            drafts = []
            for index, manifest in enumerate(manifests, start=1):
                if manifest.get("tool_code") != STATE["op_code"]:
                    self._send(400, envelope("INVALID_MANIFEST", "unexpected tool_code", None))
                    return
                draft = draft_from_manifest(manifest, body["authRef"], index)
                if STATE["mode"] == "bad-draft":
                    draft["status"] = "PUBLISHED"
                drafts.append(draft)
            warnings = [
                "导入仅生成可编辑DRAFT；真实连接测试与模型选型验收通过后才能发布。",
                "DRAFT 技能尚未绑定运行实例，不能作为已验收通过的证据。",
            ]
            self._send(200, envelope("OK", "success", {"drafts": drafts, "warnings": warnings}))
            return
        if path == "/onto/internal/release-bindings":
            # 只读凭据写绑定必须被拒；binder 首次 true、重复 false 且 bindingId 不变。
            if self.headers.get("X-API-Key") == STATE["read_key"]:
                self._send(403, envelope("NO_PERMISSION", "read-only credential cannot bind", None))
                return
            try:
                body = json.loads(raw.decode("utf-8")) if raw else {}
            except ValueError:
                self._send(400, envelope("INVALID_JSON", "body is not json", None))
                return
            app_code = str(body.get("appCode") or "")
            binding = BINDINGS.get(app_code)
            changed = binding is None
            if changed:
                binding = {
                    "bindingId": "c1-stub-binding-" + str(len(BINDINGS) + 1),
                    "releaseId": str(body.get("releaseId") or STATE["release_id"]),
                }
                BINDINGS[app_code] = binding
            self._send(200, envelope("OK", "success", {
                "bindingId": binding["bindingId"],
                "releaseId": binding["releaseId"],
                "changed": changed,
            }))
            return
        self._send(404, envelope("NOT_FOUND", "stub route not found", None))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18282)
    parser.add_argument("--mode", default="normal",
                        choices=["normal", "server-error", "bad-draft", "empty-manifest",
                                 "preview-manifest", "bad-endpoint"])
    parser.add_argument("--space-code", default="joint_stub_space")
    parser.add_argument("--release-id", default="11111111-1111-4111-8111-111111111111")
    parser.add_argument("--op-code", default="joint_stub_op")
    parser.add_argument("--read-key", default="ontosa_" + "R" * 43)
    parser.add_argument("--bearer", default="stub-admin-token")
    parser.add_argument("--auth-ref", default="env:AGENT_SECRET_JOINT_STUB_CONSUMER")
    parser.add_argument("--public-base", default="")
    args = parser.parse_args()
    STATE.update({
        "mode": args.mode,
        "space_code": args.space_code,
        "release_id": args.release_id,
        "op_code": args.op_code,
        "read_key": args.read_key,
        "bearer": args.bearer,
        "auth_ref": args.auth_ref,
        "digest": "a" * 64,
        "public_base": (args.public_base or ("http://127.0.0.1:%d" % args.port)).rstrip("/"),
    })
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("stub-joint-j4 listening on 127.0.0.1:%d mode=%s space=%s release=%s"
          % (args.port, args.mode, args.space_code, args.release_id), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
