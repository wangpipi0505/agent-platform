"""EX-07 J3 自测桩：最小 context-pack 端点（仅本机、仅测试）。

为什么需要它：J3 的通过条件是"真实 release 的包能取到、四段结构正确、按原始字节复算的摘要可核对"。
在本机没有 ONTO 运行栈时，用一个只实现 `GET /onto/releases/{uuid}/context-pack` 的桩，
可以证明脚本的判定逻辑（PASS / FAIL / 401 / 空段）本身成立；它**不冒充**真实 ONTO。

用法：
    python scripts/acceptance/stub-context-pack.py --port 18199 --mode normal
    --mode normal   四段都有内容，candidateSha256 是合法 64hex
    --mode empty    四段都是空数组（用于验证"空段如实列出、不算内容正确"）
    --mode badsha   candidateSha256 不是 64hex（用于验证脚本会 FAIL）
鉴权：要求请求头 `X-API-Key: good-key`，否则 401（与服务账号面的行为一致）。
"""

import argparse
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

RELEASE_ID = "7dc4a1e2-5b3c-4f8d-9a11-2c3d4e5f6a7b"
CANDIDATE_SHA = "3f1a9c0d5e7b2468ace013579bdf24680ace13579bdf24680ace13579bdf2468"
GOOD_KEY = "good-key"

PACK_NORMAL = {
    "candidateId": "0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d",
    "candidateSha256": CANDIDATE_SHA,
    "conceptCards": [
        {"code": "CAMPAIGN", "name": "营销活动", "definition": "营销侧对一次投放的组织单元", "attributes": ["id", "name"]},
        {"code": "SEGMENT", "name": "人群包", "definition": "按规则圈选出的用户集合", "attributes": ["id", "rule"]},
    ],
    "termDictionary": [
        {"term": "活动", "aliases": ["campaign"], "definition": "同 conceptCards.CAMPAIGN", "conceptRef": "CAMPAIGN", "sourceRef": "release"}
    ],
    "relationSummaries": [
        {"from": "CAMPAIGN", "to": "SEGMENT", "type": "TARGETS", "cardinality": "N:N"}
    ],
    "exampleQa": [
        {"question": "上个月投放了哪些活动？", "answer": "按 campaign.start_at 过滤上个月并列出 campaign.name"}
    ],
}

PACK_EMPTY = {
    "candidateId": "0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d",
    "candidateSha256": CANDIDATE_SHA,
    "conceptCards": [],
    "termDictionary": [],
    "relationSummaries": [],
    "exampleQa": [],
}

PACK_BADSHA = dict(PACK_NORMAL)
PACK_BADSHA["candidateSha256"] = "NOT-A-SHA"


class Handler(BaseHTTPRequestHandler):
    mode = "normal"

    def log_message(self, fmt, *args):  # 静音，避免污染测试输出
        return

    def _send(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json;charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        expected = f"/onto/releases/{RELEASE_ID}/context-pack"
        if path != expected:
            self._send(404, {"code": "NOT_FOUND", "message": "路径不存在", "data": None})
            return
        if self.headers.get("X-API-Key") != GOOD_KEY:
            self._send(401, {"code": "SERVICE_CREDENTIAL_REQUIRED", "message": "需要服务账号凭据", "data": None})
            return
        pack = {"empty": PACK_EMPTY, "badsha": PACK_BADSHA}.get(self.mode, PACK_NORMAL)
        self._send(200, {"code": "OK", "message": "success", "data": pack})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18199)
    parser.add_argument("--mode", default="normal", choices=["normal", "empty", "badsha"])
    args = parser.parse_args()
    Handler.mode = args.mode
    server = HTTPServer(("127.0.0.1", args.port), Handler)
    print(f"stub-context-pack listening on 127.0.0.1:{args.port} mode={args.mode}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
