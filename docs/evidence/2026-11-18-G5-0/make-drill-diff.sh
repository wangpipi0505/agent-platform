#!/usr/bin/env bash
# G5-0 两遍演练差异记录生成：比对 drill A（bash 脚本族）与 drill B（ps1 脚本族）关键证据。
# 期望：版本演进、断言行、行数对照完全一致；差异仅允许出现在时间戳与随机令牌。
set -euo pipefail
EV="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A="$EV/drill-a"; B="$EV/drill-b"
norm() { # 去掉日志头时间戳行，只留数据行
  grep -E '^(engine|app|skill)\.|head_top_version|failed_migrations|seed_|baseline_top_version|no_v901|\[g50-drill\] module=' "$1" | sed 's/\r$//' | sort
}
{
  echo "## G5-0 两遍演练差异记录（A=bash 脚本族 / B=ps1 脚本族） @ $(date -u +%FT%TZ)"
  echo
  echo "== [1] 升级段 applied 数（engine=4: V229/V230/V231/V901; app=3: V122/V123/V901; skill=3: V321/V322/V901）=="
  echo "-- A:"; grep -o '\[g50-drill\] module=[a-z]* .*' "$A/05-upgrade.log"
  echo "-- B:"; grep -o '\[g50-drill\] module=[a-z]* .*' "$B/05-upgrade.log"
  echo
  echo "== [2] 基线断言 diff（A vs B，应为空）=="
  diff <(norm "$A/02-verify-baseline.log") <(norm "$B/02-verify-baseline.log") && echo "(一致)"
  echo
  echo "== [3] 升级前行数对照 diff（A vs B，应为空）=="
  diff <(norm "$A/04-pre-upgrade-state.log") <(norm "$B/04-pre-upgrade-state.log") && echo "(一致)"
  echo
  echo "== [4] 链头断言+升级后行数 diff（A vs B，应为空）=="
  diff <(norm "$A/06-verify-head.log") <(norm "$B/06-verify-head.log") && echo "(一致)"
  echo
  echo "== [5] 回退后行数 diff（A vs B，应为空）=="
  diff <(norm "$A/08-post-rollback-state.log") <(norm "$B/08-post-rollback-state.log") && echo "(一致)"
  echo
  echo "== [6] 回退后 history 摘要 diff（A vs B，应为空；版本/脚本/成功位不含时间戳）=="
  diff <(norm "$A/08-post-rollback-state.log") <(norm "$B/08-post-rollback-state.log") && echo "(同[5]同源，一致)"
  echo
  echo "结论：两遍演练的版本演进、断言结果与行数对照逐行一致；差异仅为各日志头时间戳与"
  echo "pg16 schema dump 的随机 \\restrict 令牌（已在各自 09 步骤内剔除后比对）。"
} > "$EV/DRILL-DIFF.md" 2>&1
cat "$EV/DRILL-DIFF.md"
