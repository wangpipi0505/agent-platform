#!/usr/bin/env python3
"""FT-MDL-05 计费对账：用 call_log 里记录的 request_snapshot.messages 原样直调
DeepSeek API（同模型同名、stream=false），取其 usage 与平台记录三元组比对。
密钥从 /run/prb1-ds.key 读取（root 600），绝不打印/入库/进日志。
用法：python3 prb1-replay-usage.py <run_public_id> <outfile>
"""
import json
import ssl
import subprocess
import sys
import urllib.request

RUN = sys.argv[1]
OUT = sys.argv[2]

row = subprocess.check_output(
    ["docker", "exec", "agent-platform-pg", "psql", "-U", "agentplat", "-d", "r24_check",
     "-At", "-c",
     "select row_to_json(t) from (select request_snapshot, prompt_tokens, completion_tokens,"
     " total_tokens, estimated_tokens, metering_snapshot from model.call_log"
     " where run_id='%s' and call_kind='FORMAL') t" % RUN]).decode("utf-8", "replace").strip()
rec = json.loads(row)
messages = rec["request_snapshot"]["messages"]

key = open("/run/prb1-ds.key").read().strip()
body = json.dumps({"model": "deepseek-flash", "messages": messages, "stream": False}).encode("utf-8")
req = urllib.request.Request(
    "https://api.deepseek.com/chat/completions", data=body,
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
ctx = ssl.create_default_context()
resp = json.loads(urllib.request.urlopen(req, timeout=180, context=ctx).read().decode("utf-8"))
usage = resp.get("usage") or {}

platform = {"prompt_tokens": rec["prompt_tokens"], "completion_tokens": rec["completion_tokens"],
            "total_tokens": rec["total_tokens"], "estimated_tokens": rec["estimated_tokens"]}
compare = {
    "platform_recorded": platform,
    "deepseek_direct_usage": usage,
    "prompt_tokens_match": usage.get("prompt_tokens") == rec["prompt_tokens"],
    "total_tokens_within_reason": abs((usage.get("total_tokens") or 0) - rec["total_tokens"]) <= max(8, (rec["completion_tokens"] or 0) // 20),
}
out = {"runId": RUN, "messages_count": len(messages), "stream_recorded": rec["request_snapshot"].get("stream"),
       "metering": rec["metering_snapshot"], "comparison": compare}
json.dump(out, open(OUT, "w"), ensure_ascii=False, indent=2)
print(json.dumps(compare, ensure_ascii=False, indent=2))
