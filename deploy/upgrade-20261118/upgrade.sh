#!/usr/bin/env bash
# =============================================================================
# G5-0 存量升级脚本（deploy/upgrade-20261118/upgrade.sh）
#
# 用法：
#   # 1) 构造“存量”基线（flyway target 钉在升级前版本：engine V228 / app V121 / skill V320）
#   ./upgrade.sh --database drill_g50_a --create-database \
#                --pin "engine=228,app=121,skill=320"
#
#   # 2) 全链升级到链头（engine V229/V230/V231/V901、app V122/V123/V901、skill V321/V322/V901）
#   #    升级动作执行前自动 pg_dump 一致性快照到 --backup-dir（回退唯一锚点）。
#   ./upgrade.sh --database drill_g50_a --backup-dir /abs/path/backup
#
#   # 3) 真实 dev 库升级（先自动快照）：
#   ./upgrade.sh --database agent_platform_dev_v12 --backup-dir /abs/path/dev-backup
#
# 环境约定：
#   - AGENT_DB_USER / AGENT_DB_PASSWORD 只从 .env 读（本脚本自动加载仓库根 .env），不进命令行/日志。
#   - 专用 PG：127.0.0.1:25432（与 EngineTestDatabase 同守卫），脚本拒绝其他实例。
#   - PG 客户端定位：env AGENT_PG_BIN 优先，其次 <repoRoot>/.tools/postgresql-16.15/pgsql/bin，
#     再次相邻主工作树 agent-platform 的同名目录。
#   - Flyway 与各服务同源（Boot 3.5.0 BOM 的 flyway-core 11.7.2），经 scripts/g50/DrillFlyway.java
#     单文件启动；依赖 classpath 缺失时自动用 mvn -pl engine-runtime dependency:build-classpath 生成。
#
# 回退：本脚本不做任何回退。Flyway 社区版无 downgrade——回退唯一受支持路径是
#   rollback.sh 的“备份恢复”（pg_restore），见 CHECKLIST.md。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SQL_DIR="$SCRIPT_DIR/sql"

PG_HOST="127.0.0.1"
PG_PORT="25432"

die() { echo "[upgrade][FATAL] $*" >&2; exit 1; }
log() { echo "[upgrade] $*"; }

DATABASE=""
PIN=""
CREATE_DB=0
BACKUP_DIR=""
VERIFY_HEAD=0

while [ $# -gt 0 ]; do
  case "$1" in
    --database) DATABASE="$2"; shift 2 ;;
    --pin) PIN="$2"; shift 2 ;;
    --create-database) CREATE_DB=1; shift ;;
    --backup-dir) BACKUP_DIR="$2"; shift 2 ;;
    --verify-head) VERIFY_HEAD=1; shift ;;
    *) die "未知参数: $1（支持 --database --pin --create-database --backup-dir --verify-head）" ;;
  esac
done

[ -n "$DATABASE" ] || die "--database 必填"
case "$DATABASE" in
  postgres|template0|template1) die "拒绝操作系统库: $DATABASE" ;;
esac

# ---- .env（密钥只从 env；兼容 CRLF） ----
env_var() { grep -E "^$1=" "$ROOT/.env" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r'; }
[ -z "${AGENT_DB_USER:-}" ]     && AGENT_DB_USER="$(env_var AGENT_DB_USER)"
[ -z "${AGENT_DB_PASSWORD:-}" ] && AGENT_DB_PASSWORD="$(env_var AGENT_DB_PASSWORD)"
[ -n "${AGENT_DB_PASSWORD:-}" ] || die "AGENT_DB_PASSWORD 为空：请确认仓库根 .env 存在（凭据只从 .env 读）"
export AGENT_DB_USER AGENT_DB_PASSWORD

# ---- PG 客户端定位 ----
PG_BIN="${AGENT_PG_BIN:-}"
if [ -z "$PG_BIN" ]; then
  for cand in "$ROOT/.tools/postgresql-16.15/pgsql/bin" \
              "$ROOT/../agent-platform/.tools/postgresql-16.15/pgsql/bin"; do
    if [ -x "$cand/psql.exe" ] || [ -x "$cand/psql" ]; then PG_BIN="$cand"; break; fi
  done
fi
[ -n "$PG_BIN" ] || die "找不到 psql/pg_dump：设 AGENT_PG_BIN=<pgsql/bin> 后重试"
PSQL="$PG_BIN/psql.exe"; [ -x "$PSQL" ] || PSQL="$PG_BIN/psql"
PG_DUMP="$PG_BIN/pg_dump.exe"; [ -x "$PG_DUMP" ] || PG_DUMP="$PG_BIN/pg_dump"
export PGPASSWORD="$AGENT_DB_PASSWORD"
export PGCLIENTENCODING=UTF8

# ---- 连通性 + 专用实例守卫 ----
server_port="$("$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d postgres -At -c "show port;" 2>/dev/null)" \
  || die "专用 PG 不可达: $PG_HOST:$PG_PORT"
[ "$server_port" = "$PG_PORT" ] || die "端口不符（期望 $PG_PORT，实际 $server_port）"

JDBC_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/$DATABASE"

# ---- 建库（可选） ----
if [ "$CREATE_DB" = 1 ]; then
  exists="$("$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d postgres -At \
    -c "select count(*) from pg_database where datname='$DATABASE';")"
  if [ "$exists" = "0" ]; then
    "$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d postgres -c \
      "create database \"$DATABASE\";" >/dev/null
    log "已建库: $DATABASE"
  else
    log "库已存在（不重建）: $DATABASE"
  fi
fi

exists="$("$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d postgres -At \
  -c "select count(*) from pg_database where datname='$DATABASE';")"
[ "$exists" = "1" ] || die "库不存在: $DATABASE（构造基线请加 --create-database）"

# ---- Flyway runner + classpath ----
export G50_REPO_ROOT="$ROOT"
JAVA_HOME="${JAVA_HOME:-}"
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java.exe" -a ! -x "$JAVA_HOME/bin/java" ]; then
  for cand in "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"; do
    [ -x "$cand/bin/java.exe" ] && JAVA_HOME="$cand" && break
  done
fi
JAVA="$JAVA_HOME/bin/java.exe"; [ -x "$JAVA" ] || JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVA" ] || die "找不到 java：请 export JAVA_HOME=<jdk21>"

CP_FILE="$ROOT/.local/g50/engine-cp.txt"
if [ ! -f "$CP_FILE" ]; then
  log "生成依赖 classpath（mvn dependency:build-classpath）..."
  mkdir -p "$(dirname "$CP_FILE")"
  (cd "$ROOT" && mvn -B -ntp -q -pl engine-runtime dependency:build-classpath -Dmdep.outputFile="$CP_FILE") \
    || die "classpath 生成失败：请检查 JAVA_HOME(JDK21)/mvn 可用"
fi
CP="$(cat "$CP_FILE")"

run_flyway() { # $1=module $2=target(可空=链头)
  local mod="$1" tgt="${2:-}"
  log "flyway migrate: module=$mod target=${tgt:-HEAD} db=$DATABASE"
  "$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "$ROOT/scripts/g50/DrillFlyway.java" \
    migrate "$JDBC_URL" "$mod" $tgt
}

# ---- 升级前快照（回退唯一锚点；在首个迁移动作前完成） ----
HAS_HISTORY="$("$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d "$DATABASE" -At \
  -c "select count(*) from information_schema.tables where table_name='flyway_schema_history' and table_schema in ('engine','app','skill');")"
if [ -n "$BACKUP_DIR" ]; then
  mkdir -p "$BACKUP_DIR"
  STAMP="$(date -u +%Y%m%d-%H%M%S)"
  DUMP="$BACKUP_DIR/$DATABASE-pre-upgrade-$STAMP.dump"
  log "pg_dump 一致性快照 -> $DUMP"
  "$PG_DUMP" -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d "$DATABASE" \
    --format=custom --no-owner --no-privileges --file="$DUMP" || die "备份失败；按红线拒绝升级（回退锚点必须先在）"
  SHA="$(sha256sum "$DUMP" | cut -d' ' -f1)"
  printf '{"formatVersion":1,"database":"%s","createdAt":"%s","sha256":"%s","bytes":%s,"postgres":"16.15","scope":"PostgreSQL only; vault/master key/MinIO objects need separate backup"}\n' \
    "$DATABASE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$SHA" "$(stat -c %s "$DUMP" 2>/dev/null || wc -c < "$DUMP")" \
    > "$BACKUP_DIR/$DATABASE-pre-upgrade-$STAMP.manifest.json"
  log "备份 manifest 已写（sha256=$SHA）"
elif [ "$HAS_HISTORY" != "0" ]; then
  log "警告：--backup-dir 未提供；已有迁移历史的库升级将没有回退锚点（演练/首装除外，不建议对 dev 这样做）"
fi

# ---- 执行升级（基线 or 链头） ----
declare -A TARGETS
if [ -n "$PIN" ]; then
  IFS=',' read -ra PAIRS <<< "$PIN"
  for p in "${PAIRS[@]}"; do
    k="${p%%=*}"; v="${p#*=}"
    case "$k" in engine|app|skill) TARGETS["$k"]="$v" ;; *) die "--pin 键必须为 engine/app/skill: $p" ;; esac
  done
fi
for mod in engine app skill; do
  run_flyway "$mod" "${TARGETS[$mod]:-}"
done

# ---- 升级后 history 摘要 ----
log "升级后 flyway_schema_history 摘要："
"$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d "$DATABASE" -At -f "$SQL_DIR/history-summary.sql"

# ---- 可选链头验证 ----
if [ "$VERIFY_HEAD" = 1 ]; then
  log "verify-head 断言："
  OUT="$("$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d "$DATABASE" -At -f "$SQL_DIR/verify-head.sql")"
  echo "$OUT"
  if echo "$OUT" | grep -qE '\|f$'; then die "verify-head 存在失败断言（行尾 f）"; fi
  log "verify-head 全部通过"
fi

log "完成：$DATABASE"
