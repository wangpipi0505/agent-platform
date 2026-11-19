#!/usr/bin/env bash
# PR-B1 CHAT 全链：建会话 -> 轮次 -> 轮询 engine.run 终态（上限 5 分钟）。
# 用法：bash prb1-chat-run.sh <evidence-dir> [message]
set -euo pipefail
EV="${1:-/home/agent-platform/evidence/PR-B1}"
MSG="${2:-请用两三句话说明卫星观测规划的关键检查点，并明确说明本回答不构成审批。}"
GATEWAY="http://127.0.0.1:18980"
USR="$(cat "$EV/.user-token")"

echo "== 创建会话 =="
curl -s -o "$EV/06-session-create.json" -w "HTTP %{http_code}\n" \
  -X POST "$GATEWAY/v1/apps/satellite-planning-assistant/sessions" \
  -H "Authorization: Bearer $USR" -H "Content-Type: application/json" -d '{}'
SESSION_ID=$(python3 -c "import json;print(json.load(open('$EV/06-session-create.json'))['data']['sessionId'])")
echo "sessionId=$SESSION_ID"
echo "$SESSION_ID" > "$EV/.session-id"

echo "== 创建轮次 =="
REQID="prb1-turn-$(date +%s)"
echo "$REQID" > "$EV/.request-id"
PAYLOAD=$(python3 -c "import json;print(json.dumps({'requestId':'$REQID','inputs':{'message':'''$MSG'''}},ensure_ascii=False))")
curl -s -o "$EV/07-turn-create.json" -w "HTTP %{http_code}\n" \
  -X POST "$GATEWAY/v1/apps/satellite-planning-assistant/sessions/$SESSION_ID/turns" \
  -H "Authorization: Bearer $USR" -H "Content-Type: application/json" -d "$PAYLOAD"
RUN_ID=$(python3 -c "
import json
d=json.load(open('$EV/07-turn-create.json'))['data']
print(d.get('runId') or d.get('runPublicId') or d.get('id') or '')
")
echo "runId=$RUN_ID"
echo "$RUN_ID" > "$EV/.run-id"

echo "== 轮询终态（最多 300s）=="
DEADLINE=$(( $(date +%s) + 300 ))
: > "$EV/08-run-poll.txt"
STATUS=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  RESP=$(curl -s "$GATEWAY/v1/runs/$RUN_ID" -H "Authorization: Bearer $USR")
  STATUS=$(printf '%s' "$RESP" | python3 -c "
import json,sys
try: d=json.load(sys.stdin)['data']
except Exception: print('PARSE_ERR'); raise SystemExit
print(d.get('status') or d.get('state') or 'UNKNOWN')
")
  TS=$(date +%H:%M:%S)
  echo "[$TS] $STATUS" >> "$EV/08-run-poll.txt"
  case "$STATUS" in
    SUCCEEDED|FAILED|CANCELLED|DEADLINE_EXCEEDED|TIMEOUT)
      printf '%s' "$RESP" > "$EV/09-run-final.json"; break ;;
  esac
  sleep 5
done
if [ ! -s "$EV/09-run-final.json" ]; then
  echo "POLL_TIMEOUT status=$STATUS" | tee "$EV/09-run-final.txt"
  exit 2
fi
echo "final status=$STATUS"
