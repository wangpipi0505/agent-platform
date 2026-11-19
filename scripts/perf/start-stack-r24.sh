#!/bin/bash
# R-24 端到端起栈：五服务起在 189xx 段，专用库 r24_check（V901 已落，等价 dev 状态承载）。
# 结构与 scripts/perf/start-stack.sh 同款；差异仅端口段/库名/密钥与日志目录。
# - engine 的 Memories 装配已由 R-9① 源码修复——无需 shim，直接 -jar 启动。
# - 模型桩 18985（scripts/e2e/fixtures/model-stub.mjs）：demo-provider 出话链。
# - Redis/MinIO 未起：engine/model 探针 503/DOWN 属预期，不影响任务链。
# 重复执行安全：已监听的端口跳过（幂等）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOGDIR="$ROOT/.local/r24-logs"
mkdir -p "$LOGDIR" "$ROOT/.local/secrets-r24"

if [ ! -x "/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe" ]; then
  echo "需要 JDK21" >&2; exit 1
fi
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin:$PATH"

set -a; source "$ROOT/.env"; set +a
export AGENT_SECRET_DIR="$ROOT/.local/secrets-r24"
export AGENT_BIND_ADDRESS=127.0.0.1
export AGENT_SECURITY_MODE=dev-hmac
export AGENT_DEV_AUTH=true
# R-25：本机单栈=非生产发布目标（V901 演示评测来源=DEMO，仅 NON_PRODUCTION 目标可发——
# AppPublicationService:478；@Value 属性 agent.publish.target-environment 的 env 映射名）。
export AGENT_PUBLISH_TARGETENVIRONMENT=NON_PRODUCTION
export AGENT_APP_URL=http://127.0.0.1:18981
export AGENT_ENGINE_URL=http://127.0.0.1:18982
export AGENT_SKILL_URL=http://127.0.0.1:18983
export AGENT_MODEL_URL=http://127.0.0.1:18984
DB="jdbc:postgresql://127.0.0.1:25432/r24_check"

for name in platform-gateway app-management engine-runtime skill-registry model-gateway; do
  JAR="$ROOT/$name/target/$name-0.1.0-SNAPSHOT.jar"
  if [ ! -f "$JAR" ]; then
    echo "缺少构建产物 $JAR —— 先打包" >&2; exit 2
  fi
done

listening() { netstat -ano 2>/dev/null | grep "127.0.0.1:$1" | grep -q LISTENING; }

start() { # name jar port schema serviceid
  local name="$1" jar="$2" port="$3" schema="$4" sid="$5"
  if listening "$port"; then echo "$name：端口 $port 已监听，跳过"; return 0; fi
  export AGENT_DB_URL="$DB?currentSchema=$schema"
  export SERVER_PORT="$port"
  export AGENT_SECURITY_SERVICEID="$sid"
  nohup java -Xms64m -Xmx512m -jar "$jar" > "$LOGDIR/$name.out.log" 2> "$LOGDIR/$name.err.log" &
  echo "$name：启动 pid=$! 端口=$port"
  unset AGENT_SECURITY_SERVICEID
}

if ! listening 18985; then
  nohup node "$ROOT/scripts/e2e/fixtures/model-stub.mjs" 18985 > "$LOGDIR/model-stub.log" 2>&1 &
  echo "model-stub：启动 端口=18985"
else
  echo "model-stub：端口 18985 已监听，跳过"
fi

start platform-gateway "$ROOT/platform-gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar" 18980 gateway gateway
start app-management  "$ROOT/app-management/target/app-management-0.1.0-SNAPSHOT.jar"    18981 app     app
start engine-runtime  "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar"    18982 engine  engine
start skill-registry  "$ROOT/skill-registry/target/skill-registry-0.1.0-SNAPSHOT.jar"    18983 skill  skill
start model-gateway   "$ROOT/model-gateway/target/model-gateway-0.1.0-SNAPSHOT.jar"      18984 model  model
echo "起栈完成（网关 18980；模型桩 18985）"
