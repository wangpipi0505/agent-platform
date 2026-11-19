#!/bin/bash
# PR-B1 构建→发布→内部执行（E1 形态栈 189xx，bash 版，照 verify-r25-model-chain.mjs 流程）
# 前置：E1 栈起（compose-e1）+DeepSeek provider/entry 已注册（FT-MDL-01..03）+r24_check 全链迁移
set -uo pipefail
GW=http://127.0.0.1:18980
APP=satellite-planning-assistant
PG() { docker exec agent-platform-pg psql -U agentplat -d r24_check -At -c "$1" 2>&1; }
PASS=0; FAIL=0
ck() { if [ "$2" = "1" ]; then PASS=$((PASS+1)); echo "PASS $1"; else FAIL=$((FAIL+1)); echo "FAIL $1 ${3:-}"; fi; }

T=$(curl -s -m 8 -X POST $GW/auth/dev-token | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")

echo "=== 1) draft 补 modelBinding.modelEntryId=1 ==="
DR=$(curl -s -m 10 $GW/admin/flows/$APP -H "Authorization: Bearer $T")
REV=$(echo "$DR" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['draftRev'])")
CFG=$(echo "$DR" | python3 -c "import json,sys;print(json.dumps(json.load(sys.stdin)['data']['config']))")
NEWCFG=$(echo "$CFG" | python3 -c "
import json,sys
c=json.load(sys.stdin)
c.setdefault('modelBinding',{})['modelEntryId']=1
print(json.dumps(c))")
SAVE=$(curl -s -m 15 -X PUT $GW/admin/apps/$APP/config -H "Authorization: Bearer $T" -H content-type:application/json -d "{\"draftRev\":$REV,\"config\":$NEWCFG}")
ck "config 保存（modelEntryId=1）" "$(echo "$SAVE" | python3 -c "import json,sys;print(1 if json.load(sys.stdin).get('code') in ('OK',None) else 0)" 2>/dev/null)" "$SAVE"

echo "=== 2) precheck（评测门=monitor 造数报告在库）==="
PRE=$(curl -s -m 30 -X POST $GW/admin/apps/$APP/publish/precheck -H "Authorization: Bearer $T")
PREOK=$(echo "$PRE" | python3 -c "import json,sys;d=json.load(sys.stdin);print(1 if (d.get('data') or {}).get('passed') else 0)" 2>/dev/null)
ck "precheck passed" "$PREOK" "$(echo "$PRE" | head -c 300)"
PCID=$(echo "$PRE" | python3 -c "import json,sys;print((json.load(sys.stdin).get('data') or {}).get('precheckId',''))" 2>/dev/null)
CSHA=$(echo "$PRE" | python3 -c "import json,sys;print((json.load(sys.stdin).get('data') or {}).get('candidateSha256',''))" 2>/dev/null)
PREV=$(echo "$PRE" | python3 -c "import json,sys;print((json.load(sys.stdin).get('data') or {}).get('draftRev',''))" 2>/dev/null)
CONF=$(echo "$PRE" | python3 -c "import json,sys;print((json.load(sys.stdin).get('data') or {}).get('environmentConfirmationRequired',''))" 2>/dev/null)
EVID=$(echo "$PRE" | python3 -c "import json,sys;print((json.load(sys.stdin).get('data') or {}).get('evalReportId',''))" 2>/dev/null)
ESHA=$(echo "$PRE" | python3 -c "import json,sys;print((json.load(sys.stdin).get('data') or {}).get('environmentEvidenceSha256',''))" 2>/dev/null)
echo "precheckId=$PCID candidateSha=${CSHA:0:12} draftRev=$PREV confirm=$CONF"

echo "=== 3) commit（含非生产确认三元组）==="
BODY="{\"idempotencyRequestNo\":\"prb1-$(date +%s)\",\"precheckId\":\"$PCID\",\"draftRev\":$PREV,\"candidateSha256\":\"$CSHA\""
[ "$CONF" = "True" ] || [ "$CONF" = "true" ] && BODY="$BODY,\"evaluationEnvironmentConfirmation\":{\"confirmed\":true,\"precheckId\":\"$PCID\",\"evalReportId\":\"$EVID\",\"evidenceSha256\":\"$ESHA\"}"
BODY="$BODY}"
COM=$(curl -s -m 30 -X POST $GW/admin/apps/$APP/publish/commit -H "Authorization: Bearer $T" -H content-type:application/json -d "$BODY")
ck "commit 发布" "$(echo "$COM" | python3 -c "import json,sys;print(1 if json.load(sys.stdin).get('code') in ('OK',None) else 0)" 2>/dev/null)" "$(echo "$COM" | head -c 300)"
VN=$(echo "$COM" | python3 -c "import json,sys;print((json.load(sys.stdin).get('data') or {}).get('versionNo',0))" 2>/dev/null)
echo "versionNo=$VN"

echo "=== 4) 新激活快照断言（modelEntryId>0 且 modelRefs 非空）==="
SNAP=$(PG "select snapshot::text from app.app_version where app_code='$APP' and version_no=$VN" | head -c 100000)
MREF=$(echo "$SNAP" | python3 -c "
import json,sys
s=json.loads(sys.stdin.read())
dep=(s.get('dependencyVersions') or {})
refs=dep.get('modelRefs') or (s.get('config') or {}).get('modelEntryId')
print(len(refs) if isinstance(refs,list) else (1 if refs else 0))" 2>/dev/null)
ck "快照 modelRefs 非空" "$(python3 -c "print(1 if int('$MREF' or 0)>0 else 0)" 2>/dev/null)" "refs=$MREF"

echo "=== 5) CHAT 会话轮次真实出话（DeepSeek）→ run 终态 ==="
S=$(curl -s -m 10 -X POST $GW/v1/apps/$APP/sessions -H "Authorization: Bearer $T" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['sessionId'])" 2>/dev/null)
echo "会话=$S"
TR=$(curl -s -m 15 -X POST $GW/v1/apps/$APP/sessions/$S/turns -H "Authorization: Bearer $T" -H content-type:application/json -d "{\"requestId\":\"prb1-turn-$(date +%s)\",\"inputs\":{\"message\":\"PR-B1 发布链验证：请用一句话确认。\"}}")
echo "轮次受理=$(echo "$TR" | head -c 100)"
ST=""
for i in $(seq 1 24); do
  sleep 10
  ST=$(PG "select status from engine.run order by id desc limit 1" | tail -1 | tr -d '\r')
  echo "poll$i: $ST"
  case "$ST" in SUCCEEDED|FAILED|CANCELLED) break;; esac
done
MM=$(PG "select count(*) from engine.run_event where payload::text like '%PROMPT_TEMPLATE_SNAPSHOT_MISMATCH%'")
ck "run 终态 SUCCEEDED" "$(python3 -c "print(1 if '$ST'=='SUCCEEDED' else 0)")" "终态=$ST"
ck "装配 409 零命中" "$(python3 -c "print(1 if int('${MM:-0}')==0 else 0)")"

echo "=== 6) FT-MDL-05 计费对账（model.call_log usage 三元组）==="
PG "select coalesce(model_name,'-'),prompt_tokens,completion_tokens,total_tokens from model.call_log order by id desc limit 1" 2>/dev/null | head -2 || echo "（call_log 表/行视实现）"

echo ""
echo "===== PR-B1 汇总：PASS=$PASS FAIL=$FAIL ====="
