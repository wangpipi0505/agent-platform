#!/bin/bash
# R-9②③④⑤ 网关与契约缺陷收口——最小栈起栈脚本：
# 四服务起在 186xx 段（与并行工作隔离），专用库 r9_fix（全链迁移 + V901 种子随服务启动
# Flyway 自动应用）。R-9①（基线 b63bfe0c）已修 Memories 双构造器装配阻塞，engine 直接
# java -jar 启动，无 shim 外挂（M5-2/M5-3 时代 shim 已随源码修复下线）。
# - 密钥只从仓库根 .env 读取，不进命令行参数或输出。
# - Redis/MinIO 未起：engine 健康探针 503 属预期，不影响任务/记忆数据面链路（M5-2 同口径）。
# - model-gateway/monitor 未起：任务执行派发停受理态，计划面确认/执行链路与模型出话无关。
# 重复执行安全：已监听的端口跳过（幂等）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
LOGDIR="$ROOT/.local/r9-logs"
mkdir -p "$LOGDIR" "$ROOT/.local/secrets-r9"

if [ ! -x "/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe" ]; then
  echo "需要 JDK21：C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot" >&2; exit 2
fi
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin:$PATH"

set -a; source "$ROOT/.env"; set +a
export AGENT_SECRET_DIR="$ROOT/.local/secrets-r9"
export AGENT_BIND_ADDRESS=127.0.0.1
export AGENT_SECURITY_MODE=dev-hmac
export AGENT_DEV_AUTH=true
export AGENT_APP_URL=http://127.0.0.1:18681
export AGENT_ENGINE_URL=http://127.0.0.1:18682
export AGENT_SKILL_URL=http://127.0.0.1:18683
export AGENT_MODEL_URL=http://127.0.0.1:18684
DB="jdbc:postgresql://127.0.0.1:25432/r9_fix"

for name in platform-gateway app-management engine-runtime skill-registry; do
  JAR="$ROOT/$name/target/$name-0.1.0-SNAPSHOT.jar"
  if [ ! -f "$JAR" ]; then
    echo "缺少构建产物 $JAR —— 先构建：mvn -B -ntp -pl platform-gateway,app-management,engine-runtime,skill-registry -am -DskipTests package"
    exit 2
  fi
done

listening() { netstat -ano 2>/dev/null | grep "127.0.0.1:$1" | grep -q LISTENING; }

start() { # name jar port schema serviceid
  local name="$1" jar="$2" port="$3" schema="$4" sid="$5"
  if listening "$port"; then echo "$name：端口 $port 已监听，跳过"; return 0; fi
  export AGENT_DB_URL="$DB?currentSchema=$schema"
  export SERVER_PORT="$port"
  export AGENT_SECURITY_SERVICEID="$sid"
  java -Xms64m -Xmx512m -jar "$jar" > "$LOGDIR/$name.out.log" 2> "$LOGDIR/$name.err.log" &
  echo "$name：启动 pid=$! 端口=$port 日志=$LOGDIR/$name.out.log"
  unset AGENT_SECURITY_SERVICEID
}

start gateway         "$ROOT/platform-gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar" 18680 gateway gateway
start app-management  "$ROOT/app-management/target/app-management-0.1.0-SNAPSHOT.jar"    18681 app     app
start engine-runtime  "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar"    18682 engine  engine
start skill-registry  "$ROOT/skill-registry/target/skill-registry-0.1.0-SNAPSHOT.jar"    18683 skill  skill

echo "等待监听就绪（engine 健康探针 503 属预期）..."
for i in $(seq 1 60); do
  ok=0
  for p in 18680 18681 18682 18683; do
    if listening "$p"; then ok=$((ok+1)); fi
  done
  if [ "$ok" -eq 4 ]; then echo "四服务全部监听就绪（库 r9_fix）。"; exit 0; fi
  sleep 2
done
echo "部分服务未在 120 秒内监听，请查看 $LOGDIR/*.err.log" >&2
exit 1
