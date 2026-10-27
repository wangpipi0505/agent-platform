#!/usr/bin/env bash
# T01.4 红→绿取证脚本（可重跑）：临时回退三个主文件得到真实失败输出，
# 恢复后得到通过输出；trap 保证任何中断都会把改动还原。
set -u
cd "$(dirname "$0")/../../.." || exit 1
OUT="docs/evidence/2026-10-27-PLAT-01.4-assertion-promote"
BK="$(mktemp -d)"
FILES=(
  "monitor/src/main/java/cn/agentplatform/monitor/application/DatasetService.java"
  "engine-runtime/src/main/java/cn/agentplatform/engine/application/TestInputService.java"
  "engine-runtime/src/main/java/cn/agentplatform/engine/infra/TestInputClient.java"
)
for f in "${FILES[@]}"; do mkdir -p "$BK/$(dirname "$f")"; cp "$f" "$BK/$f"; done
restore() { for f in "${FILES[@]}"; do cp "$BK/$f" "$f"; done; }
trap restore EXIT

export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
source .tools/env-load.sh >/dev/null 2>&1

run_mvn() {
  mvn -B -ntp -DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" \
    -Dmaven.repo.local=.tools/m2 "$@"
}

git checkout -- "${FILES[@]}"
run_mvn -pl monitor test -Dtest=DatasetServiceTest > "$OUT/red-dataset.log" 2>&1 || true
run_mvn -pl engine-runtime test -Dtest=TestInputClientTest,TestInputPromotionPostgresTest > "$OUT/red-engine.log" 2>&1 || true

restore
trap - EXIT

run_mvn -pl monitor test -Dtest=DatasetServiceTest,EvaluationAssertionServiceTest > "$OUT/green-monitor.log" 2>&1
run_mvn -pl engine-runtime test -Dtest=TestInputClientTest,TestInputPromotionPostgresTest > "$OUT/green-engine.log" 2>&1

echo "RED dataset:  $(grep -h 'Tests run:' "$OUT/red-dataset.log" | tail -1)"
echo "RED engine:   $(grep -h 'Tests run:' "$OUT/red-engine.log" | tail -1)"
echo "GREEN monitor:$(grep -h 'Tests run:' "$OUT/green-monitor.log" | tail -1)"
echo "GREEN engine: $(grep -h 'Tests run:' "$OUT/green-engine.log" | tail -1)"
