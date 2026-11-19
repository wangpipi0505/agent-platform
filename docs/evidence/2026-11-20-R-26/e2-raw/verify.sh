#!/bin/bash
# R-26 E2 验证脚本（在 192.168.1.2 上执行）——09 细则 v1.2 §9.2 + 卡面 DoD。
# 输出全部落在 /home/agent-platform/evidence/R-26/。
set -u
EV=/home/agent-platform/evidence/R-26
mkdir -p "$EV"
AUTH=http://127.0.0.1:18086
GW=http://127.0.0.1:18090
BODY='{"tenantId":"demo","subject":"tester-1","roles":["USER"],"attributes":{"region":["A"],"dataLabels":["public"],"bizSystemCode":"agent-platform","userId":"tester-1"},"ttlSeconds":600}'

echo "== [01] mint USER ticket =="
MINT=$(curl -s -w '\n%{http_code}' -X POST $AUTH/test/tickets -H 'content-type: application/json' -d "$BODY")
echo "$MINT" > $EV/01-mint-user-ticket.txt
echo "$MINT" | tail -1
TOKEN=$(echo "$MINT" | head -1 | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
JTI=$(echo "$MINT" | head -1 | python3 -c 'import json,sys;print(json.load(sys.stdin)["jti"])')

echo "== [02] ticket header/claims (kid/issuer/aud triple) =="
echo "$TOKEN" | cut -d. -f1 | base64 -d 2>/dev/null | tee $EV/02-token-header.json; echo
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | tee $EV/02-token-claims.json; echo

echo "== [03] gateway /v1 sessions (DoD literal route) =="
curl -s -w '\nHTTP_STATUS:%{http_code}\n' "$GW/v1/apps/satellite-planning-assistant/sessions?mine=true" -H "Authorization: Bearer $TOKEN" | tee $EV/03-gateway-v1-sessions.txt

echo "== [04] gateway /admin/apps/{app}/runs (portal-governed shape POST) =="
curl -s -w '\nHTTP_STATUS:%{http_code}\n' -X POST "$GW/admin/apps/satellite-planning-assistant/runs" -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{}' | tee $EV/04-gateway-admin-runshape.txt

echo "== [05] tampered ticket -> 401 =="
TAMPER="${TOKEN%?}x"
curl -s -w '\nHTTP_STATUS:%{http_code}\n' "$GW/v1/apps/satellite-planning-assistant/sessions?mine=true" -H "Authorization: Bearer $TAMPER" | tee $EV/05-gateway-tampered-ticket.txt

echo "== [06] expired ticket (ttlSeconds=1) -> 401 =="
EXPBODY='{"tenantId":"demo","subject":"expiry-probe","roles":["USER"],"attributes":{"region":["A"],"dataLabels":[],"bizSystemCode":"agent-platform","userId":"expiry-probe"},"ttlSeconds":1}'
EXPTOKEN=$(curl -s -X POST $AUTH/test/tickets -H 'content-type: application/json' -d "$EXPBODY" | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
sleep 2
curl -s -w '\nHTTP_STATUS:%{http_code}\n' "$GW/v1/apps/satellite-planning-assistant/sessions?mine=true" -H "Authorization: Bearer $EXPTOKEN" | tee $EV/06-gateway-expired-ticket.txt

echo "== [07] mint negatives (all 400) =="
NEG1=$(curl -s -w ' [%{http_code}]' -X POST $AUTH/test/tickets -H 'content-type: application/json' -d '{"tenantId":"demo","subject":"s","roles":["USER","SERVICE"],"attributes":{"region":"A","dataLabels":[],"bizSystemCode":"B","userId":"u"}}')
echo "SERVICE role: $NEG1" | tee $EV/07-negative-service-role.txt
NEG2=$(curl -s -w ' [%{http_code}]' -X POST $AUTH/test/tickets -H 'content-type: application/json' -d '{"tenantId":"demo","subject":"s","roles":["SUDO"],"attributes":{"region":"A","dataLabels":[],"bizSystemCode":"B","userId":"u"}}')
echo "unknown role: $NEG2" | tee $EV/07-negative-unknown-role.txt
NEG3=$(curl -s -w ' [%{http_code}]' -X POST $AUTH/test/tickets -H 'content-type: application/json' -d '{"tenantId":"prod-x","subject":"s","roles":["USER"],"attributes":{"region":"A","dataLabels":[],"bizSystemCode":"B","userId":"u"}}')
echo "tenant outside whitelist: $NEG3" | tee $EV/07-negative-tenant.txt
NEG4=$(curl -s -w ' [%{http_code}]' -X POST $AUTH/test/tickets -H 'content-type: application/json' -d '{"tenantId":"demo","subject":"s","roles":["USER"],"attributes":{"region":"A","dataLabels":[],"bizSystemCode":"B","userId":"u"},"ttlSeconds":7200}')
echo "ttl 7200: $NEG4" | tee $EV/07-negative-ttl-7200.txt

echo "== [08] audit line =="
docker logs agent-platform-authority 2>&1 | grep 'TEST_TICKET_MINTED' | tail -5 | tee $EV/08-authority-audit-lines.txt

echo "== [09] session jti cross-check (audit vs mint response) =="
echo "minted jti=$JTI"
echo "DONE"
