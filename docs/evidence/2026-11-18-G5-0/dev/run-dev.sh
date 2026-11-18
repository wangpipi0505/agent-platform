#!/usr/bin/env bash
# G5-0 真实 dev 库 agent_platform_dev_v12 升级执行脚本（bash 脚本族）。
# 步骤：01 升级前状态留档 → 02 独立 pg_dump 备份（.local/ 不入 Git）→ 03 全链升级（脚本内再自动快照一次）→
#       04 链头验证留档 → 05 回退可用性文档化（不执行）。
# 幂等：各步骤日志存在即跳过。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
DEPLOY="$ROOT/deploy/upgrade-20261118"
EV="$SCRIPT_DIR"
BK="$ROOT/.local/g50/dev-backup"
export JAVA_HOME="${JAVA_HOME:-C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot}"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$BK"

PSQL="$ROOT/../agent-platform/.tools/postgresql-16.15/pgsql/bin/psql.exe"
[ -x "$PSQL" ] || PSQL="$ROOT/.tools/postgresql-16.15/pgsql/bin/psql.exe"
PGD="$(dirname "$PSQL")"
export PGPASSWORD="$(grep -E '^AGENT_DB_PASSWORD=' "$ROOT/.env" | tail -1 | cut -d= -f2- | tr -d '\r')"
export PGCLIENTENCODING=UTF8
DB=agent_platform_dev_v12

run() { # $1=log  其余=命令；日志存在即跳过
  local name="$1"; shift
  local f="$EV/$name.log"
  if [ -s "$f" ]; then echo "[dev] 跳过（已有日志）: $name"; return 0; fi
  echo "[dev] === $name ==="
  { echo "## dev $name @ $(date -u +%FT%TZ)"; echo "\$ $*"; } > "$f"
  "$@" >> "$f" 2>&1
  local rc=$?
  echo "## exit=$rc" >> "$f"
  tail -5 "$f"
  return $rc
}

# ---- 01 升级前状态（history + 全表行数 + 种子开关 + schema 清单） ----
if [ ! -s "$EV/01-pre-state.log" ]; then
  {
    echo "## dev 01 升级前状态 @ $(date -u +%FT%TZ)"
    echo "== schema 清单（engine/app/skill）=="
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d "$DB" -At -c "select schema_name from information_schema.schemata where schema_name in ('engine','app','skill') order by 1;"
    echo "== history-summary（升级前）=="
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d "$DB" -At -f "$DEPLOY/sql/history-summary.sql"
    echo "== row-counts（升级前；app/skill 未安装时仅 engine 段有行）=="
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d "$DB" -At -f "$DEPLOY/sql/row-counts.sql"
    echo "== 演示种子开关（须 unset/off，否则 V901 整段 no-op）=="
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d "$DB" -At -c "select coalesce(current_setting('agent.demo_seed_skip', true),'unset');"
  } > "$EV/01-pre-state.log" 2>&1
fi
grep -E '^engine\.' "$EV/01-pre-state.log" | sort | uniq > "$EV/01-pre-rowcounts.sorted"
tail -3 "$EV/01-pre-state.log"

# ---- 02 独立 pg_dump 备份（回退锚点一；升级脚本内还会再自动快照一次） ----
if [ ! -s "$EV/02-backup.log" ]; then
  STAMP="$(date -u +%Y%m%d-%H%M%S)"
  DUMP="$BK/${DB}-pre-upgrade-$STAMP.dump"
  {
    echo "## dev 02 独立备份 @ $(date -u +%FT%TZ)"
    echo "\$ pg_dump --format=custom --no-owner --no-privileges --file=$DUMP（.local/ 下，不入 Git）"
    "$PGD/pg_dump.exe" -h 127.0.0.1 -p 25432 -U agent_v12 -d "$DB" --format=custom --no-owner --no-privileges --file="$DUMP" && echo "pg_dump OK"
    echo "-- sha256:"; sha256sum "$DUMP"
    echo "-- bytes:"; stat -c %s "$DUMP"
    printf '{"formatVersion":1,"database":"%s","createdAt":"%s","sha256":"%s","bytes":%s,"postgres":"16.15","scope":"PostgreSQL only; vault/master key/MinIO objects need separate backup"}\n' \
      "$DB" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(sha256sum "$DUMP" | cut -d' ' -f1)" "$(stat -c %s "$DUMP")" \
      > "$BK/${DB}-pre-upgrade-$STAMP.manifest.json"
    echo "-- manifest.json 已写"
  } > "$EV/02-backup.log" 2>&1
fi
tail -4 "$EV/02-backup.log"

# ---- 03 全链升级（脚本自动再快照一次 → 迁移 → verify-head） ----
run 03-upgrade bash "$DEPLOY/upgrade.sh" --database "$DB" --backup-dir "$BK" --verify-head

# ---- 04 链头验证留档 ----
if [ ! -s "$EV/04-verify-head.log" ]; then
  {
    echo "## dev 04 链头验证 @ $(date -u +%FT%TZ)"
    echo "== verify-head 断言明细 =="
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d "$DB" -At -f "$DEPLOY/sql/verify-head.sql"
    echo "== 失败断言（行尾f）=="
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d "$DB" -At -f "$DEPLOY/sql/verify-head.sql" | grep -E '\|f$' || echo "(无失败断言)"
    echo "== row-counts（升级后）=="
    "$PSQL" -X -h 127.0.0.1 -p 25432 -U agent_v12 -d "$DB" -At -f "$DEPLOY/sql/row-counts.sql"
  } > "$EV/04-verify-head.log" 2>&1
fi
grep -E '^engine\.' "$EV/04-verify-head.log" | sort | uniq > "$EV/04-post-rowcounts.sorted"
echo "[dev] 04 断言："
grep -E 'head_top_version|failed_migrations|seed_|activation_pointer' "$EV/04-verify-head.log"
echo "[dev] engine 升级前→后行数 diff（应只增 V901 种子：memory_entry 0→2）"
diff "$EV/01-pre-rowcounts.sorted" "$EV/04-post-rowcounts.sorted" || true

# ---- 05 回退可用性文档化（不执行） ----
if [ ! -s "$EV/05-rollback-availability.md" ]; then
  DUMP_ANY="$(ls -1 "$BK/"${DB}-pre-upgrade-*.dump | head -1)"
  {
    echo "## dev 05 回退可用性（文档化，未执行） @ $(date -u +%FT%TZ)"
    echo
    echo "- 回退唯一受支持路径 = 备份恢复（Flyway 社区版无 downgrade）。"
    echo "- 回退锚点：本目录 \`$(basename "$DUMP_ANY")\`（含同名 manifest.json sha256）。"
    echo "- 命令（真要回退时逐字执行）："
    echo "  \`bash deploy/upgrade-20261118/rollback.sh --database agent_platform_dev_v12 --backup-file <本目录dump绝对路径> --force\`"
    echo "  （PowerShell：\`rollback.ps1 -Database agent_platform_dev_v12 -BackupFile <dump> -Force\`）"
    echo "- 回退后验证：history-summary 应回到升级前（engine V200–V231 且无 app/skill schema）；"
    echo "  row-counts 与 \`01-pre-rowcounts.sorted\` 一致。"
    echo "- 本次演练**未执行**该回退：dev 库保持链头态（三模块 history 全 success + V901 种子在案）。"
  } > "$EV/05-rollback-availability.md"
fi
echo "[dev] 完成"
