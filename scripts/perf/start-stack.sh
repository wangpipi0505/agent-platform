#!/bin/bash
# R-14 M5-4 性能实测起栈：五服务起在 188xx 段（与 184xx/186xx 并行工作隔离），专用库 r14_perf。
# 结构与 M5-3 scripts/e2e/start-stack.sh 同款；差异仅端口段/库名/密钥目录/日志目录。
# - 密钥只从仓库根 .env 读取，不进命令行参数或输出。
# - engine 因 Memories 双构造器装配阻塞（M5-3 登记的平台缺陷）以 PropertiesLauncher 外挂
#   scripts/e2e/shim 补足装配——不改服务源码；shim 按服务 jar 的 Spring 组件编译。
# - 本地模型桩 18885（scripts/e2e/fixtures/model-stub.mjs 同款）：承接造数期会话轮次出话。
# - Redis/MinIO 未起：engine/model 健康探针 503/DOWN 属预期（单副本本地观察降级），不影响业务链。
# - 全链迁移（V100-V231/V300-V322/V901 种子）由各服务启动时 Flyway 自举到 r14_perf。
# 重复执行安全：已监听的端口跳过（幂等）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOGDIR="$ROOT/.local/r14-logs"
mkdir -p "$LOGDIR" "$ROOT/.local/secrets-r14"

if [ ! -x "/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe" ]; then
  echo "需要 JDK21：C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot" >&2; exit 2
fi
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin:$PATH"

set -a; source "$ROOT/.env"; set +a
export AGENT_SECRET_DIR="$ROOT/.local/secrets-r14"
export AGENT_BIND_ADDRESS=127.0.0.1
export AGENT_SECURITY_MODE=dev-hmac
export AGENT_DEV_AUTH=true
export AGENT_APP_URL=http://127.0.0.1:18881
export AGENT_ENGINE_URL=http://127.0.0.1:18882
export AGENT_SKILL_URL=http://127.0.0.1:18883
export AGENT_MODEL_URL=http://127.0.0.1:18884
DB="jdbc:postgresql://127.0.0.1:25432/r14_perf"

for name in platform-gateway app-management engine-runtime skill-registry model-gateway; do
  JAR="$ROOT/$name/target/$name-0.1.0-SNAPSHOT.jar"
  if [ ! -f "$JAR" ]; then
    echo "缺少构建产物 $JAR —— 先构建：mvn -B -ntp -pl platform-gateway,app-management,engine-runtime,skill-registry,model-gateway -am -DskipTests package"
    exit 2
  fi
done

# shim 编译（源在 scripts/e2e/shim/src，classpath 取自 engine jar 内嵌 Spring 组件；与 M5-3 同款）
SHIM_OUT="$ROOT/.local/r14-shim"
SHIM_JAR="$ROOT/.local/r14-shim.jar"
rm -rf "$SHIM_OUT" "$ROOT/.local/r14-shim-extract"
mkdir -p "$SHIM_OUT" "$ROOT/.local/r14-shim-extract"
BEANS=$(unzip -l "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar" | grep -oE 'BOOT-INF/lib/spring-beans-[0-9.]+\.jar' | head -1)
CORE=$(unzip -l "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar" | grep -oE 'BOOT-INF/lib/spring-core-[0-9.]+\.jar' | head -1)
CTX=$(unzip -l "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar" | grep -oE 'BOOT-INF/lib/spring-context-[0-9.]+\.jar' | head -1)
unzip -o -q "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar" "$BEANS" "$CORE" "$CTX" -d "$ROOT/.local/r14-shim-extract"
# Windows javac 只认本盘相对/绝对路径；在仓库根用相对路径组装 classpath（Git Bash POSIX 路径不可传）。
CP=".local/r14-shim-extract/$BEANS;.local/r14-shim-extract/$CORE;.local/r14-shim-extract/$CTX;engine-runtime/target/classes"
(cd "$ROOT" && javac -cp "$CP" -d "$SHIM_OUT" "scripts/e2e/shim/src/cn/agentplatform/engine/m53shim/MemoriesInstanceSupplierShim.java")
(cd "$SHIM_OUT" && jar cf "$SHIM_JAR" cn)

listening() { netstat -ano 2>/dev/null | grep "127.0.0.1:$1" | grep -q LISTENING; }

# 模型桩：OpenAI 兼容最小面（连通探测+确定性出话）；stdin 保持打开（后台 nohup 重定向）。
if listening 18885; then
  echo "model-stub：端口 18885 已监听，跳过"
else
  nohup node "$ROOT/scripts/e2e/fixtures/model-stub.mjs" 18885 \
    > "$LOGDIR/model-stub.out.log" 2> "$LOGDIR/model-stub.err.log" < /dev/null &
  echo "model-stub：启动 pid=$! 端口=18885 日志=$LOGDIR/model-stub.out.log"
fi

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

start gateway         "$ROOT/platform-gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar" 18880 gateway gateway
start app-management  "$ROOT/app-management/target/app-management-0.1.0-SNAPSHOT.jar"    18881 app     app
start engine-runtime  "$ROOT/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar"    18882 engine  engine shim
start skill-registry  "$ROOT/skill-registry/target/skill-registry-0.1.0-SNAPSHOT.jar"    18883 skill  skill
start model-gateway   "$ROOT/model-gateway/target/model-gateway-0.1.0-SNAPSHOT.jar"      18884 model   model

# 首跑含全链迁移+V901 种子，等待窗口给足（M5-3 经验 30s→120s）。
echo "等待健康检查（监听即算就绪；engine/model 依赖探针 503 属预期）..."
for i in $(seq 1 90); do
  ok=0
  for p in 18880 18881 18882 18883 18884; do
    if listening "$p"; then ok=$((ok+1)); fi
  done
  if [ "$ok" -eq 5 ]; then echo "五服务全部监听就绪（r14_perf）。"; exit 0; fi
  sleep 2
done
echo "部分服务未在 180 秒内监听，请查看 $LOGDIR/*.err.log" >&2
exit 1
