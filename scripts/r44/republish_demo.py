"""Repair and publish the V901 satellite demo through the platform's normal IAM APIs."""
import argparse
import json
import os
import sys
import uuid
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

APP = "/admin/apps/satellite-planning-assistant"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def api(origin, token):
    parts = urlsplit(origin)
    if (not parts.hostname or parts.username or parts.password or parts.query or parts.fragment
            or parts.path not in ("", "/") or not (parts.scheme == "https" or
            parts.scheme == "http" and parts.hostname in ("127.0.0.1", "localhost", "::1"))):
        raise ValueError("网关须使用 HTTPS 或本机回环地址")
    opener = build_opener(NoRedirect())

    def call(method, suffix="", body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = Request(origin.rstrip("/") + APP + suffix, data=data, method=method,
                          headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with opener.open(request, timeout=45) as response:
                envelope = json.load(response)
        except HTTPError as exc:
            try:
                code = json.loads(exc.read(8192)).get("code", "HTTP_ERROR")
            except (ValueError, UnicodeError):
                code = "HTTP_ERROR"
            raise RuntimeError(f"{method} {suffix} HTTP {exc.code} {code}") from None
        if envelope.get("code") != "OK":
            raise RuntimeError(str(envelope.get("code", "INVALID_RESPONSE")))
        return envelope["data"]
    return call


def prepare(call, model_id):
    if model_id < 1:
        raise ValueError("需要当前租户中已经启用的模型条目 ID")
    draft = call("GET")
    if draft["type"] != "CHAT":
        raise ValueError("种子应用类型与 CHAT 修复脚本不匹配")
    config = dict(draft["config"])
    binding = dict(config.get("modelBinding", {}))
    if binding.get("modelEntryId") not in (None, model_id):
        raise ValueError("应用已有其他模型绑定，请通过正常管理流程调整")
    config["modelBinding"] = {"modelEntryId": model_id}
    if "maxTurns" in config:
        context = dict(config.get("context", {}))
        context.setdefault("maxTurns", config.pop("maxTurns"))
        config["context"] = context
    if config != draft["config"]:
        draft = call("PUT", "/config", {"draftRev": draft["draftRev"], "config": config})
    rev = draft["draftRev"]
    receipt = call("POST", "/model-bindings/refreeze", {"draftRev": rev, "requestId": f"r44-{rev}-{model_id}"})
    if not receipt.get("modelBindingRefs"):
        raise RuntimeError("重冻结未返回绑定回执")
    return receipt


def publish(call, revision, confirm_demo=False):
    check = call("POST", "/publish/precheck")
    if check["draftRev"] != revision:
        raise ValueError("待发布修订与指定修订不一致")
    if not check["passed"]:
        raise RuntimeError(f"发布预检未通过，请查看预检记录 {check['precheckId']}")
    key = str(uuid.uuid5(uuid.NAMESPACE_URL, f"r44/{revision}/{check['candidateSha256']}"))
    body = {k: check[k] for k in ("precheckId", "draftRev", "candidateSha256")}
    body["idempotencyRequestNo"] = key
    if check.get("environmentConfirmationRequired"):
        if not confirm_demo:
            raise ValueError("预检要求确认 DEMO 评测证据，请核对后使用 --confirm-demo")
        body["evaluationEnvironmentConfirmation"] = {
            "confirmed": True, "precheckId": check["precheckId"], "evalReportId": check["evalReportId"],
            "evidenceSha256": check["environmentEvidenceSha256"]}
    return call("POST", "/publish/commit", body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare").add_argument("--model-entry-id", type=int, required=True)
    commit = commands.add_parser("publish")
    commit.add_argument("--draft-rev", type=int, required=True)
    commit.add_argument("--confirm-demo", action="store_true")
    args = parser.parse_args()
    token = os.environ.get("AGENT_PORTAL_TOKEN")
    if not token:
        parser.error("请通过 AGENT_PORTAL_TOKEN 提供当前租户管理员门户票据")
    call = api(args.gateway, token)
    result = (prepare(call, args.model_entry_id) if args.command == "prepare"
              else publish(call, args.draft_rev, args.confirm_demo))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, RuntimeError, OSError) as error:
        sys.exit(str(error))
