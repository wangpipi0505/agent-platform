# PR-B1 构建→发布→内部执行（E1 形态，demo HS256 票；python3.6 兼容）
import json, sys, time, urllib.request

GW = "http://127.0.0.1:18980"
APP = "satellite-planning-assistant"
TICKET = open("/tmp/tk-demo.txt").read().strip()
PASS = 0
FAIL = 0

def call(method, path, body=None):
    req = urllib.request.Request(GW + path, method=method)
    req.add_header("Authorization", "Bearer " + TICKET)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())

def ck(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("PASS", name)
    else:
        FAIL += 1
        print("FAIL", name, detail[:200])

# 1) draft 读 + config 补 modelEntryId=1
st, d = call("GET", "/admin/flows/" + APP)
ck("draft 读", st == 200 and d.get("code") == "OK", json.dumps(d)[:150])
rev = d["data"]["draftRev"]
cfg = d["data"]["config"]
cfg.setdefault("modelBinding", {})["modelEntryId"] = 1
st, saved = call("PUT", "/admin/apps/%s/config" % APP, {"draftRev": rev, "config": cfg})
ck("config 保存 modelEntryId=1", st == 200, json.dumps(saved)[:150])

# 2) precheck
st, pre = call("POST", "/admin/apps/%s/publish/precheck" % APP)
data = pre.get("data") or {}
ck("precheck passed", data.get("passed") is True, json.dumps(pre)[:300])
pcid = data.get("precheckId", "")
csha = data.get("candidateSha256", "")
prev = data.get("draftRev", "")
confirm = data.get("environmentConfirmationRequired", False)
evid = data.get("evalReportId", "")
esha = data.get("environmentEvidenceSha256", "")
print("precheckId=%s candidate=%s rev=%s confirm=%s" % (pcid, csha[:12], prev, confirm))

# 3) commit（非生产确认三元组按需）
body = {"idempotencyRequestNo": "prb1-%d" % int(time.time()), "precheckId": pcid,
        "draftRev": prev, "candidateSha256": csha}
if confirm:
    body["evaluationEnvironmentConfirmation"] = {"confirmed": True, "precheckId": pcid,
                                                 "evalReportId": evid, "evidenceSha256": esha}
st, com = call("POST", "/admin/apps/%s/publish/commit" % APP, body)
ck("commit 发布", st == 200, json.dumps(com)[:300])
vn = (com.get("data") or {}).get("versionNo", 0)
print("versionNo=%s" % vn)

# 4) 新激活快照断言
st, ver = call("GET", "/admin/apps/%s/versions/%s" % (APP, vn))
snap = ver.get("data") or {}
deps = snap.get("dependencyVersions") or {}
refs = deps.get("modelRefs") or []
ck("快照 modelRefs 非空", len(refs) > 0, "refs=%s" % len(refs))
print("modelRefs=%s" % json.dumps(refs)[:200])

# 5) CHAT 出话
st, s = call("POST", "/v1/apps/%s/sessions" % APP, {})
sid = (s.get("data") or {}).get("sessionId", "")
ck("会话创建", st == 200 and bool(sid), json.dumps(s)[:150])
st, tr = call("POST", "/v1/apps/%s/sessions/%s/turns" % (APP, sid),
              {"requestId": "prb1-turn-%d" % int(time.time()), "inputs": {"message": "PR-B1 发布链验证：请用一句话确认。"}})
ck("轮次受理", st == 200, json.dumps(tr)[:150])
print("会话=%s（轮询 run 终态…）" % sid)
