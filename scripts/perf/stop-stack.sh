#!/bin/bash
# R-14 压测停栈：按 188xx 端口找到监听进程逐个 taskkill（模型桩 18885 一并收口）。
# 只杀本压测栈端口，不触碰其他端口段进程。幂等：无监听即报已停。
set -uo pipefail
PIDS_SEEN=0
for port in 18880 18881 18882 18883 18884 18885; do
  PIDS=$(netstat -ano 2>/dev/null | grep "127.0.0.1:$port" | grep LISTENING | awk '{print $NF}' | sort -u)
  if [ -z "$PIDS" ]; then
    echo "端口 $port：无监听，跳过"
    continue
  fi
  for pid in $PIDS; do
    echo "端口 $port：终止 pid=$pid"
    taskkill //F //PID "$pid" >/dev/null 2>&1 || echo "  （pid $pid 已退出或终止失败）"
    PIDS_SEEN=$((PIDS_SEEN+1))
  done
done
if [ "$PIDS_SEEN" -eq 0 ]; then echo "R-14 栈已全部停止。"; else echo "R-14 栈停止完成（共终止 $PIDS_SEEN 个进程）。"; fi
