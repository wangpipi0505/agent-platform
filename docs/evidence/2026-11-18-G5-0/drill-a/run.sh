#!/usr/bin/env bash
# =============================================================================
# G5-0 演练A 完整重放脚本（bash 脚本族：upgrade.sh / rollback.sh）
# 库：drill_g50_a（工作）+ drill_g50_base_a（基线对照）
# 产物：本目录下 01..09 日志；备份 dump 在 .local/g50/drill-a/backup（不入 Git）。
# 幂等：已存在且非空的步骤日志会跳过（重放时先清空对应日志即可强制重跑）。
# 证据说明：本仓库归档的 01/02 日志产生于 2026-11-18 07:0x（与 03..09 同一命令逐字相同，
# 仅先后执行）；03 首次尝试因 pg_dump 误加 psql 专属 -X 选项失败，失败日志见 failures/。
# =============================================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
DEPLOY="$ROOT/deploy/upgrade-20261118"
EV="$SCRIPT_DIR"
export JAVA_HOME="${JAVA_HOME:-C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot}"
export PATH="$JAVA_HOME/bin:$PATH"

step() { # $1=log-name  其余=命令
  local name="$1"; shift
  local f="$EV/$name.txt"
  if [ -s "$f" ]; then echo "[run] 跳过（已有日志）: $name"; return 0; fi
  echo "[run] === $name ==="
  { echo "## 演练A $name @ $(date -u +%FT%TZ)"; echo "\$ $*"; } > "$f"
  "$@" >> "$f" 2>&1
  local rc=$?
  echo "## exit=$rc" >> "$f"
  tail -5 "$f"
  return $rc
}

PSQL="$ROOT/../agent-platform/.tools/postgresql-16.15/pgsql/bin/psql.exe"
[ -x "$PSQL" ] || PSQL="$ROOT/.tools/postgresql-16.15/pgsql/bin/psql.exe"
export PGPASSWORD="$(grep -E '^AGENT_DB_PASSWORD=' "$ROOT/.env" | tail -1 | cut -d= -f2- | tr -d '\r')"
export PGCLIENTENCODING=UTF8

# ---- 01 建库+钉版基线（engine=228, app=121, skill=320） ----
step 01-baseline-build bash "$DEPLOY/upgrade.sh" --database drill_g50_a --create-database --pin "engine=228,app=121,skill=320"
# ---- 02 基线断言 ----
if [ ! -s "$EV/02-verify-baseline.txt" ]; then
  { echo "## 演练A 02 基线断言 @ $(date -u +%FT%TZ)"
    echo "\$ psql -d drill_g50_a -At -f sql/verify-baseline.sql"
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_a -At -f "$DEPLOY/sql/verify-baseline.sql"
    echo "== 失败断言（行尾f）=="
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_a -At -f "$DEPLOY/sql/verify-baseline.sql" | grep -E '\|f$' || echo "(无失败断言)"
  } > "$EV/02-verify-baseline.txt" 2>&1
fi
# ---- 03 基线快照→pg_restore→对照库 drill_g50_base_a ----
if [ ! -s "$EV/03-snapshot-restore-base-a.txt" ]; then
  DUMP="$ROOT/.local/g50/drill-a/backup/drill_g50_a-baseline-$(date -u +%Y%m%d-%H%M%S).dump"
  PGD="$(dirname "$PSQL")"
  {
    echo "## 演练A 03 基线快照+恢复出对照库 drill_g50_base_a @ $(date -u +%FT%TZ)"
    echo "-- pg_dump --format=custom --no-owner --no-privileges -> $DUMP"
    "$PGD/pg_dump.exe" -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_a --format=custom --no-owner --no-privileges --file="$DUMP" && echo "pg_dump OK"
    echo "-- sha256:"; sha256sum "$DUMP"
    echo "-- drop/create 对照库"
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -c 'drop database if exists "drill_g50_base_a";'
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -c 'create database "drill_g50_base_a";'
    echo "-- pg_restore --exit-on-error -> drill_g50_base_a"
    "$PGD/pg_restore.exe" -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_base_a --no-owner --no-privileges --exit-on-error "$DUMP" && echo "pg_restore OK"
    echo "-- restore 后基线断言（对照库）"
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_base_a -At -f "$DEPLOY/sql/verify-baseline.sql"
  } > "$EV/03-snapshot-restore-base-a.txt" 2>&1
fi
# ---- 04 升级前状态：row-counts（工作库+对照库） ----
if [ ! -s "$EV/04-pre-upgrade-state.txt" ]; then
  {
    echo "## 演练A 04 升级前状态 @ $(date -u +%FT%TZ)"
    echo "== drill_g50_a row-counts（升级前）=="
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_a -At -f "$DEPLOY/sql/row-counts.sql"
    echo "== drill_g50_base_a row-counts（对照库）=="
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_base_a -At -f "$DEPLOY/sql/row-counts.sql"
  } > "$EV/04-pre-upgrade-state.txt" 2>&1
fi
grep -E '^engine\.|^app\.|^skill\.' "$EV/04-pre-upgrade-state.txt" | sort | uniq > "$EV/04-pre-rowcounts.sorted"
# ---- 05 全链升级（自动 pg_dump 快照 + verify-head） ----
step 05-upgrade bash "$DEPLOY/upgrade.sh" --database drill_g50_a --backup-dir "$ROOT/.local/g50/drill-a/backup" --verify-head
# ---- 06 升级后状态：verify-head 明细 + row-counts ----
{
  echo "## 演练A 06 升级后状态 @ $(date -u +%FT%TZ)"
  echo "== verify-head 断言明细 =="
  "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_a -At -f "$DEPLOY/sql/verify-head.sql"
  echo "== 失败断言（行尾f）=="
  "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_a -At -f "$DEPLOY/sql/verify-head.sql" | grep -E '\|f$' || echo "(无失败断言)"
  echo "== drill_g50_a row-counts（升级后）=="
  "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_a -At -f "$DEPLOY/sql/row-counts.sql"
} > "$EV/06-verify-head.txt" 2>&1
# ---- 07 回退（备份恢复路径） ----
DUMP_FILE="$(ls -1 "$ROOT/.local/g50/drill-a/backup/"drill_g50_a-pre-upgrade-*.dump | head -1)"
{ echo "## 演练A 07 回退 @ $(date -u +%FT%TZ)"; echo "\$ rollback.sh --database drill_g50_a --backup-file $DUMP_FILE --force"
  bash "$DEPLOY/rollback.sh" --database drill_g50_a --backup-file "$DUMP_FILE" --force; } > "$EV/07-rollback.txt" 2>&1
tail -3 "$EV/07-rollback.txt"
# ---- 08 回退后状态 ----
{
  echo "## 演练A 08 回退后状态 @ $(date -u +%FT%TZ)"
  echo "== drill_g50_a history-summary（回退后）=="
  "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_a -At -f "$DEPLOY/sql/history-summary.sql"
  echo "== drill_g50_a row-counts（回退后）=="
  "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_a -At -f "$DEPLOY/sql/row-counts.sql"
} > "$EV/08-post-rollback-state.txt" 2>&1
grep -E '^engine\.|^app\.|^skill\.' "$EV/08-post-rollback-state.txt" | sort > "$EV/08-post-rowcounts.sorted"
# ---- 09 一致性 diff：schema + 行数 ----
PGD="$(dirname "$PSQL")"
SCHEMA_A="$ROOT/.local/g50/drill-a/schema-base_a.sql"
SCHEMA_R="$ROOT/.local/g50/drill-a/schema-rolledback.sql"
"$PGD/pg_dump.exe" -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_base_a --schema-only --no-owner --no-privileges -f "$SCHEMA_A"
"$PGD/pg_dump.exe" -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_a --schema-only --no-owner --no-privileges -f "$SCHEMA_R"
{
  echo "## 演练A 09 回退一致性 diff @ $(date -u +%FT%TZ)"
  echo "== [1] 行数：回退后 vs 升级前（应为空）=="
  diff "$EV/04-pre-rowcounts.sorted" "$EV/08-post-rowcounts.sorted" && echo "(一致)"
  echo "== [2] 行数：回退后 vs 对照库 base_a（应为空）=="
  "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_base_a -At -f "$DEPLOY/sql/row-counts.sql" | grep -E '^engine\.|^app\.|^skill\.' | sort > "$EV/09-base-rowcounts.sorted"
  diff "$EV/09-base-rowcounts.sorted" "$EV/08-post-rowcounts.sorted" && echo "(一致)"
  echo "== [3] schema-only dump：对照库 vs 回退后（应为空；pg16 每次导出会带随机 \\restrict psql 防护令牌，先行剔除再比）=="
  grep -v -e '^\\restrict ' -e '^\\unrestrict ' "$SCHEMA_A" > "$SCHEMA_A.norm"
  grep -v -e '^\\restrict ' -e '^\\unrestrict ' "$SCHEMA_R" > "$SCHEMA_R.norm"
  diff "$SCHEMA_A.norm" "$SCHEMA_R.norm" && echo "(一致)"
} > "$EV/09-consistency-diff.txt" 2>&1
echo "[run] 演练A 完成；日志见 $EV"
