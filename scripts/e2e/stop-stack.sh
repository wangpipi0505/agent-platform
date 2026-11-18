#!/bin/bash
# M5-3 E2E 停栈：按端口找本仓 java 进程并结束（只处理 184xx 段监听者；非本栈进程不动）。
set -uo pipefail
for port in 18480 18481 18482 18483 18484; do
  pids=$(netstat -ano 2>/dev/null | grep "127.0.0.1:$port" | grep LISTENING | awk '{print $NF}' | sort -u)
  for pid in $pids; do
    cmdline=$(powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter 'ProcessId=$pid').CommandLine" 2>/dev/null || true)
    if echo "$cmdline" | grep -q "engine-runtime\|platform-gateway\|app-management\|skill-registry\|model-gateway\|PropertiesLauncher"; then
      echo "停止端口 $port 的 pid=$pid"
      taskkill //PID "$pid" //F >/dev/null 2>&1 || true
    else
      echo "端口 $port 的 pid=$pid 非本栈 java 进程，不动"
    fi
  done
done
echo "停栈完成。"
