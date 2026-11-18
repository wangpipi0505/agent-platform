#!/usr/bin/env bash
# =============================================================================
# G5-0 回退脚本（deploy/upgrade-20261118/rollback.sh）
#
# 用法：
#   ./rollback.sh --database drill_g50_a --backup-file /abs/path/drill_g50_a-pre-upgrade-<stamp>.dump --force
#
# 唯一受支持回退路径 = 备份恢复（pg_restore）。Flyway 社区版没有 downgrade，
# 本脚本以“删库重建 + pg_restore”把库恢复到备份时点（即升级前快照）。
#
# 安全护栏：
#   - 必须 --force 才会执行 drop/create（破坏性动作显式确认）。
#   - 拒绝 postgres/template0/template1 等系统库。
#   - 备份文件必须存在，且（若同名 manifest.json 在旁）先校验 sha256。
#   - 只操作显式传入的单个库；绝不触碰共享 test 库与其他卡的验证库。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SQL_DIR="$SCRIPT_DIR/sql"

PG_HOST="127.0.0.1"
PG_PORT="25432"

die() { echo "[rollback][FATAL] $*" >&2; exit 1; }
log() { echo "[rollback] $*"; }

DATABASE=""
BACKUP_FILE=""
FORCE=0
VERIFY_BASELINE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --database) DATABASE="$2"; shift 2 ;;
    --backup-file) BACKUP_FILE="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --verify-baseline) VERIFY_BASELINE=1; shift ;;
    *) die "未知参数: $1（支持 --database --backup-file --force --verify-baseline）" ;;
  esac
done

[ -n "$DATABASE" ] || die "--database 必填"
[ -n "$BACKUP_FILE" ] || die "--backup-file 必填"
[ "$FORCE" = 1 ] || die "破坏性回退必须显式 --force 确认（drop + recreate + pg_restore）"
case "$DATABASE" in
  postgres|template0|template1) die "拒绝操作系统库: $DATABASE" ;;
esac
[ -f "$BACKUP_FILE" ] || die "备份文件不存在: $BACKUP_FILE"

env_var() { grep -E "^$1=" "$ROOT/.env" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r'; }
[ -z "${AGENT_DB_USER:-}" ]     && AGENT_DB_USER="$(env_var AGENT_DB_USER)"
[ -z "${AGENT_DB_PASSWORD:-}" ] && AGENT_DB_PASSWORD="$(env_var AGENT_DB_PASSWORD)"
[ -n "${AGENT_DB_PASSWORD:-}" ] || die "AGENT_DB_PASSWORD 为空：请确认仓库根 .env 存在"
export AGENT_DB_USER AGENT_DB_PASSWORD

PG_BIN="${AGENT_PG_BIN:-}"
if [ -z "$PG_BIN" ]; then
  for cand in "$ROOT/.tools/postgresql-16.15/pgsql/bin" \
              "$ROOT/../agent-platform/.tools/postgresql-16.15/pgsql/bin"; do
    if [ -x "$cand/psql.exe" ] || [ -x "$cand/psql" ]; then PG_BIN="$cand"; break; fi
  done
fi
[ -n "$PG_BIN" ] || die "找不到 psql/pg_restore：设 AGENT_PG_BIN=<pgsql/bin> 后重试"
PSQL="$PG_BIN/psql.exe";        [ -x "$PSQL" ]        || PSQL="$PG_BIN/psql"
PG_RESTORE="$PG_BIN/pg_restore.exe"; [ -x "$PG_RESTORE" ] || PG_RESTORE="$PG_BIN/pg_restore"
export PGPASSWORD="$AGENT_DB_PASSWORD"
export PGCLIENTENCODING=UTF8

"$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d postgres -At -c "select 1;" >/dev/null \
  || die "专用 PG 不可达: $PG_HOST:$PG_PORT"

# ---- 备份完整性校验（manifest 在旁则校验 sha256） ----
MANIFEST="${BACKUP_FILE%.dump}.manifest.json"
if [ -f "$MANIFEST" ]; then
  WANT="$(grep -o '"sha256":"[0-9a-f]*"' "$MANIFEST" | head -1 | cut -d'"' -f4)"
  GOT="$(sha256sum "$BACKUP_FILE" | cut -d' ' -f1)"
  if [ -n "$WANT" ] && [ "$WANT" != "$GOT" ]; then
    die "备份 sha256 不匹配（manifest=$WANT 实际=$GOT）——拒绝用损坏备份回退"
  fi
  log "备份 sha256 校验通过（manifest 在旁）"
else
  log "警告：无 manifest.json，跳过 sha256 校验（$MANIFEST 不存在）"
fi

# ---- drop + create + restore ----
db_exists="$("$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d postgres -At \
  -c "select count(*) from pg_database where datname='$DATABASE';")"
if [ "$db_exists" = "1" ]; then
  log "drop database: $DATABASE"
  "$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d postgres -c \
    "drop database \"$DATABASE\";" >/dev/null
fi
log "create database: $DATABASE"
"$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d postgres -c \
  "create database \"$DATABASE\";" >/dev/null

log "pg_restore <- $BACKUP_FILE"
"$PG_RESTORE" -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d "$DATABASE" \
  --no-owner --no-privileges --exit-on-error "$BACKUP_FILE" \
  || die "pg_restore 失败（回退未完成；库内状态为半恢复，需整库重放本步骤）"

# ---- 回退后验证 ----
log "回退后 flyway_schema_history 摘要："
"$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d "$DATABASE" -At -f "$SQL_DIR/history-summary.sql"

if [ "$VERIFY_BASELINE" = 1 ]; then
  log "verify-baseline 断言（仅演练基线库适用；dev 库请按 CHECKLIST 与升级前留档 diff）："
  OUT="$("$PSQL" -X -h "$PG_HOST" -p "$PG_PORT" -U "$AGENT_DB_USER" -d "$DATABASE" -At -f "$SQL_DIR/verify-baseline.sql")"
  echo "$OUT"
  if echo "$OUT" | grep -qE '\|f$'; then die "verify-baseline 存在失败断言（行尾 f）"; fi
  log "verify-baseline 全部通过"
fi

log "完成：$DATABASE 已恢复到备份时点（回退=备份恢复路径）"
