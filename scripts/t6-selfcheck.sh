#!/bin/bash
# T6 环境自检（09 测试细则 §2.4）——在 192.168.1.2 上执行
cd /home/agent-platform
{
echo "=== T6 环境自检（$(date -Iseconds)）==="
echo "--- 容器（compose-test）---"
docker compose -f compose-test.yaml ps --format "{{.Name}} {{.Status}}" | sort
echo "--- 观测栈（observability-test）---"
docker compose -f observability-test.yaml ps --format "{{.Name}} {{.Status}}" | sort
echo "--- 端口健康 ---"
for p in 18090 18081 18082 18083 18084 18085 18086; do
  printf "%s " "$p"
  curl -fsS -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:$p/actuator/health" 2>/dev/null || \
  curl -fsS -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:$p/actuator/health/readiness" 2>/dev/null || echo "000"
done
echo "--- 各域迁移链尾 ---"
for s in gateway app engine skill model monitor; do
  v=$(docker exec agent-platform-pg psql -U agentplat -d agent_platform -Atc "select max(version) from $s.flyway_schema_history" 2>/dev/null)
  echo "  $s 链尾: $v"
done
echo "--- DeepSeek /models（key 来自 secrets/）---"
KF=$(find secrets /home/agent-platform -maxdepth 1 -name "deepseek.key" 2>/dev/null | head -1)
if [ -n "$KF" ]; then
  curl -s -m 8 https://api.deepseek.com/models -H "Authorization: Bearer $(cat "$KF")" | head -c 150
  echo
else
  echo "deepseek.key 不在服务器（R-22 时经 ssh stdin 验证过；测试时由用户提供或上传）"
fi
echo "--- Prometheus targets ---"
curl -s -m 4 http://127.0.0.1:18092/api/v1/targets | grep -oE '"health":"(up|down)"' | sort | uniq -c
echo "--- Grafana ---"
curl -s -m 4 http://127.0.0.1:18093/api/health
echo
} > /home/agent-platform/evidence/t6-selfcheck.txt 2>&1
tail -40 /home/agent-platform/evidence/t6-selfcheck.txt
