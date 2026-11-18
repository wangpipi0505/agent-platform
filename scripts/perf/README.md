# scripts/perf/ — R-14 M5-4 性能 P50/P95 实测

单命令复现（在仓库根、Git Bash 下执行）：

```bash
# 1. 专用库（幂等，已存在则跳过）：
export PGPASSWORD=$(grep '^AGENT_DB_PASSWORD=' .env | cut -d= -f2-)
"G:/207/项目/算法重组工具/代码/_worktrees/upgrade-20261103/agent-platform/.tools/postgresql-16.15/pgsql/bin/psql.exe" \
  -h 127.0.0.1 -p 25432 -d postgres -c "CREATE DATABASE r14_perf OWNER agent_v12"

# 2. 起栈（188xx 段 + r14_perf 库 + 模型桩 18885；首跑含全链迁移约 2-3 分钟）：
bash scripts/perf/start-stack.sh

# 3. 造数（经网关真实链路：50 AGENT 任务 + 10 PLAN 任务×3 修订 + 60 会话×1 轮次 + 60 记忆）：
E2E_GATEWAY=http://127.0.0.1:18880 E2E_ENGINE=http://127.0.0.1:18882 node scripts/perf/seed-data.mjs

# 4. 压测（每类 240 采样 + 20 预热不计入；样本/汇总落 docs/evidence/2026-11-19-R-14/）：
E2E_GATEWAY=http://127.0.0.1:18880 node scripts/perf/run-bench.mjs

# 5. 停栈：
bash scripts/perf/stop-stack.sh
```

- 分位算法：线性插值（Type-7，与 numpy `linear` 同式），`percentile()` 在 `run-bench.mjs` 内置。
- 端口/库隔离：188xx 段、库 `r14_perf`，与 dev / 共享 test 库 / 其他 worktree 工作隔离。
- 密钥只从仓库根 `.env` 读；证据落 `docs/evidence/2026-11-19-R-14/`。
- 结构沿用 M5-3 `scripts/e2e/start-stack.sh`（含 engine 外挂 shim，见该文件头注释与
  `scripts/e2e/README.md` 平台缺陷登记）；模型桩复用 `scripts/e2e/fixtures/model-stub.mjs`。
