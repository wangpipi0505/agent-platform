#!/bin/bash
# M5-3 E2E 起栈脚本：五服务起在 184xx 段（与并行工作隔离），专用库 m53_e2e。
# - 密钥只从仓库根 .env 读取，不进命令行参数或输出。
# - engine 因 Memories 双构造器装配阻塞（详见 README「平台缺陷登记」）以 PropertiesLauncher
#   外挂 scripts/e2e/shim 补足装配——不改服务源码；shim 按服务 jar 的 Spring 组件编译。
# - Redis/MinIO 未起：engine/model 健康探针 503/DOWN 属预期（单副本本地观察降级），不影响业务链。
# 重复执行安全：已监听的端口跳过（幂等）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOGDIR="$ROOT/.local/m53-logs"
mkdir -p "$LOGDIR" "$ROOT/.local/secrets-m53"

if [ ! -x "/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe" ]; then
  echo "需要 JDK21：C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot" >&2; exit 2
fi
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin:$PATH"

set -a; source "$ROOT/.env"; set +a
export AGENT_SECRET_DIR="$ROOT/.local/secrets-m53"
export AGENT_BIND_ADDRESS=127.0.0.1
export AGENT_SECURITY_MODE=dev-hmac
export AGENT_DEV_AUTH=true
export AGENT_APP_URL=http://127.0.0.1:18481
export AGENT_ENGINE_URL=http://127.0.0.1:18482
export AGENT_SKILL_URL=http://127.0.0.1:18483
export AGENT_MODEL_URL=http://127.0.0.1:18484
DB="jdbc:postgresql://127.0.0.1:25432/m53_e2e"

for name in platform-gateway app-management engine-runtime skill-registry model-gateway; do
  JAR="$ROOT/$name/target/$name-0.1.0-SNAPSHOT.jar"
  if [ ! -f "$JAR" ]; then
    echo "缺少构建产物 $JAR —— 先构建：mvn -B -ntp -pl platform-gateway,app-management,engine-runtime,skill-registry,model-gateway -am -DskipTests package"
    exit 2
  fi
done

# shim 编译（源在 scripts/e2e/shim/src，classpath 取自 engine jar 内嵌 Spring 组件）
SHIM_OUT="$ROOT/.local/m53-shim"
SHIM_JAR="$ROOT/.local/m53-shim.jar"
rm -rf "$SHIM_OUT" "$ROOT/.local/m53-shim-extract"
mkdir -p "$SHIM_OUT" "$ROOT/.local/m53-shim-extract"
BEANS=$(unzip -l "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar" | grep -oE 'BOOT-INF/lib/spring-beans-[0-9.]+\.jar' | head -1)
CORE=$(unzip -l "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar" | grep -oE 'BOOT-INF/lib/spring-core-[0-9.]+\.jar' | head -1)
CTX=$(unzip -l "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar" | grep -oE 'BOOT-INF/lib/spring-context-[0-9.]+\.jar' | head -1)
unzip -o -q "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar" "$BEANS" "$CORE" "$CTX" -d "$ROOT/.local/m53-shim-extract"
# Windows javac 只认本盘相对/绝对路径；在仓库根用相对路径组装 classpath（Git Bash POSIX 路径不可传）。
CP=".local/m53-shim-extract/$BEANS;.local/m53-shim-extract/$CORE;.local/m53-shim-extract/$CTX;engine-runtime/target/classes"
(cd "$ROOT" && javac -cp "$CP" -d "$SHIM_OUT" "scripts/e2e/shim/src/cn/agentplatform/engine/m53shim/MemoriesInstanceSupplierShim.java")
(cd "$SHIM_OUT" && jar cf "$SHIM_JAR" cn)

listening() { netstat -ano 2>/dev/null | grep "127.0.0.1:$1" | grep -q LISTENING; }

start() { # name jar port schema serviceid [shim]
  local name="$1" jar="$2" port="$3" schema="$4" sid="$5" shim="${6:-}"
  if listening "$port"; then echo "$name：端口 $port 已监听，跳过"; return 0; fi
  export AGENT_DB_URL="$DB?currentSchema=$schema"
  export SERVER_PORT="$port"
  export AGENT_SECURITY_SERVICEID="$sid"
  if [ "$shim" = "shim" ]; then
    java -Dloader.path="$SHIM_JAR" -cp "$jar" org.springframework.boot.loader.launch.PropertiesLauncher \
      > "$LOGDIR/$name.out.log" 2> "$LOGDIR/$name.err.log" &
  else
    java -Xms64m -Xmx512m -jar "$jar" > "$LOGDIR/$name.out.log" 2> "$LOGDIR/$name.err.log" &
  fi
  echo "$name：启动 pid=$! 端口=$port 日志=$LOGDIR/$name.out.log"
  unset AGENT_SECURITY_SERVICEID
}

start gateway         "$ROOT/platform-gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar" 18480 gateway gateway
start app-management  "$ROOT/app-management/target/app-management-0.1.0-SNAPSHOT.jar"    18481 app     app
start engine-runtime  "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar"    18482 engine  engine shim
start skill-registry  "$ROOT/skill-registry/target/skill-registry-0.1.0-SNAPSHOT.jar"    18483 skill  skill
start model-gateway   "$ROOT/model-gateway/target/model-gateway-0.1.0-SNAPSHOT.jar"      18484 model   model

echo "等待健康检查（监听即算就绪；engine/model 依赖探针 503 属预期）..."
for i in $(seq 1 40); do
  ok=0
  for p in 18480 18481 18482 18483 18484; do
    if listening "$p"; then ok=$((ok+1)); fi
  done
  if [ "$ok" -eq 5 ]; then echo "五服务全部监听就绪。"; exit 0; fi
  sleep 2
done
echo "部分服务未在 80 秒内监听，请查看 $LOGDIR/*.err.log" >&2
exit 1
