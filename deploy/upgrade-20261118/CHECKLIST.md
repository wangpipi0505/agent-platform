# G5-0 升级/回退操作检查单（2026-11-18）

适用对象：单库（演练库 `drill_g50_*` 或 dev 库 `agent_platform_dev_v12`），专用 PG `127.0.0.1:25432`（PostgreSQL 16.15）。
升级链：**存量基线 → 链头**，三模块同时推进：

| 模块 | 存量钉版（基线顶版本） | 升级段 | 链头顶版本 |
|------|------------------------|--------|------------|
| engine (`engine-runtime`) | **V228** goal_outcome | V229 harness_session_log / V230 task_plan_family / V231 memory_spill / **V901 demo_seed** | **V901** |
| app (`app-management`) | **V121** onboarding_import_step | V122 workspace_manifest / V123 rule_assets / **V901 demo_seed** | **V901** |
| skill (`skill-registry`) | **V320** onboarding_skill_import_receipt | V321 skill_dual_track_plugins / V322 mcp_connections / **V901 demo_seed** | **V901** |

**V232 schedule 为后置预留，本仓库链上不存在该迁移文件，不在本期演练/升级范围。**

## 总原则（先读）

1. **回退唯一受支持路径 = 备份恢复**（`rollback.sh|ps1`：drop + create + `pg_restore`）。
   Flyway 社区版无 downgrade；任何"降版本重跑"都不是受支持回退。
2. **升级前必须先有 pg_dump 一致性快照**（`upgrade.sh|ps1 -BackupDir` 自动完成）；
   备份失败即拒绝升级。快照与 manifest（sha256）放 `.local/`（不入 Git）。
3. **密钥只从 `.env` 读**（脚本自动加载；`AGENT_DB_USER`/`AGENT_DB_PASSWORD` 不进命令行、不进日志、不进 Git）。
4. 红线：演练只允许操作 `drill_g50_a`、`drill_g50_b`、`drill_g50_base_a`、`drill_g50_base_b` 四库 + dev 库最后升级；
   绝不触碰共享 test 库与其他卡的验证库。脚本本身只操作显式传入的单个库名。

前置准备（每个 shell 会话一次）：

```bash
# bash（Git Bash）
cd <repoRoot>
set -a; source .env; set +a     # 脚本也会自动加载，双保险
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
```
```powershell
# PowerShell
cd <repoRoot>   # 脚本自动加载 .env
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
```

脚本会自动准备 `scripts/g50/DrillFlyway.java` 的依赖 classpath（`.local/g50/engine-cp.txt`，
用 `mvn -pl engine-runtime dependency:build-classpath` 生成；Flyway 与服务同源 11.7.2，checksum 一致）。

---

## 第一段：升级前快照（每个库必做）

- [ ] 1.1 确认专用 PG 在跑：`psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -c "show port;"` → `25432`
- [ ] 1.2 记录升级前 flyway 版本（升级前库若已存在）：
  `psql ... -d <db> -At -f deploy/upgrade-20261118/sql/history-summary.sql`，逐行留档
- [ ] 1.3 记录升级前逐表行数：
  `psql ... -d <db> -At -f deploy/upgrade-20261118/sql/row-counts.sql > pre-rowcounts.log`
- [ ] 1.4 确认演示种子跳过开关未开启（开启会导致 V901 整段 no-op）：
  `psql ... -d <db> -At -c "select coalesce(current_setting('agent.demo_seed_skip', true),'unset');"` → `unset`（或 `off`）
- [ ] 1.5 pg_dump 备份落盘（升级脚本带 `-BackupDir|--backup-dir` 时自动完成；核对产物）：
  - `<backupDir>/<db>-pre-upgrade-<stamp>.dump`（custom 格式）
  - `<backupDir>/<db>-pre-upgrade-<stamp>.manifest.json`（含 sha256）
  - 人工核对：`sha256sum <dump>` 与 manifest 一致
- [ ] 1.6 （演练 A/B 专用）把基线库快照恢复为对照库：`pg_restore` 进 `drill_g50_base_a|b`，
  作为回退一致性比对的参考副本

## 第二段：升级

- [ ] 2.1 （仅演练构造存量）钉版建基线：
  `./deploy/upgrade-20261118/upgrade.sh --database drill_g50_a --create-database --pin "engine=228,app=121,skill=320"`
  （PowerShell：`.\deploy\upgrade-20261118\upgrade.ps1 -Database drill_g50_b -CreateDatabase -Pin "engine=228,app=121,skill=320"`）
  完成后 `history-summary.sql` 顶版本必须 = 228/121/320（`verify-baseline.sql` 全 pass）
- [ ] 2.2 全链升级：`upgrade.sh --database <db> --backup-dir <dir> --verify-head`
  （PowerShell：`upgrade.ps1 -Database <db> -BackupDir <dir> -VerifyHead`）
- [ ] 2.3 核对迁移日志：engine 应用 V229→V230→V231→V901、app 应用 V122→V123→V901、
  skill 应用 V321→V322→V901（已是链头的库对应段无动作，属正常幂等）
- [ ] 2.4 中途任何一步失败：**停止**，按第一段快照走回退（不要带伤继续）

## 第三段：验证（升级后正向态）

- [ ] 3.1 `verify-head.sql` 全 pass（脚本 `--verify-head` 已跑过则核对其输出）：
  - 三模块 history 顶版本 = **901**
  - 失败迁移数 = 0（三 schema 全 success）
  - V901 种子在案：engine **2** 行 / app **38** 行 / skill **32** 行（`tenant_id='demo'`）
  - 激活指针 active() 口径（与 `ConfigReleaseRepository.active()` 主查询同源）：
    每工作区恰 **1** 条 ACTIVE，`manifest_digest` 与 `workspace_manifest.digest` **一致**（2 行/2 一致）
- [ ] 3.2 `row-counts.sql` 升级后留档 `post-rowcounts.log`（行数应 ≥ 升级前，新增表出现在清单尾部）
- [ ] 3.3 （服务侧冒烟，可选）启动三服务确认 flyway 启动校验通过（迁移在库即成功，服务无需再动）

## 第四段：回退验证（演练必做；dev 库文档化不执行）

- [ ] 4.1 回退：`./deploy/upgrade-20261118/rollback.sh --database <db> --backup-file <dump> --force`
  （PowerShell：`.\deploy\upgrade-20261118\rollback.ps1 -Database <db> -BackupFile <dump> -Force`）
- [ ] 4.2 回退后 history 与升级前逐行一致（`history-summary.sql` 输出 diff 第一段 1.2 留档 = 空）
- [ ] 4.3 回退后行数与升级前一致：`row-counts.sql` 输出与 1.3 的 `pre-rowcounts.log` diff = 空；
  演练库另与对照库（`drill_g50_base_a|b`）同查询 diff = 空
- [ ] 4.4 回退后 schema 一致（可选加测）：`pg_dump --schema-only` 升级前库/回退后库各一份，diff = 空
- [ ] 4.5 （dev 库）**不实际执行**回退；确认 `rollback.ps1|sh` 对 dev 库可用性 = 参数化同一入口
  （`--database agent_platform_dev_v12 --backup-file <dev备份dump> --force`），文档留档即可

## 第五段：升级后验证（回退演练后重新升回链头，或 dev 库升级完成后）

- [ ] 5.1 重跑第二段升级 + 第三段验证全绿（演练 B 独立完整重放一遍，两遍输出 diff 仅时间戳）
- [ ] 5.2 dev 库链头态留档：`verify-head.sql` 输出（含 2/38/32 种子与激活指针 2行/2一致）
- [ ] 5.3 演练/dev 全部日志归档 `docs/evidence/2026-11-18-G5-0/`（**先脱敏**：任何日志不得含
  `AGENT_DB_PASSWORD`/`AGENT_JWT_SECRET`/`AGENT_MASTER_KEY` 值）
- [ ] 5.4 生产等价库升级时的演示种子开关（占位符接线属本卡交付的文档化部分）：
  生产库执行 `ALTER DATABASE <prod> SET agent.demo_seed_skip = 'on';` 后，V901 整段 no-op
  （迁移文件内置守卫，见各模块 `V901__demo_seed.sql` 头注）。验证/演示库**不要**设置该开关。

---

## 诚实登记（本检查单的边界）

- 回退**仅备份恢复路径**受支持；RTO ≈ 库大小线性（pg_restore 单库秒级/百MB 内）。
- V232 schedule 为后置预留，不在链上，本演练不覆盖；其后到链时需重做一次增量演练。
- dev 库 `agent_platform_dev_v12` 升级前仅有 engine schema（V200–V231 全 success）：
  engine 实际增量 = **V901 一段**；app/skill 为**首次安装**（V100–V123/V300–V322 + V901），
  这部分不存在"存量数据迁移"语义，登记如实。
- 激活指针验证用 SQL 与 `ConfigReleaseRepository.active()` 主查询逐字同源（去语义视图装饰），
  未起 Spring 上下文调用 Java 方法；如需全链路 API 冒烟属服务侧验证，不在本检查单范围。
