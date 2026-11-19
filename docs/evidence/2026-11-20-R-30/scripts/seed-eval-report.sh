#!/bin/bash
# R-30 monitor 最小评测报告造数（兜底；数据来源=造数/产品）——在 E2（192.168.1.2）执行。
# 用法：bash /home/agent-platform/scripts/seed-eval-report.sh
# 可选：R30_DATASET_VERSION=r30-seed-v1  R30_SEED_ENV=REAL|DEMO  R30_EVIDENCE_DIR=/home/agent-platform/evidence/R-30
# 幂等：dataset/report 均 on conflict do nothing；重放只刷新验证证据。
set -euo pipefail
cd "$(dirname "$0")"
exec python3 "$(dirname "$0")/seed-eval-report.py"
