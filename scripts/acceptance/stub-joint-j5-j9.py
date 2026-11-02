#!/usr/bin/env python3
"""EX-06 J5–J9 本地协议桩（只证明协议/装配，不连真实服务）。

一个进程同时扮演 AP（/admin/**）与 OR（/onto/**）的 HTTP 形状，端口由 --port 指定。
阶段脚本通过 -PlatformBase / -OntologyBase 指向同一端口即可；J6 的故障面再用
--mode gateway-500 起第二个实例，模拟 AP 把 OR 5xx 收敛成 503 CONTEXT_PACK_UNAVAILABLE。

用法：
    python stub-joint-j5-j9.py --port 18283 --mode normal
    python stub-joint-j5-j9.py --port 18284 --mode gateway-500
    python stub-joint-j5-j9.py --port 18285 --mode server-error
    python stub-joint-j5-j9.py --port 18286 --mode pin-skipped
    python stub-joint-j5-j9.py --port 18287 --mode pin-failed

模式：
    normal       全部通过
    server-error POST /onto/internal/release-bindings 返回 500（J5 判 FAIL / 退出 1）
    gateway-500  POST /admin/apps/{code}/runs 返回 503 CONTEXT_PACK_UNAVAILABLE（J6 故障腿）
    pin-skipped  commit 的 pin 为 SKIPPED（J9 必须判 FAIL）
    pin-failed   commit 直接 502 PIN_FAILED（J9 必须判 FAIL）
    binding-404  GET 绑定恒 404 BINDING_NOT_FOUND

常量（阶段脚本参数必须与之一致）：
    tenant=local-v12  environment=production
    space A=11111111-1111-4111-8111-111111111111  spaceCode=joint_a
    space B=22222222-2222-4222-8222-222222222222  spaceCode=joint_b
    old release=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa
    new release=bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb
    B release=cccccccc-cccc-4ccc-8ccc-cccccccccccc
    app resolve=joint_stub_resolve（config.ontology 只有 space）
    app explicit=joint_stub_explicit（config.ontology.releaseId=old）
    app pin=joint_stub_pin（config.ontology.releaseId=old）
    app j8=joint_stub_j8（A 空间绑定基线）
    bearer=stub-acceptance-bearer
    binder key=ontosa_ + 43 个 b；read key=ontosa_ + 43 个 r
    temp binder key=ontosa_ + 43 个 t；temp consumer key=ontopa_ + 43 个 c
"""

import argparse
import hashlib
import json
import re
import secrets
import threading
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

TENANT = "local-v12"
ENV = "production"
SPACE_A = "11111111-1111-4111-8111-111111111111"
SPACE_A_CODE = "joint_a"
SPACE_B = "22222222-2222-4222-8222-222222222222"
SPACE_B_CODE = "joint_b"
RELEASE_OLD = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
RELEASE_NEW = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
RELEASE_B = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
OP_CODE = "joint_list"
BEARER = "stub-acceptance-bearer"

APP_RESOLVE = "joint_stub_resolve"
APP_EXPLICIT = "joint_stub_explicit"
APP_PIN = "joint_stub_pin"
APP_J8 = "joint_stub_j8"

BINDER_KEY = "ontosa_" + "b" * 43
READ_KEY = "ontosa_" + "r" * 43
TEMP_BINDER_KEY = "ontosa_" + "t" * 43
TEMP_CONSUMER_KEY = "ontopa_" + "c" * 43

BINDER_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
READ_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
TEMP_BINDER_ID = "ffffffff-ffff-4fff-8fff-ffffffffffff"
TEMP_CONSUMER_ID = "99999999-9999-4999-8999-999999999999"
TEMP_BINDER_PRINCIPAL = "88888888-8888-4888-8888-888888888888"
ROLE_BINDER_ID = "77777777-7777-4777-8777-777777777777"
ROLE_MEMBER_ID = "66666666-6666-4666-8666-666666666666"

LOCK = threading.Lock()
STATE = {}


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha(value):
    return hashlib.sha256(str(value).encode("utf-8")).hexdigest()


def default_config(with_release):
    config = {
        "modelBinding": {"modelEntryId": "model_fixture_1"},
        "inputs": {"message": {"type": "string", "required": True}},
        "systemPrompt": "stub",
        "params": {"temperature": 0},
        "skillRefs": [],
        "guard": {"roles": ["MEMBER"], "attributes": []},
        "ontology": {"space": SPACE_A},
    }
    if with_release:
        config["ontology"]["releaseId"] = RELEASE_OLD
    return config


def new_app(code, with_release, status="PUBLISHED", version=1):
    return {
        "code": code,
        "name": code,
        "type": "CHAT",
        "description": "EX-06 J5-J9 protocol stub",
        "domain": None,
        "status": status,
        "id": 1,
        "draftId": 1,
        "draftNo": 1,
        "bizCategory": None,
        "draftRev": 1,
        "currentVersionNo": version,
        "updatedAt": now_iso(),
        "tags": [],
        "config": default_config(with_release),
        "definition": {},
        "layout": {},
        "candidateSha256": sha(code + ":1"),
        "semanticDigest": sha(code + ":sem"),
    }


def reset_state(mode):
    STATE.clear()
    STATE["mode"] = mode
    STATE["requests"] = 0
    STATE["apps"] = {
        APP_RESOLVE: new_app(APP_RESOLVE, False),
        APP_EXPLICIT: new_app(APP_EXPLICIT, True),
        APP_PIN: new_app(APP_PIN, True),
        APP_J8: new_app(APP_J8, True),
    }
    STATE["versions"] = {code: [{"versionNo": 1, "appVersionId": 1, "appCode": code}] for code in STATE["apps"]}
    STATE["bindings"] = {}
    STATE["pins"] = {}
    STATE["evaluations"] = {}
    STATE["commits"] = {}
    STATE["runs"] = {}
    STATE["keys"] = {
        BINDER_KEY: {"id": BINDER_ID, "space": SPACE_A, "tenant": TENANT, "kind": "SERVICE_ACCOUNT", "roles": {"release.bind", "release.read"}, "revoked": False},
        READ_KEY: {"id": READ_ID, "space": SPACE_A, "tenant": TENANT, "kind": "SERVICE_ACCOUNT", "roles": {"release.read"}, "revoked": False},
        TEMP_BINDER_KEY: {"id": TEMP_BINDER_ID, "space": SPACE_A, "tenant": TENANT, "kind": "SERVICE_ACCOUNT", "roles": {"release.bind"}, "revoked": False},
        TEMP_CONSUMER_KEY: {"id": TEMP_CONSUMER_ID, "space": SPACE_A, "tenant": TENANT, "kind": "PLATFORM_ACTOR", "roles": {"service.execute"}, "revoked": False},
    }
    STATE["binder_role_removed"] = False
    STATE["users"] = [{
        "id": TEMP_BINDER_PRINCIPAL,
        "username": "joint_temp_binder",
        "displayName": "joint temp binder",
        "status": "ACTIVE",
        "revision": 1,
        "createdAt": now_iso(),
        "roles": [{"id": ROLE_BINDER_ID, "code": "SERVICE_ACCOUNT_BINDER", "name": "binder"}],
    }]
    STATE["roles"] = [
        {"id": ROLE_BINDER_ID, "code": "SERVICE_ACCOUNT_BINDER", "name": "binder"},
        {"id": ROLE_MEMBER_ID, "code": "MEMBER", "name": "member"},
    ]
    for code, release in ((APP_RESOLVE, RELEASE_OLD), (APP_EXPLICIT, RELEASE_OLD), (APP_PIN, RELEASE_OLD), (APP_J8, RELEASE_OLD)):
        STATE["bindings"][(TENANT, code, ENV)] = {
            "bindingId": str(uuid.uuid4()),
            "releaseId": release,
            "platformTenant": TENANT,
            "appCode": code,
            "environment": ENV,
            "boundAt": now_iso(),
            "status": "ACTIVE",
        }
    for code in (APP_EXPLICIT, APP_PIN):
        STATE["pins"][code] = {
            "appCode": code,
            "versionNo": 1,
            "state": "PINNED",
            "releaseId": RELEASE_OLD,
            "bindingId": STATE["bindings"][(TENANT, code, ENV)]["bindingId"],
            "reason": None,
            "environment": ENV,
            "lastPinnedReleaseId": RELEASE_OLD,
            "lastPinnedBindingId": STATE["bindings"][(TENANT, code, ENV)]["bindingId"],
            "lastPinnedAt": now_iso(),
            "updatedAt": now_iso(),
        }
    STATE["pin_seed"] = {code: dict(row) for code, row in STATE["pins"].items()}


def envelope(data=None, code="OK", message="ok"):
    return {"code": code, "message": message, "data": data}


def error(status, code, message, data=None):
    return status, {"code": code, "message": message, "data": data}


def parse_body(handler):
    length = int(handler.headers.get("Content-Length") or 0)
    if not length:
        return None
    raw = handler.rfile.read(length)
    if not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "JointStub/1.0"

    def log_message(self, fmt, *args):
        pass

    def send_json(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json;charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def dispatch(self, method):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        body = parse_body(self)
        # /__stats 是探针自身，不计入被测请求数，便于证明“缺输入时 0 请求”。
        if path == "/__stats":
            with LOCK:
                self.send_json(200, {"mode": STATE["mode"], "requests": STATE["requests"]})
            return
        with LOCK:
            STATE["requests"] += 1

        try:
            if path.startswith("/admin/"):
                self.handle_ap(method, path, query, body)
            elif path.startswith("/onto/"):
                self.handle_or(method, path, query, body)
            else:
                self.send_json(404, {"code": "NOT_FOUND", "message": "stub: unknown path " + path, "data": None})
        except Exception as exc:  # 桩自身异常也返回 500，便于阶段判 FAIL
            self.send_json(500, {"code": "STUB_INTERNAL_ERROR", "message": repr(exc), "data": None})

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def do_PUT(self):
        self.dispatch("PUT")

    def do_DELETE(self):
        self.dispatch("DELETE")

    # ---------------- AP ----------------
    def bearer_ok(self):
        header = self.headers.get("Authorization") or ""
        return header == "Bearer " + BEARER

    def handle_ap(self, method, path, query, body):
        if not self.bearer_ok():
            self.send_json(*error(401, "UNAUTHORIZED", "stub: 需要 Bearer " + BEARER))
            return
        parts = path.strip("/").split("/")
        # /admin/apps/{code}/...
        if len(parts) >= 3 and parts[0] == "admin" and parts[1] == "apps":
            code = parts[2]
            tail = parts[3:]
            app = STATE["apps"].get(code)
            if app is None:
                self.send_json(*error(404, "APP_NOT_FOUND", "stub: 应用不存在"))
                return
            if not tail and method == "GET":
                self.send_json(200, envelope(dict(app)))
                return
            if tail == ["config"] and method == "PUT":
                self.save_config(code, app, body)
                return
            if tail == ["pin-status"] and method == "GET":
                row = STATE["pins"].get(code)
                self.send_json(200, envelope(dict(row) if row else {}))
                return
            if tail == ["versions"] and method == "GET":
                self.send_json(200, envelope(list(STATE["versions"].get(code, []))))
                return
            if len(tail) == 2 and tail[0] == "versions" and method == "GET":
                number = int(tail[1]) if tail[1].isdigit() else -1
                rows = [v for v in STATE["versions"].get(code, []) if v["versionNo"] == number]
                if not rows:
                    self.send_json(*error(404, "APP_VERSION_NOT_FOUND", "stub: 版本不存在"))
                    return
                self.send_json(200, envelope(dict(rows[0])))
                return
            if tail == ["publish", "precheck"] and method == "POST":
                self.precheck(code, app)
                return
            if tail == ["publish", "commit"] and method == "POST":
                self.commit(code, app, body)
                return
            if tail == ["runs"] and method == "POST":
                self.create_run(code, app, body)
                return
        if len(parts) == 3 and parts[:2] == ["admin", "runs"] and method == "GET":
            row = STATE["runs"].get(parts[2])
            if row is None:
                self.send_json(*error(404, "RUN_NOT_FOUND", "stub: run 不存在"))
                return
            self.send_json(200, envelope(dict(row)))
            return
        if len(parts) == 2 and parts[:2] == ["admin", "evaluations"] and method == "POST":
            self.evaluate(body)
            return
        if len(parts) == 3 and parts[:2] == ["admin", "evaluations"] and method == "GET":
            report = STATE["evaluations"].get(parts[2])
            if report is None:
                self.send_json(*error(404, "EVALUATION_NOT_FOUND", "stub: 评测报告不存在"))
                return
            self.send_json(200, envelope(dict(report)))
            return
        self.send_json(*error(404, "NOT_FOUND", "stub: 未实现的 AP 路径 " + path))

    def save_config(self, code, app, body):
        if not isinstance(body, dict) or "draftRev" not in body or "config" not in body:
            self.send_json(*error(400, "INVALID_REQUEST", "stub: 需要 draftRev/config"))
            return
        if int(body["draftRev"]) != int(app["draftRev"]):
            self.send_json(*error(409, "DRAFT_REV_CONFLICT", "stub: draftRev 冲突"))
            return
        app["config"] = body["config"]
        app["draftRev"] = int(app["draftRev"]) + 1
        app["candidateSha256"] = sha(json.dumps(app["config"], sort_keys=True, ensure_ascii=False) + ":" + str(app["draftRev"]))
        app["status"] = "DRAFT"
        app["updatedAt"] = now_iso()
        self.send_json(200, envelope(dict(app)))

    def evaluate(self, body):
        if not isinstance(body, dict):
            self.send_json(*error(400, "INVALID_REQUEST", "stub: 需要 JSON body"))
            return
        for key in ("appCode", "draftRev", "candidateSha256", "datasetVersion"):
            if key not in body:
                self.send_json(*error(400, "INVALID_REQUEST", "stub: 缺少 " + key))
                return
        report_id = "er_" + str(uuid.uuid4())
        report = {
            "id": report_id,
            "appCode": body["appCode"],
            "draftRev": int(body["draftRev"]),
            "candidateSha256": body["candidateSha256"],
            "datasetVersion": body["datasetVersion"],
            "status": "ACCEPTED",
            "accepted": True,
            "threshold": 0.85,
            "results": [],
            "createdAt": now_iso(),
            "completedAt": now_iso(),
        }
        STATE["evaluations"][report_id] = report
        self.send_json(200, envelope(dict(report)))

    def precheck(self, code, app):
        report_id = ""
        for report in STATE["evaluations"].values():
            if report["appCode"] == code and int(report["draftRev"]) == int(app["draftRev"]) and report["candidateSha256"] == app["candidateSha256"]:
                report_id = report["id"]
        if not report_id:
            self.send_json(*error(409, "EVAL_GATE_BLOCKED", "stub: 没有匹配当前候选的 ACCEPTED 评测"))
            return
        data = {
            "precheckId": "pc_" + str(uuid.uuid4()),
            "draftRev": int(app["draftRev"]),
            "candidateSha256": app["candidateSha256"],
            "evalReportId": report_id,
            "passed": True,
            "result": "PASS",
            "gateMode": "BLOCKING",
            "waiverId": None,
            "publicationTarget": "NON_PRODUCTION",
            "evaluationEnvironment": "DEMO",
            "environmentConfirmationRequired": False,
            "environmentEvidenceSha256": sha("env:" + report_id),
            "report": {"checks": [], "warnings": []},
        }
        app["precheck"] = data
        self.send_json(200, envelope(data))

    def commit(self, code, app, body):
        if not isinstance(body, dict) or "idempotencyRequestNo" not in body:
            self.send_json(*error(400, "INVALID_REQUEST", "stub: 需要 idempotencyRequestNo"))
            return
        request_no = str(body["idempotencyRequestNo"])
        mode = STATE["mode"]
        if request_no in STATE["commits"]:
            number = STATE["commits"][request_no]
            pin = dict(STATE["pins"].get(code) or {})
            if pin:
                pin["changed"] = False
            self.send_json(200, envelope({
                "versionNo": number,
                "sha256": app["candidateSha256"],
                "appCode": code,
                "artifacts": {},
                "pin": pin,
            }))
            return
        precheck = app.get("precheck") or {}
        if body.get("precheckId") != precheck.get("precheckId"):
            self.send_json(*error(409, "PRECHECK_REQUIRED", "stub: precheckId 不匹配"))
            return
        if mode == "pin-failed":
            self.send_json(*error(502, "PIN_FAILED", "stub: pin fail-closed"))
            return
        number = int(app["currentVersionNo"]) + 1
        app["currentVersionNo"] = number
        app["status"] = "PUBLISHED"
        STATE["versions"].setdefault(code, []).append({"versionNo": number, "appVersionId": number, "appCode": code})
        ontology = (app["config"] or {}).get("ontology") or {}
        release = ontology.get("releaseId")
        binding = STATE["bindings"].get((TENANT, code, ENV))
        if mode == "pin-skipped" or not release:
            pin = {"state": "SKIPPED", "reason": "stub: config.ontology 缺少 space/releaseId"}
        elif binding is None or binding["releaseId"] != release:
            binding = {
                "bindingId": str(uuid.uuid4()),
                "releaseId": release,
                "platformTenant": TENANT,
                "appCode": code,
                "environment": ENV,
                "boundAt": now_iso(),
                "status": "ACTIVE",
            }
            STATE["bindings"][(TENANT, code, ENV)] = binding
            pin = {"state": "PINNED", "bindingId": binding["bindingId"], "changed": True}
        else:
            pin = {"state": "PINNED", "bindingId": binding["bindingId"], "changed": False}
        STATE["commits"][request_no] = number
        if pin.get("state") == "PINNED":
            previous = STATE["pins"].get(code, {})
            STATE["pins"][code] = {
                "appCode": code,
                "versionNo": number,
                "state": "PINNED",
                "releaseId": release,
                "bindingId": pin.get("bindingId"),
                "reason": None,
                "environment": ENV,
                "lastPinnedReleaseId": previous.get("releaseId"),
                "lastPinnedBindingId": previous.get("bindingId"),
                "lastPinnedAt": now_iso(),
                "updatedAt": now_iso(),
            }
        else:
            STATE["pins"][code] = {
                "appCode": code,
                "versionNo": number,
                "state": pin["state"],
                "releaseId": release,
                "bindingId": pin.get("bindingId"),
                "reason": pin.get("reason"),
                "environment": ENV,
                "updatedAt": now_iso(),
            }
        self.send_json(200, envelope({
            "versionNo": number,
            "sha256": app["candidateSha256"],
            "appCode": code,
            "artifacts": {},
            "pin": pin,
        }))

    def create_run(self, code, app, body):
        if STATE["mode"] == "gateway-500":
            self.send_json(*error(503, "CONTEXT_PACK_UNAVAILABLE", "stub: 注入 OR 5xx → 503"))
            return
        run_id = "r_" + secrets.token_hex(16)
        config = app.get("config") or {}
        ontology = config.get("ontology") or {}
        space = ontology.get("space")
        release = ontology.get("releaseId")
        if not release and space:
            binding = STATE["bindings"].get((TENANT, code, ENV))
            release = binding["releaseId"] if binding else None
        STATE["runs"][run_id] = {
            "runId": run_id,
            "appCode": code,
            "status": "SUCCEEDED",
            "inputs": body.get("inputs") if isinstance(body, dict) else None,
            "output": {"text": "stub-ok"},
            "observation": {
                "traceId": "trace_" + secrets.token_hex(8),
                "releaseId": release,
                "spaceId": space,
                "capabilityPackages": [],
            },
            "eventsWatermark": 1,
        }
        self.send_json(200, envelope({"runId": run_id, "status": "SUCCEEDED"}))

    # ---------------- OR ----------------
    def service_key(self):
        key = self.headers.get("X-API-Key") or ""
        return STATE["keys"].get(key), key

    def handle_or(self, method, path, query, body):
        parts = path.strip("/").split("/")
        tail = parts[1:]
        if not tail:
            self.send_json(*error(404, "NOT_FOUND", "stub: 空 OR 路径"))
            return
        # /onto/internal/release-bindings
        if tail[:2] == ["internal", "release-bindings"]:
            if len(tail) == 2 and method == "POST":
                self.bind(body)
                return
            if len(tail) == 2 and method == "GET":
                self.current_binding(query)
                return
            if len(tail) == 3 and method == "DELETE":
                self.unbind(tail[2])
                return
        if tail[:2] == ["internal", "releases"] and len(tail) == 5 and tail[3] == "ops" and method == "POST":
            self.internal_ops(tail[2], tail[4])
            return
        if len(tail) == 6 and tail[0] == "r" and tail[2] == "releases" and tail[4] == "ops" and method == "POST":
            self.consumer_ops(tail[1], tail[3])
            return
        if tail[:1] == ["releases"] and len(tail) == 3 and tail[2] == "bindings" and method == "GET":
            self.release_bindings(tail[1])
            return
        if tail[:1] == ["releases"] and len(tail) == 3 and tail[2] == "manifest" and method == "GET":
            self.manifest(tail[1])
            return
        if tail[:1] == ["platform-actors"] and len(tail) == 3 and method == "POST":
            self.platform_actor(tail[1], tail[2])
            return
        if tail[:1] == ["system"] and len(tail) >= 2:
            self.system(method, tail, body)
            return
        self.send_json(*error(404, "NOT_FOUND", "stub: 未实现的 OR 路径 " + path))

    def require_service(self, need_bind=False):
        credential, _ = self.service_key()
        if credential is None:
            self.send_json(*error(401, "UNAUTHORIZED", "stub: 未知或已撤的 key"))
            return None
        if credential["revoked"]:
            self.send_json(*error(401, "UNAUTHORIZED", "stub: 凭据已撤"))
            return None
        if need_bind and "release.bind" not in credential["roles"]:
            self.send_json(*error(403, "NO_PERMISSION", "stub: 缺 release.bind"))
            return None
        return credential

    def bind(self, body):
        credential = self.require_service(need_bind=True)
        if credential is None:
            return
        if STATE["mode"] == "server-error":
            self.send_json(*error(500, "INTERNAL_ERROR", "stub: 注入 500"))
            return
        if not isinstance(body, dict):
            self.send_json(*error(400, "INVALID_REQUEST", "stub: 需要 JSON body"))
            return
        target = body.get("spaceId") or body.get("spaceCode")
        if target == SPACE_A_CODE:
            target = SPACE_A
        if target != credential["space"]:
            self.send_json(*error(403, "NO_PERMISSION", "stub: 凭据不属于该空间"))
            return
        if body.get("platformTenant") != credential["tenant"]:
            self.send_json(*error(403, "NO_PERMISSION", "stub: 凭据不属于该租户"))
            return
        release = body.get("releaseId")
        if release not in (RELEASE_OLD, RELEASE_NEW):
            self.send_json(*error(403, "NO_PERMISSION", "stub: 版本不属于该空间"))
            return
        app_code = str(body.get("appCode") or "")
        environment = str(body.get("environment") or ENV)
        key = (credential["tenant"], app_code, environment)
        existing = STATE["bindings"].get(key)
        if existing and existing["releaseId"] == release:
            self.send_json(200, envelope({
                "bindingId": existing["bindingId"],
                "releaseId": release,
                "changed": False,
                "platformTenant": credential["tenant"],
                "appCode": app_code,
                "environment": environment,
            }))
            return
        binding = {
            "bindingId": existing["bindingId"] if existing else str(uuid.uuid4()),
            "releaseId": release,
            "platformTenant": credential["tenant"],
            "appCode": app_code,
            "environment": environment,
            "boundAt": now_iso(),
            "status": "ACTIVE",
        }
        STATE["bindings"][key] = binding
        self.send_json(200, envelope({
            "bindingId": binding["bindingId"],
            "releaseId": release,
            "changed": True,
            "platformTenant": credential["tenant"],
            "appCode": app_code,
            "environment": environment,
        }))

    def current_binding(self, query):
        credential = self.require_service()
        if credential is None:
            return
        tenant = (query.get("platformTenant") or [""])[0]
        app_code = (query.get("appCode") or [""])[0]
        environment = (query.get("environment") or [""])[0]
        if tenant != credential["tenant"]:
            self.send_json(*error(403, "NO_PERMISSION", "stub: 凭据不属于该租户"))
            return
        if STATE["mode"] == "binding-404":
            self.send_json(*error(404, "BINDING_NOT_FOUND", "stub: 注入无绑定"))
            return
        binding = STATE["bindings"].get((tenant, app_code, environment))
        if binding is None:
            self.send_json(*error(404, "BINDING_NOT_FOUND", "stub: 该应用在当前环境没有生效的版本绑定"))
            return
        self.send_json(200, envelope(dict(binding)))

    def unbind(self, binding_id):
        credential = self.require_service(need_bind=True)
        if credential is None:
            return
        for key, binding in list(STATE["bindings"].items()):
            if binding["bindingId"] == binding_id:
                if key[0] != credential["tenant"]:
                    self.send_json(*error(403, "NO_PERMISSION", "stub: 凭据不属于该租户"))
                    return
                del STATE["bindings"][key]
                self.send_json(200, envelope({"bindingId": binding_id, "releaseId": binding["releaseId"], "status": "UNBOUND"}))
                return
        self.send_json(*error(404, "BINDING_NOT_FOUND", "stub: 绑定不存在"))

    def internal_ops(self, release, op):
        credential = self.require_service()
        if credential is None:
            return
        if release not in (RELEASE_OLD, RELEASE_NEW):
            self.send_json(*error(403, "NO_PERMISSION", "stub: 版本不属于该空间"))
            return
        self.send_json(200, envelope({"releaseId": release, "opCode": op, "rows": []}))

    def consumer_ops(self, space_code, release):
        key = self.headers.get("X-API-Key") or ""
        credential = STATE["keys"].get(key)
        if credential is None or credential["revoked"]:
            self.send_json(*error(401, "UNAUTHORIZED", "stub: 消费凭据已撤或未知"))
            return
        if space_code != SPACE_A_CODE and space_code != SPACE_A:
            self.send_json(*error(403, "NO_PERMISSION", "stub: 空间不匹配"))
            return
        self.send_json(200, envelope({"releaseId": release, "rows": []}))

    def release_bindings(self, release):
        if not self.bearer_ok():
            self.send_json(*error(401, "UNAUTHORIZED", "stub: 需要人工 Bearer"))
            return
        rows = [dict(b) for b in STATE["bindings"].values() if b["releaseId"] == release]
        self.send_json(200, envelope(rows))

    def manifest(self, release):
        credential = self.require_service()
        if credential is None:
            return
        if release not in (RELEASE_OLD, RELEASE_NEW):
            self.send_json(*error(404, "RELEASE_NOT_FOUND", "stub: release 不存在"))
            return
        self.send_json(200, envelope({"releaseId": release, "spaceId": credential["space"], "manifestSha256": sha(release)}))

    def platform_actor(self, credential_id, action):
        if not self.bearer_ok():
            self.send_json(*error(401, "UNAUTHORIZED", "stub: 需要人工 Bearer"))
            return
        entry = None
        for key, item in STATE["keys"].items():
            if item["id"] == credential_id:
                entry = (key, item)
                break
        if entry is None:
            self.send_json(*error(404, "CREDENTIAL_NOT_FOUND", "stub: 凭据不存在"))
            return
        old_key, item = entry
        if item["revoked"]:
            self.send_json(*error(409, "CREDENTIAL_REVOKED", "stub: 凭据已撤"))
            return
        if action == "rotate":
            new_key = ("ontopa_" if item["kind"] == "PLATFORM_ACTOR" else "ontosa_") + "".join(secrets.choice("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-") for _ in range(43))
            STATE["keys"][new_key] = dict(item)
            del STATE["keys"][old_key]
            self.send_json(200, envelope({"credential": {"id": item["id"], "status": "ACTIVE", "credentialKind": item["kind"]}, "key": new_key}))
            return
        if action == "revoke":
            item["revoked"] = True
            self.send_json(200, envelope({"id": item["id"], "status": "REVOKED"}))
            return
        self.send_json(*error(404, "NOT_FOUND", "stub: 未实现的 platform-actors 动作"))

    def system(self, method, tail, body):
        if not self.bearer_ok():
            self.send_json(*error(401, "UNAUTHORIZED", "stub: 需要人工 Bearer"))
            return
        if tail == ["system", "users"] and method == "GET":
            self.send_json(200, envelope([dict(u) for u in STATE["users"]]))
            return
        if tail == ["system", "roles"] and method == "GET":
            self.send_json(200, envelope([dict(r) for r in STATE["roles"]]))
            return
        if len(tail) == 3 and tail[:2] == ["system", "users"] and method == "PUT":
            user = next((u for u in STATE["users"] if u["id"] == tail[2]), None)
            if user is None:
                self.send_json(*error(404, "USER_NOT_FOUND", "stub: 用户不存在"))
                return
            if isinstance(body, dict) and "roleIds" in body:
                role_ids = set(body["roleIds"] or [])
                STATE["binder_role_removed"] = ROLE_BINDER_ID not in role_ids
                if STATE["binder_role_removed"]:
                    for key, item in STATE["keys"].items():
                        if item["id"] == TEMP_BINDER_ID:
                            item["roles"] = set()
                user["revision"] = int(user.get("revision", 1)) + 1
                user["roles"] = [dict(r) for r in STATE["roles"] if r["id"] in role_ids]
            self.send_json(200, envelope(dict(user)))
            return
        self.send_json(*error(404, "NOT_FOUND", "stub: 未实现的 system 路径"))


def main():
    parser = argparse.ArgumentParser(description="EX-06 J5-J9 本地协议桩")
    parser.add_argument("--port", type=int, default=18283)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--mode", default="normal",
                        choices=["normal", "server-error", "gateway-500", "pin-skipped", "pin-failed", "binding-404"])
    args = parser.parse_args()
    reset_state(args.mode)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print("STUB-J5-J9 listening http://%s:%d mode=%s" % (args.host, args.port, args.mode), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
